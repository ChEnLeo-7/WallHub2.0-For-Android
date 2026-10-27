package com.wallhub.android.data.steamaccess

import android.net.Network
import com.wallhub.android.core.model.SteamAccessMode
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

internal enum class SteamNetworkEnvironment {
    UNKNOWN,
    RESTRICTED,
    OPEN_INTERNET,
}

internal fun shouldApplySteamAccess(
    configured: Boolean,
    mode: SteamAccessMode,
    environment: SteamNetworkEnvironment,
): Boolean =
    configured &&
        (
            mode != SteamAccessMode.SMART_DOH ||
                environment != SteamNetworkEnvironment.OPEN_INTERNET
        )

/**
 * Classifies the active Android network by real HTTPS reachability rather than ICMP. Transparent proxy and
 * fake-IP networks commonly reject ping while successfully carrying TLS, so a 204 response is the useful
 * product signal. Failures are confirmed twice to avoid enabling alternate routes for one transient packet loss.
 */
@Singleton
internal class SteamNetworkEnvironmentProbe
    @Inject
    constructor(
        private val systemProxyResolver: SteamSystemProxyResolver,
    ) {
        suspend fun classify(network: Network?): SteamNetworkEnvironment {
            if (network == null) return SteamNetworkEnvironment.UNKNOWN
            val proxyRoute = systemProxyResolver.resolve(STEAM_SYSTEM_PROXY_TARGETS)
            if (!proxyRoute.representable) return SteamNetworkEnvironment.UNKNOWN
            if (canReachGoogle(proxyRoute.sdkProxyUri)) return SteamNetworkEnvironment.OPEN_INTERNET
            delay(PROBE_FAILURE_CONFIRMATION_DELAY_MS)
            return if (canReachGoogle(proxyRoute.sdkProxyUri)) {
                SteamNetworkEnvironment.OPEN_INTERNET
            } else {
                SteamNetworkEnvironment.RESTRICTED
            }
        }

        private suspend fun canReachGoogle(proxyUri: String?): Boolean =
            withContext(Dispatchers.IO) {
                val proxy = proxyUri?.let(::sdkSystemProxy)
                val client =
                    OkHttpClient
                        .Builder()
                        .dns(Dns.SYSTEM)
                        .proxy(proxy ?: Proxy.NO_PROXY)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .build()
                runCatching {
                    client.newCall(
                        Request
                            .Builder()
                            .url(GOOGLE_CONNECTIVITY_CHECK_URL)
                            .header("Cache-Control", "no-cache")
                            .header("Connection", "close")
                            .build(),
                    ).execute().use { response -> response.code == 204 }
                }.getOrDefault(false)
            }
    }

private fun sdkSystemProxy(raw: String): Proxy {
    val uri = URI(raw)
    val type = if (uri.scheme == "socks") Proxy.Type.SOCKS else Proxy.Type.HTTP
    return Proxy(type, InetSocketAddress.createUnresolved(requireNotNull(uri.host), uri.port))
}

internal val STEAM_SYSTEM_PROXY_TARGETS =
    listOf(
        URI(GOOGLE_CONNECTIVITY_CHECK_URL),
        URI("https://cm0.steamserver.net:443/"),
        URI("https://cdn.steamcontent.com:443/"),
    )
internal const val GOOGLE_CONNECTIVITY_CHECK_URL = "https://www.google.com/generate_204"
private const val PROBE_TIMEOUT_SECONDS = 3L
private const val PROBE_FAILURE_CONFIRMATION_DELAY_MS = 500L
internal const val STEAM_ENVIRONMENT_OPEN_TTL_MS = 5 * 60_000L
internal const val STEAM_ENVIRONMENT_RESTRICTED_TTL_MS = 60_000L
internal const val STEAM_ENVIRONMENT_NETWORK_DEBOUNCE_MS = 500L
internal const val STEAM_ENVIRONMENT_WARNING_REPROBE_COOLDOWN_MS = 60_000L
