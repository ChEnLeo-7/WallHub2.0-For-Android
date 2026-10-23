package com.wallhub.android.data.steam

import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.data.steamaccess.SteamDomainPolicy
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
 * - **the CM channel** uses the platform's authenticated loopback route. The bridge is transparent while
 *   enhancement is disabled and selects strictly verified enhanced candidates while it is enabled.
 * - **the directory fetch** is the platform's job, through the same bridged client its other Web API calls
 *   use, so the endpoint set it hands over is one the platform already proved reachable.
 * - **trust** stays the platform store plus one process-private root scoped to this immutable SDK route; the
 *   root is used only when the bridge terminates loopback TLS after strictly validating Steam upstream.
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
         * Directory discovery uses the platform-enhanced Web API route. Account CM uses direct WSS and public
         * CM uses Steam3, matching WebView/SteamKit. SteamPipe content follows the Directory-advertised scheme
         * directly; an explicit user proxy still takes precedence for the SDK content route.
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
            cmTransport: Int = SessionTransportKind.WEBSOCKET,
        ): SteamContentSdkEngine.Route? {
            val directoryCandidates = cmEndpoints.candidates(refreshLive = refreshLive) ?: return null
            val preferences = settingsRepository.preferences.first()
            val configuredProxy =
                cdnProxy ?: preferences.downloadProxyUrl.takeIf { preferences.downloadProxyEnabled }
            val preparedCandidates = mutableListOf<String>()
            directoryCandidates.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { candidate ->
                val endpoint = parseCmEndpoint(candidate) ?: return@forEach
                // Match WebView's split topology: password/remembered account sessions use direct WSS, while
                // ordinary anonymous/download CM keeps Steam's advertised native Steam3 port.
                val port = if (cmTransport == SessionTransportKind.WEBSOCKET) CM_WEBSOCKET_PORT else endpoint.second
                val normalized = "${endpoint.first}:$port"
                if (normalized !in preparedCandidates) preparedCandidates += normalized
            }
            val candidates = preparedCandidates.joinToString("\n").takeIf(String::isNotBlank) ?: return null
            val bridgeAddress = bridge.proxy.address() as InetSocketAddress
            val bridgeHost = bridgeAddress.address?.hostAddress ?: bridgeAddress.hostString
            val bridgeAuthority = "$bridgeHost:${bridgeAddress.port}"
            return sdkPlatformRoute(
                cmCandidates = candidates,
                configuredProxyUri = configuredProxy,
                // Match WebView/DepotDownloader's nearest strategy: SteamPipe content connects directly using
                // the scheme Steam Directory advertised. Only an explicit user HTTP proxy replaces this route.
                authenticatedCdnProxy = null,
                authenticatedCdnProxyAuthorization = null,
                enhancedCmProxy = configuredProxy?.let { null } ?: bridgeAuthority,
                enhancedCmProxyAuthorization = configuredProxy?.let { null } ?: bridge.cmProxyAuthorization,
                enhancedTrustRootPem = configuredProxy?.let { null } ?: bridge.routeTrustRootPem,
                cmTransport = cmTransport,
            )
        }

        /** The route for a network that passes Steam directly. No extra root is trusted here. */
        fun direct(cdnProxy: String? = null): SteamContentSdkEngine.Route =
            SteamContentSdkEngine.Route(cdnProxy = cdnProxy)
    }

/**
 * Maps the platform route into the SDK route at the Android/AAR seam.
 *
 * User-configured HTTP(S) and SOCKS proxy URIs are validated and normalized without discarding their scheme;
 * the newer SDK parses the complete URI itself. The same URI serves both SDK traffic planes, and this platform
 * supplies no user-proxy credential. Legacy internal bridge values remain unchanged as `host:port` authorities
 * or explicit HTTP URIs.
 */
internal fun sdkPlatformRoute(
    cmCandidates: String,
    configuredProxyUri: String?,
    authenticatedCdnProxy: String? = null,
    authenticatedCdnProxyAuthorization: String? = null,
    enhancedCmProxy: String? = null,
    enhancedCmProxyAuthorization: String? = null,
    enhancedTrustRootPem: String? = null,
    cmTransport: Int = SessionTransportKind.WEBSOCKET,
): SteamContentSdkEngine.Route {
    val configuredProxy = configuredProxyUri?.let(::sdkProxyUri)
    return SteamContentSdkEngine.Route(
        cmCandidates = cmCandidates,
        connectAttempts = cmCandidates.lineSequence().count { it.isNotBlank() }.coerceAtMost(MAX_CM_CANDIDATES),
        transport = cmTransport,
        cdnProxy = configuredProxy ?: authenticatedCdnProxy,
        cdnProxyAuthorization = authenticatedCdnProxyAuthorization.takeIf { configuredProxy == null && authenticatedCdnProxy != null },
        cmProxy = configuredProxy ?: enhancedCmProxy,
        cmProxyAuthorization = enhancedCmProxyAuthorization.takeIf { configuredProxy == null && enhancedCmProxy != null },
        extraTrustRootPem =
            enhancedTrustRootPem.takeIf {
                configuredProxy == null && (enhancedCmProxy != null || authenticatedCdnProxy != null)
            },
    )
}

private fun parseCmEndpoint(candidate: String): Pair<String, Int>? {
    val separator = candidate.lastIndexOf(':')
    if (separator <= 0 || separator == candidate.lastIndex) return null
    val host = candidate.substring(0, separator).trim().removePrefix("[").removeSuffix("]")
    val port = candidate.substring(separator + 1).trim().toIntOrNull() ?: return null
    if (!SteamDomainPolicy.supportsEndpoint(host, port)) return null
    return host to port
}

/** Validates and normalizes the complete proxy URI consumed by the SDK. */
internal fun sdkProxyUri(raw: String): String {
    val uri =
        runCatching { URI(raw.trim()) }
            .getOrElse { throw IllegalArgumentException("Invalid SDK proxy URI", it) }
    val scheme = uri.scheme?.lowercase()
    require(scheme in SDK_PROXY_DEFAULT_PORTS) { "The Steam Content SDK proxy scheme is unsupported" }
    require(!uri.host.isNullOrBlank()) { "The Steam Content SDK proxy URI has no host" }
    require(uri.userInfo == null) { "The Steam Content SDK route does not accept proxy credentials" }
    require(uri.query == null && uri.fragment == null) { "The Steam Content SDK proxy URI cannot carry query or fragment data" }
    require(uri.path.isNullOrEmpty() || uri.path == "/") { "The Steam Content SDK proxy URI cannot carry a path" }
    require(uri.port == -1 || uri.port in 1..65_535) { "The Steam Content SDK proxy port is invalid" }
    val normalizedScheme = requireNotNull(scheme)
    val port = uri.port.takeIf { it != -1 } ?: requireNotNull(SDK_PROXY_DEFAULT_PORTS[normalizedScheme])
    val rawHost = requireNotNull(uri.host).removePrefix("[").removeSuffix("]").lowercase()
    val host = if (':' in rawHost) "[$rawHost]" else rawHost
    return "$normalizedScheme://$host:$port"
}

private val SDK_PROXY_DEFAULT_PORTS =
    mapOf(
        "http" to 80,
        "https" to 443,
        "socks" to 1080,
        "socks5" to 1080,
    )
