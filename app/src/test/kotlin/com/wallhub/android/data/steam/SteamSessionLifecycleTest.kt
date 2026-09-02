package com.wallhub.android.data.steam

import com.wallhub.android.core.model.SteamSessionPhase
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamSessionLifecycleTest {
    @Test
    fun `foreground does not refresh a short background interval`() {
        assertFalse(
            shouldRefreshSessionOnForeground(
                phase = SteamSessionPhase.SIGNED_IN,
                hasStoredSession = true,
                sessionUsable = true,
                backgroundedAtElapsedRealtime = 1_000L,
                nowElapsedRealtime = 1_000L + FOREGROUND_SESSION_REFRESH_AFTER_BACKGROUND_MS - 1,
            ),
        )
    }

    @Test
    fun `foreground refreshes a long-lived signed-in session`() {
        assertTrue(
            shouldRefreshSessionOnForeground(
                phase = SteamSessionPhase.SIGNED_IN,
                hasStoredSession = true,
                sessionUsable = true,
                backgroundedAtElapsedRealtime = 1_000L,
                nowElapsedRealtime = 1_000L + FOREGROUND_SESSION_REFRESH_AFTER_BACKGROUND_MS,
            ),
        )
    }

    @Test
    fun `foreground refreshes an unusable restorable session immediately`() {
        assertTrue(
            shouldRefreshSessionOnForeground(
                phase = SteamSessionPhase.RESTORABLE,
                hasStoredSession = true,
                sessionUsable = false,
                backgroundedAtElapsedRealtime = null,
                nowElapsedRealtime = 1_000L,
            ),
        )
    }

    @Test
    fun `foreground does not restore without a saved session`() {
        assertFalse(
            shouldRefreshSessionOnForeground(
                phase = SteamSessionPhase.SIGNED_OUT,
                hasStoredSession = false,
                sessionUsable = false,
                backgroundedAtElapsedRealtime = null,
                nowElapsedRealtime = 1_000L,
            ),
        )
    }

    @Test
    fun `foreground does not retry an expired saved session`() {
        assertFalse(
            shouldRefreshSessionOnForeground(
                phase = SteamSessionPhase.EXPIRED,
                hasStoredSession = true,
                sessionUsable = false,
                backgroundedAtElapsedRealtime = 1_000L,
                nowElapsedRealtime = 1_000L + FOREGROUND_SESSION_REFRESH_AFTER_BACKGROUND_MS,
            ),
        )
    }
}
