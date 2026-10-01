package com.wallhub.android.data.downloads

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wallhub.android.core.database.DownloadCommandDao

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
    private val wakeSignal: DownloadHostWakeSignal,
    private val runner: FormalDownloadRunner,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val result =
            DownloadHostReconciler(
                pendingTaskIds = { commandDao.pending(MAX_COMMANDS_PER_WAKE).map { it.taskId } },
                host = host,
                signal = wakeSignal,
                launchTask = runner::run,
            ).reconcile()
        return if (result == DownloadHostRunResult.RETRY) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_COMMANDS_PER_WAKE = 128
    }
}
