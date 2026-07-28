package com.wallhub.android.data.steamaccess

import com.wallhub.android.core.model.STEAM_ACCESS_DOH_ENDPOINT_LIMIT
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

internal data class SteamDohQuery(
    val hostname: String,
    val endpoint: String,
    val recordType: Int,
)

internal class SteamAccessDohResolver(
    private val client: OkHttpClient,
    private val executor: ExecutorService,
) {
    fun resolve(
        hostnames: List<String>,
        endpoints: List<String>,
        includeIpv6: Boolean,
    ): List<InetAddress> {
        val tasks = queryPlan(hostnames, endpoints, includeIpv6).map { query ->
            Callable { resolveOne(query.hostname, query.endpoint, query.recordType) }
        }
        if (tasks.isEmpty()) return emptyList()
        return executor.invokeAll(tasks, QUERY_BUDGET_MS, TimeUnit.MILLISECONDS)
            .flatMap { future -> runCatching { future.get() }.getOrDefault(emptyList()) }
            .distinctBy(InetAddress::getHostAddress)
    }

    private fun resolveOne(hostname: String, endpoint: String, type: Int): List<InetAddress> {
        val baseUrl = endpoint.toHttpUrlOrNull() ?: return emptyList()
        if (baseUrl.scheme != "https") return emptyList()
        val url = baseUrl.newBuilder()
            .setQueryParameter("name", hostname)
            .setQueryParameter("type", type.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            parseAddresses(response.peekBody(MAX_RESPONSE_BYTES).string(), type)
        }
    }

    companion object {
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28
        private const val QUERY_BUDGET_MS = 3_000L
        private const val MAX_RESPONSE_BYTES = 64L * 1024L

        internal fun queryPlan(
            hostnames: List<String>,
            endpoints: List<String>,
            includeIpv6: Boolean,
        ): List<SteamDohQuery> {
            val recordTypes = if (includeIpv6) listOf(TYPE_A, TYPE_AAAA) else listOf(TYPE_A)
            return endpoints.take(STEAM_ACCESS_DOH_ENDPOINT_LIMIT).flatMap { endpoint ->
                hostnames.flatMap { hostname ->
                    recordTypes.map { type -> SteamDohQuery(hostname, endpoint, type) }
                }
            }
        }

        internal fun parseAddresses(body: String, expectedType: Int): List<InetAddress> {
            val answers = runCatching { JSONObject(body).optJSONArray("Answer") }.getOrNull()
                ?: return emptyList()
            return buildList {
                repeat(answers.length()) { index ->
                    val answer = answers.optJSONObject(index) ?: return@repeat
                    if (answer.optInt("type") != expectedType) return@repeat
                    val value = answer.optString("data").substringBefore('%')
                    val looksLikeAddress = when (expectedType) {
                        TYPE_A -> value.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")) &&
                            value.split('.').all { part ->
                                part.toIntOrNull()?.let { octet -> octet in 0..255 } == true
                            }
                        TYPE_AAAA -> ':' in value
                        else -> false
                    }
                    if (!looksLikeAddress) return@repeat
                    runCatching { InetAddress.getByName(value) }.getOrNull()?.let(::add)
                }
            }.distinctBy(InetAddress::getHostAddress)
        }
    }
}
