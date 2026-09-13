package com.wallhub.android.data.downloads

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

internal data class DownloadTimingContext(
    val taskId: String,
    val workshopId: Long,
)

internal object DownloadTimingTelemetry {
    const val HOMEPAGE_DOWNLOAD_CLICK = "homepage_download_click"
    const val TASK_ENQUEUED = "task_enqueued"
    const val WORKER_STARTED = "worker_started"
    const val RESOLVING_STARTED = "resolving_started"
    const val MANIFEST_REQUEST_STARTED = "manifest_request_started"
    const val MANIFEST_COMPLETED = "manifest_completed"
    const val FIRST_CHUNK_COMMITTED = "first_chunk_committed"
    const val FIRST_NONZERO_SPEED_PERSISTED = "first_nonzero_speed_persisted"
    const val FIRST_NONZERO_SPEED_VISIBLE = "first_nonzero_speed_visible"

    private val oneShotEvents = ConcurrentHashMap.newKeySet<String>()

    fun log(
        event: String,
        taskId: String,
        workshopId: Long,
        elapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    ) {
        Log.i(
            DOWNLOAD_LOG_TAG,
            "timing event=$event taskId=$taskId workshopId=$workshopId " +
                "elapsedRealtimeMs=$elapsedRealtimeMs",
        )
    }

    fun log(
        event: String,
        context: DownloadTimingContext,
        elapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    ) = log(event, context.taskId, context.workshopId, elapsedRealtimeMs)

    fun logOnce(
        event: String,
        context: DownloadTimingContext,
        elapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    ) {
        if (oneShotEvents.add("$event:${context.taskId}")) {
            log(event, context, elapsedRealtimeMs)
        }
    }
}

private const val DOWNLOAD_LOG_TAG = "WallHubDownload"
