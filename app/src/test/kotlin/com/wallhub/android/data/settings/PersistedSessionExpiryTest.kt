package com.wallhub.android

import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedSessionExpiryTest {
    @Test
    fun `reports an expired saved session once`() {
        val expiredSession =
            SteamSessionState(
                phase = SteamSessionPhase.EXPIRED,
                hasStoredSession = false,
            )

        assertTrue(shouldReportExpiredPersistedSession(expiredSession, alreadyReported = false))
        assertFalse(shouldReportExpiredPersistedSession(expiredSession, alreadyReported = true))
    }

    @Test
    fun `does not report signed out or transient restore failures`() {
        assertFalse(
            shouldReportExpiredPersistedSession(
                SteamSessionState(phase = SteamSessionPhase.SIGNED_OUT),
                alreadyReported = false,
            ),
        )
        assertFalse(
            shouldReportExpiredPersistedSession(
                SteamSessionState(
                    phase = SteamSessionPhase.RESTORABLE,
                    hasStoredSession = true,
                ),
                alreadyReported = false,
            ),
        )
    }

    @Test
    fun `shows a global restore banner while a saved session is not signed in`() {
        assertTrue(
            shouldShowSteamSessionRestoreBanner(
                SteamSessionState(
                    phase = SteamSessionPhase.SIGNING_IN,
                    hasStoredSession = true,
                ),
            ),
        )
        assertTrue(
            shouldShowSteamSessionRestoreBanner(
                SteamSessionState(
                    phase = SteamSessionPhase.RESTORABLE,
                    hasStoredSession = true,
                ),
            ),
        )
        assertFalse(
            shouldShowSteamSessionRestoreBanner(
                SteamSessionState(
                    phase = SteamSessionPhase.SIGNED_IN,
                    hasStoredSession = true,
                ),
            ),
        )
        assertFalse(
            shouldShowSteamSessionRestoreBanner(
                SteamSessionState(
                    phase = SteamSessionPhase.SIGNING_IN,
                    hasStoredSession = false,
                ),
            ),
        )
    }

    @Test
    fun `expired sessions remain reportable after credentials are removed`() {
        assertTrue(
            shouldReportExpiredPersistedSession(
                SteamSessionState(phase = SteamSessionPhase.EXPIRED, hasStoredSession = false),
                alreadyReported = false,
            ),
        )
    }
}
