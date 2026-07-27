package com.wallhub.android.data.downloads

import android.content.Context
import android.util.Log
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.DownloadTaskRepository
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.WorkshopType
import javax.inject.Inject
import java.io.File
import java.util.UUID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomDownloadTaskRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskDao: FormalTaskRecordDao,
    private val credentialProvider: SteamContentCredentialProvider,
    private val workScheduler: DownloadWorkScheduler,
    private val conversionScheduler: ConversionWorkScheduler,
    private val settingsRepository: SettingsRepository,
    private val downloadConcurrencyGovernor: DownloadConcurrencyGovernor,
) : DownloadTaskRepository {
    override val tasks: Flow<List<DownloadTask>> = taskDao.observeAll().map { records ->
        records.map(FormalTaskRecordEntity::toModel)
    }

    override suspend fun find(taskId: String): DownloadTask? = taskDao.find(taskId)?.toModel()

    override suspend fun upsert(task: DownloadTask) {
        taskDao.upsert(task.toEntity())
    }

    override suspend fun enqueue(request: DownloadRequest): DownloadTask {
        require(request.workshopId > 0L) { "创意工坊项目 ID 无效" }
        taskDao.findActiveForWorkshop(request.workshopId)?.toModel()?.let { return it }
        val now = System.currentTimeMillis()
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            workshopId = request.workshopId,
            title = request.title.ifBlank { "Workshop ${request.workshopId}" },
            type = request.type,
            status = DownloadStatus.QUEUED,
            previewUrl = request.previewUrl,
            totalBytes = request.expectedTotalBytes.coerceAtLeast(0L),
            accountName = credentialProvider.loadContentCredential()?.accountName,
            outputTreeUri = request.outputTreeUri,
            exportFormat = request.exportFormat,
            message = "等待 Steam 下载队列执行",
            queuePosition = taskDao.nextQueuePosition(),
            createdAt = now,
            updatedAt = now,
        )
        upsert(task)
        return try {
            workScheduler.enqueue(task.id)
            Log.i(LOG_TAG, "Queued formal Steam download taskId=${task.id}")
            task
        } catch (error: Throwable) {
            val failed = task.copy(
                status = DownloadStatus.FAILED,
                message = "无法启动下载任务：${error.javaClass.simpleName}",
                updatedAt = System.currentTimeMillis(),
            )
            upsert(failed)
            Log.e(
                LOG_TAG,
                "Unable to queue formal Steam download taskId=${task.id}, type=${error.javaClass.name}",
            )
            failed
        }
    }

    override suspend fun requestAction(taskId: String, action: DownloadAction) {
        val task = find(taskId) ?: return
        require(action in task.availableActions) {
            "${task.status} 状态不支持 ${action.name} 操作"
        }
        val activeWorker = ActiveFormalWorkshopDownloadWorkers.isActive(taskId)
        when (action) {
            DownloadAction.PAUSE -> upsert(
                task.copy(
                    requestedAction = action,
                    message = "已请求暂停，下载器会保留已验证的数据",
                    updatedAt = System.currentTimeMillis(),
                ),
            )

            DownloadAction.RESUME,
            DownloadAction.RETRY,
            -> {
                val hasStagingDirectory = task.stagingDirectory
                    ?.let(::File)
                    ?.isDirectory == true
                if (action == DownloadAction.RETRY && hasStagingDirectory) {
                    val reconverting = task.copy(
                        status = DownloadStatus.CONVERTING,
                        requestedAction = null,
                        message = "正在重试转换和导出…",
                        updatedAt = System.currentTimeMillis(),
                    )
                    upsert(reconverting)
                    conversionScheduler.enqueue(taskId)
                    return
                }
                val resumed = task.copy(
                    status = if (activeWorker) DownloadStatus.DOWNLOADING else DownloadStatus.QUEUED,
                    requestedAction = null,
                    message = if (activeWorker) {
                        "正在继续当前 Steam 内容会话…"
                    } else {
                        "正在恢复 Steam 下载队列…"
                    },
                    updatedAt = System.currentTimeMillis(),
                )
                upsert(resumed)
                if (!activeWorker) workScheduler.enqueue(taskId)
            }

            DownloadAction.EXPORT -> {
                val outputTreeUri = settingsRepository.preferences.first().outputTreeUri
                val stagingDirectory = task.stagingDirectory?.let(::File)
                require(stagingDirectory?.isDirectory == true) { "下载暂存文件不存在，无法导出" }
                val exporting = task.copy(
                    status = DownloadStatus.CONVERTING,
                    outputTreeUri = outputTreeUri,
                    outputUri = null,
                    requestedAction = null,
                    message = "正在准备转换并导出…",
                    updatedAt = System.currentTimeMillis(),
                )
                upsert(exporting)
                conversionScheduler.enqueue(taskId)
            }

            DownloadAction.CANCEL -> {
                if (task.status == DownloadStatus.CONVERTING) {
                    if (!FormalWorkshopConversionCancellation.beginRequest(taskId)) return
                    try {
                        withContext(NonCancellable) {
                            upsert(
                                task.copy(
                                    requestedAction = action,
                                    message = "正在取消转换并清理暂存文件",
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                            FormalWorkshopConversionCancellation.completeRequest(taskId)
                        }
                    } catch (error: Throwable) {
                        FormalWorkshopConversionCancellation.abortRequest(taskId)
                        throw error
                    }
                    return
                }
                if (activeWorker) {
                    upsert(
                        task.copy(
                            requestedAction = action,
                            message = "正在取消下载并清理暂存文件",
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                } else {
                    deleteManagedStagingDirectory(context, task.stagingDirectory)
                    upsert(
                        task.copy(
                            status = DownloadStatus.CANCELLED,
                            downloadedBytes = 0L,
                            stagingDirectory = null,
                            requestedAction = null,
                            message = "下载任务已取消，暂存文件已清理",
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }

            DownloadAction.DELETE -> {
                require(
                    task.status in setOf(
                        DownloadStatus.COMPLETED,
                        DownloadStatus.FAILED,
                        DownloadStatus.CANCELLED,
                    ),
                ) { "进行中的下载不能直接删除，请先取消任务" }
                deleteManagedStagingDirectory(context, task.stagingDirectory)
                taskDao.delete(taskId)
            }
        }
    }

    override suspend fun reorder(taskIds: List<String>) {
        val requestedOrder = taskIds.distinct()
        if (requestedOrder.size < 2) return
        val records = taskDao.observeAll().first()
        val reorderable = records.filter { record -> record.status in REORDERABLE_STATUSES }
        val reorderableIds = reorderable.map(FormalTaskRecordEntity::taskId).toSet()
        val orderedActiveIds = requestedOrder.filter(reorderableIds::contains)
        val requestedSet = orderedActiveIds.toSet()
        val orderedRequested = orderedActiveIds.iterator()
        val mergedOrder = reorderable.map { record ->
            if (record.taskId in requestedSet && orderedRequested.hasNext()) {
                orderedRequested.next()
            } else {
                record.taskId
            }
        }
        taskDao.updateQueueOrder(mergedOrder)
        downloadConcurrencyGovernor.updatePriorities(mergedOrder)
    }

    override suspend fun clearFinishedHistory(): Int {
        // Completed video tasks retain their private source files for local playback.
        // Once their queue history is cleared, there is no route back to that player,
        // so clean only those private staging directories while keeping exported files.
        taskDao.observeAll()
            .first()
            .filter { task ->
                task.status in setOf(
                    DownloadStatus.COMPLETED.name,
                    DownloadStatus.FAILED.name,
                    DownloadStatus.CANCELLED.name,
                )
            }
            .forEach { task -> deleteManagedStagingDirectory(context, task.stagingDirectory) }
        return taskDao.clearFinishedHistory()
    }
}

internal fun FormalTaskRecordEntity.toModel(): DownloadTask = DownloadTask(
    id = taskId,
    workshopId = workshopId,
    title = title,
    type = type.toWorkshopType(),
    status = status.toDownloadStatus(),
    previewUrl = previewUrl,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    bytesPerSecond = bytesPerSecond,
    accountName = accountName,
    message = message,
    outputLabel = outputLabel,
    stagingDirectory = stagingDirectory,
    contentManifestId = contentManifestId,
    appId = appId,
    outputTreeUri = outputTreeUri,
    outputUri = outputUri,
    exportFormat = exportFormat.toExportFormat(),
    requestedAction = requestedAction?.toDownloadAction(),
    isResumable = isResumable,
    queuePosition = queuePosition,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun DownloadTask.toEntity(): FormalTaskRecordEntity = FormalTaskRecordEntity(
    taskId = id,
    workshopId = workshopId,
    title = title,
    type = type.name,
    status = status.name,
    previewUrl = previewUrl,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    bytesPerSecond = bytesPerSecond,
    accountName = accountName,
    outputLabel = outputLabel,
    stagingDirectory = stagingDirectory,
    contentManifestId = contentManifestId,
    appId = appId,
    outputTreeUri = outputTreeUri,
    outputUri = outputUri,
    exportFormat = exportFormat.name,
    message = message,
    requestedAction = requestedAction?.name,
    isResumable = isResumable,
    queuePosition = queuePosition,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun String.toWorkshopType(): WorkshopType = enumValues<WorkshopType>()
    .firstOrNull { it.name == this }
    ?: WorkshopType.UNKNOWN

private fun String.toDownloadStatus(): DownloadStatus = enumValues<DownloadStatus>()
    .firstOrNull { it.name == this }
    ?: DownloadStatus.FAILED

private fun String.toDownloadAction(): DownloadAction? = enumValues<DownloadAction>()
    .firstOrNull { it.name == this }

private fun String.toExportFormat(): ExportFormat = enumValues<ExportFormat>()
    .firstOrNull { it.name == this }
    ?: ExportFormat.AUTO

private fun deleteManagedStagingDirectory(context: Context, path: String?) {
    if (path.isNullOrBlank()) return
    val directory = runCatching { File(path).canonicalFile }.getOrNull() ?: return
    if (
        isManagedWorkshopStagingDirectory(
            persistentRoot = File(context.filesDir, WORKSHOP_STAGING_DIRECTORY_NAME),
            legacyRoot = File(context.cacheDir, WORKSHOP_STAGING_DIRECTORY_NAME),
            directory = directory,
        )
    ) {
        directory.deleteRecursively()
    }
}

private const val LOG_TAG = "WallHubDownload"
private val REORDERABLE_STATUSES = setOf(
    DownloadStatus.QUEUED.name,
    DownloadStatus.RESOLVING.name,
    DownloadStatus.DOWNLOADING.name,
    DownloadStatus.PAUSED.name,
)
