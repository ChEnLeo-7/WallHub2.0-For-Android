package com.wallhub.android.feature.setup

import com.wallhub.android.data.steamaccess.SteamDohWarmupResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Warm-up finished" now means a route was decided, not that five domestic DoH lines answered.
 *
 * The per-region counts measure whether a DoH endpoint can resolve one Steam CDN authority, which domestic
 * resolvers do not do on a direct China network. Gating on them reported "warm-up incomplete" for a network
 * whose verified bridge was already carrying traffic, and left setup with no way forward.
 */
class SetupAntiBlockingWarmupTest {
    @Test
    fun `a verified bridge is a finished warm-up even with no domestic DoH line`() {
        val state =
            SteamDohWarmupResult(
                domesticRoutes = 0,
                foreignRoutes = 5,
                measuredRoutes = 6,
                elapsedMillis = 9_400L,
                bridged = true,
            ).toSetupState()

        assertEquals(SetupAntiBlockingWarmupPhase.READY, state.phase)
        assertTrue(state.bridged)
        assertFalse(state.bypassed, "a bridged plan is not a bypassed direct plan")
        assertEquals(0, state.domesticRoutes)
        assertEquals(5, state.foreignRoutes)
    }

    @Test
    fun `an open direct baseline is a bypassed warm-up`() {
        val state =
            SteamDohWarmupResult(
                domesticRoutes = 1,
                foreignRoutes = 1,
                measuredRoutes = 2,
                elapsedMillis = 1_000L,
                bypassed = true,
            ).toSetupState()

        assertEquals(SetupAntiBlockingWarmupPhase.READY, state.phase)
        assertTrue(state.bypassed)
        assertFalse(state.bridged)
    }

    @Test
    fun `a blocked network with no verified bridge stays degraded instead of claiming success`() {
        val state =
            SteamDohWarmupResult(
                domesticRoutes = 5,
                foreignRoutes = 5,
                measuredRoutes = 10,
                elapsedMillis = 12_000L,
                bypassed = false,
                bridged = false,
            ).toSetupState()

        assertEquals(
            SetupAntiBlockingWarmupPhase.DEGRADED,
            state.phase,
            "measured DoH lines are not a route: without a decision the page must say so",
        )
    }
}
