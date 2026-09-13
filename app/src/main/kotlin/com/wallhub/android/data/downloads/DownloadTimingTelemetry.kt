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
    const val PROGRESS_CALLBACK_STARTED = "progress_callback_started"
    const val PROGRESS_CALLBACK_ENDED = "progress_callback_ended"
    const val PROGRESS_PERSIST_STARTED = "progress_persist_started"
    const val PROGRESS_PERSIST_COMPLETED = "progress_persist_completed"
    const val FORMAL_CHUNK_REQUEST_STARTED = "formal_chunk_request_started"
    const val FORMAL_CHUNK_RESPONSE_BODY_COMPLETED = "formal_chunk_response_body_completed"
    const val FORMAL_CHUNK_DECODE_COMPLETED = "formal_chunk_decode_completed"
    const val FORMAL_CHUNK_FILE_WRITE_COMPLETED = "formal_chunk_file_write_completed"

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

    fun logChunk(
        event: String,
        context: DownloadTimingContext,
        chunkOffset: Long,
        compressedBytes: Int,
        uncompressedBytes: Int,
        elapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    ) {
        Log.i(
            DOWNLOAD_LOG_TAG,
            "timing event=$event taskId=${context.taskId} workshopId=${context.workshopId} " +
                "chunkOffset=$chunkOffset compressedBytes=$compressedBytes " +
                "uncompressedBytes=$uncompressedBytes elapsedRealtimeMs=$elapsedRealtimeMs",
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
