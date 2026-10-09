package com.wallhub.android.app

import android.content.Intent
import com.wallhub.android.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Debug-build-only command seam for deterministic ADB playback and seek verification. */
internal object DebugVideoCommandBus {
    const val ACTION_OPEN = "com.wallhub.android.debug.OPEN_ONLINE_VIDEO"
    const val ACTION_SEEK = "com.wallhub.android.debug.SEEK_ONLINE_VIDEO"
    const val EXTRA_WORKSHOP_ID = "workshop_id"
    const val EXTRA_POSITION_MS = "position_ms"

    private val mutableOpenWorkshopId = MutableStateFlow<Long?>(null)
    val openWorkshopId = mutableOpenWorkshopId.asStateFlow()

    private val mutableSeekPositionMs = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val seekPositionMs = mutableSeekPositionMs.asSharedFlow()

    fun accept(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        when (intent.action) {
            ACTION_OPEN ->
                intent.getLongExtra(EXTRA_WORKSHOP_ID, 0L).takeIf { it > 0L }?.let {
                    mutableOpenWorkshopId.value = it
                }
            ACTION_SEEK ->
                intent.getLongExtra(EXTRA_POSITION_MS, -1L).takeIf { it >= 0L }?.let {
                    mutableSeekPositionMs.tryEmit(it)
                }
        }
    }

    fun consumeOpen(workshopId: Long) {
        mutableOpenWorkshopId.compareAndSet(workshopId, null)
    }
}
