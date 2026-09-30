package com.wallhub.android.data.downloads

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wallhub.android.core.database.DownloadCommandDao
import java.util.concurrent.TimeUnit

/**
 * WorkManager wake-up adapter for the durable download host.
 *
 * It owns no Steam session, native task, transfer buffer or file. During migration it starts the existing
 * per-task adapter for each durable command; the process-wide coordinator remains the one live owner and writes
 * epoch-fenced receipts. The adapter disappears once the transfer body moves fully behind DownloadHost.
 */
internal class DownloadHostWakeWorker(
    appContext: Context,
    params: WorkerParameters,
    private val commandDao: DownloadCommandDao,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val taskIds = commandDao.pending(MAX_COMMANDS_PER_WAKE).map { it.taskId }.distinct()
        val manager = WorkManager.getInstance(applicationContext)
        taskIds.forEach { taskId ->
            val request =
                OneTimeWorkRequestBuilder<FormalWorkshopDownloadWorker>()
                    .setInputData(workDataOf(FormalWorkshopDownloadWorker.KEY_TASK_ID to taskId))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10_000L, TimeUnit.MILLISECONDS)
                    .addTag(FormalWorkshopDownloadWorker.WORK_TAG_PREFIX + taskId)
                    .build()
            manager.enqueueUniqueWork(
                FormalWorkshopDownloadWorker.UNIQUE_DOWNLOAD_WORK_PREFIX + taskId,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
        return Result.success()
    }

    private companion object {
        const val MAX_COMMANDS_PER_WAKE = 128
    }
}
