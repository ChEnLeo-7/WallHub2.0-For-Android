package com.wallhub.android.data.downloads

import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal interface VideoStreamCloseScheduler {
    fun tryReserve(): VideoStreamCloseReservation?
}

internal interface VideoStreamCloseReservation : Closeable {
    fun schedule(action: () -> Unit)
}

internal object DirectVideoStreamCloseScheduler : VideoStreamCloseScheduler {
    override fun tryReserve(): VideoStreamCloseReservation =
        object : VideoStreamCloseReservation {
            override fun schedule(action: () -> Unit) = action()

            override fun close() = Unit
        }
}

/**
 * Process-owned bounded cleanup queue for potentially blocking native stream shutdown.
 *
 * Capacity is reserved before a stream opens, so every admitted stream owns one guaranteed cleanup slot. Two workers
 * cap blocking native closes and the reservation limit bounds active plus queued stream/session graphs. Cleanup does
 * not inherit a player or ViewModel job and therefore survives destruction of those callers.
 */
@Singleton
internal class CoroutineVideoStreamCloseScheduler internal constructor(
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
    private val report: (operationId: Long, elapsedMs: Long, failure: Throwable?) -> Unit = ::reportVideoStreamClose,
) : VideoStreamCloseScheduler {
    private val queue = Channel<CloseTask>(capacity = MAX_OUTSTANDING_STREAMS)
    private val reservations = AtomicInteger(0)

    @Inject
    constructor() : this(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    init {
        repeat(MAX_CONCURRENT_STREAM_CLOSES) {
            scope.launch {
                for (task in queue) execute(task)
            }
        }
    }

    override fun tryReserve(): VideoStreamCloseReservation? {
        while (true) {
            val current = reservations.get()
            if (current >= MAX_OUTSTANDING_STREAMS) return null
            if (reservations.compareAndSet(current, current + 1)) {
                return Reservation()
            }
        }
    }

    private fun execute(task: CloseTask) {
        val startedAtMs = clockMs()
        val failure = runCatching(task.action).exceptionOrNull()
        val elapsedMs = (clockMs() - startedAtMs).coerceAtLeast(0L)
        reservations.decrementAndGet()
        report(task.operationId, elapsedMs, failure)
    }

    private inner class Reservation : VideoStreamCloseReservation {
        private val transferred = AtomicBoolean(false)

        override fun schedule(action: () -> Unit) {
            if (!transferred.compareAndSet(false, true)) return
            val operationId = nextOperationId.incrementAndGet()
            check(queue.trySend(CloseTask(operationId, action)).isSuccess) {
                "Reserved video stream close queue rejected its owned slot"
            }
        }

        override fun close() {
            if (transferred.compareAndSet(false, true)) reservations.decrementAndGet()
        }
    }

    private data class CloseTask(
        val operationId: Long,
        val action: () -> Unit,
    )

    private companion object {
        const val MAX_CONCURRENT_STREAM_CLOSES = 2
        const val MAX_OUTSTANDING_STREAMS = 10
        val nextOperationId = AtomicLong(0L)
    }
}

private fun reportVideoStreamClose(
    operationId: Long,
    elapsedMs: Long,
    failure: Throwable?,
) {
    if (failure != null) {
        Log.w(STREAM_CLOSE_TAG, "video_stream_close op=$operationId outcome=failed elapsed_ms=$elapsedMs", failure)
    } else {
        val level = if (elapsedMs >= SLOW_CLOSE_THRESHOLD_MS) Log.WARN else Log.INFO
        Log.println(level, STREAM_CLOSE_TAG, "video_stream_close op=$operationId outcome=complete elapsed_ms=$elapsedMs")
    }
}

private const val STREAM_CLOSE_TAG = "SteamVideoStream"
private const val SLOW_CLOSE_THRESHOLD_MS = 5_000L
