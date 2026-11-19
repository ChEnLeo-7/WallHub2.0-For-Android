package com.wallhub.android.data.steamaccess

import com.wallhub.android.core.model.SteamAccessPhase
import kotlinx.coroutines.CancellationException

/** Small Android-free policy seam for explicit Steam route refreshes. */
internal class SteamAccessRefreshGate {
    private var running = false

    @Synchronized
    fun tryStart(): Boolean {
        if (running) return false
        running = true
        return true
    }

    @Synchronized
    fun finish() {
        running = false
    }
}

internal fun steamAccessRefreshStartPhase(enabled: Boolean): SteamAccessPhase =
    if (enabled) SteamAccessPhase.RESOLVING else SteamAccessPhase.DISABLED

internal fun steamAccessRefreshFailurePhase(
    error: Throwable,
    hasUsableRoute: Boolean,
): SteamAccessPhase {
    if (error is CancellationException) throw error
    return if (hasUsableRoute) SteamAccessPhase.DEGRADED else SteamAccessPhase.FAILED
}
