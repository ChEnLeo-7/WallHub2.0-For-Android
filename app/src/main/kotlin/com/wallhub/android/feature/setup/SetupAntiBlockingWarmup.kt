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
    val bypassed: Boolean = false,
    val bridged: Boolean = false,
    val reason: String = "",
) {
    companion object {
        fun warming(): SetupAntiBlockingWarmupState =
            SetupAntiBlockingWarmupState(phase = SetupAntiBlockingWarmupPhase.WARMING)
    }
}

/**
 * What "warm-up finished" means: a route was actually decided for this network.
 *
 * The per-region counts are a diagnostic, not a gate. They measure how many DoH endpoints can resolve one
 * Steam CDN authority, which domestic resolvers cannot do on a direct China network - gating on five
 * domestic plus five international lines therefore reported "incomplete" for a network whose verified
 * bridge was working. A baseline the probe found open, or a bridge whose authority dial was verified, is
 * the only thing setup has to wait for. When neither is true the phase stays degraded and says so.
 */
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
        bypassed = bypassed,
        bridged = bridged,
    )
