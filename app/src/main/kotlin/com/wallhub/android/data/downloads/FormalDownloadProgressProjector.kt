package com.wallhub.android.data.downloads

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.wallhub.android.R
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.DownloadStatus

internal data class ProgressProjectionWrite(
    val status: DownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val message: String,
)

/**
 * Turns SDK and preset-dependency progress into rate-limited durable task projections.
 *
 * All cadence and throughput state uses one monotonic clock. The caller supplies only the persistence seam and
 * reads [task] after callbacks finish; it does not coordinate phase, speed, checkpoint floors, or telemetry.
 */
internal class FormalDownloadProgressProjector(
    private val context: Context,
    initialTask: FormalTaskRecordEntity,
    private val durableCheckpointBytes: Long,
    private val authenticated: Boolean,
    private val timingContext: DownloadTimingContext,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val persist: suspend (FormalTaskRecordEntity, ProgressProjectionWrite) -> FormalTaskRecordEntity,
) {
    var task: FormalTaskRecordEntity = initialTask
        private set

    private var previousPhase: SteamDownloadPhase? = null
    private var lastPersistedAt = 0L
    private var lastSpeedAt = nowMs()
    private var lastSpeedBytes = initialTask.downloadedBytes
    private var firstNonzeroSpeedPersisted = initialTask.bytesPerSecond > 0L

    suspend fun accept(progress: SteamDownloadProgress) {
        DownloadTimingTelemetry.log(
            event = DownloadTimingTelemetry.PROGRESS_CALLBACK_STARTED,
            context = timingContext,
        )
        val now = nowMs()
        if (progress.phase != previousPhase) {
            Log.i(
                DOWNLOAD_LOG_TAG,
                "taskId=${task.taskId} phase $previousPhase -> ${progress.phase} " +
                    "(${progress.completedBytes}/${progress.totalBytes} bytes)",
            )
        }
        val shouldPersist =
            progress.phase != previousPhase ||
                now - lastPersistedAt >= PROGRESS_PERSIST_INTERVAL_MS ||
                (progress.totalBytes > 0L && progress.completedBytes >= progress.totalBytes)
        if (shouldPersist) {
            val speed =
                downloadSpeedBytesPerSecond(
                    phase = progress.phase,
                    completedBytes = progress.completedBytes,
                    previousBytes = lastSpeedBytes,
                    elapsedMs = now - lastSpeedAt,
                    previousSpeed = task.bytesPerSecond,
                )
            DownloadTimingTelemetry.log(
                event = DownloadTimingTelemetry.PROGRESS_PERSIST_STARTED,
                context = timingContext,
            )
            task =
                persist(
                    task,
                    ProgressProjectionWrite(
                        status = progress.toDownloadStatus(),
                        downloadedBytes =
                            if (progress.phase == SteamDownloadPhase.DOWNLOADING) {
                                durableDownloadedBytes(durableCheckpointBytes, progress.completedBytes)
                            } else {
                                task.downloadedBytes
                            },
                        totalBytes = progress.totalBytes.takeIf { it > 0L } ?: task.totalBytes,
                        bytesPerSecond = speed,
                        message = progress.toMessage(context, authenticated),
                    ),
                )
            DownloadTimingTelemetry.log(
                event = DownloadTimingTelemetry.PROGRESS_PERSIST_COMPLETED,
                context = timingContext,
            )
            if (!firstNonzeroSpeedPersisted && speed > 0L) {
                firstNonzeroSpeedPersisted = true
                DownloadTimingTelemetry.log(
                    event = DownloadTimingTelemetry.FIRST_NONZERO_SPEED_PERSISTED,
                    context = timingContext,
                )
            }
            if (progress.completedBytes > lastSpeedBytes) {
                lastSpeedAt = now
                lastSpeedBytes = progress.completedBytes
            }
            previousPhase = progress.phase
            lastPersistedAt = now
        }
        DownloadTimingTelemetry.log(
            event = DownloadTimingTelemetry.PROGRESS_CALLBACK_ENDED,
            context = timingContext,
        )
    }

    suspend fun acceptDependency(
        primaryDownloadedBytes: Long,
        primaryTotalBytes: Long,
        completedBytes: Long,
        totalBytes: Long,
        dependencyTitle: String,
    ) {
        val now = nowMs()
        if (now - lastPersistedAt < PROGRESS_PERSIST_INTERVAL_MS && completedBytes < totalBytes) return
        task =
            persist(
                task,
                ProgressProjectionWrite(
                    status = DownloadStatus.DOWNLOADING,
                    downloadedBytes = primaryDownloadedBytes + completedBytes,
                    totalBytes = primaryTotalBytes + totalBytes,
                    bytesPerSecond = 0L,
                    message = "正在下载依赖项目：$dependencyTitle",
                ),
            )
        lastPersistedAt = now
    }

    private fun SteamDownloadProgress.toDownloadStatus(): DownloadStatus =
        when (phase) {
            SteamDownloadPhase.CONNECTING,
            SteamDownloadPhase.AUTHENTICATING,
            SteamDownloadPhase.RESOLVING,
            -> DownloadStatus.RESOLVING

            SteamDownloadPhase.PREPARING_DOWNLOAD,
            SteamDownloadPhase.DOWNLOADING,
            -> DownloadStatus.DOWNLOADING
        }

    private fun SteamDownloadProgress.toMessage(
        context: Context,
        authenticated: Boolean,
    ): String =
        when (phase) {
            SteamDownloadPhase.CONNECTING ->
                context.getString(
                    if (authenticated) {
                        R.string.backend_download_connecting_authenticated
                    } else {
                        R.string.backend_download_connecting_anonymous
                    },
                )
            SteamDownloadPhase.AUTHENTICATING -> context.getString(R.string.backend_download_authenticating)
            SteamDownloadPhase.RESOLVING -> context.getString(R.string.backend_download_resolving_content)
            SteamDownloadPhase.PREPARING_DOWNLOAD ->
                context.getString(R.string.backend_download_preparing_transfer, totalFiles)
            SteamDownloadPhase.DOWNLOADING ->
                if (currentFile.isNullOrBlank()) {
                    context.getString(R.string.backend_download_progress, completedFiles, totalFiles)
                } else {
                    context.getString(
                        R.string.backend_download_progress_file,
                        completedFiles,
                        totalFiles,
                        currentFile,
                    )
                }
        }

    private companion object {
        const val PROGRESS_PERSIST_INTERVAL_MS = 750L
    }
}
