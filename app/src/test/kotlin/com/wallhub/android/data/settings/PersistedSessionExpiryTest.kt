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
                hasStoredSession = true,
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
}
