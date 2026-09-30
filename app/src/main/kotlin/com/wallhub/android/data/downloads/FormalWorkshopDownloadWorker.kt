package com.wallhub.android.data.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.wallhub.android.R

/** Upgrade-drain adapter for per-task WorkSpecs created before Room v9; no new work uses this class. */
class FormalWorkshopDownloadWorker
    internal constructor(
        appContext: Context,
        params: WorkerParameters,
        private val runner: FormalDownloadRunner,
        private val downloadHostCoordinator: DownloadHostCoordinator,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val taskId = resolveDownloadTaskId(inputData.getString(KEY_TASK_ID), tags)
            if (taskId == null) {
                Log.w(DOWNLOAD_LOG_TAG, "doWork missing input data; keys=${inputData.keyValueMap.keys}")
                return Result.failure()
            }
            return downloadHostCoordinator.reconcile(taskId) { runner.run(taskId) }.toWorkerResult()
        }

        override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

        private fun createForegroundInfo(): ForegroundInfo {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    applicationContext.getString(R.string.backend_download_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            val notification =
                NotificationCompat
                    .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_wallhub_notification)
                    .setContentTitle(applicationContext.getString(R.string.backend_download_notification_title))
                    .setContentText(applicationContext.getString(R.string.backend_download_notification_text))
                    .setOngoing(true)
                    .build()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
        }

        companion object {
            const val KEY_TASK_ID = "task_id"
            const val WORK_TAG_PREFIX = "wallhub_formal_workshop_download_task_"
            private const val NOTIFICATION_CHANNEL_ID = "wallhub_workshop_download"
            private const val NOTIFICATION_ID = 4202
        }
    }
