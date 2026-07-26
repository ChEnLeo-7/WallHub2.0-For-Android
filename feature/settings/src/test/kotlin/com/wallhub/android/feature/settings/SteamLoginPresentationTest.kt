package com.wallhub.android.feature.settings

import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SteamLoginPresentationTest {
    @Test
    fun `device confirmation shows fallback while keeping credential form busy`() {
        val uiState = SteamSessionState(
            phase = SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
            awaitingDeviceConfirmation = true,
        ).toSteamLoginUiState()

        assertTrue(uiState.isBusy)
        assertTrue(uiState.showDeviceConfirmationHint)
        assertTrue(uiState.showManualCodeFallback)
        assertFalse(uiState.showCodeInput)
        assertFalse(uiState.showRestoreRetry)
        assertFalse(uiState.isFailure)
    }

    @Test
    fun `guard code state only shows code input`() {
        val uiState = SteamSessionState(
            phase = SteamSessionPhase.WAITING_FOR_CODE,
            requiresCode = true,
        ).toSteamLoginUiState()

        assertTrue(uiState.isBusy)
        assertFalse(uiState.showDeviceConfirmationHint)
        assertFalse(uiState.showManualCodeFallback)
        assertTrue(uiState.showCodeInput)
    }

    @Test
    fun `restorable stored session exposes retry without staying busy`() {
        val uiState = SteamSessionState(
            phase = SteamSessionPhase.RESTORABLE,
            hasStoredSession = true,
        ).toSteamLoginUiState()

        assertFalse(uiState.isBusy)
        assertTrue(uiState.showRestoreRetry)
        assertTrue(uiState.isFailure)
    }

    @Test
    fun `signed in and expired states expose mutually exclusive actions`() {
        val signedIn = SteamSessionState(phase = SteamSessionPhase.SIGNED_IN).toSteamLoginUiState()
        val expired = SteamSessionState(phase = SteamSessionPhase.EXPIRED).toSteamLoginUiState()

        assertTrue(signedIn.isSignedIn)
        assertFalse(signedIn.isBusy)
        assertFalse(signedIn.isFailure)
        assertFalse(expired.isSignedIn)
        assertFalse(expired.isBusy)
        assertTrue(expired.isFailure)
    }
}
