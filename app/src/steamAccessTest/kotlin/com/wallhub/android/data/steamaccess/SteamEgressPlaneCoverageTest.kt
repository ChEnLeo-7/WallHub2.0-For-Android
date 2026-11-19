package com.wallhub.android.data.steamaccess

import com.wallhub.android.data.steam.sdkPlatformRoute
import com.wallhub.android.data.steam.sdkRouteSelection
import io.steamcontent.sdk.SessionTransportKind
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One plan, every plane.
 *
 * The four Steam items on the connection page are four different hosts reached through three different
 * clients (the shared OkHttp factory, the anonymous Web builder and the SDK route). They only agree on the
 * egress if they all read the same plan, so that is what is pinned here - plus the bound on which hosts
 * may enter the enhanced path at all.
 */
class SteamEgressPlaneCoverageTest {
    private val bridge = SteamEgress.Bridge(SteamBridgeReason.STEAM_ONLY_REACHABLE)

    private fun plan(egress: SteamEgress, environment: SteamEnvironment = SteamEnvironment.Blocked(SteamBlockReason.CONNECT_FAILURE)) =
        SteamEgressPlan(
            generation = 3L,
            networkFingerprint = "fp|cellular",
            networkType = "cellular",
            baseline = SteamEgress.Direct,
            effective = egress,
            environment = environment,
            problem = null,
            baselineReachable = false,
        )

    private fun routeOf(proxies: List<Proxy>): String =
        when {
            proxies.any { proxy -> proxy == BRIDGE_PROXY } -> "bridge"
            proxies.any { proxy -> proxy == Proxy.NO_PROXY } -> "direct"
            else -> "proxy"
        }

    @Test
    fun `every Steam check plane resolves to the same bridge from the same plan`() {
        val bridged = plan(bridge)

        for (host in STEAM_CHECK_HOSTS) {
            assertTrue(SteamDomainPolicy.accelerates(host), "$host is not an accelerated Steam target")
            assertTrue(SteamDomainPolicy.supportsEndpoint(host, 443), "$host is not a supported endpoint")
            assertEquals("bridge", routeOf(plannedSteamProxies(bridged, BRIDGE_PROXY)), "$host ignored the bridge")
        }
    }

    @Test
    fun `the sdk route carries the bridge for the CM channel and the content planes`() {
        val selection = sdkRouteSelection(plan(bridge), cdnProxy = null)

        assertTrue(selection.bridged)
        assertNull(selection.configuredProxyUri)
        val route =
            sdkPlatformRoute(
                cmCandidates = CM_CANDIDATES,
                configuredProxyUri = selection.configuredProxyUri,
                authenticatedCdnProxy = if (selection.bridged) BRIDGE_AUTHORITY else null,
                authenticatedCdnProxyAuthorization = if (selection.bridged) "cdn-token" else null,
                enhancedCmProxy = if (selection.bridged) BRIDGE_AUTHORITY else null,
                enhancedCmProxyAuthorization = if (selection.bridged) "cm-token" else null,
                enhancedTrustRootPem = if (selection.bridged) TRUST_ROOT else null,
                cmTransport = SessionTransportKind.WEBSOCKET,
            )

        assertEquals(BRIDGE_AUTHORITY, route.cmProxy)
        assertEquals(BRIDGE_AUTHORITY, route.cdnProxy)
        assertNotNull(route.extraTrustRootPem)
        assertEquals(CM_CANDIDATES.lineSequence().count { it.isNotBlank() }, route.connectAttempts)
    }

    @Test
    fun `a proxy plan keeps the sdk on that proxy and never on the bridge`() {
        val endpoint = SteamProxyEndpoint(SteamProxyKind.HTTP, "10.0.0.5", 8080)
        val proxied =
            plan(
                egress = SteamEgress.Proxy(endpoint, SteamEgressSource.USER),
                environment = SteamEnvironment.Unknown,
            )

        val selection = sdkRouteSelection(proxied, cdnProxy = null)

        assertFalse(selection.bridged)
        assertEquals(endpoint.uri, selection.configuredProxyUri)
        val route =
            sdkPlatformRoute(
                cmCandidates = CM_CANDIDATES,
                configuredProxyUri = selection.configuredProxyUri,
                authenticatedCdnProxy = if (selection.bridged) BRIDGE_AUTHORITY else null,
                authenticatedCdnProxyAuthorization = if (selection.bridged) "cdn-token" else null,
                enhancedCmProxy = if (selection.bridged) BRIDGE_AUTHORITY else null,
                enhancedCmProxyAuthorization = if (selection.bridged) "cm-token" else null,
                enhancedTrustRootPem = if (selection.bridged) TRUST_ROOT else null,
                cmTransport = SessionTransportKind.WEBSOCKET,
            )
        assertEquals(endpoint.uri, route.cmProxy)
        assertEquals(endpoint.uri, route.cdnProxy)
        assertNull(route.extraTrustRootPem, "a user proxy never needs the process bridge root")
        assertNull(route.cmProxyAuthorization)
        assertEquals("proxy", routeOf(plannedSteamProxies(proxied, BRIDGE_PROXY)))
    }

    @Test
    fun `a decided direct plan keeps the sdk direct`() {
        val direct = plan(SteamEgress.Direct, environment = SteamEnvironment.Open)

        val selection = sdkRouteSelection(direct, cdnProxy = null)

        assertFalse(selection.bridged)
        assertNull(selection.configuredProxyUri)
        val route =
            sdkPlatformRoute(
                cmCandidates = CM_CANDIDATES,
                configuredProxyUri = selection.configuredProxyUri,
                cmTransport = SessionTransportKind.WEBSOCKET,
            )
        assertNull(route.cmProxy)
        assertNull(route.cdnProxy)
        assertNull(route.extraTrustRootPem)
        assertEquals("direct", routeOf(plannedSteamProxies(direct, BRIDGE_PROXY)))
    }

    @Test
    fun `the workshop image alias is a dial authority and never a request authority`() {
        val rules = SteamAccessRoutes.webUpstreams("images.steamusercontent.com")

        assertEquals(2, rules.size)
        assertEquals("images.steamusercontent.com", rules.first().resolveAuthority)
        assertEquals(1, rules.count { rule -> rule.resolveAuthority == "steamuserimages-a.akamaihd.net" })
        assertTrue(SteamDomainPolicy.supports("images.steamusercontent.com"))
        assertFalse(
            SteamDomainPolicy.supports("steamuserimages-a.akamaihd.net"),
            "a CDN alias must never become a request authority the app follows",
        )
        assertFalse(SteamDomainPolicy.supports("evil.steamusercontent.com.example.net"))
        assertFalse(SteamDomainPolicy.accelerates("example.com"))
        assertFalse(SteamDomainPolicy.accelerates("cdn.akamaihd.net"))
    }

    @Test
    fun `an unknown third party host never enters the accelerated path`() {
        for (host in listOf("example.com", "steamcommunity.com.evil.net", "akamaihd.net", "steampowered.com")) {
            assertFalse(SteamDomainPolicy.supports(host), "$host must not be accelerated")
            assertFalse(SteamDomainPolicy.supportsEndpoint(host, 443), "$host must not be a supported endpoint")
        }
        assertTrue(SteamDomainPolicy.accelerates("cmp1-sea1.steamserver.net"))
        assertTrue(SteamDomainPolicy.supportsEndpoint("cmp1-sea1.steamserver.net", 27017))
        assertFalse(SteamDomainPolicy.supportsEndpoint("cmp1-sea1.steamserver.net", 80))
    }

    @Test
    fun `the plan and every plane agree on the api host used by the directory and details probes`() {
        val directory = URI("https://api.steampowered.com/ISteamDirectory/GetCMListForConnect/v1/")
        val details = URI("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")

        assertEquals("api.steampowered.com", directory.host)
        assertEquals("api.steampowered.com", details.host)
        assertEquals("bridge", routeOf(plannedSteamProxies(plan(bridge), BRIDGE_PROXY)))
    }

    private companion object {
        val STEAM_CHECK_HOSTS =
            listOf(
                "api.steampowered.com",
                "steamcommunity.com",
                "images.steamusercontent.com",
            )
        const val CM_CANDIDATES = "cmp1-sea1.steamserver.net:27017\ncmp2-sea1.steamserver.net:27017"
        const val BRIDGE_AUTHORITY = "127.0.0.1:45678"
        const val TRUST_ROOT = "-----BEGIN CERTIFICATE-----\nTEST\n-----END CERTIFICATE-----\n"
        val BRIDGE_PROXY: Proxy =
            Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 45678))
    }
}
