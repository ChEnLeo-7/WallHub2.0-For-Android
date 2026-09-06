package com.wallhub.android.data.steam

import bruhcollective.itaysonlab.ksteam.models.account.AuthorizationState
import bruhcollective.itaysonlab.ksteam.network.CMClientState
import com.wallhub.android.core.model.SteamSessionPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamLoginConfirmationTest {
    @Test
    fun `backgrounding keeps Steam active while content transfers hold leases`() {
        val state = SteamContentLifecycleState()

        assertFalse(state.acquire())
        assertFalse(state.onBackgrounded())
        assertFalse(state.shouldPause())
        assertTrue(state.release())
        assertTrue(state.shouldPause())
    }

    @Test
    fun `content transfer started in background resumes Steam until final lease closes`() {
        val state = SteamContentLifecycleState()

        assertTrue(state.onBackgrounded())
        assertTrue(state.acquire())
        assertFalse(state.acquire())
        assertFalse(state.release())
        assertFalse(state.shouldPause())
        assertTrue(state.release())
        assertTrue(state.shouldPause())
    }

    @Test
    fun `foregrounding prevents final lease from pausing Steam`() {
        val state = SteamContentLifecycleState()

        state.onBackgrounded()
        assertTrue(state.acquire())
        state.onForegrounded()

        assertFalse(state.release())
        assertFalse(state.shouldPause())
    }

    @Test
    fun `authenticated Steam client is usable only after account logon completes`() {
        assertTrue(
            isUsableAuthenticatedSteamClient(
                auth = AuthorizationState.Success,
                connection = CMClientState.Connected,
            ),
        )
        assertFalse(
            isUsableAuthenticatedSteamClient(
                auth = AuthorizationState.Success,
                connection = CMClientState.Offline,
            ),
        )
        assertFalse(
            isUsableAuthenticatedSteamClient(
                auth = AuthorizationState.Success,
                connection = CMClientState.AwaitingAuthorization,
            ),
        )
        assertFalse(
            isUsableAuthenticatedSteamClient(
                auth = AuthorizationState.Success,
                connection = CMClientState.Authorizing,
            ),
        )
        assertFalse(
            isUsableAuthenticatedSteamClient(
                auth = AuthorizationState.Unauthorized,
                connection = CMClientState.Connected,
            ),
        )
    }

    @Test
    fun `foreground reconnects a stale signed in session with no active CM transport`() {
        assertTrue(
            shouldReconnectForegroundSteamSession(
                phase = SteamSessionPhase.SIGNED_IN,
                hasUsableConnection = false,
            ),
        )
        assertFalse(
            shouldReconnectForegroundSteamSession(
                phase = SteamSessionPhase.SIGNED_IN,
                hasUsableConnection = true,
            ),
        )
        assertFalse(
            shouldReconnectForegroundSteamSession(
                phase = SteamSessionPhase.RESTORABLE,
                hasUsableConnection = false,
            ),
        )
    }

    @Test
    fun `foreground reconnect failure remains recoverable when a saved session exists`() {
        assertTrue(
            shouldRestoreAfterForegroundReconnectFailure(
                hasStoredSession = true,
            ),
        )
        assertFalse(
            shouldRestoreAfterForegroundReconnectFailure(
                hasStoredSession = false,
            ),
        )
    }

    @Test
    fun `background keeps a saved account connection active`() {
        assertFalse(
            shouldPauseBackgroundSteamEngine(
                idleInBackground = true,
                hasStoredSession = true,
            ),
        )
        assertTrue(
            shouldPauseBackgroundSteamEngine(
                idleInBackground = true,
                hasStoredSession = false,
            ),
        )
        assertFalse(
            shouldPauseBackgroundSteamEngine(
                idleInBackground = false,
                hasStoredSession = false,
            ),
        )
    }

    @Test
    fun `Steam websocket ping interval stays below common gateway idle timeout`() {
        assertTrue(KSteamSessionRepository.KSTEAM_WEBSOCKET_PING_INTERVAL_MS < 60_000L)
    }

    @Test
    fun `passive disconnect does not erase a login failure`() {
        assertFalse(
            shouldPublishPassiveSignedOut(
                phase = SteamSessionPhase.FAILED,
                hasStoredSession = false,
            ),
        )
    }

    @Test
    fun `passive disconnect signs out a disconnected transient session`() {
        assertTrue(
            shouldPublishPassiveSignedOut(
                phase = SteamSessionPhase.SIGNING_IN,
                hasStoredSession = false,
            ),
        )
    }

    @Test
    fun `empty confirmation list remains in login progress while kSteam polls`() {
        assertEquals(
            SteamSessionPhase.SIGNING_IN,
            steamLoginPhaseForConfirmations(emptyList()),
        )
    }

    @Test
    fun `machine token does not become a phone confirmation prompt`() {
        assertEquals(
            SteamSessionPhase.FAILED,
            steamLoginPhaseForConfirmations(
                listOf(AuthorizationState.AwaitingTwoFactor.ConfirmationMethod.MachineToken),
            ),
        )
    }

    @Test
    fun `manual code confirmation remains visible`() {
        assertEquals(
            SteamSessionPhase.WAITING_FOR_CODE,
            steamLoginPhaseForConfirmations(
                listOf(AuthorizationState.AwaitingTwoFactor.ConfirmationMethod.EmailCode),
            ),
        )
    }

    @Test
    fun `device confirmation remains visible when no manual code is offered`() {
        assertEquals(
            SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
            steamLoginPhaseForConfirmations(
                listOf(AuthorizationState.AwaitingTwoFactor.ConfirmationMethod.DeviceConfirmation),
            ),
        )
    }

    @Test
    fun `manual code takes precedence when automatic confirmation is also offered`() {
        assertEquals(
            SteamSessionPhase.WAITING_FOR_CODE,
            steamLoginPhaseForConfirmations(
                listOf(
                    AuthorizationState.AwaitingTwoFactor.ConfirmationMethod.EmailCode,
                    AuthorizationState.AwaitingTwoFactor.ConfirmationMethod.DeviceConfirmation,
                ),
            ),
        )
    }
}
