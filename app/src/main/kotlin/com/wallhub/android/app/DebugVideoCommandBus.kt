package com.wallhub.android.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process command seam used by debug-only tooling for deterministic playback verification. */
internal object DebugVideoCommandBus {
    private val mutableOpenWorkshopId = MutableStateFlow<Long?>(null)
    val openWorkshopId = mutableOpenWorkshopId.asStateFlow()

    private val mutableSeekPositionMs = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val seekPositionMs = mutableSeekPositionMs.asSharedFlow()

    fun open(workshopId: Long) {
        if (workshopId > 0L) mutableOpenWorkshopId.value = workshopId
    }

    fun seek(positionMs: Long) {
        if (positionMs >= 0L) mutableSeekPositionMs.tryEmit(positionMs)
    }

    fun consumeOpen(workshopId: Long) {
        mutableOpenWorkshopId.compareAndSet(workshopId, null)
    }
}
