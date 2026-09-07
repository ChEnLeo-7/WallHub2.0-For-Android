package com.wallhub.android.data.steamaccess

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLParameters

internal data class SteamProbeResult(
    val address: InetAddress,
    val successful: Boolean,
    val elapsedMs: Long,
)

internal class SteamAccessProbe(
    private val executor: ExecutorService,
) {
    fun rank(
        hostname: String,
        candidates: List<InetAddress>,
        port: Int = STEAM_HTTPS_PORT,
    ): List<SteamProbeResult> {
        val host = SteamDomainPolicy.requireSupportedEndpoint(hostname, port)
        val limited = candidates.distinctBy(InetAddress::getHostAddress).take(MAX_PROBE_ADDRESSES)
        val tasks = limited.map { address -> Callable { probe(host, address, port) } }
        if (tasks.isEmpty()) return emptyList()
        return executor
            .invokeAll(tasks, TOTAL_PROBE_BUDGET_MS, TimeUnit.MILLISECONDS)
            .mapIndexed { index, future ->
                runCatching { future.get() }.getOrElse {
                    SteamProbeResult(
                        address = limited[index],
                        successful = false,
                        elapsedMs = TOTAL_PROBE_BUDGET_MS,
                    )
                }
            }.sortedWith(compareByDescending<SteamProbeResult> { it.successful }.thenBy { it.elapsedMs })
    }

    private fun probe(
        hostname: String,
        address: InetAddress,
        port: Int,
    ): SteamProbeResult {
        val startedAt = System.nanoTime()
        val successful = runCatching { probeTlsHandshake(hostname, address, port) }.getOrDefault(false)
        return SteamProbeResult(
            address = address,
            successful = successful,
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
        )
    }

    private fun probeTlsHandshake(
        hostname: String,
        address: InetAddress,
        port: Int,
    ): Boolean {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(address, port), TLS_PROBE_CONNECT_TIMEOUT_MS)
            socket.soTimeout = TLS_PROBE_HANDSHAKE_TIMEOUT_MS
            val sslSocket =
                SSLContext.getDefault().socketFactory.createSocket(socket, hostname, port, true) as SSLSocket
            sslSocket.useClientMode = true
            sslSocket.sslParameters =
                (sslSocket.sslParameters ?: SSLParameters()).apply {
                    serverNames = listOf<SNIServerName>(SNIHostName(hostname))
                }
            sslSocket.startHandshake()
            sslSocket.close()
            return true
        } finally {
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val MAX_PROBE_ADDRESSES = 4
        const val TOTAL_PROBE_BUDGET_MS = 4_000L
        const val TLS_PROBE_CONNECT_TIMEOUT_MS = 2_500
        const val TLS_PROBE_HANDSHAKE_TIMEOUT_MS = 2_500
    }
}
