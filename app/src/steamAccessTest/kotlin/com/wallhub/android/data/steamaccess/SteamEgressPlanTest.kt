package com.wallhub.android.data.steamaccess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The single decision that every Steam plane consumes. These tests encode the audit findings
 * ANDROID-REV-001/002/003/005: a working proxy is never displaced by the enhancement, and an
 * unrepresentable or unverified egress fails explicitly instead of becoming a silent direct route.
 */
class SteamEgressPlanTest {
    private val systemProxy = SteamProxyEndpoint(SteamProxyKind.HTTP, "10.0.0.5", 8080)
    private val userProxy = SteamProxyEndpoint(SteamProxyKind.SOCKS, "10.0.0.9", 1080)

    private fun plan(
        antiBlockingEnabled: Boolean = true,
        userProxy: SteamProxyEndpoint? = null,
        systemProxy: SteamSystemProxy = SteamSystemProxy.Absent,
        environment: SteamEnvironment = SteamEnvironment.Blocked(SteamBlockReason.CONNECT_FAILURE),
        baselineReachable: Boolean = false,
        enhancedReachable: Boolean = true,
        networkType: String = "wifi",
        generation: Long = 7L,
    ) = SteamEgressPlanner.plan(
        SteamEgressInput(
            generation = generation,
            networkFingerprint = "fp|$networkType",
            networkType = networkType,
            antiBlockingEnabled = antiBlockingEnabled,
            userProxy = userProxy,
            systemProxy = systemProxy,
            environment = environment,
            baselineReachable = baselineReachable,
            enhancedReachable = enhancedReachable,
        ),
    )

    @Test
    fun `a reachable steam baseline keeps the system proxy and never bridges`() {
        val result = plan(systemProxy = SteamSystemProxy.Present(systemProxy), baselineReachable = true)

        assertIs<SteamEgress.Proxy>(result.baseline)
        assertEquals(SteamEgressSource.SYSTEM, result.baseline.source)
        assertEquals(result.baseline, result.effective)
    }

    @Test
    fun `google being unreachable does not matter when steam is reachable through the proxy`() {
        // The probe result is the Steam verdict; a blocked Google signal is not an input at all.
        val result =
            plan(
                systemProxy = SteamSystemProxy.Present(systemProxy),
                environment = SteamEnvironment.Open,
                baselineReachable = true,
            )

        assertIs<SteamEgress.Proxy>(result.effective)
        assertNull(result.problem)
    }

    @Test
    fun `an unreachable steam baseline behind a proxy still keeps that proxy`() {
        val result = plan(systemProxy = SteamSystemProxy.Present(systemProxy), baselineReachable = false)

        assertIs<SteamEgress.Proxy>(result.effective)
        assertEquals(result.baseline, result.effective)
        // The enhancement must not be offered while any representable proxy exists.
        assertIs<SteamEgress.Proxy>(result.baseline)
    }

    @Test
    fun `the user proxy wins over the system proxy and over the bridge`() {
        val result =
            plan(
                userProxy = userProxy,
                systemProxy = SteamSystemProxy.Present(systemProxy),
                baselineReachable = false,
            )

        val effective = assertIs<SteamEgress.Proxy>(result.effective)
        assertEquals(userProxy, effective.endpoint)
        assertEquals(SteamEgressSource.USER, effective.source)
    }

    @Test
    fun `an ambiguous system proxy is an explicit failure not a direct route`() {
        val result =
            plan(
                systemProxy = SteamSystemProxy.Ambiguous(SteamProxyAmbiguity.MultipleProxies),
                baselineReachable = false,
            )

        assertIs<SteamEgress.Unavailable>(result.baseline)
        assertEquals(SteamEgressProblem.AMBIGUOUS_SYSTEM_PROXY, assertIs<SteamEgress.Unavailable>(result.baseline).problem)
        assertIs<SteamEgress.Unavailable>(result.effective)
        assertEquals(SteamEgressProblem.AMBIGUOUS_SYSTEM_PROXY, result.problem)
    }

    @Test
    fun `an unreadable system proxy never becomes direct`() {
        val result = plan(systemProxy = SteamSystemProxy.Unavailable, baselineReachable = true)

        assertIs<SteamEgress.Unavailable>(result.baseline)
        assertEquals(SteamEgressProblem.SYSTEM_PROXY_UNREADABLE, result.problem)
    }

    @Test
    fun `an ambiguous proxy is not silently bridged over`() {
        val result =
            plan(
                systemProxy = SteamSystemProxy.Ambiguous(SteamProxyAmbiguity.InconsistentTargets),
                baselineReachable = false,
                enhancedReachable = true,
            )

        assertIs<SteamEgress.Unavailable>(result.effective)
    }

    @Test
    fun `bridge is used only when the baseline is direct, blocked and the enhanced path verified`() {
        val result = plan(networkType = "wifi", baselineReachable = false, enhancedReachable = true)

        assertIs<SteamEgress.Direct>(result.baseline)
        val bridge = assertIs<SteamEgress.Bridge>(result.effective)
        assertEquals(SteamBridgeReason.STEAM_ONLY_REACHABLE, bridge.reason)
    }

    @Test
    fun `a reachable direct baseline is never replaced by the bridge`() {
        val result = plan(baselineReachable = true, enhancedReachable = true)

        assertIs<SteamEgress.Direct>(result.effective)
    }

    @Test
    fun `a vpn default network preserves its own domain routing instead of bridging`() {
        val result = plan(networkType = "vpn", baselineReachable = false, enhancedReachable = true)

        assertIs<SteamEgress.Direct>(result.effective)
        assertEquals(SteamEgressProblem.VPN_POLICY_PRESERVED, result.problem)
    }

    @Test
    fun `an unverified environment never bridges and never claims direct success`() {
        val result =
            plan(
                environment = SteamEnvironment.Unknown,
                baselineReachable = false,
                enhancedReachable = true,
            )

        assertIs<SteamEgress.Direct>(result.effective)
        assertEquals(SteamEgressProblem.ENVIRONMENT_UNKNOWN, result.problem)
    }

    @Test
    fun `a proxy authentication failure is classified and never switches to direct`() {
        val result =
            plan(
                systemProxy = SteamSystemProxy.Present(systemProxy),
                environment = SteamEnvironment.Blocked(SteamBlockReason.PROXY_AUTH_REQUIRED),
                baselineReachable = false,
            )

        val effective = assertIs<SteamEgress.Proxy>(result.effective)
        assertEquals(systemProxy, effective.endpoint)
        assertNull(result.problem)
    }

    @Test
    fun `anti blocking disabled always follows the baseline`() {
        val result =
            plan(
                antiBlockingEnabled = false,
                systemProxy = SteamSystemProxy.Present(systemProxy),
                baselineReachable = false,
            )

        assertEquals(result.baseline, result.effective)
    }

    @Test
    fun `the plan carries the generation and network identity it was decided for`() {
        val result = plan(generation = 41L, networkType = "cellular")

        assertEquals(41L, result.generation)
        assertEquals("fp|cellular", result.networkFingerprint)
        assertEquals("cellular", result.networkType)
        assertTrue(result.isFor(41L))
        assertTrue(!result.isFor(42L))
    }

    @Test
    fun `the egress identity names where traffic goes and nothing else`() {
        val proxy = plan(systemProxy = SteamSystemProxy.Present(systemProxy), baselineReachable = true)
        val sameProxyOtherGeneration = plan(systemProxy = SteamSystemProxy.Present(systemProxy), generation = 99L)
        val otherProxy = plan(systemProxy = SteamSystemProxy.Present(SteamProxyEndpoint(SteamProxyKind.HTTP, "10.0.0.9", 1080)))

        assertEquals("proxy:http://10.0.0.5:8080", proxy.egressIdentity)
        // A new generation or a new probe verdict for the same route is not a moved route.
        assertEquals(proxy.egressIdentity, sameProxyOtherGeneration.egressIdentity)
        // A different proxy target is.
        assertEquals("proxy:http://10.0.0.9:1080", otherProxy.egressIdentity)
        assertEquals("proxy", proxy.egressKindLabel)
        assertTrue(!proxy.egressIdentity.orEmpty().contains("@"))
    }

    @Test
    fun `a bridged, direct and unavailable plan each have their own identity`() {
        assertEquals(
            SteamEgressPlan.DIRECT_EGRESS_IDENTITY,
            plan(baselineReachable = true).egressIdentity,
        )
        assertEquals("bridge:STEAM_ONLY_REACHABLE", plan(enhancedReachable = true).egressIdentity)
        assertEquals(
            "unavailable:AMBIGUOUS_SYSTEM_PROXY",
            plan(systemProxy = SteamSystemProxy.Ambiguous(SteamProxyAmbiguity.MultipleProxies)).egressIdentity,
        )
        assertEquals(
            "unavailable",
            plan(systemProxy = SteamSystemProxy.Ambiguous(SteamProxyAmbiguity.MultipleProxies)).egressKindLabel,
        )
    }

    @Test
    fun `an undecided plan has no egress identity to reopen on`() {
        val undecided =
            plan(baselineReachable = true).copy(
                effective = SteamEgress.Direct,
                decided = false,
            )

        assertNull(undecided.egressIdentity)
        assertEquals("undecided", undecided.egressKindLabel)
    }
}
