package com.wallhub.android.data.downloads

import androidx.work.ListenableWorker

/** Host-owned execution result; WorkManager is only one adapter for it. */
internal enum class DownloadHostRunResult {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal val DownloadHostRunResult.settlesCommand: Boolean
    get() = this != DownloadHostRunResult.RETRY

internal fun DownloadHostRunResult.toWorkerResult(): ListenableWorker.Result =
    when (this) {
        DownloadHostRunResult.SUCCESS -> ListenableWorker.Result.success()
        DownloadHostRunResult.RETRY -> ListenableWorker.Result.retry()
        DownloadHostRunResult.FAILURE -> ListenableWorker.Result.failure()
    }
