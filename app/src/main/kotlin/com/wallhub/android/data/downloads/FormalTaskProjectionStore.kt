package com.wallhub.android.data.downloads

import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.DownloadStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single writer for observable formal-download task projections.
 *
 * Execution intent and receipt fields remain owned by their durable command transactions. Projection writes may
 * update user-visible transfer fields, but preserve those authority fields and may not revive terminal work.
 */
@Singleton
internal class FormalTaskProjectionStore
    @Inject
    constructor(
        private val taskDao: FormalTaskRecordDao,
    ) {
        suspend fun persist(
            previous: FormalTaskRecordEntity,
            title: String = previous.title,
            type: String = previous.type,
            previewUrl: String? = previous.previewUrl,
            appId: Int = previous.appId,
            contentManifestId: Long = previous.contentManifestId,
            stagingDirectory: String? = previous.stagingDirectory,
            accountName: String? = previous.accountName,
            credentialMode: String = previous.credentialMode,
            status: DownloadStatus = previous.status.toDownloadStatus(),
            downloadedBytes: Long = previous.downloadedBytes,
            totalBytes: Long = previous.totalBytes,
            bytesPerSecond: Long = previous.bytesPerSecond,
            outputLabel: String? = previous.outputLabel,
            message: String? = previous.message,
        ): FormalTaskRecordEntity {
            val persisted = taskDao.find(previous.taskId)
            if (persisted != null && !mayPublishDownloadProgress(persisted.status, status)) {
                return persisted
            }
            val updated =
                previous.copy(
                    title = title,
                    type = type,
                    previewUrl = previewUrl,
                    appId = appId,
                    contentManifestId = contentManifestId,
                    stagingDirectory = stagingDirectory,
                    accountName = accountName,
                    credentialMode = credentialMode,
                    status = status.name,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    bytesPerSecond = bytesPerSecond,
                    outputLabel = outputLabel,
                    message = message,
                    queuePosition = persisted?.queuePosition ?: previous.queuePosition,
                    updatedAt = System.currentTimeMillis(),
                )
            return taskDao.upsertPreservingExecutionIntent(updated)
        }

        private fun String.toDownloadStatus(): DownloadStatus =
            enumValues<DownloadStatus>().firstOrNull { it.name == this } ?: DownloadStatus.FAILED
    }
