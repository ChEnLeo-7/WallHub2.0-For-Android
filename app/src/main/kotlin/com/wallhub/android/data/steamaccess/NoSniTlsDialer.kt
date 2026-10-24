package com.wallhub.android.data.steamaccess

import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal fun visibleSteamTlsParameters(
    target: SteamWebDialTarget,
    base: SSLParameters = SSLParameters(),
): SSLParameters =
    base.apply {
        endpointIdentificationAlgorithm = null
        serverNames = listOf(SNIHostName(target.rule.tlsAuthority))
    }

internal fun verifySteamTlsPeer(
    target: SteamWebDialTarget,
    hostnameVerifier: HostnameVerifier,
    session: SSLSession,
): Boolean = hostnameVerifier.verify(target.rule.tlsAuthority, session)

internal data class AuthenticatedSteamSocket(
    val address: InetAddress,
    val socket: SSLSocket,
    val elapsedMs: Long,
    val webTarget: SteamWebDialTarget? = null,
)

@Singleton
internal class NoSniTlsDialer internal constructor(
    private val socketFactory: SSLSocketFactory,
    private val hostnameVerifier: HostnameVerifier,
    private val raceDelayMs: Long = RACE_DELAY_MS,
    private val connectRaceBudgetMs: Long = CONNECT_RACE_BUDGET_MS,
) {
    @Inject
    constructor() : this(
        socketFactory =
            SSLContext
                .getInstance("TLS")
                .apply {
                    init(null, null, SecureRandom())
                }.socketFactory,
        hostnameVerifier = OkHttpClient().hostnameVerifier,
        raceDelayMs = RACE_DELAY_MS,
        connectRaceBudgetMs = CONNECT_RACE_BUDGET_MS,
    )

    fun connect(
        hostname: String,
        candidates: List<InetAddress>,
        port: Int = HTTPS_PORT,
        onFailure: (InetAddress, Throwable) -> Unit = { _, _ -> },
    ): AuthenticatedSteamSocket =
        connectAuthenticated(hostname, candidates, port, hideSni = true, authenticatedCdn = false, onFailure)

    /** Connects to selected IP candidates while preserving the original Steam authority as TLS SNI. */
    fun connectWithSni(
        hostname: String,
        candidates: List<InetAddress>,
        port: Int = HTTPS_PORT,
        onFailure: (InetAddress, Throwable) -> Unit = { _, _ -> },
    ): AuthenticatedSteamSocket =
        connectAuthenticated(hostname, candidates, port, hideSni = false, authenticatedCdn = false, onFailure)

    fun connectAuthenticatedCdn(
        tlsHostname: String,
        candidates: List<InetAddress>,
        port: Int = HTTPS_PORT,
        hideSni: Boolean = true,
        onFailure: (InetAddress, Throwable) -> Unit = { _, _ -> },
    ): AuthenticatedSteamSocket =
        connectAuthenticated(tlsHostname, candidates, port, hideSni, authenticatedCdn = true, onFailure)

    fun connectWebAuthorities(
        targets: List<SteamWebDialTarget>,
        port: Int = HTTPS_PORT,
        onFailure: (SteamWebDialTarget, Throwable) -> Unit = { _, _ -> },
    ): AuthenticatedSteamSocket {
        require(port == HTTPS_PORT) { "Steam Web authority routes require HTTPS" }
        val boundedTargets =
            targets
                .filter { target ->
                    SteamAccessRoutes.webUpstreams(target.rule.httpAuthority).contains(target.rule)
                }.distinctBy(SteamWebDialTarget::healthKey)
                .take(MAX_RACE_ADDRESSES)
        if (boundedTargets.isEmpty()) throw IOException("No allowlisted Steam Web TLS targets")
        val openedSockets = ConcurrentLinkedQueue<Socket>()
        val raceExecutor =
            Executors.newFixedThreadPool(boundedTargets.size) { runnable ->
                Thread(runnable, "WallHub-WebAuthorityDialer").apply { isDaemon = true }
            }
        val completion = ExecutorCompletionService<WebDialAttempt>(raceExecutor)
        val futures =
            boundedTargets.mapIndexed { index, target ->
                completion.submit(
                    java.util.concurrent.Callable {
                        if (index > 0) Thread.sleep(index * raceDelayMs)
                        WebDialAttempt(
                            target,
                            runCatching {
                                authenticate(
                                    hostname = target.rule.tlsAuthority,
                                    address = target.address,
                                    port = port,
                                    openedSockets = openedSockets,
                                    hideSni = false,
                                    webTarget = target,
                                )
                            },
                        )
                    },
                )
            }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(connectRaceBudgetMs)
        var winnerSocket: SSLSocket? = null
        var lastFailure: Throwable? = null
        val completedTargets = mutableSetOf<String>()
        try {
            repeat(boundedTargets.size) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) return@repeat
                val attempt = completion.poll(remainingNanos, TimeUnit.NANOSECONDS)?.get() ?: return@repeat
                completedTargets += attempt.target.healthKey
                attempt.result
                    .onSuccess { winner ->
                        winnerSocket = winner.socket
                        return winner
                    }.onFailure { error ->
                        lastFailure = error
                        runCatching { onFailure(attempt.target, error) }
                    }
            }
            val timeout = SocketTimeoutException("No Steam Web authority connected within $connectRaceBudgetMs ms")
            boundedTargets.filterNot { it.healthKey in completedTargets }.forEach { target ->
                runCatching { onFailure(target, timeout) }
            }
            throw IOException("No authenticated Steam Web authority route", lastFailure ?: timeout)
        } finally {
            futures.forEach { future -> future.cancel(true) }
            openedSockets.filter { socket -> socket !== winnerSocket }.forEach { socket -> socket.closeQuietly() }
            raceExecutor.shutdownNow()
        }
    }

    private fun connectAuthenticated(
        hostname: String,
        candidates: List<InetAddress>,
        port: Int,
        hideSni: Boolean,
        authenticatedCdn: Boolean,
        onFailure: (InetAddress, Throwable) -> Unit,
    ): AuthenticatedSteamSocket {
        val host =
            if (authenticatedCdn) {
                SteamDomainPolicy.requireAuthenticatedCdnEndpoint(hostname, port)
            } else {
                SteamDomainPolicy.requireSupportedEndpoint(hostname, port)
            }
        val addresses = candidates.distinctBy(InetAddress::getHostAddress).take(MAX_RACE_ADDRESSES)
        if (addresses.isEmpty()) throw IOException("No no-SNI candidates for $host")

        val openedSockets = ConcurrentLinkedQueue<Socket>()
        val raceExecutor =
            Executors.newFixedThreadPool(addresses.size) { runnable ->
                Thread(runnable, "WallHub-NoSniDialer").apply { isDaemon = true }
            }
        val completion = ExecutorCompletionService<DialAttempt>(raceExecutor)
        val futures =
            addresses.mapIndexed { index, address ->
                completion.submit(
                    java.util.concurrent.Callable {
                        if (index > 0) Thread.sleep(raceDelayMs)
                        DialAttempt(
                            address = address,
                            result = runCatching { authenticate(host, address, port, openedSockets, hideSni) },
                        )
                    },
                )
            }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(connectRaceBudgetMs)
        val completedAddresses = mutableSetOf<String>()
        var winnerSocket: SSLSocket? = null
        var lastFailure: Throwable? = null
        try {
            repeat(addresses.size) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) return@repeat
                val completed = completion.poll(remainingNanos, TimeUnit.NANOSECONDS) ?: return@repeat
                val attempt = completed.get()
                completedAddresses += attempt.address.hostAddress.orEmpty()
                attempt.result
                    .onSuccess { winner ->
                        winnerSocket = winner.socket
                        return winner
                    }.onFailure { error ->
                        lastFailure = error
                        runCatching { onFailure(attempt.address, error) }
                    }
            }
            val timeout =
                SocketTimeoutException(
                    "No no-SNI candidate connected within $connectRaceBudgetMs ms",
                )
            addresses.filterNot { address -> address.hostAddress.orEmpty() in completedAddresses }.forEach { address ->
                runCatching { onFailure(address, timeout) }
            }
            throw IOException("No authenticated no-SNI route for $host", lastFailure ?: timeout)
        } finally {
            futures.forEach { future -> future.cancel(true) }
            openedSockets.filter { socket -> socket !== winnerSocket }.forEach { socket -> socket.closeQuietly() }
            raceExecutor.shutdownNow()
        }
    }

    private fun authenticate(
        hostname: String,
        address: InetAddress,
        port: Int,
        openedSockets: ConcurrentLinkedQueue<Socket>?,
        hideSni: Boolean,
        webTarget: SteamWebDialTarget? = null,
    ): AuthenticatedSteamSocket {
        val startedAt = System.nanoTime()
        val rawSocket = Socket()
        openedSockets?.add(rawSocket)
        try {
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = HANDSHAKE_TIMEOUT_MS
            rawSocket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            val sslSocket =
                socketFactory.createSocket(
                    rawSocket,
                    if (hideSni) address.hostAddress else hostname,
                    port,
                    true,
                ) as SSLSocket
            openedSockets?.add(sslSocket)
            openedSockets?.remove(rawSocket)
            sslSocket.enabledProtocols =
                sslSocket.supportedProtocols
                    .filter { protocol -> protocol == "TLSv1.3" || protocol == "TLSv1.2" }
                    .toTypedArray()
            sslSocket.sslParameters =
                when {
                    webTarget != null -> visibleSteamTlsParameters(webTarget, sslSocket.sslParameters)
                    else ->
                        sslSocket.sslParameters.apply {
                            endpointIdentificationAlgorithm = null
                            if (hideSni) serverNames = Collections.emptyList()
                        }
                }
            sslSocket.startHandshake()
            val verified =
                webTarget?.let { target -> verifySteamTlsPeer(target, hostnameVerifier, sslSocket.session) }
                    ?: hostnameVerifier.verify(hostname, sslSocket.session)
            if (!verified) {
                throw SSLPeerUnverifiedException("Certificate does not match $hostname")
            }
            sslSocket.soTimeout = 0
            return AuthenticatedSteamSocket(
                address = address,
                socket = sslSocket,
                elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                webTarget = webTarget,
            )
        } catch (error: Throwable) {
            openedSockets?.remove(rawSocket)
            rawSocket.closeQuietly()
            throw error
        }
    }

    private fun Socket.closeQuietly() {
        runCatching { close() }
    }

    private data class DialAttempt(
        val address: InetAddress,
        val result: Result<AuthenticatedSteamSocket>,
    )

    private data class WebDialAttempt(
        val target: SteamWebDialTarget,
        val result: Result<AuthenticatedSteamSocket>,
    )

    private companion object {
        const val HTTPS_PORT = 443
        const val MAX_RACE_ADDRESSES = 4
        const val RACE_DELAY_MS = 200L
        const val CONNECT_RACE_BUDGET_MS = 6_000L
        const val CONNECT_TIMEOUT_MS = 4_000
        const val HANDSHAKE_TIMEOUT_MS = 5_000
    }
}
