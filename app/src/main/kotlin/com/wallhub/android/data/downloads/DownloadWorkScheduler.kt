package com.wallhub.android.data.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.model.SteamContentCredentialProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface DownloadWorkScheduler {
    fun enqueue(taskId: String)

    fun cancel(taskId: String)
}

interface ConversionWorkScheduler {
    fun enqueue(taskId: String)

    fun cancel(taskId: String)
}

@Singleton
class WorkManagerDownloadWorkScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : DownloadWorkScheduler {
        override fun enqueue(taskId: String) {
            val request =
                OneTimeWorkRequestBuilder<FormalWorkshopDownloadWorker>()
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).setInputData(workDataOf(FormalWorkshopDownloadWorker.KEY_TASK_ID to taskId))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .addTag(FORMAL_DOWNLOAD_TAG)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                FormalWorkshopDownloadWorker.UNIQUE_DOWNLOAD_WORK_PREFIX + taskId,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        override fun cancel(taskId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(
                FormalWorkshopDownloadWorker.UNIQUE_DOWNLOAD_WORK_PREFIX + taskId,
            )
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
        override fun enqueue(taskId: String) {
            val request =
                OneTimeWorkRequestBuilder<FormalWorkshopConversionWorker>()
                    .setInputData(workDataOf(FormalWorkshopConversionWorker.KEY_TASK_ID to taskId))
                    .addTag(FormalWorkshopConversionWorker.WORK_TAG_PREFIX + taskId)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                FormalWorkshopConversionWorker.UNIQUE_WORK_NAME_PREFIX + taskId,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        override fun cancel(taskId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(
                FormalWorkshopConversionWorker.UNIQUE_WORK_NAME_PREFIX + taskId,
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
