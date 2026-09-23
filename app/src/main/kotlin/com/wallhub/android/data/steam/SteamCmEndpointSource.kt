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
 * Cache first: the directory the kSteam transport already caches is read before any request, so a
 * signed-in app normally answers without a network call. A fetch that fails leaves the decision to the
 * engine rather than inventing an endpoint.
 */
@Singleton
internal class SteamCmEndpointSource
    @Inject
    constructor(
        private val httpClientFactory: SteamHttpClientFactory,
        private val cmListCache: SteamCmListCache,
    ) {
        /** Newline-separated `host:port` candidates, or null when the platform has none to offer. */
        suspend fun candidates(): String? =
            withContext(Dispatchers.IO) {
                val cached =
                    cmListCache
                        .loadMostRecent()
                        ?.takeIf { cmListCacheStaleUsable(it.ageMs) }
                        ?.body
                        ?.let(::cmCandidatesFromBody)
                        .orEmpty()
                if (cached.isNotEmpty()) {
                    Log.i(TAG, "cm candidates=${cached.lines().size} source=cache")
                    return@withContext cached
                }
                val live =
                    runCatching { fetchDirectory() }
                        .getOrNull()
                        ?.let(::cmCandidatesFromBody)
                        .orEmpty()
                Log.i(TAG, "cm candidates=${live.lines().size} source=live")
                live.ifEmpty { null }
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
 * kept, WebSocket-port endpoints come first (the bridge route speaks that transport), and the order is
 * otherwise the directory's own.
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
        .sortedByDescending { endpoint -> endpoint.endsWith(":$CM_WEBSOCKET_PORT") }
        .take(MAX_CM_CANDIDATES)
        .joinToString("\n")
