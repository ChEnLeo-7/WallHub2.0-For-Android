package com.wallhub.android.data.steam

import android.util.Log
import com.wallhub.android.data.steamaccess.SteamAccessManager
import com.wallhub.android.data.steamaccess.SteamDomainPolicy
import com.wallhub.android.data.steamaccess.SteamEgressPlan
import com.wallhub.android.data.steamaccess.SteamLoopbackTlsBridge
import com.wallhub.android.data.steamaccess.steamProxyEndpointOrNull
import io.steamcontent.sdk.SessionTransportKind
import java.net.InetSocketAddress
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

private const val LOG_TAG = "WallHubSdkRoute"

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
        private val bridge: SteamLoopbackTlsBridge,
        private val egress: SteamAccessManager,
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
            val plan = egress.egressPlan()
            if (plan.isUnavailable) {
                // The platform could not work out a single safe egress. Returning null makes the caller
                // report a route failure; building a direct route here would be the silent bypass.
                Log.i(LOG_TAG, "sdk route unavailable reason=${plan.problem?.name}")
                return null
            }
            val selection = sdkRouteSelection(plan, cdnProxy)
            val configuredProxy = selection.configuredProxyUri
            val useBridge = selection.bridged
            // The SDK applies a CM proxy only to its TLS/WebSocket transports, so a bridged route that kept
            // the Steam3 transport would dial Steam directly while the CDN plane used the bridge - one plan
            // with two egresses. Force the transport the loopback route can actually serve.
            val effectiveTransport = if (useBridge) SessionTransportKind.WEBSOCKET else cmTransport
            val candidates = cmRouteCandidates(directoryCandidates, effectiveTransport) ?: return null
            val bridgeAddress = bridge.proxy.address() as InetSocketAddress
            val bridgeHost = bridgeAddress.address?.hostAddress ?: bridgeAddress.hostString
            val bridgeAuthority = "$bridgeHost:${bridgeAddress.port}"
            return sdkPlatformRoute(
                cmCandidates = candidates,
                configuredProxyUri = configuredProxy,
                // Every content plane follows the one plan: with a planned proxy the SDK's CDN plane uses
                // that proxy; with a planned bridge it uses the same bridge the Web and CM planes use; a
                // direct plan is the only case that resolves content over the default network.
                authenticatedCdnProxy = if (useBridge) bridgeAuthority else null,
                authenticatedCdnProxyAuthorization = if (useBridge) bridge.cdnProxyAuthorization else null,
                enhancedCmProxy = if (useBridge) bridgeAuthority else null,
                enhancedCmProxyAuthorization = if (useBridge) bridge.cmProxyAuthorization else null,
                enhancedTrustRootPem = if (useBridge) bridge.routeTrustRootPem else null,
                cmTransport = effectiveTransport,
            )
        }

        /**
         * The semantic egress the plan currently names, for the other SDK session owners to compare.
         *
         * It does not force a decision: it is read immediately after [platformRoute] built a route, so it
         * names the plan that route came from.
         */
        fun currentEgressIdentity(): String? = egress.egressPublication.value.identity

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

/**
 * Which egress the SDK route must carry for one plan.
 *
 * It is one function because the SDK takes three inputs (a configured proxy, a bridge authority and a trust
 * root) that must never disagree: a route built from the plan's proxy while the Web plane is bridged would
 * put CM and content on two different networks. An explicit `cdnProxy` argument is the download plane's
 * own override and still wins, because the user asked for it.
 */
internal data class SdkRouteSelection(
    val configuredProxyUri: String?,
    val bridged: Boolean,
)

internal fun sdkRouteSelection(
    plan: SteamEgressPlan,
    cdnProxy: String?,
): SdkRouteSelection {
    val configured = cdnProxy?.let(::steamProxyEndpointOrNull)?.uri ?: plan.sdkProxyUri
    return SdkRouteSelection(configuredProxyUri = configured, bridged = configured == null && plan.usesBridge)
}

/**
 * The CM candidate window handed to the SDK, in the transport's own port space.
 *
 * Matches WebView's split topology: password/remembered account sessions use direct WSS, while ordinary
 * anonymous/download CM keeps Steam's advertised native Steam3 port. Null means the Directory named no
 * endpoint this platform can accelerate, which is a route failure rather than "connect directly".
 */
internal fun cmRouteCandidates(
    directoryCandidates: String,
    transport: Int,
): String? {
    val prepared = mutableListOf<String>()
    directoryCandidates.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { candidate ->
        val endpoint = parseCmEndpoint(candidate) ?: return@forEach
        val port = if (transport == SessionTransportKind.WEBSOCKET) CM_WEBSOCKET_PORT else endpoint.second
        val normalized = "${endpoint.first}:$port"
        if (normalized !in prepared) prepared += normalized
    }
    return prepared.take(MAX_CM_CANDIDATES).joinToString("\n").takeIf(String::isNotBlank)
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
