package com.wallhub.android.feature.setup

import com.wallhub.android.data.steamaccess.SteamBlockReason
import com.wallhub.android.data.steamaccess.SteamBridgeReason
import com.wallhub.android.data.steamaccess.SteamEgress
import com.wallhub.android.data.steamaccess.SteamEgressDecisionSource
import com.wallhub.android.data.steamaccess.SteamEgressPlan
import com.wallhub.android.data.steamaccess.SteamEgressProblem
import com.wallhub.android.data.steamaccess.SteamEnvironment
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * The connection check measures the egress the app finally uses, not the one it had when the page opened.
 *
 * The field report showed all four Steam items "Timed out" on a network whose probe had not finished yet:
 * the page read the provisional direct baseline, shrank its own timeouts, and never looked at the bridge
 * that the decision produced seconds later. These are the rules that stop that, exercised with a scripted
 * egress so no real China network is needed.
 */
class SetupNetworkCheckTest {
    private fun plan(
        generation: Long = 1L,
        egress: SteamEgress = SteamEgress.Direct,
        environment: SteamEnvironment = SteamEnvironment.Blocked(SteamBlockReason.CONNECT_FAILURE),
        problem: SteamEgressProblem? = null,
        decided: Boolean = true,
    ) = SteamEgressPlan(
        generation = generation,
        networkFingerprint = "fp",
        networkType = "cellular",
        baseline = SteamEgress.Direct,
        effective = egress,
        environment = environment,
        problem = problem,
        baselineReachable = environment is SteamEnvironment.Open,
        decided = decided,
    )

    private fun bridgePlan(generation: Long = 7L) =
        plan(
            generation = generation,
            egress = SteamEgress.Bridge(SteamBridgeReason.STEAM_ONLY_REACHABLE),
        )

    /** A decided plan for the decision wait, and whatever the manager has published for the cheap read. */
    private class ScriptedEgress(
        private val decidedForWait: SteamEgressPlan?,
        private val published: () -> SteamEgressPlan,
    ) : SteamEgressDecisionSource {
        var waits = 0
            private set

        override fun publishedEgressPlan(): SteamEgressPlan = published()

        override suspend fun awaitDecidedEgressPlan(budgetMillis: Long): SteamEgressPlan? {
            waits += 1
            return decidedForWait
        }
    }

    private fun passed() =
        SetupNetworkPreflightResult(
            SetupNetworkOverallStatus.PASSED,
            SetupNetworkCheck.entries.map { SetupNetworkCheckResult(it, SetupNetworkCheckStatus.PASSED, 12L) },
        )

    private fun failed() =
        SetupNetworkPreflightResult(
            SetupNetworkOverallStatus.WARNING,
            SetupNetworkCheck.entries.map {
                SetupNetworkCheckResult(it, SetupNetworkCheckStatus.FAILED, 34_000L, "Timed out", SetupProbeStage.BUDGET)
            },
        )

    private fun SteamEgressPlan.kindLabel(): String =
        when (effective) {
            SteamEgress.Direct -> "direct"
            is SteamEgress.Bridge -> "bridge"
            is SteamEgress.Proxy -> "proxy"
            is SteamEgress.Unavailable -> "unavailable"
        }

    @Test
    fun `the check runs on the decided egress instead of the provisional direct baseline`() {
        runBlocking {
            val decided = bridgePlan()
            val egress = ScriptedEgress(decided, published = { decided })
            val measured = mutableListOf<String>()

            val result =
                runSetupNetworkCheckOnLatestEgress(egress) { measuredPlan ->
                    measured += measuredPlan.kindLabel()
                    passed()
                }

            assertEquals(listOf("bridge"), measured)
            assertEquals(SetupNetworkOverallStatus.PASSED, result.overallStatus)
            assertEquals(1, egress.waits, "the wait for a decision must be one bounded wait")
        }
    }

    @Test
    fun `a bridge published while the check runs replaces the direct result`() {
        runBlocking {
            var published = plan(generation = 7L)
            var decided = plan(generation = 7L)
            val source =
                object : SteamEgressDecisionSource {
                    var calls = 0

                    override fun publishedEgressPlan(): SteamEgressPlan = published

                    override suspend fun awaitDecidedEgressPlan(budgetMillis: Long): SteamEgressPlan {
                        calls += 1
                        return decided
                    }
                }
            val attempts = mutableListOf<String>()

            val result =
                runSetupNetworkCheckOnLatestEgress(source) { measuredPlan ->
                    attempts += measuredPlan.kindLabel()
                    if (attempts.size == 1) {
                        // The decision lands while the first attempt is still measuring.
                        decided = bridgePlan(generation = 7L)
                        published = decided
                        failed()
                    } else {
                        passed()
                    }
                }

            assertEquals(listOf("direct", "bridge"), attempts)
            assertEquals(SetupNetworkOverallStatus.PASSED, result.overallStatus)
            assertTrue(result.items.all { it.status == SetupNetworkCheckStatus.PASSED })
        }
    }

    @Test
    fun `the retry waits for the moved egress instead of measuring a baseline`() {
        runBlocking {
            var published = plan(generation = 7L)
            var decided = plan(generation = 7L)
            val source =
                object : SteamEgressDecisionSource {
                    var calls = 0

                    override fun publishedEgressPlan(): SteamEgressPlan = published

                    override suspend fun awaitDecidedEgressPlan(budgetMillis: Long): SteamEgressPlan {
                        calls += 1
                        if (calls == 2) decided = bridgePlan(generation = 7L)
                        return decided
                    }
                }
            val attempts = mutableListOf<String>()

            runSetupNetworkCheckOnLatestEgress(source) { measuredPlan ->
                attempts += measuredPlan.kindLabel()
                if (attempts.size == 1) published = bridgePlan(generation = 7L)
                passed()
            }

            assertEquals(listOf("direct", "bridge"), attempts)
        }
    }

    @Test
    fun `an undecided egress is reported as a budget failure instead of being measured`() {
        runBlocking {
            val egress = ScriptedEgress(decidedForWait = null, published = { plan(decided = false) })
            var attempts = 0

            val result =
                runSetupNetworkCheckOnLatestEgress(egress) {
                    attempts += 1
                    passed()
                }

            assertEquals(0, attempts, "an undecided egress must never be measured")
            assertEquals(SetupNetworkOverallStatus.WARNING, result.overallStatus)
            assertEquals(SetupNetworkCheck.entries.size, result.items.size)
            assertTrue(result.items.all { it.status == SetupNetworkCheckStatus.FAILED })
            assertTrue(result.items.all { it.stage == SetupProbeStage.BUDGET })
        }
    }

    @Test
    fun `the retry is bounded so a churning network cannot loop the check`() {
        runBlocking {
            var generation = 1L
            val egress =
                object : SteamEgressDecisionSource {
                    override fun publishedEgressPlan(): SteamEgressPlan = bridgePlan(generation)

                    override suspend fun awaitDecidedEgressPlan(budgetMillis: Long): SteamEgressPlan = plan(generation)
                }
            var attempts = 0

            runSetupNetworkCheckOnLatestEgress(egress) {
                attempts += 1
                generation += 1
                SetupNetworkPreflightResult(SetupNetworkOverallStatus.CHECKING, emptyList())
            }

            assertEquals(2, attempts, "one stale result is re-measured; the check never loops")
        }
    }

    @Test
    fun `a cancelled attempt keeps propagating instead of becoming a result`() {
        runBlocking {
            val egress = ScriptedEgress(bridgePlan(), published = { bridgePlan() })
            var propagated = 0

            try {
                runSetupNetworkCheckOnLatestEgress(egress) { throw CancellationException("page left") }
            } catch (_: CancellationException) {
                propagated += 1
            }

            assertEquals(1, propagated)
        }
    }

    @Test
    fun `a superseded egress blocks publication but a current one publishes`() {
        runBlocking {
            var current = false
            val published = mutableListOf<SetupNetworkPreflightResult>()
            val runner =
                SetupNetworkPreflightRunner(
                    probes =
                        SetupNetworkCheck.entries.associateWith {
                            SetupNetworkProbe { SetupProbeOutcome.Passed("ok") }
                        },
                )

            val staleRun =
                runner.run(
                    SetupNetworkPreflightInput(automaticAntiBlockingEnabled = true),
                    onUpdate = { snapshot -> published += snapshot },
                    isCurrent = { current },
                )
            assertEquals(SetupNetworkOverallStatus.PASSED, staleRun.overallStatus)
            assertTrue(published.isEmpty(), "a snapshot for a superseded egress was published")

            current = true
            runner.run(
                SetupNetworkPreflightInput(automaticAntiBlockingEnabled = true),
                onUpdate = { snapshot -> published += snapshot },
                isCurrent = { current },
            )
            assertEquals(6, published.size, "a snapshot for the current egress was not published")
            assertEquals(6, published.last().items.size)
        }
    }

    @Test
    fun `a failure is classified by stage instead of by the word timeout`() {
        assertEquals(SetupProbeStage.DNS, setupProbeStage(UnknownHostException("api.steampowered.com")))
        assertEquals(SetupProbeStage.TLS, setupProbeStage(SSLHandshakeException("unrecognized_name")))
        assertEquals(SetupProbeStage.TLS, setupProbeStage(IOException("wrapped", SSLHandshakeException("bad"))))
        assertEquals(SetupProbeStage.TIMEOUT, setupProbeStage(SocketTimeoutException("read timed out")))
        assertEquals(SetupProbeStage.TIMEOUT, setupProbeStage(InterruptedIOException("timeout")))
        assertEquals(SetupProbeStage.TCP, setupProbeStage(ConnectException("connection refused")))
        assertEquals(SetupProbeStage.HTTP, setupProbeStage(SetupProbeHttpException(503)))
        assertEquals(SetupProbeStage.CONTENT, setupProbeStage(SetupProbeContentException("Unexpected Community HTML")))
        assertEquals(SetupProbeStage.UNKNOWN, setupProbeStage(IllegalStateException("boom")))
        assertEquals(SetupProbeStage.PASSED, setupProbeStage(null))
    }

    @Test
    fun `the steam budgets are the bridged ones only while the plan is bridged`() {
        assertEquals(SETUP_DETAILS_PROBE_TIMEOUT_MS, setupProbeTimeoutMillis(SetupNetworkCheck.PUBLIC_DETAILS_JSON, true))
        assertEquals(SETUP_PROBE_TIMEOUT_MS, setupProbeTimeoutMillis(SetupNetworkCheck.PUBLIC_DETAILS_JSON, false))
        assertEquals(SETUP_DETAILS_PROBE_TIMEOUT_MS, setupProbeTimeoutMillis(SetupNetworkCheck.WORKSHOP_IMAGE, true))
        assertEquals(SETUP_PROBE_TIMEOUT_MS, setupProbeTimeoutMillis(SetupNetworkCheck.WORKSHOP_IMAGE, false))
        assertEquals(SETUP_WEB_PROBE_TIMEOUT_MS, setupProbeTimeoutMillis(SetupNetworkCheck.COMMUNITY_HTML, false))
        assertEquals(SETUP_DIRECTORY_PROBE_TIMEOUT_MS, setupProbeTimeoutMillis(SetupNetworkCheck.DIRECTORY, false))
    }

    @Test
    fun `every check reports the egress it measured and never a url or a credential`() {
        val result =
            SetupNetworkPreflightResult(
                SetupNetworkOverallStatus.PASSED,
                listOf(
                    SetupNetworkCheckResult(SetupNetworkCheck.DIRECTORY, SetupNetworkCheckStatus.PASSED, 1941L),
                    SetupNetworkCheckResult(
                        SetupNetworkCheck.COMMUNITY_HTML,
                        SetupNetworkCheckStatus.FAILED,
                        12_000L,
                        "Timed out",
                        SetupProbeStage.TIMEOUT,
                    ),
                ),
            )

        val attributes = setupNetworkCheckDiagnosticAttributes(bridgePlan(generation = 12L), result)

        assertEquals("bridge", attributes["egress"])
        assertEquals("direct", attributes["baseline"])
        assertEquals("12", attributes["generation"])
        assertEquals("passed:1941", attributes["check.DIRECTORY"])
        assertEquals("failed:12000:TIMEOUT", attributes["check.COMMUNITY_HTML"])
        assertEquals("blocked:connect_failure", attributes["environment"])
        assertTrue(attributes.keys.none { key -> key.contains("url", ignoreCase = true) })
        assertTrue(attributes.values.none { value -> "://" in value })
        assertTrue(attributes.values.none { value -> value.contains("hidden", ignoreCase = true) })
    }

    @Test
    fun `a bridge plan is distinguishable from a direct one`() {
        assertFalse(bridgePlan().effective is SteamEgress.Direct)
        assertTrue(bridgePlan().usesBridge)
    }
}
