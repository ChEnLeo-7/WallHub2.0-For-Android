package com.wallhub.android.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug-build-only ADB seam for deterministic online-video seek verification. */
class DebugVideoCommandReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_SEEK) return
        DebugVideoCommandBus.seek(intent.getLongExtra(EXTRA_POSITION_MS, -1L))
    }

    private companion object {
        const val ACTION_SEEK = "com.wallhub.android.debug.SEEK_VIDEO"
        const val EXTRA_POSITION_MS = "position_ms"
    }
}
