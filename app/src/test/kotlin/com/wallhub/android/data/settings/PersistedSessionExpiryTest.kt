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
    fun `keeps active restoration quiet until a saved session needs attention`() {
        assertFalse(
            shouldShowSteamSessionRestoreBanner(
                SteamSessionState(
                    phase = SteamSessionPhase.SIGNING_IN,
                    hasStoredSession = true,
                ),
            ),
        )
        assertFalse(
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
    fun `saved disconnected identity stays quiet even with an old failure message`() {
        for (phase in listOf(SteamSessionPhase.RESTORABLE, SteamSessionPhase.SIGNING_IN, SteamSessionPhase.SIGNED_IN)) {
            val session =
                SteamSessionState(
                    phase = phase,
                    hasStoredSession = true,
                    isConnected = false,
                    message = "Previous restore failed",
                )
            assertFalse(shouldShowSteamSessionRestoreBanner(session))
            assertFalse(shouldOpenSteamSignInFromRestoreBanner(session))
            assertFalse(shouldReportExpiredPersistedSession(session, alreadyReported = false))
            assertFalse(session.isConnected)
        }
    }

    @Test
    fun `failed saved restore remains visible with retry rather than forced sign in`() {
        val session = SteamSessionState(phase = SteamSessionPhase.FAILED, hasStoredSession = true)
        assertTrue(shouldShowSteamSessionRestoreBanner(session))
        assertFalse(shouldOpenSteamSignInFromRestoreBanner(session))
    }

    @Test
    fun `expiry and Steam challenges stay visible and open sign in regardless of credential storage`() {
        for (stored in listOf(false, true)) {
            for (phase in listOf(
                SteamSessionPhase.EXPIRED,
                SteamSessionPhase.WAITING_FOR_CODE,
                SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
            )) {
                val session = SteamSessionState(phase = phase, hasStoredSession = stored)
                assertTrue(shouldShowSteamSessionRestoreBanner(session))
                assertTrue(shouldOpenSteamSignInFromRestoreBanner(session))
            }
        }
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
