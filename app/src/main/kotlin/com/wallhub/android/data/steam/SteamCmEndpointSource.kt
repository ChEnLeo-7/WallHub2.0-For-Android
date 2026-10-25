package com.wallhub.android.data.steam

import android.util.Log
import com.wallhub.android.data.steamaccess.SteamDomainPolicy
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody

/** The directory the platform reads its own CM endpoint set from. */
internal const val CM_DIRECTORY_URL =
    "https://api.steampowered.com/ISteamDirectory/GetCMListForConnect/v1/?format=json&cellid=0"

/** The port a WebSocket CM endpoint is reached on, which is the transport this platform uses. */
internal const val CM_WEBSOCKET_PORT = 443

/** How many endpoints are handed to the engine. A short list is enough; the engine tries them in order. */
internal const val MAX_CM_CANDIDATES = 4

private const val CM_DIRECT_FETCH_TIMEOUT_MS = 6_000L
private const val CM_ENHANCED_FETCH_TIMEOUT_MS = 34_000L

private const val TAG = "WallHubSdkRoute"
private val CM_ENDPOINT_REGEX = Regex("([A-Za-z0-9][A-Za-z0-9.-]*\\.steamserver\\.net):(\\d{2,5})")

/**
 * The CM endpoint set the platform hands to the Steam Content SDK.
 *
 * The SDK's own CM discovery fetches Steam's directory directly, which a network that needs the
 * platform's local bridge cannot do: that request is only reachable through the bridge, and the engine
 * exposes no route setting for its discovery. The platform already reaches the directory (its Workshop
 * traffic goes the same way), so it reads the endpoint list itself and passes it as
 * [SteamContentSdkEngine.Route.cmCandidates]; the engine then connects to one of them directly, which is how
 * this platform's own signed-in transport reaches the same channel.
 *
 * Every cold start reads Steam's directory live so a changed egress receives Steam's current regional/load
 * assignment. The persisted response is disaster recovery only: a normal attempt may use it after the live
 * request fails, while a retry after a transport failure never reuses the same candidate input.
 */
@Singleton
internal class SteamCmEndpointSource
    @Inject
    constructor(
        private val httpClientFactory: SteamHttpClientFactory,
        private val cmListCache: SteamCmListCache,
    ) {
        /**
         * Newline-separated `host:port` candidates, or null when the platform has none to offer.
         *
         * [refreshLive] is the single bounded retry a caller makes after a transport-level logon or restore
         * failure: the cached body is not offered again, so the result is a list read live or nothing.
         */
        suspend fun candidates(refreshLive: Boolean = false): String? =
            withContext(Dispatchers.IO) {
                val live =
                    runCatching { fetchDirectory() }
                        .getOrNull()
                        ?.let(::cmCandidatesFromBody)
                        .orEmpty()
                val cachedCandidates =
                    if (live.isEmpty() && !refreshLive) {
                        cmListCache
                            .loadMostRecent()
                            ?.takeIf { cmListCacheStaleUsable(it.ageMs) }
                            ?.body
                            ?.let(::cmCandidatesFromBody)
                            .orEmpty()
                    } else {
                        ""
                    }
                Log.i(
                    TAG,
                    "cm candidates=${cmCandidateCount(live)} source=live" + if (refreshLive) " retry=true" else "",
                )
                selectCmCandidates(live, cachedCandidates, refreshLive)?.also { selected ->
                    Log.i(
                        TAG,
                        "cm candidates=${cmCandidateCount(selected)} source=" +
                            if (live.isNotEmpty()) "live" else "cache_fallback",
                    )
                }
            }

        private suspend fun fetchDirectory(): String? {
            val enhanced = httpClientFactory.enhancedConnectionEnabled()
            val timeoutMillis =
                if (enhanced) CM_ENHANCED_FETCH_TIMEOUT_MS else CM_DIRECT_FETCH_TIMEOUT_MS
            val builder =
                if (enhanced) {
                    httpClientFactory.newAnonymousReadBuilder()
                } else {
                    httpClientFactory.builderForSteamEndpoint("api.steampowered.com", CM_WEBSOCKET_PORT)
                }
            val client =
                builder
                    .protocols(listOf(Protocol.HTTP_1_1))
                    .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .build()
            val request =
                Request
                    .Builder()
                    .url(CM_DIRECTORY_URL)
                    .header("Accept-Encoding", "identity")
                    .build()
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = readBoundedCmDirectoryBody(response.body) ?: return null
                body.also {
                    if (cmCandidatesFromBody(it).isNotEmpty()) {
                        cmListCache.save(CM_DIRECTORY_URL.substringAfter('?'), it)
                    }
                }
            }
        }
    }

internal fun cmCandidateCount(candidates: String): Int = candidates.lineSequence().count { it.isNotBlank() }

internal fun selectCmCandidates(
    live: String,
    cached: String,
    refreshLive: Boolean,
): String? = live.takeIf(String::isNotEmpty) ?: cached.takeIf { !refreshLive && it.isNotEmpty() }

internal fun readBoundedCmDirectoryBody(body: ResponseBody): String? {
    val declaredLength = body.contentLength()
    if (declaredLength > MAX_CM_LIST_CACHE_BYTES) return null
    val output = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8 * 1024)
    val input = body.byteStream()
    while (true) {
        val remaining = MAX_CM_LIST_CACHE_BYTES + 1L - output.size().toLong()
        if (remaining <= 0L) return null
        val read = input.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
        if (read < 0) break
        output.write(chunk, 0, read)
    }
    val bytes = output.toByteArray()
    if (bytes.size.toLong() > MAX_CM_LIST_CACHE_BYTES) return null
    return bytes.toString(StandardCharsets.UTF_8)
}

/** Merges a refreshed CM window with still-fresh prior candidates, keeping fresh order and one bounded cap. */
internal fun mergeCmCandidateWindows(live: String, previous: String): String =
    (live.lineSequence() + previous.lineSequence())
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(MAX_CM_CANDIDATES)
        .joinToString("\n")

/** A bounded retry starts with a different Directory candidate instead of repeating the failed first host. */
internal fun rotateCmCandidateWindow(candidates: String): String {
    val entries = candidates.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    if (entries.size < 2) return entries.joinToString("\n")
    return (entries.drop(1) + entries.first()).joinToString("\n")
}

/**
 * Extracts the platform-supported CM endpoints from a CM directory body.
 *
 * The extraction is textual on purpose: the response's key order is not a contract, and a JVM unit test
 * carries no Android JSON implementation. Only hosts and ports the platform can actually accelerate are
 * kept, and their order remains exactly the directory's own. Steam owns regional/load assignment; the app
 * must not replace it with a port or geography preference.
 */
internal fun cmCandidatesFromBody(body: String): String =
    CM_ENDPOINT_REGEX
        .findAll(body)
        .mapNotNull { match ->
            val host = match.groupValues[1].lowercase().trimEnd('.')
            val port = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            if (!SteamDomainPolicy.supportsEndpoint(host, port)) return@mapNotNull null
            "$host:$port"
        }.distinct()
        .take(MAX_CM_CANDIDATES)
        .joinToString("\n")
