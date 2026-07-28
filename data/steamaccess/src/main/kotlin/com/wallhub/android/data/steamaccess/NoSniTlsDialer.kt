package com.wallhub.android.data.steamaccess

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
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
import okhttp3.OkHttpClient

internal data class AuthenticatedSteamSocket(
    val address: InetAddress,
    val socket: SSLSocket,
    val elapsedMs: Long,
)

@Singleton
internal class NoSniTlsDialer internal constructor(
    private val socketFactory: SSLSocketFactory,
    private val hostnameVerifier: HostnameVerifier,
) {
    @Inject
    constructor() : this(
        socketFactory = SSLContext.getInstance("TLS").apply {
            init(null, null, SecureRandom())
        }.socketFactory,
        hostnameVerifier = OkHttpClient().hostnameVerifier,
    )

    private val raceExecutor = Executors.newFixedThreadPool(MAX_RACING_THREADS) { runnable ->
        Thread(runnable, "WallHub-NoSniDialer").apply { isDaemon = true }
    }

    fun connect(
        hostname: String,
        candidates: List<InetAddress>,
        port: Int = HTTPS_PORT,
    ): AuthenticatedSteamSocket {
        val host = SteamDomainPolicy.requireSupported(hostname)
        val addresses = candidates.distinctBy(InetAddress::getHostAddress).take(MAX_RACE_ADDRESSES)
        if (addresses.isEmpty()) throw IOException("No no-SNI candidates for $host")

        val openedSockets = ConcurrentLinkedQueue<SSLSocket>()
        val completion = ExecutorCompletionService<Result<AuthenticatedSteamSocket>>(raceExecutor)
        val futures = addresses.mapIndexed { index, address ->
            completion.submit(java.util.concurrent.Callable {
                if (index > 0) Thread.sleep(RACE_DELAY_MS)
                runCatching { authenticate(host, address, port, openedSockets) }
            })
        }
        var lastFailure: Throwable? = null
        repeat(addresses.size) {
            val result = completion.take().get()
            result.onSuccess { winner ->
                futures.forEach { future -> future.cancel(true) }
                openedSockets.filter { socket -> socket !== winner.socket }.forEach { socket -> socket.closeQuietly() }
                return winner
            }.onFailure { error -> lastFailure = error }
        }
        openedSockets.forEach { socket -> socket.closeQuietly() }
        throw IOException("No authenticated no-SNI route for $host", lastFailure)
    }

    fun probe(
        hostname: String,
        address: InetAddress,
        port: Int = HTTPS_PORT,
    ): SteamProbeResult {
        val startedAt = System.nanoTime()
        val successful = runCatching {
            authenticate(
                hostname = SteamDomainPolicy.requireSupported(hostname),
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
        openedSockets: ConcurrentLinkedQueue<SSLSocket>?,
    ): AuthenticatedSteamSocket {
        val startedAt = System.nanoTime()
        val rawSocket = Socket()
        try {
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = HANDSHAKE_TIMEOUT_MS
            rawSocket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            val sslSocket = socketFactory.createSocket(
                rawSocket,
                address.hostAddress,
                port,
                true,
            ) as SSLSocket
            openedSockets?.add(sslSocket)
            sslSocket.enabledProtocols = sslSocket.supportedProtocols
                .filter { protocol -> protocol == "TLSv1.3" || protocol == "TLSv1.2" }
                .toTypedArray()
            sslSocket.sslParameters = sslSocket.sslParameters.apply {
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
            rawSocket.closeQuietly()
            throw error
        }
    }

    private fun Socket.closeQuietly() {
        runCatching { close() }
    }

    private companion object {
        const val HTTPS_PORT = 443
        const val MAX_RACE_ADDRESSES = 2
        const val MAX_RACING_THREADS = 4
        const val RACE_DELAY_MS = 200L
        const val CONNECT_TIMEOUT_MS = 4_000
        const val HANDSHAKE_TIMEOUT_MS = 5_000
    }
}
