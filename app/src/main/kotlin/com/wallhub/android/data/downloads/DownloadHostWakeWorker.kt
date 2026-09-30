package com.wallhub.android.data.downloads

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wallhub.android.core.database.DownloadCommandDao
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * WorkManager wake-up/reconcile adapter for the durable download host.
 *
 * It owns no Steam session, native task, transfer buffer or file. One unique WorkRequest keeps the OS execution
 * grant while pending durable tasks join the application-scoped host; the host owns execution and receipts.
 */
internal class DownloadHostWakeWorker(
    appContext: Context,
    params: WorkerParameters,
    private val commandDao: DownloadCommandDao,
    private val host: SingleProcessDownloadHostCoordinator,
    private val runner: FormalDownloadRunner,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        coroutineScope {
            var taskIds = commandDao.pending(MAX_COMMANDS_PER_WAKE).map { it.taskId }.distinct()
            while (taskIds.isNotEmpty()) {
                taskIds.map { taskId -> async { host.reconcile(taskId) { runner.run(taskId) } } }.awaitAll()
                taskIds = commandDao.pending(MAX_COMMANDS_PER_WAKE).map { it.taskId }.distinct()
            }
            Result.success()
        }

    private companion object {
        const val MAX_COMMANDS_PER_WAKE = 128
    }
}
