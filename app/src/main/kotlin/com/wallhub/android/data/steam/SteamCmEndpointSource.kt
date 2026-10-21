package com.wallhub.android.data.steam

import android.util.Log
import com.wallhub.android.data.steamaccess.SteamDomainPolicy
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Protocol
import okhttp3.Request

/** The directory the platform reads its own CM endpoint set from. */
internal const val CM_DIRECTORY_URL =
    "https://api.steampowered.com/ISteamDirectory/GetCMListForConnect/v1/?format=json&cellid=0"

/** The port a WebSocket CM endpoint is reached on, which is the transport this platform uses. */
internal const val CM_WEBSOCKET_PORT = 443

/** How many endpoints are handed to the engine. A short list is enough; the engine tries them in order. */
internal const val MAX_CM_CANDIDATES = 4

private const val CM_FETCH_TIMEOUT_MS = 6_000L

private const val TAG = "WallHubSdkRoute"
private val CM_BOOTSTRAP_CANDIDATES =
    listOf(
        "cmp1-hkg1.steamserver.net:443",
        "cmp2-hkg1.steamserver.net:443",
        "cmp1-tyo3.steamserver.net:443",
        "cmp1-sgp1.steamserver.net:443",
    ).joinToString("\n")
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
                val cached =
                    cmListCache
                        .loadMostRecent()
                        ?.takeIf { cmListCacheStaleUsable(it.ageMs) }
                val cachedCandidates = cached?.body?.let(::cmCandidatesFromBody).orEmpty()
                val live =
                    runCatching { fetchDirectory() }
                        .getOrNull()
                        ?.let(::cmCandidatesFromBody)
                        .orEmpty()
                Log.i(
                    TAG,
                    "cm candidates=${cmCandidateCount(live)} source=live" + if (refreshLive) " retry=true" else "",
                )
                if (live.isNotEmpty()) {
                    val selected =
                        if (refreshLive) rotateCmCandidateWindow(mergeCmCandidateWindows(live, cachedCandidates)) else live
                    Log.i(TAG, "cm candidates=${cmCandidateCount(selected)} source=selected")
                    return@withContext selected
                }
                // The first cold-start attempt may recover from a Directory outage with the bounded persisted
                // response. A transport retry deliberately refuses that same input: it already failed to make a
                // usable session, so presenting it again would not be a new attempt.
                cachedCandidates
                    .takeIf {
                        it.isNotEmpty() && cmCandidatesMayFallBack(cached?.ageMs, refreshLive)
                    }?.also {
                        Log.i(TAG, "cm candidates=${cmCandidateCount(it)} source=cache_fallback")
                    } ?: CM_BOOTSTRAP_CANDIDATES.also {
                        Log.i(TAG, "cm candidates=${cmCandidateCount(it)} source=bootstrap")
                    }
            }

        private suspend fun fetchDirectory(): String? {
            val client =
                httpClientFactory
                    .builderForSteamEndpoint("api.steampowered.com", CM_WEBSOCKET_PORT)
                    .protocols(listOf(Protocol.HTTP_1_1))
                    .connectTimeout(CM_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(CM_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .callTimeout(CM_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build()
            val request =
                Request
                    .Builder()
                    .url(CM_DIRECTORY_URL)
                    .header("Accept-Encoding", "identity")
                    .build()
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body.string().also { body ->
                    if (cmCandidatesFromBody(body).isNotEmpty()) {
                        cmListCache.save(CM_DIRECTORY_URL.substringAfter('?'), body)
                    }
                }
            }
        }
    }

internal fun cmCandidateCount(candidates: String): Int = candidates.lineSequence().count { it.isNotBlank() }

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
