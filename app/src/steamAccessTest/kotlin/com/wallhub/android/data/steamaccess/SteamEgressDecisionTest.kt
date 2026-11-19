package com.wallhub.android.data.steamaccess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * The egress decision as one pipeline, driven only by injected observations.
 *
 * These are the scenarios the China-direct field report was actually about: a baseline that cannot
 * reach every Steam plane, a DoH resolution that works and a bridged dial that works, must end in a
 * bridge plan - and a verification that fails must stay a visible failure instead of a direct answer.
 */
class SteamEgressDecisionTest {
    private fun directInput(
        antiBlockingEnabled: Boolean = true,
        smartDohEnabled: Boolean = true,
        systemProxy: SteamSystemProxy = SteamSystemProxy.Absent,
        userProxy: SteamProxyEndpoint? = null,
        networkType: String = "cellular",
    ) = SteamEgressDecisionInput(
        generation = 4L,
        networkFingerprint = "fp|cellular",
        networkType = networkType,
        antiBlockingEnabled = antiBlockingEnabled,
        smartDohEnabled = smartDohEnabled,
        userProxy = userProxy,
        systemProxy = systemProxy,
    )

    @Test
    fun `blocked direct baseline plus a verified enhanced path produces a bridge plan`() =
        runBlocking {
            val verified = mutableListOf<Unit>()
            val outcome =
                SteamEgressDecisionPlanner(
                    probeEnvironment = { SteamEnvironment.Blocked(SteamBlockReason.CONNECT_FAILURE) },
                    verifyEnhancedPath = {
                        verified += Unit
                        SteamEnhancedPath.reachable(211L)
                    },
                ).decide(directInput())

            val bridge = assertIs<SteamEgress.Bridge>(outcome.plan.effective)
            assertEquals(SteamBridgeReason.STEAM_ONLY_REACHABLE, bridge.reason)
            assertTrue(outcome.plan.usesBridge)
            assertTrue(outcome.plan.decided)
            assertNull(outcome.plan.problem)
            assertEquals(1, verified.size)
            assertEquals(SteamEnhancedPathStage.REACHED, outcome.enhancedPath?.stage)
        }

    @Test
    fun `a fully reachable direct baseline stays direct and never dials the enhanced path`() =
        runBlocking {
            var dials = 0
            val outcome =
                SteamEgressDecisionPlanner(
                    probeEnvironment = { SteamEnvironment.Open },
                    verifyEnhancedPath = {
                        dials += 1
                        SteamEnhancedPath.reachable(1L)
                    },
                ).decide(directInput())

            assertEquals(SteamEgress.Direct, outcome.plan.effective)
            assertFalse(outcome.plan.usesBridge)
            assertNull(outcome.plan.problem)
            assertEquals(0, dials)
            assertNull(outcome.enhancedPath)
        }

    @Test
    fun `an existing user proxy is never replaced by a bridge`() =
        runBlocking {
            var dials = 0
            val endpoint = SteamProxyEndpoint(SteamProxyKind.HTTP, "10.0.0.5", 8080)
            val outcome =
                SteamEgressDecisionPlanner(
                    probeEnvironment = { SteamEnvironment.Blocked(SteamBlockReason.CONNECT_FAILURE) },
                    verifyEnhancedPath = {
                        dials += 1
                        SteamEnhancedPath.reachable(1L)
                    },
                ).decide(directInput(userProxy = endpoint))

            val proxy = assertIs<SteamEgress.Proxy>(outcome.plan.effective)
            assertEquals(SteamEgressSource.USER, proxy.source)
            assertEquals(0, dials)
        }

    @Test
    fun `a proxy that answers 407 is reported as such and never bridged or dialled around`() =
        runBlocking {
            var dials = 0
            val endpoint = SteamProxyEndpoint(SteamProxyKind.HTTP, "10.0.0.5", 8080)
            val outcome =
                SteamEgressDecisionPlanner(
                    probeEnvironment = { SteamEnvironment.Blocked(SteamBlockReason.PROXY_AUTH_REQUIRED) },
                    verifyEnhancedPath = {
                        dials += 1
                        SteamEnhancedPath.reachable(1L)
                    },
                ).decide(directInput(systemProxy = SteamSystemProxy.Present(endpoint)))

            assertEquals(
                SteamEgress.Proxy(endpoint, SteamEgressSource.SYSTEM),
                outcome.plan.effective,
            )
            assertEquals(0, dials)
            assertEquals(SteamBlockReason.PROXY_AUTH_REQUIRED, assertIs<SteamEnvironment.Blocked>(outcome.plan.environment).reason)
        }

    @Test
    fun `an unrepresentable system proxy stays an explicit unavailable egress`() =
        runBlocking {
            var dials = 0
            val outcome =
                SteamEgressDecisionPlanner(
                    probeEnvironment = { SteamEnvironment.Blocked(SteamBlockReason.CONNECT_FAILURE) },
                    verifyEnhancedPath = {
                        dials += 1
                        SteamEnhancedPath.reachable(1L)
                    },
                ).decide(
                    directInput(
                        systemProxy = SteamSystemProxy.Ambiguous(SteamProxyAmbiguity.EmptySelection),
                    ),
                )

            assertEquals(
                SteamEgress.Unavailable(SteamEgressProblem.AMBIGUOUS_SYSTEM_PROXY),
                outcome.plan.effective,
            )
            assertTrue(outcome.plan.isUnavailable)
            assertEquals(0, dials)
            // A plan the app could not represent must never be answered with a direct connection.
            assertFalse(outcome.plan.effective is SteamEgress.Direct)
        }

    @Test
    fun `an unknown environment is neither open nor restricted and does not bridge`() =
        runBlocking {
            var dials = 0
            val outcome =
                SteamEgressDecisionPlanner(
                    probeEnvironment = { SteamEnvironment.Unknown },
                    verifyEnhancedPath = {
                        dials += 1
                        SteamEnhancedPath.reachable(1L)
                    },
                ).decide(directInput())

            assertEquals(SteamEgress.Direct, outcome.plan.effective)
            assertEquals(SteamEgressProblem.ENVIRONMENT_UNKNOWN, outcome.plan.problem)
            assertEquals(0, dials)
        }

    @Test
    fun `a vpn default network preserves its policy instead of bridging`() =
        runBlocking {
            var dials = 0
            val outcome =
                SteamEgressDecisionPlanner(
                    probeEnvironment = { SteamEnvironment.Blocked(SteamBlockReason.CONNECT_FAILURE) },
                    verifyEnhancedPath = {
                        dials += 1
                        SteamEnhancedPath.reachable(1L)
                    },
                ).decide(directInput(networkType = "vpn"))

            assertEquals(SteamEgress.Direct, outcome.plan.effective)
            assertEquals(SteamEgressProblem.VPN_POLICY_PRESERVED, outcome.plan.problem)
            assertEquals(0, dials)
        }

    @Test
    fun `a failed enhanced path keeps a named stage and never pretends the bridge works`() =
        runBlocking {
            for (failure in ENHANCED_FAILURES) {
                val outcome =
                    SteamEgressDecisionPlanner(
                        probeEnvironment = { SteamEnvironment.Blocked(SteamBlockReason.CONNECT_FAILURE) },
                        verifyEnhancedPath = { SteamEnhancedPath.unreachable(failure, 640L) },
                    ).decide(directInput())

                assertEquals(SteamEgress.Direct, outcome.plan.effective)
                assertFalse(outcome.plan.usesBridge)
                assertEquals(SteamEgressProblem.ENHANCED_PATH_UNAVAILABLE, outcome.plan.problem)
                assertEquals(failure, outcome.enhancedPath?.stage)
            }
        }

    @Test
    fun `anti blocking disabled keeps a blocked direct baseline direct`() =
        runBlocking {
            var dials = 0
            val outcome =
                SteamEgressDecisionPlanner(
                    probeEnvironment = { SteamEnvironment.Blocked(SteamBlockReason.TIMEOUT) },
                    verifyEnhancedPath = {
                        dials += 1
                        SteamEnhancedPath.reachable(1L)
                    },
                ).decide(directInput(antiBlockingEnabled = false))

            assertEquals(SteamEgress.Direct, outcome.plan.effective)
            assertNull(outcome.plan.problem)
            assertEquals(0, dials)
        }

    @Test
    fun `a cancelled verification propagates instead of deciding the network`() =
        runBlocking {
            var cancelled = 0
            val planner =
                SteamEgressDecisionPlanner(
                    probeEnvironment = { SteamEnvironment.Blocked(SteamBlockReason.CONNECT_FAILURE) },
                    verifyEnhancedPath = { throw CancellationException("superseded") },
                )
            try {
                planner.decide(directInput())
            } catch (_: CancellationException) {
                cancelled += 1
            }

            assertEquals(1, cancelled, "a superseded probe must not publish an unreachable verdict")
        }

    private companion object {
        val ENHANCED_FAILURES =
            listOf(
                SteamEnhancedPathStage.DNS_FAILURE,
                SteamEnhancedPathStage.CONNECT_FAILURE,
                SteamEnhancedPathStage.TIMEOUT,
                SteamEnhancedPathStage.TLS_FAILURE,
                SteamEnhancedPathStage.BUDGET_EXHAUSTED,
            )
    }
}
