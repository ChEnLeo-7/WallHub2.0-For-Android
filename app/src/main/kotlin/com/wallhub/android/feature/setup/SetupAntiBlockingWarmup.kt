package com.wallhub.android.feature.setup

import com.wallhub.android.data.steamaccess.SteamDohWarmupResult

internal enum class SetupAntiBlockingWarmupPhase {
    IDLE,
    WARMING,
    READY,
    DEGRADED,
}

internal data class SetupAntiBlockingWarmupState(
    val phase: SetupAntiBlockingWarmupPhase = SetupAntiBlockingWarmupPhase.IDLE,
    val domesticRoutes: Int = 0,
    val foreignRoutes: Int = 0,
    val measuredRoutes: Int = 0,
    val elapsedMillis: Long = 0L,
    val reason: String = "",
) {
    companion object {
        fun warming(): SetupAntiBlockingWarmupState =
            SetupAntiBlockingWarmupState(phase = SetupAntiBlockingWarmupPhase.WARMING)
    }
}

internal fun SteamDohWarmupResult.toSetupState(): SetupAntiBlockingWarmupState =
    SetupAntiBlockingWarmupState(
        phase =
            if (ready) {
                SetupAntiBlockingWarmupPhase.READY
            } else {
                SetupAntiBlockingWarmupPhase.DEGRADED
            },
        domesticRoutes = domesticRoutes,
        foreignRoutes = foreignRoutes,
        measuredRoutes = measuredRoutes,
        elapsedMillis = elapsedMillis,
    )
