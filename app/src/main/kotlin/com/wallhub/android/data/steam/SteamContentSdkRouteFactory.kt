package com.wallhub.android.data.steam

import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.data.steamaccess.SteamLoopbackTlsBridge
import io.steamcontent.sdk.SessionTransportKind
import java.net.InetSocketAddress
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Builds the Steam Content SDK's own route description from what this platform already has.
 *
 * The SDK deliberately takes route inputs and never guesses one. This class is the only place that knows both
 * vocabularies, and it exists so the adapter above it deals in SDK types only:
 *
 * - **the CM channel** is reached directly, with the endpoint set the platform read from Steam's directory.
 *   That is how the platform's own signed-in transport reaches it, and the engine's own discovery cannot
 *   reach the directory on a network that only passes the platform's bridge.
 * - **the directory fetch** is the platform's job, through the same bridged client its other Web API calls
 *   use, so the endpoint set it hands over is one the platform already proved reachable.
 * - **trust** stays the platform store. The bridge's own root is only needed by a route that terminates TLS
 *   locally, and this route does not.
 */
@Singleton
internal class SteamContentSdkRouteFactory
    @Inject
    constructor(
        private val cmEndpoints: SteamCmEndpointSource,
        private val settingsRepository: SettingsRepository,
        private val bridge: SteamLoopbackTlsBridge,
    ) {
        /**
         * The route this platform actually has.
         *
         * The channel is reached the way the platform's own transport reaches it: directly, with the endpoint
         * set the platform read from Steam's directory, because the engine's own discovery cannot reach that
         * directory on a network that only passes the platform's bridge. The directory fetch itself goes
         * through the bridge, like every other Web API call the platform makes, and so does the trust the
         * CDN needs if a caller supplies one.
         *
         * The loopback bridge is deliberately **not** used for the channel. It only tunnels a route the
         * platform has already discovered, and it never discovers a CM endpoint: `shouldAccelerate` and
         * `requestRouteRefresh` both cover the Web API and community hosts only, so a bridged CM channel
         * cannot come up at all. The platform's own signed-in transport proves the direct channel works here.
         *
         * Returns null when the platform has no endpoint set to offer, which means the platform route is
         * unavailable rather than "connect directly": the caller must not silently fall back to the engine's
         * own discovery on a network that blocks it.
         *
         * [refreshLive] is the one bounded retry a caller makes after a transport-level logon or restore
         * failure: the cached endpoint set is exactly the input under suspicion, so the candidates are read
         * live, and a route that cannot be built from a live read is no route at all.
         */
        suspend fun platformRoute(
            cdnProxy: String? = null,
            refreshLive: Boolean = false,
        ): SteamContentSdkEngine.Route? {
            val candidates = cmEndpoints.candidates(refreshLive = refreshLive) ?: return null
            val configuredProxy = cdnProxy ?: configuredProxyUri()
            val bridgeAddress = bridge.proxy.address() as InetSocketAddress
            val bridgeHost = bridgeAddress.address?.hostAddress ?: bridgeAddress.hostString
            return sdkPlatformRoute(
                cmCandidates = candidates,
                configuredProxyUri = configuredProxy,
                authenticatedCdnProxy = configuredProxy?.let { null } ?: "$bridgeHost:${bridgeAddress.port}",
                authenticatedCdnProxyAuthorization = configuredProxy?.let { null } ?: bridge.cdnProxyAuthorization,
            )
        }

        /** The route for a network that passes Steam directly. No extra root is trusted here. */
        fun direct(cdnProxy: String? = null): SteamContentSdkEngine.Route =
            SteamContentSdkEngine.Route(cdnProxy = cdnProxy)

        /** The enabled proxy URI from the product's settings, or null for a direct route. */
        private suspend fun configuredProxyUri(): String? {
            val preferences = settingsRepository.preferences.first()
            return preferences.downloadProxyUrl.takeIf { preferences.downloadProxyEnabled }
        }
    }

/**
 * Maps the platform route into the SDK route at the Android/AAR seam.
 *
 * The SDK accepts only an HTTP proxy authority. Parsing the URI structurally prevents another proxy kind from
 * being silently stripped to a plausible-looking `host:port`. The same authority serves both SDK traffic
 * planes, and this platform supplies no proxy credential.
 */
internal fun sdkPlatformRoute(
    cmCandidates: String,
    configuredProxyUri: String?,
    authenticatedCdnProxy: String? = null,
    authenticatedCdnProxyAuthorization: String? = null,
): SteamContentSdkEngine.Route {
    val configuredProxy = configuredProxyUri?.let(::sdkHttpProxyAuthority)
    return SteamContentSdkEngine.Route(
        cmCandidates = cmCandidates,
        connectAttempts = cmCandidates.lineSequence().count { it.isNotBlank() }.coerceAtMost(MAX_CM_CANDIDATES),
        transport = SessionTransportKind.WEBSOCKET,
        cdnProxy = configuredProxy ?: authenticatedCdnProxy,
        cdnProxyAuthorization = authenticatedCdnProxyAuthorization.takeIf { configuredProxy == null && authenticatedCdnProxy != null },
        cmProxy = configuredProxy,
    )
}

/** Returns the `host:port` accepted by the SDK, rejecting every non-HTTP or credentialed proxy URI. */
internal fun sdkHttpProxyAuthority(raw: String): String {
    val uri =
        runCatching { URI(raw.trim()) }
            .getOrElse { throw IllegalArgumentException("Invalid SDK proxy URI", it) }
    require(uri.scheme?.lowercase() == "http") { "The Steam Content SDK supports only an HTTP proxy" }
    require(!uri.host.isNullOrBlank()) { "The Steam Content SDK proxy URI has no host" }
    require(uri.userInfo == null) { "The Steam Content SDK route does not accept proxy credentials" }
    require(uri.query == null && uri.fragment == null) { "The Steam Content SDK proxy URI cannot carry query or fragment data" }
    require(uri.path.isNullOrEmpty() || uri.path == "/") { "The Steam Content SDK proxy URI cannot carry a path" }
    require(uri.port == -1 || uri.port in 1..65_535) { "The Steam Content SDK proxy port is invalid" }
    val port = uri.port.takeIf { it != -1 } ?: DEFAULT_HTTP_PROXY_PORT
    val host = uri.host.let { if (':' in it) "[$it]" else it }
    return "$host:$port"
}

private const val DEFAULT_HTTP_PROXY_PORT = 80
