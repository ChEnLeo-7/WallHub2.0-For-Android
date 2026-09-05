package com.wallhub.android.data.steam

import bruhcollective.itaysonlab.ksteam.models.account.AuthorizationState
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
    fun `authenticated Steam client is usable only while its CM transport is active`() {
        assertTrue(isUsableAuthenticatedSteamClient(authorized = true, connected = true))
        assertFalse(isUsableAuthenticatedSteamClient(authorized = true, connected = false))
        assertFalse(isUsableAuthenticatedSteamClient(authorized = false, connected = true))
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
