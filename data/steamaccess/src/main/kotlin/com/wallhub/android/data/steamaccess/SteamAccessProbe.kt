package com.wallhub.android.data.steamaccess

import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class SteamProbeResult(
    val address: InetAddress,
    val successful: Boolean,
    val elapsedMs: Long,
)

internal class SteamAccessProbe(
    private val baseClient: OkHttpClient,
    private val executor: ExecutorService,
) {
    fun rank(hostname: String, candidates: List<InetAddress>): List<SteamProbeResult> {
        val limited = candidates.distinctBy(InetAddress::getHostAddress).take(MAX_PROBE_ADDRESSES)
        val tasks = limited.map { address -> Callable { probe(hostname, address) } }
        if (tasks.isEmpty()) return emptyList()
        return executor.invokeAll(tasks, TOTAL_PROBE_BUDGET_MS, TimeUnit.MILLISECONDS)
            .mapIndexed { index, future ->
                runCatching { future.get() }.getOrElse {
                    SteamProbeResult(
                        address = limited[index],
                        successful = false,
                        elapsedMs = TOTAL_PROBE_BUDGET_MS,
                    )
                }
            }
            .sortedWith(compareByDescending<SteamProbeResult> { it.successful }.thenBy { it.elapsedMs })
    }

    private fun probe(hostname: String, address: InetAddress): SteamProbeResult {
        val startedAt = System.nanoTime()
        val client = baseClient.newBuilder()
            .dns(Dns { requestedHost ->
                if (requestedHost.equals(hostname, ignoreCase = true)) listOf(address) else Dns.SYSTEM.lookup(requestedHost)
            })
            .build()
        val request = Request.Builder()
            .url("https://$hostname${probePath(hostname)}")
            .header("User-Agent", "WallHub-Android/SteamAccessProbe")
            .build()
        val successful = runCatching {
            client.newCall(request).execute().use { response ->
                response.code in 200..499
            }
        }.getOrDefault(false)
        return SteamProbeResult(
            address = address,
            successful = successful,
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
        )
    }

    private fun probePath(hostname: String): String = SteamDomainPolicy.probePath(hostname)

    private companion object {
        const val MAX_PROBE_ADDRESSES = 4
        const val TOTAL_PROBE_BUDGET_MS = 3_000L
    }
}
