package com.wallhub.android.data.downloads

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class PlaybackDownloadCoordinator @Inject constructor() {
    private val activePlaybackCount = MutableStateFlow(0)

    fun registerPlayback(): Closeable {
        synchronized(activePlaybackCount) { activePlaybackCount.value += 1 }
        val closed = AtomicBoolean(false)
        return Closeable {
            if (closed.compareAndSet(false, true)) {
                synchronized(activePlaybackCount) {
                    activePlaybackCount.value = (activePlaybackCount.value - 1).coerceAtLeast(0)
                }
            }
        }
    }

    suspend fun awaitBackgroundWorkAllowed() {
        activePlaybackCount.first { it == 0 }
    }
}
