package com.wallhub.android.feature.library

import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The floor between two automatic restore attempts.
 *
 * The library refreshes at UI rate and used to start a restore on every pass whenever the state was
 * retry-eligible, so one bad network produced a fresh logon against Steam per refresh. A state that no longer
 * needs restoring is never retried, and a failed one is not retried again until the floor has passed.
 */
class SteamSessionRestoreRetryTest {
    private val failedWithStoredSession =
        SteamSessionState(
            phase = SteamSessionPhase.FAILED,
            hasStoredSession = true,
            updatedAt = 1_000L,
        )

    @Test
    fun `a failure with a stored session is retried once the floor has passed`() {
        assertTrue(
            failedWithStoredSession.shouldAutoRestore(
                nowMs = 1_000L + RESTORE_RETRY_MIN_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun `a failure is not retried again inside the floor`() {
        assertFalse(failedWithStoredSession.shouldAutoRestore(nowMs = 1_000L + RESTORE_RETRY_MIN_INTERVAL_MS - 1))
    }

    @Test
    fun `a restorable state with a stored session is retried`() {
        val restorable =
            failedWithStoredSession.copy(phase = SteamSessionPhase.RESTORABLE, updatedAt = 0L)

        assertTrue(restorable.shouldAutoRestore(nowMs = RESTORE_RETRY_MIN_INTERVAL_MS))
    }

    @Test
    fun `a state with nothing stored is never retried`() {
        val signedOut = failedWithStoredSession.copy(phase = SteamSessionPhase.SIGNED_OUT, hasStoredSession = false)

        assertFalse(signedOut.shouldAutoRestore(nowMs = Long.MAX_VALUE))
    }

    @Test
    fun `a signed-in session is never retried`() {
        val signedIn = failedWithStoredSession.copy(phase = SteamSessionPhase.SIGNED_IN)

        assertFalse(signedIn.shouldAutoRestore(nowMs = Long.MAX_VALUE))
    }

    @Test
    fun `explicit refresh restores immediately inside the automatic retry floor`() {
        assertTrue(
            failedWithStoredSession.shouldRestoreSession(
                explicit = true,
                nowMs = failedWithStoredSession.updatedAt,
            ),
        )
    }

    @Test
    fun `automatic refresh remains throttled inside the retry floor`() {
        assertFalse(
            failedWithStoredSession.shouldRestoreSession(
                explicit = false,
                nowMs = failedWithStoredSession.updatedAt,
            ),
        )
    }
}
