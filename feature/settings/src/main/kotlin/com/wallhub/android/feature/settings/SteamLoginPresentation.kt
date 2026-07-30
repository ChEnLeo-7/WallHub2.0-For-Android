package com.wallhub.android.feature.settings

import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState

internal data class SteamLoginUiState(
    val isBusy: Boolean,
    val isSignedIn: Boolean,
    val showDeviceConfirmationHint: Boolean,
    val showManualCodeFallback: Boolean,
    val showCodeInput: Boolean,
    val showRestoreRetry: Boolean,
    val isFailure: Boolean,
)

internal fun SteamSessionState.toSteamLoginUiState(): SteamLoginUiState {
    val isBusy =
        phase in
            setOf(
                SteamSessionPhase.SIGNING_IN,
                SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
                SteamSessionPhase.WAITING_FOR_CODE,
            )
    val isWaitingForDeviceConfirmation =
        phase == SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION && awaitingDeviceConfirmation
    val isWaitingForCode = phase == SteamSessionPhase.WAITING_FOR_CODE && requiresCode

    return SteamLoginUiState(
        isBusy = isBusy,
        isSignedIn = phase == SteamSessionPhase.SIGNED_IN,
        showDeviceConfirmationHint = isWaitingForDeviceConfirmation,
        showManualCodeFallback = isWaitingForDeviceConfirmation,
        showCodeInput = isWaitingForCode,
        showRestoreRetry = phase == SteamSessionPhase.RESTORABLE && hasStoredSession,
        isFailure =
            phase == SteamSessionPhase.FAILED ||
                phase == SteamSessionPhase.EXPIRED ||
                phase == SteamSessionPhase.RESTORABLE,
    )
}
