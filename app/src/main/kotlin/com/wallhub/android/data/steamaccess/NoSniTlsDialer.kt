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
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal data class AuthenticatedSteamSocket(
    val address: InetAddress,
    val socket: SSLSocket,
    val elapsedMs: Long,
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
    ): AuthenticatedSteamSocket {
        val host = SteamDomainPolicy.requireSupportedEndpoint(hostname, port)
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
                            result = runCatching { authenticate(host, address, port, openedSockets) },
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

    fun probe(
        hostname: String,
        address: InetAddress,
        port: Int = HTTPS_PORT,
    ): SteamProbeResult {
        val host = SteamDomainPolicy.requireSupportedEndpoint(hostname, port)
        val startedAt = System.nanoTime()
        val successful =
            runCatching {
                authenticate(
                    hostname = host,
                    address = address,
                    port = port,
                    openedSockets = null,
                ).socket.close()
            }.isSuccess
        return SteamProbeResult(
            address = address,
            successful = successful,
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
        )
    }

    private fun authenticate(
        hostname: String,
        address: InetAddress,
        port: Int,
        openedSockets: ConcurrentLinkedQueue<Socket>?,
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
                    address.hostAddress,
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
                sslSocket.sslParameters.apply {
                    endpointIdentificationAlgorithm = null
                    serverNames = Collections.emptyList()
                }
            sslSocket.startHandshake()
            if (!hostnameVerifier.verify(hostname, sslSocket.session)) {
                throw SSLPeerUnverifiedException("Certificate does not match $hostname")
            }
            sslSocket.soTimeout = 0
            return AuthenticatedSteamSocket(
                address = address,
                socket = sslSocket,
                elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
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

    private companion object {
        const val HTTPS_PORT = 443
        const val MAX_RACE_ADDRESSES = 2
        const val RACE_DELAY_MS = 200L
        const val CONNECT_RACE_BUDGET_MS = 6_000L
        const val CONNECT_TIMEOUT_MS = 4_000
        const val HANDSHAKE_TIMEOUT_MS = 5_000
    }
}
