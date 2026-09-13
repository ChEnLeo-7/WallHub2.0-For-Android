package com.wallhub.android.data.downloads

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.model.SteamContentCredentialProvider
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

    suspend fun cancel(taskId: String)
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
                OneTimeWorkRequestBuilder<FormalWorkshopDownloadWorker>()
                    .setInputData(workDataOf(FormalWorkshopDownloadWorker.KEY_TASK_ID to taskId))
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10_000L, TimeUnit.MILLISECONDS)
                    .addTag(FORMAL_DOWNLOAD_TAG)
                    .addTag(FormalWorkshopDownloadWorker.WORK_TAG_PREFIX + taskId)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    FormalWorkshopDownloadWorker.UNIQUE_DOWNLOAD_WORK_PREFIX + taskId,
                    policy,
                    request,
                ).awaitCompletion()
        }

        override suspend fun cancel(taskId: String) {
            Log.w(
                "WallHubDownloadScheduler",
                "cancel($taskId) requested",
                Exception("cancel trace"),
            )
            WorkManager.getInstance(context)
                .cancelUniqueWork(FormalWorkshopDownloadWorker.UNIQUE_DOWNLOAD_WORK_PREFIX + taskId)
                .awaitCompletion()
        }

        private companion object {
            const val FORMAL_DOWNLOAD_TAG = "wallhub_formal_workshop_download"
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
        private val credentialProvider: SteamContentCredentialProvider,
        private val conversionScheduler: ConversionWorkScheduler,
        private val settingsRepository: com.wallhub.android.core.model.SettingsRepository,
        private val downloadConcurrencyGovernor: DownloadConcurrencyGovernor,
        private val steamWorkshopContentClient: SteamWorkshopContentClient,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): androidx.work.ListenableWorker? =
            when (workerClassName) {
                FormalWorkshopDownloadWorker::class.java.name ->
                    FormalWorkshopDownloadWorker(
                        appContext = appContext,
                        params = workerParameters,
                        taskDao = taskDao,
                        credentialProvider = credentialProvider,
                        conversionScheduler = conversionScheduler,
                        settingsRepository = settingsRepository,
                        downloadConcurrencyGovernor = downloadConcurrencyGovernor,
                        steamWorkshopContentClient = steamWorkshopContentClient,
                    )

                FormalWorkshopConversionWorker::class.java.name ->
                    FormalWorkshopConversionWorker(
                        appContext = appContext,
                        params = workerParameters,
                        taskDao = taskDao,
                        downloadConcurrencyGovernor = downloadConcurrencyGovernor,
                    )

                else -> null
            }
    }
