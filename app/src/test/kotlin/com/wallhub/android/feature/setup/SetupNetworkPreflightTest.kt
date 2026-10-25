package com.wallhub.android.feature.setup

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SetupNetworkPreflightTest {
    @Test
    fun `disabled automatic anti blocking still runs every fixed connectivity check`() = runTest {
        var calls = 0
        val runner = SetupNetworkPreflightRunner(
            probes = SetupNetworkCheck.entries.associateWith {
                SetupNetworkProbe { calls++; SetupProbeOutcome.Passed("reachable") }
            },
            nowMillis = { 10L },
        )

        val result = runner.run(SetupNetworkPreflightInput(automaticAntiBlockingEnabled = false))

        assertEquals(SetupNetworkCheck.entries.size, calls)
        assertEquals(SetupNetworkOverallStatus.PASSED, result.overallStatus)
        assertTrue(result.items.all { it.status == SetupNetworkCheckStatus.PASSED })
    }

    @Test
    fun `enabled check reports each outcome and warning overall without blocking continuation`() = runTest {
        var clock = 0L
        val runner = SetupNetworkPreflightRunner(
            probes = mapOf(
                SetupNetworkCheck.DIRECTORY to SetupNetworkProbe { SetupProbeOutcome.Passed("2 CM endpoints") },
                SetupNetworkCheck.PUBLIC_DETAILS_JSON to SetupNetworkProbe { throw IOException("HTTP 503") },
                SetupNetworkCheck.COMMUNITY_HTML to SetupNetworkProbe { SetupProbeOutcome.Passed("Workshop page") },
                SetupNetworkCheck.WORKSHOP_IMAGE to SetupNetworkProbe { SetupProbeOutcome.Passed("image/png") },
                SetupNetworkCheck.CM_TRANSPORT to SetupNetworkProbe { SetupProbeOutcome.Skipped("No safe anonymous transport probe") },
            ),
            nowMillis = { clock.also { clock += 7L } },
        )

        val result = runner.run(SetupNetworkPreflightInput(automaticAntiBlockingEnabled = true))

        assertEquals(SetupNetworkOverallStatus.WARNING, result.overallStatus)
        assertEquals(SetupNetworkCheckStatus.FAILED, result.items.single { it.check == SetupNetworkCheck.PUBLIC_DETAILS_JSON }.status)
        assertEquals(7L, result.items.first().durationMillis)
        assertTrue(result.mayContinue)
    }

    @Test
    fun `passed and deliberately skipped checks produce passed overall`() = runTest {
        val runner = SetupNetworkPreflightRunner(
            probes = SetupNetworkCheck.entries.associateWith { check ->
                SetupNetworkProbe {
                    if (check == SetupNetworkCheck.CM_TRANSPORT) {
                        SetupProbeOutcome.Skipped("unsafe without credentials")
                    } else {
                        SetupProbeOutcome.Passed("reachable")
                    }
                }
            },
            nowMillis = { 0L },
        )

        val result = runner.run(SetupNetworkPreflightInput(automaticAntiBlockingEnabled = true))

        assertEquals(SetupNetworkOverallStatus.PASSED, result.overallStatus)
        assertTrue(result.mayContinue)
    }

    @Test
    fun `Web probes receive budgets that can complete response failover`() {
        assertEquals(34_000L, setupProbeTimeoutMillis(SetupNetworkCheck.DIRECTORY, true))
        assertEquals(34_000L, setupProbeTimeoutMillis(SetupNetworkCheck.PUBLIC_DETAILS_JSON, true))
        assertEquals(12_000L, setupProbeTimeoutMillis(SetupNetworkCheck.COMMUNITY_HTML, true))
        assertEquals(68_000L, setupProbeTimeoutMillis(SetupNetworkCheck.WORKSHOP_IMAGE, true))
        assertEquals(5_000L, setupProbeTimeoutMillis(SetupNetworkCheck.CM_TRANSPORT, true))
        assertEquals(5_000L, setupProbeTimeoutMillis(SetupNetworkCheck.PUBLIC_DETAILS_JSON, false))
        assertEquals(5_000L, setupProbeTimeoutMillis(SetupNetworkCheck.WORKSHOP_IMAGE, false))
    }

    @Test
    fun `successful result exposes compact millisecond latency`() {
        val result =
            SetupNetworkCheckResult(
                check = SetupNetworkCheck.DIRECTORY,
                status = SetupNetworkCheckStatus.PASSED,
                durationMillis = 240L,
            )

        assertEquals("240 ms", result.compactLatencyLabel())
        assertEquals(
            null,
            result.copy(status = SetupNetworkCheckStatus.FAILED).compactLatencyLabel(),
        )
    }

    @Test
    fun `checking hides primary and retry actions`() {
        assertEquals(false, SetupNetworkOverallStatus.CHECKING.showsContinueAction())
        assertEquals(false, SetupNetworkOverallStatus.CHECKING.showsRetryAction())
        assertEquals(true, SetupNetworkOverallStatus.PASSED.showsContinueAction())
        assertEquals(true, SetupNetworkOverallStatus.WARNING.showsRetryAction())
    }

    @Test
    fun `runner preserves cancellation`() = runTest {
        val cancelling = SetupNetworkProbe { throw CancellationException("left page") }
        val runner = SetupNetworkPreflightRunner(
            probes = SetupNetworkCheck.entries.associateWith { cancelling },
            nowMillis = { 0L },
        )

        assertFailsWith<CancellationException> {
            runner.run(SetupNetworkPreflightInput(automaticAntiBlockingEnabled = true))
        }
    }
}
