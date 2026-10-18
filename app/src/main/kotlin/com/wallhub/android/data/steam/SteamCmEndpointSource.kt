package com.wallhub.android.data.steam

import android.util.Log
import com.wallhub.android.data.steamaccess.SteamDomainPolicy
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Cache first while the cached directory is fresh: the directory the kSteam transport already caches is read
 * before any request, so a signed-in app normally answers without a network call. Past the fresh age the
 * directory is read first and the stale body only answers if that read fails, and a caller retrying after a
 * transport-level failure never takes the cached body at all. A fetch that fails leaves the decision to the
 * engine rather than inventing an endpoint.
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
                if (cmCandidatesCacheAnswers(cached?.ageMs, refreshLive) && cachedCandidates.isNotEmpty()) {
                    Log.i(TAG, "cm candidates=${cachedCandidates.lines().size} source=cache")
                    return@withContext cachedCandidates
                }
                val live =
                    runCatching { fetchDirectory() }
                        .getOrNull()
                        ?.let(::cmCandidatesFromBody)
                        .orEmpty()
                Log.i(
                    TAG,
                    "cm candidates=${live.lines().size} source=live" + if (refreshLive) " retry=true" else "",
                )
                if (live.isNotEmpty()) return@withContext live
                // The directory is unreachable. "No route" is worse than a candidate list the platform has
                // already read and that may have gone stale: with no route there is no session at all, and a
                // recovery attempt that could not reach the directory is exactly when the cached list is the
                // only thing left to try. It used to be withheld from the live retry on the theory that the
                // retry had already seen it fail, which left a dropped connection with nothing to reconnect
                // through until the operator brought the app back to the foreground.
                cachedCandidates.takeIf { it.isNotEmpty() }
            }

        private fun fetchDirectory(): String? {
            val client =
                httpClientFactory
                    .newBuilder()
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
