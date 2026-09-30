package com.wallhub.android.data.downloads

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wallhub.android.core.database.DownloadCommandDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton

interface DownloadWorkScheduler {
    suspend fun enqueue(taskId: String)

    suspend fun replace(taskId: String)
}

interface ConversionWorkScheduler {
    suspend fun enqueue(taskId: String)

    suspend fun replace(taskId: String)

    suspend fun cancel(taskId: String)
}

@Singleton
class WorkManagerDownloadWorkScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : DownloadWorkScheduler {
        override suspend fun enqueue(taskId: String) = wakeHost()

        override suspend fun replace(taskId: String) = wakeHost()

        private suspend fun wakeHost() {
            // Reconciliation itself must run offline so PAUSE/CANCEL can settle and release local writers.
            // RUN commands that still require the network return RETRY and use this request's backoff.
            val wake =
                OneTimeWorkRequestBuilder<DownloadHostWakeWorker>()
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10_000L, TimeUnit.MILLISECONDS)
                    .addTag(FORMAL_DOWNLOAD_TAG)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(DOWNLOAD_HOST_WAKE_WORK, ExistingWorkPolicy.KEEP, wake)
                .awaitCompletion()
        }

        private companion object {
            const val FORMAL_DOWNLOAD_TAG = "wallhub_formal_workshop_download"
            const val DOWNLOAD_HOST_WAKE_WORK = "wallhub_download_host_reconcile"
        }
    }

@Singleton
class WorkManagerConversionWorkScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ConversionWorkScheduler {
        override suspend fun enqueue(taskId: String) {
            enqueue(taskId, ExistingWorkPolicy.KEEP)
        }

        override suspend fun replace(taskId: String) {
            enqueue(taskId, ExistingWorkPolicy.REPLACE)
        }

        private suspend fun enqueue(
            taskId: String,
            policy: ExistingWorkPolicy,
        ) {
            val request =
                OneTimeWorkRequestBuilder<FormalWorkshopConversionWorker>()
                    .setInputData(workDataOf(FormalWorkshopConversionWorker.KEY_TASK_ID to taskId))
                    .addTag(FormalWorkshopConversionWorker.WORK_TAG_PREFIX + taskId)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    FormalWorkshopConversionWorker.UNIQUE_WORK_NAME_PREFIX + taskId,
                    policy,
                    request,
                ).awaitCompletion()
        }

        override suspend fun cancel(taskId: String) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(FormalWorkshopConversionWorker.UNIQUE_WORK_NAME_PREFIX + taskId)
                .awaitCompletion()
        }
    }

private suspend fun androidx.work.Operation.awaitCompletion() {
    suspendCancellableCoroutine { continuation ->
        result.addListener(
            {
                try {
                    result.get()
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error.cause ?: error)
                }
            },
            java.util.concurrent.Executor(Runnable::run),
        )
    }
}

@Singleton
internal class WallHubDownloadWorkerFactory
    @Inject
    constructor(
        private val taskDao: FormalTaskRecordDao,
        private val commandDao: DownloadCommandDao,
        private val formalDownloadRunner: FormalDownloadRunner,
        private val downloadConcurrencyGovernor: DownloadConcurrencyGovernor,
        private val downloadHostCoordinator: SingleProcessDownloadHostCoordinator,
        private val playbackCoordinator: PlaybackDownloadCoordinator,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): androidx.work.ListenableWorker? =
            when (workerClassName) {
                DownloadHostWakeWorker::class.java.name ->
                    DownloadHostWakeWorker(
                        appContext = appContext,
                        params = workerParameters,
                        commandDao = commandDao,
                        host = downloadHostCoordinator,
                        runner = formalDownloadRunner,
                    )

                FormalWorkshopDownloadWorker::class.java.name ->
                    FormalWorkshopDownloadWorker(
                        appContext = appContext,
                        params = workerParameters,
                        runner = formalDownloadRunner,
                        downloadHostCoordinator = downloadHostCoordinator,
                    )

                FormalWorkshopConversionWorker::class.java.name ->
                    FormalWorkshopConversionWorker(
                        appContext = appContext,
                        params = workerParameters,
                        taskDao = taskDao,
                        downloadConcurrencyGovernor = downloadConcurrencyGovernor,
                        playbackCoordinator = playbackCoordinator,
                    )

                else -> null
            }
    }
