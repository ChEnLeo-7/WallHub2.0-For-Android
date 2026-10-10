package com.wallhub.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wallhub.android.MainActivity
import com.wallhub.android.app.DebugVideoCommandBus

/** Shell-only bridge from ADB broadcasts to the app's in-process debug command bus. */
class DebugVideoCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_OPEN -> {
                val workshopId = intent.getLongExtra(EXTRA_WORKSHOP_ID, 0L)
                if (workshopId <= 0L) return
                DebugVideoCommandBus.open(workshopId)
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                )
            }

            ACTION_SEEK -> {
                val positionMs = intent.getLongExtra(EXTRA_POSITION_MS, -1L)
                if (positionMs >= 0L) DebugVideoCommandBus.seek(positionMs)
            }
        }
    }

    companion object {
        const val ACTION_OPEN = "com.wallhub.android.debug.OPEN_ONLINE_VIDEO"
        const val ACTION_SEEK = "com.wallhub.android.debug.SEEK_ONLINE_VIDEO"
        const val EXTRA_WORKSHOP_ID = "workshop_id"
        const val EXTRA_POSITION_MS = "position_ms"
    }
}
