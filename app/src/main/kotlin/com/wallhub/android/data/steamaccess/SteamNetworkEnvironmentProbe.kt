package com.wallhub.android.data.steamaccess

import android.net.Network
import com.wallhub.android.core.model.SteamAccessMode
import java.net.ProxySelector
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
    constructor() {
        suspend fun classify(network: Network?): SteamNetworkEnvironment {
            if (network == null) return SteamNetworkEnvironment.UNKNOWN
            if (canReachGoogle()) return SteamNetworkEnvironment.OPEN_INTERNET
            delay(PROBE_FAILURE_CONFIRMATION_DELAY_MS)
            return if (canReachGoogle()) {
                SteamNetworkEnvironment.OPEN_INTERNET
            } else {
                SteamNetworkEnvironment.RESTRICTED
            }
        }

        private suspend fun canReachGoogle(): Boolean =
            withContext(Dispatchers.IO) {
                val client =
                    OkHttpClient
                        .Builder()
                        .dns(Dns.SYSTEM)
                        .proxySelector(ProxySelector.getDefault())
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

internal const val GOOGLE_CONNECTIVITY_CHECK_URL = "https://www.google.com/generate_204"
private const val PROBE_TIMEOUT_SECONDS = 3L
private const val PROBE_FAILURE_CONFIRMATION_DELAY_MS = 500L
internal const val STEAM_ENVIRONMENT_OPEN_TTL_MS = 5 * 60_000L
internal const val STEAM_ENVIRONMENT_RESTRICTED_TTL_MS = 60_000L
internal const val STEAM_ENVIRONMENT_NETWORK_DEBOUNCE_MS = 500L
