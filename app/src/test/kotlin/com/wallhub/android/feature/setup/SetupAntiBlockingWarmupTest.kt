package com.wallhub.android.feature.setup

import com.wallhub.android.data.steamaccess.SteamDohWarmupResult
import kotlin.test.Test
import kotlin.test.assertEquals

class SetupAntiBlockingWarmupTest {
    @Test
    fun `five domestic and five foreign routes are ready`() {
        val state =
            SteamDohWarmupResult(
                domesticRoutes = 5,
                foreignRoutes = 5,
                measuredRoutes = 18,
                elapsedMillis = 2_400L,
            ).toSetupState()

        assertEquals(SetupAntiBlockingWarmupPhase.READY, state.phase)
        assertEquals(5, state.domesticRoutes)
        assertEquals(5, state.foreignRoutes)
    }

    @Test
    fun `missing either region remains degraded`() {
        val state =
            SteamDohWarmupResult(
                domesticRoutes = 5,
                foreignRoutes = 4,
                measuredRoutes = 16,
                elapsedMillis = 30_000L,
            ).toSetupState()

        assertEquals(SetupAntiBlockingWarmupPhase.DEGRADED, state.phase)
        assertEquals(16, state.measuredRoutes)
    }
}
