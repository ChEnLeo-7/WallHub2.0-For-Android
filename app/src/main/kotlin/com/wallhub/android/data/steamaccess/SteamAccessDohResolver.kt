package com.wallhub.android.data.steamaccess

import com.wallhub.android.core.model.STEAM_ACCESS_DOH_ENDPOINT_LIMIT
import com.wallhub.android.core.model.SteamDohCatalog
import com.wallhub.android.core.model.SteamDohEndpoint
import com.wallhub.android.core.model.SteamDohProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val MIN_FOREGROUND_ADDRESSES_PER_AUTHORITY = 1

internal suspend fun <T> runDohBlockingOperation(block: () -> T): T =
    withContext(Dispatchers.IO) { block() }

internal data class SteamDohQuery(
    val hostname: String,
    val endpoint: String,
    val recordType: Int,
)

internal data class SteamDohProbeResult(
    val endpoint: SteamDohEndpoint,
    val latencyMs: Long?,
    val addresses: List<InetAddress>,
) {
    val available: Boolean get() = addresses.isNotEmpty() && latencyMs != null
}

internal fun foregroundResolutionComplete(
    hostnames: List<String>,
    answers: Map<String, List<InetAddress>>,
): Boolean =
    hostnames.distinct().all { hostname ->
        answers[hostname].orEmpty().distinctBy(InetAddress::getHostAddress).size >= MIN_FOREGROUND_ADDRESSES_PER_AUTHORITY
    }

private fun Map<String, List<InetAddress>>.normalizedDohAnswers(): Map<String, List<InetAddress>> =
    mapValues { (_, addresses) -> addresses.distinctBy(InetAddress::getHostAddress) }

internal class SteamAccessDohResolver(
    private val client: OkHttpClient,
    private val foregroundExecutor: ExecutorService,
    private val benchmarkExecutor: ExecutorService,
) {
    private val bootstrapClients = ConcurrentHashMap<String, OkHttpClient>()

    fun resolve(
        hostnames: List<String>,
        endpoints: List<String>,
        includeIpv6: Boolean,
    ): List<InetAddress> =
        resolveByHostname(hostnames, endpoints, includeIpv6)
            .values
            .flatten()
            .distinctBy(InetAddress::getHostAddress)

    fun resolveByHostname(
        hostnames: List<String>,
        endpoints: List<String>,
        includeIpv6: Boolean,
    ): Map<String, List<InetAddress>> {
        val queries = queryPlan(hostnames, endpoints, includeIpv6)
        if (queries.isEmpty()) return emptyMap()
        val completion = ExecutorCompletionService<Pair<String, List<InetAddress>>>(foregroundExecutor)
        val activeCalls = ConcurrentHashMap.newKeySet<Call>()
        val futures =
            queries.map { query ->
                completion.submit {
                    query.hostname to
                        resolveOne(
                            query.hostname,
                            SteamDohCatalog.endpointFor(query.endpoint),
                            query.recordType,
                            activeCalls,
                        )
                }
            }
        val answers = linkedMapOf<String, MutableList<InetAddress>>()
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(QUERY_BUDGET_MS)
        try {
            repeat(queries.size) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) return@repeat
                val completed = completion.poll(remainingNanos, TimeUnit.NANOSECONDS) ?: return@repeat
                val (hostname, addresses) = runCatching { completed.get() }.getOrNull() ?: return@repeat
                if (addresses.isNotEmpty()) {
                    answers.getOrPut(hostname) { mutableListOf() }.addAll(addresses)
                    if (foregroundResolutionComplete(hostnames, answers)) {
                        return answers.normalizedDohAnswers()
                    }
                }
            }
            return answers.normalizedDohAnswers()
        } finally {
            activeCalls.forEach(Call::cancel)
            futures.forEach { future -> if (!future.isDone) future.cancel(true) }
        }
    }

    suspend fun benchmark(endpoints: List<SteamDohEndpoint>): List<SteamDohProbeResult> =
        runDohBlockingOperation {
            val tasks = endpoints.take(STEAM_ACCESS_DOH_ENDPOINT_LIMIT).map { endpoint ->
                Callable {
                    val startedAt = System.nanoTime()
                    val addresses = runCatching { resolveOne(BENCHMARK_HOST, endpoint, TYPE_A) }.getOrDefault(emptyList())
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    SteamDohProbeResult(endpoint, elapsedMs.takeIf { addresses.isNotEmpty() }, addresses)
                }
            }
            if (tasks.isEmpty()) return@runDohBlockingOperation emptyList()
            benchmarkExecutor
                .invokeAll(tasks, BENCHMARK_BUDGET_MS, TimeUnit.MILLISECONDS)
                .mapIndexed { index, future ->
                    runCatching { future.get() }.getOrNull()
                        ?: SteamDohProbeResult(endpoints[index], null, emptyList())
                }
        }

    private fun resolveOne(
        hostname: String,
        endpoint: SteamDohEndpoint,
        type: Int,
        activeCalls: MutableSet<Call>? = null,
    ): List<InetAddress> =
        when (endpoint.protocol) {
            SteamDohProtocol.JSON -> resolveJson(hostname, endpoint, type, activeCalls)
            SteamDohProtocol.WIRE,
            SteamDohProtocol.JSON_AND_WIRE,
            -> resolveWire(hostname, endpoint, type, activeCalls)
            SteamDohProtocol.AUTO ->
                resolveJson(hostname, endpoint, type, activeCalls).ifEmpty {
                    if (Thread.currentThread().isInterrupted) emptyList()
                    else resolveWire(hostname, endpoint, type, activeCalls)
                }
        }

    private fun resolveJson(
        hostname: String,
        endpoint: SteamDohEndpoint,
        type: Int,
        activeCalls: MutableSet<Call>?,
    ): List<InetAddress> {
        val baseUrl = endpoint.url.toHttpUrlOrNull() ?: return emptyList()
        if (!baseUrl.isHttps) return emptyList()
        val url = baseUrl.newBuilder()
            .setQueryParameter("name", hostname)
            .setQueryParameter("type", type.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()
        return execute(request, endpoint, activeCalls).use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val body = response.peekBody(MAX_RESPONSE_BYTES).string()
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return@use emptyList()
            if (json.optInt("Status", 0) != 0) return@use emptyList()
            parseAddresses(json, type)
        }
    }

    private fun resolveWire(
        hostname: String,
        endpoint: SteamDohEndpoint,
        type: Int,
        activeCalls: MutableSet<Call>?,
    ): List<InetAddress> {
        val baseUrl = endpoint.url.toHttpUrlOrNull() ?: return emptyList()
        if (!baseUrl.isHttps) return emptyList()
        val id = nextQueryId.getAndIncrement() and 0xffff
        val query = buildWireQuery(hostname, type, id)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(query)
        val url = baseUrl.newBuilder().setQueryParameter("dns", encoded).build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", DNS_MESSAGE_MEDIA_TYPE)
            .build()
        return execute(request, endpoint, activeCalls).use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val declaredLength = response.body.contentLength()
            if (declaredLength > MAX_RESPONSE_BYTES) return@use emptyList()
            parseWireAddresses(response.peekBody(MAX_RESPONSE_BYTES).bytes(), type, id)
        }
    }

    private fun execute(
        request: Request,
        endpoint: SteamDohEndpoint,
        activeCalls: MutableSet<Call>?,
    ): okhttp3.Response {
        val call = clientFor(endpoint).newCall(request)
        activeCalls?.add(call)
        if (Thread.currentThread().isInterrupted) call.cancel()
        return try {
            call.execute()
        } finally {
            activeCalls?.remove(call)
        }
    }

    private fun clientFor(endpoint: SteamDohEndpoint): OkHttpClient {
        if (endpoint.bootstrapAddresses.isEmpty()) return client
        return bootstrapClients.getOrPut(endpoint.url) {
            val endpointHost = endpoint.url.toHttpUrlOrNull()?.host ?: return@getOrPut client
            val addresses = endpoint.bootstrapAddresses.mapNotNull { value ->
                runCatching { InetAddress.getByName(value) }.getOrNull()
            }
            client.newBuilder()
                .dns(
                    Dns { hostname ->
                        if (hostname.equals(endpointHost, ignoreCase = true) && addresses.isNotEmpty()) {
                            addresses
                        } else {
                            Dns.SYSTEM.lookup(hostname)
                        }
                    },
                )
                .build()
        }
    }

    companion object {
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28
        private const val DNS_MESSAGE_MEDIA_TYPE = "application/dns-message"
        private const val BENCHMARK_HOST = "steamcommunity-a.akamaihd.net"
        private const val QUERY_BUDGET_MS = 2_500L
        private const val BENCHMARK_BUDGET_MS = 30_000L
        private const val MAX_RESPONSE_BYTES = 64L * 1024L
        private const val DNS_HEADER_BYTES = 12
        private const val MAX_DNS_NAME_JUMPS = 32
        private val nextQueryId = AtomicInteger(1)

        internal fun queryPlan(
            hostnames: List<String>,
            endpoints: List<String>,
            includeIpv6: Boolean,
        ): List<SteamDohQuery> {
            val recordTypes = if (includeIpv6) listOf(TYPE_A, TYPE_AAAA) else listOf(TYPE_A)
            val boundedEndpoints = endpoints.take(STEAM_ACCESS_DOH_ENDPOINT_LIMIT)
            return recordTypes.flatMap { type ->
                boundedEndpoints.flatMap { endpoint ->
                    hostnames.map { hostname -> SteamDohQuery(hostname, endpoint, type) }
                }
            }
        }

        internal fun parseAddresses(
            body: String,
            expectedType: Int,
        ): List<InetAddress> =
            runCatching { JSONObject(body) }.getOrNull()?.let { parseAddresses(it, expectedType) }.orEmpty()

        private fun parseAddresses(
            json: JSONObject,
            expectedType: Int,
        ): List<InetAddress> {
            val answers = json.optJSONArray("Answer") ?: return emptyList()
            return buildList {
                repeat(answers.length()) { index ->
                    val answer = answers.optJSONObject(index) ?: return@repeat
                    if (answer.optInt("type") != expectedType) return@repeat
                    val value = answer.optString("data").substringBefore('%')
                    val looksLikeAddress =
                        when (expectedType) {
                            TYPE_A ->
                                value.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")) &&
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

        internal fun buildWireQuery(hostname: String, type: Int, id: Int): ByteArray {
            val normalized = hostname.trim().trimEnd('.')
            require(normalized.isNotEmpty()) { "DNS hostname must not be empty" }
            val labels = normalized.split('.')
            require(labels.all { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 63 }) {
                "DNS hostname contains an invalid label"
            }
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { data ->
                data.writeShort(id and 0xffff)
                data.writeShort(0x0100)
                data.writeShort(1)
                data.writeShort(0)
                data.writeShort(0)
                data.writeShort(0)
                labels.forEach { label ->
                    val bytes = label.toByteArray(Charsets.UTF_8)
                    data.writeByte(bytes.size)
                    data.write(bytes)
                }
                data.writeByte(0)
                data.writeShort(type)
                data.writeShort(1)
            }
            return output.toByteArray()
        }

        internal fun parseWireAddresses(
            message: ByteArray,
            expectedType: Int,
            expectedId: Int,
        ): List<InetAddress> {
            if (message.size < DNS_HEADER_BYTES) return emptyList()
            val reader = DnsWireReader(message)
            val id = reader.u16()
            val flags = reader.u16()
            val questionCount = reader.u16()
            val answerCount = reader.u16()
            reader.u16()
            reader.u16()
            if (id != (expectedId and 0xffff) || flags and 0x8000 == 0 || flags and 0x000f != 0) return emptyList()
            repeat(questionCount) {
                reader.skipName()
                reader.skip(4)
            }
            return buildList {
                repeat(answerCount) {
                    reader.skipName()
                    val type = reader.u16()
                    val klass = reader.u16()
                    reader.skip(4)
                    val length = reader.u16()
                    val data = reader.bytes(length)
                    if (klass != 1 || type != expectedType) return@repeat
                    when {
                        type == TYPE_A && data.size == 4 -> runCatching { InetAddress.getByAddress(data) }.getOrNull()?.let(::add)
                        type == TYPE_AAAA && data.size == 16 -> runCatching { InetAddress.getByAddress(data) }.getOrNull()?.let(::add)
                    }
                }
            }.distinctBy(InetAddress::getHostAddress)
        }

        private class DnsWireReader(private val data: ByteArray) {
            private var offset = 0

            fun u16(): Int {
                requireRemaining(2)
                return ((data[offset++].toInt() and 0xff) shl 8) or (data[offset++].toInt() and 0xff)
            }

            fun skip(count: Int) {
                requireRemaining(count)
                offset += count
            }

            fun bytes(count: Int): ByteArray {
                requireRemaining(count)
                return data.copyOfRange(offset, offset + count).also { offset += count }
            }

            fun skipName() {
                var cursor = offset
                var consumed = 0
                var jumped = false
                var jumps = 0
                while (true) {
                    if (cursor !in data.indices) throw IllegalArgumentException("DNS name exceeds response")
                    val length = data[cursor].toInt() and 0xff
                    when {
                        length == 0 -> {
                            if (!jumped) consumed++
                            if (!jumped) offset += consumed
                            return
                        }
                        length and 0xc0 == 0xc0 -> {
                            if (cursor + 1 >= data.size || ++jumps > MAX_DNS_NAME_JUMPS) {
                                throw IllegalArgumentException("Invalid DNS compression pointer")
                            }
                            val pointer = ((length and 0x3f) shl 8) or (data[cursor + 1].toInt() and 0xff)
                            if (pointer >= data.size) throw IllegalArgumentException("DNS compression pointer exceeds response")
                            if (!jumped) {
                                offset += consumed + 2
                                jumped = true
                            }
                            cursor = pointer
                        }
                        length > 63 -> throw IllegalArgumentException("Invalid DNS label length")
                        else -> {
                            if (cursor + 1 + length > data.size) throw IllegalArgumentException("DNS label exceeds response")
                            cursor += 1 + length
                            if (!jumped) consumed += 1 + length
                        }
                    }
                }
            }

            private fun requireRemaining(count: Int) {
                require(count >= 0 && offset + count <= data.size) { "DNS response is truncated" }
            }
        }
    }
}
