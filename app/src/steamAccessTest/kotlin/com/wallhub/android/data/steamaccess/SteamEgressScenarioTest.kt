package com.wallhub.android.data.steamaccess

import com.wallhub.android.data.steam.sdkPlatformRoute
import io.steamcontent.sdk.SessionTransportKind
import java.net.Proxy
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The end-to-end egress scenarios from the audit, run without touching the device's real network.
 *
 * E1 and E3 are the two failures that made "automatic anti-blocking" interfere with an existing proxy:
 * a proxy that reaches Steam while Google is blocked (E1), and a proxy that answers CONNECT with 407
 * (E3). Both must keep the plan on the proxy, and neither may produce a bridge or a direct answer.
 *
 * The 407 case runs the production probe against a real loopback CONNECT proxy, so the classification
 * is driven by OkHttp's own tunnel failure rather than by a synthesised exception. E1's "Steam is
 * reachable through the proxy" verdict is supplied as the probe outcome: producing a certificate the
 * probe would accept for a Steam host would mean weakening its trust store, which is not a test the
 * app may pass with.
 */
class SteamEgressScenarioTest {
    /**
     * E1: the proxy reaches Steam while Google's 204 is blocked.
     *
     * Google reachability is not an input to [SteamEgressPlanner] at all - it survives only in the
     * diagnostics-only [SteamNetworkEnvironmentProbe.auxiliaryInternetReachable] - so the blocked signal
     * has no way to reach this decision. What the test pins is the outcome that used to be wrong: even
     * with a verified enhanced path available (the old trigger for the bridge), a baseline that already
     * reaches Steam through a proxy stays on that proxy, and no plane is handed a direct or bridged route.
     */
    @Test
    fun `E1 a proxy that reaches Steam keeps the plan on the proxy while Google stays blocked`() {
        val endpoint = SteamProxyEndpoint(SteamProxyKind.HTTP, "10.0.0.5", 8080)
        val plan =
            SteamEgressPlanner.plan(
                SteamEgressInput(
                    generation = 9L,
                    networkFingerprint = "fp|wifi",
                    networkType = "wifi",
                    antiBlockingEnabled = true,
                    userProxy = null,
                    systemProxy = SteamSystemProxy.Present(endpoint),
                    environment = SteamEnvironment.Open,
                    baselineReachable = true,
                    // The enhancement verified itself and still must not be used while a proxy exists.
                    enhancedReachable = true,
                ),
            )

        val effective = assertIs<SteamEgress.Proxy>(plan.effective)
        assertEquals(SteamEgressSource.SYSTEM, effective.source)
        assertEquals(endpoint, effective.endpoint)
        assertFalse(plan.usesBridge)
        assertFalse(plan.isUnavailable)
        assertEquals(endpoint.uri, plan.sdkProxyUri)

        assertEquals(Route.PROXY, routeOf(plannedSteamProxies(plan, BRIDGE_PROXY)))
        val sdkRoute =
            sdkPlatformRoute(
                cmCandidates = CM_CANDIDATE,
                configuredProxyUri = plan.sdkProxyUri,
                cmTransport = SessionTransportKind.WEBSOCKET,
            )
        assertEquals(Route.PROXY, routeOfSdk(sdkRoute.cmProxy))
        assertEquals(Route.PROXY, routeOfSdk(sdkRoute.cdnProxy))
        assertNull(sdkRoute.extraTrustRootPem)
    }

    @Test
    fun `E3 a proxy that answers CONNECT with 407 is reported as authentication and never bridges or goes direct`() =
        runBlocking {
            FakeHttpConnectProxy(status = FakeHttpConnectProxy.PROXY_AUTH_REQUIRED).use { proxy ->
                val endpoint = SteamProxyEndpoint(SteamProxyKind.HTTP, LOOPBACK, proxy.port)
                val environment =
                    SteamNetworkEnvironmentProbe().probe(
                        SteamEgress.Proxy(endpoint, SteamEgressSource.SYSTEM),
                    )

                // 1. The probe's own verdict names proxy authentication, not a generic connect failure.
                val blocked = assertIs<SteamEnvironment.Blocked>(environment)
                assertEquals(SteamBlockReason.PROXY_AUTH_REQUIRED, blocked.reason)

                // 2. The one egress decision keeps the proxy that failed: no bridge, no direct, no DoH.
                val plan =
                    SteamEgressPlanner.plan(
                        SteamEgressInput(
                            generation = 12L,
                            networkFingerprint = "fp|wifi",
                            networkType = "wifi",
                            antiBlockingEnabled = true,
                            userProxy = null,
                            systemProxy = SteamSystemProxy.Present(endpoint),
                            environment = environment,
                            baselineReachable = false,
                            enhancedReachable = false,
                        ),
                    )
                val effective = assertIs<SteamEgress.Proxy>(plan.effective)
                assertEquals(endpoint, effective.endpoint)
                assertFalse(plan.usesBridge)
                assertFalse(plan.isUnavailable)
                assertEquals(endpoint.uri, plan.sdkProxyUri)

                // 2b. The DoH + verified-TLS path that the enhancement would take is closed: its gate
                // requires a direct baseline, so a proxy that answers 407 is never probed around and no
                // Steam address is ever resolved through DoH on this decision.
                assertFalse(
                    steamBridgeMayBeVerified(
                        baseline = plan.baseline,
                        antiBlockingEnabled = true,
                        smartDohEnabled = true,
                        baselineReachable = false,
                        networkType = "wifi",
                    ),
                )

                // 3. Every plane is told the proxy. None is told a bridge, and none is left direct.
                assertEquals(Route.PROXY, routeOf(plannedSteamProxies(plan, BRIDGE_PROXY)))
                val sdkRoute =
                    sdkPlatformRoute(
                        cmCandidates = CM_CANDIDATE,
                        configuredProxyUri = plan.sdkProxyUri,
                        cmTransport = SessionTransportKind.WEBSOCKET,
                    )
                assertEquals(Route.PROXY, routeOfSdk(sdkRoute.cmProxy))
                assertEquals(Route.PROXY, routeOfSdk(sdkRoute.cdnProxy))
                assertNull(sdkRoute.extraTrustRootPem)
                assertNull(sdkRoute.cmProxyAuthorization)
                assertNull(sdkRoute.cdnProxyAuthorization)

                // 4. The CONNECT the proxy saw carried no credential, and it was the only egress used.
                assertTrue(proxy.requests.isNotEmpty(), "the probe never reached the proxy")
                assertEquals(STEAM_PROBE_AUTHORITIES, proxy.requests.map { it.authority }.toSet())
                assertTrue(proxy.requests.all { it.method == "CONNECT" })
                assertTrue(
                    proxy.requests.none { request ->
                        request.headers.any { header -> header.startsWith(PROXY_AUTHORIZATION, ignoreCase = true) }
                    },
                    "a proxy credential was sent while only classifying the failure",
                )
                assertNull(proxy.failureOrNull())
            }
        }

    /**
     * The gate in front of the only intrusive probe the app can run. A representable proxy, a VPN, a
     * disabled switch or a reachable baseline each keep the DoH resolve and the verified TLS dial from
     * running at all - which is what "407 must not touch DoH" means operationally.
     */
    @Test
    fun `the verified enhanced path is only ever attempted on an unreachable direct baseline`() {
        assertFalse(steamBridgeMayBeVerified(SteamEgress.Proxy(ENDPOINT, SteamEgressSource.SYSTEM), true, true, false, "wifi"))
        assertFalse(steamBridgeMayBeVerified(SteamEgress.Unavailable(SteamEgressProblem.AMBIGUOUS_SYSTEM_PROXY), true, true, false, "wifi"))
        assertFalse(steamBridgeMayBeVerified(SteamEgress.Direct, true, true, true, "wifi"))
        assertFalse(steamBridgeMayBeVerified(SteamEgress.Direct, true, true, false, "vpn"))
        assertFalse(steamBridgeMayBeVerified(SteamEgress.Direct, false, true, false, "wifi"))
        assertFalse(steamBridgeMayBeVerified(SteamEgress.Direct, true, false, false, "wifi"))
        assertTrue(steamBridgeMayBeVerified(SteamEgress.Direct, true, true, false, "wifi"))
    }

    private fun routeOf(proxies: List<Proxy>): Route =
        when {
            proxies.isEmpty() -> Route.DIRECT
            proxies.any { proxy -> proxy == Proxy.NO_PROXY } -> Route.DIRECT
            proxies.contains(BRIDGE_PROXY) -> Route.BRIDGE
            else -> Route.PROXY
        }

    private fun routeOfSdk(proxy: String?): Route =
        when (proxy) {
            null -> Route.DIRECT
            BRIDGE_AUTHORITY -> Route.BRIDGE
            else -> Route.PROXY
        }

    private enum class Route { DIRECT, PROXY, BRIDGE }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CM_CANDIDATE = "cmp1-sea1.steamserver.net:443"
        const val BRIDGE_AUTHORITY = "127.0.0.1:45678"
        const val PROXY_AUTHORIZATION = "Proxy-Authorization"
        val ENDPOINT = SteamProxyEndpoint(SteamProxyKind.HTTP, "10.0.0.5", 8080)
        val BRIDGE_PROXY: Proxy = Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved("127.0.0.1", 45678))
        val STEAM_PROBE_AUTHORITIES: Set<String> =
            STEAM_PROBE_TARGETS.map { target ->
                val uri = URI(target)
                "${uri.host}:${if (uri.port == -1) 443 else uri.port}"
            }.toSet()
    }
}
