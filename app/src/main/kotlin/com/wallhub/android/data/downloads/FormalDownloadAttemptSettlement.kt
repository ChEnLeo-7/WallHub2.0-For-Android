package com.wallhub.android.data.downloads

import android.content.Context
import android.util.Log
import com.wallhub.android.R
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Owns terminal and retry projection effects for one runner attempt. */
@Singleton
internal class FormalDownloadAttemptSettlement
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val taskDao: FormalTaskRecordDao,
        private val staging: DownloadStagingManager,
        private val projections: FormalTaskProjectionStore,
        private val contentClient: SteamWorkshopContentClient,
    ) {
        suspend fun pause(task: FormalTaskRecordEntity): DownloadHostRunResult {
            Log.i(
                DOWNLOAD_LOG_TAG,
                "taskId=${task.taskId} paused committed=${task.downloadedBytes} bytes; " +
                    "staging and the SDK checkpoint are kept",
            )
            if (task.executorVersion == "NEW") {
                taskDao.settleDesiredIntentProjection(
                    taskId = task.taskId,
                    expectedIntent = DownloadAction.PAUSE.name,
                    status = DownloadStatus.PAUSED.name,
                    message = context.getString(R.string.backend_download_paused),
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                taskDao.acknowledgeControlAction(
                    taskId = task.taskId,
                    expectedAction = DownloadAction.PAUSE.name,
                    status = DownloadStatus.PAUSED.name,
                    message = context.getString(R.string.backend_download_paused),
                    updatedAt = System.currentTimeMillis(),
                )
            }
            return DownloadHostRunResult.SUCCESS
        }

        suspend fun cancel(
            taskId: String,
            stagingDirectory: File?,
        ): DownloadHostRunResult {
            staging.discardTask(taskId, stagingDirectory)
            acknowledgeCancellation(taskId)
            return DownloadHostRunResult.SUCCESS
        }

        suspend fun interrupted(task: FormalTaskRecordEntity): DownloadHostRunResult {
            val latest = taskDao.find(task.taskId)
            Log.w(
                DOWNLOAD_LOG_TAG,
                "taskId=${task.taskId} interrupted by the system; re-queueing status=${latest?.status}",
            )
            projections.persist(
                task,
                status = DownloadStatus.QUEUED,
                message = context.getString(R.string.backend_download_interrupted),
            )
            return DownloadHostRunResult.RETRY
        }

        suspend fun failed(
            task: FormalTaskRecordEntity,
            error: Throwable,
        ): DownloadHostRunResult {
            contentClient.invalidateContentTarget(task.workshopId)
            Log.e(
                DOWNLOAD_LOG_TAG,
                "Formal Steam download failed taskId=${task.taskId}, type=${error.javaClass.name}",
                error,
            )
            projections.persist(
                task,
                status = DownloadStatus.FAILED,
                bytesPerSecond = 0L,
                message = error.message ?: error.javaClass.simpleName,
            )
            return DownloadHostRunResult.SUCCESS
        }

        private suspend fun acknowledgeCancellation(taskId: String) {
            val latest = taskDao.find(taskId) ?: return
            val isNew = latest.executorVersion == "NEW"
            val cancelRequested =
                if (isNew) {
                    latest.desiredIntent == DownloadAction.CANCEL.name
                } else {
                    latest.requestedAction == DownloadAction.CANCEL.name
                }
            if (!cancelRequested) return
            val cancelled =
                latest.copy(
                    status = DownloadStatus.CANCELLED.name,
                    stagingDirectory = null,
                    downloadedBytes = 0L,
                    bytesPerSecond = 0L,
                    requestedAction = null,
                    message = context.getString(R.string.backend_download_cancelled_cleaned),
                    updatedAt = System.currentTimeMillis(),
                )
            if (isNew) {
                taskDao.upsertPreservingExecutionIntent(cancelled)
            } else {
                taskDao.upsert(cancelled)
            }
        }
    }
