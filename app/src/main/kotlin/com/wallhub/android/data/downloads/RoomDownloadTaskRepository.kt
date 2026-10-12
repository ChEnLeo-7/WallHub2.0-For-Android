package com.wallhub.android.data.downloads

import android.content.Context
import android.util.Log
import com.wallhub.android.R
import com.wallhub.android.core.database.DownloadCommandEntity
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadCredentialMode
import com.wallhub.android.core.model.DownloadEnqueueResult
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.android.core.model.WorkshopDownloads
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.prototype.mpkg.PresetDependencyResolver
import com.wallhub.prototype.mpkg.requireBoundedPersistedConversionJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

internal class RoomDownloadTaskRepository private constructor(
    private val context: Context,
    private val taskDao: FormalTaskRecordDao,
    private val credentialProvider: SteamContentCredentialProvider,
    private val workScheduler: DownloadWorkScheduler,
    private val conversionScheduler: ConversionWorkScheduler,
    private val settingsRepository: SettingsRepository,
    private val downloadConcurrencyGovernor: DownloadConcurrencyGovernor,
    private val steamWorkshopContentClient: SteamWorkshopContentClient,
    private val liveTelemetry: DownloadLiveTelemetry,
    private val queuedMessage: () -> String,
    private val enqueueFailedMessage: (String) -> String,
) : WorkshopDownloads {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        taskDao: FormalTaskRecordDao,
        credentialProvider: SteamContentCredentialProvider,
        workScheduler: DownloadWorkScheduler,
        conversionScheduler: ConversionWorkScheduler,
        settingsRepository: SettingsRepository,
        downloadConcurrencyGovernor: DownloadConcurrencyGovernor,
        steamWorkshopContentClient: SteamWorkshopContentClient,
        liveTelemetry: DownloadLiveTelemetry,
    ) : this(
        context = context,
        taskDao = taskDao,
        credentialProvider = credentialProvider,
        workScheduler = workScheduler,
        conversionScheduler = conversionScheduler,
        settingsRepository = settingsRepository,
        downloadConcurrencyGovernor = downloadConcurrencyGovernor,
        steamWorkshopContentClient = steamWorkshopContentClient,
        liveTelemetry = liveTelemetry,
        queuedMessage = { context.getString(R.string.backend_download_queued) },
        enqueueFailedMessage = { errorType ->
            context.getString(R.string.backend_download_start_failed, errorType)
        },
    )

    internal constructor(
        context: Context,
        taskDao: FormalTaskRecordDao,
        credentialProvider: SteamContentCredentialProvider,
        workScheduler: DownloadWorkScheduler,
        conversionScheduler: ConversionWorkScheduler,
        settingsRepository: SettingsRepository,
        downloadConcurrencyGovernor: DownloadConcurrencyGovernor,
        steamWorkshopContentClient: SteamWorkshopContentClient,
        liveTelemetry: DownloadLiveTelemetry = DownloadLiveTelemetry(),
        queuedMessage: String,
        enqueueFailedMessage: (String) -> String,
    ) : this(
        context,
        taskDao,
        credentialProvider,
        workScheduler,
        conversionScheduler,
        settingsRepository,
        downloadConcurrencyGovernor,
        steamWorkshopContentClient,
        liveTelemetry,
        { queuedMessage },
        enqueueFailedMessage,
    )
        private val taskMutationMutex = Mutex()

        override val tasks: Flow<List<DownloadTask>> =
            combine(taskDao.observeAll(), liveTelemetry.readings) { records, _ ->
                records.map { record -> liveTelemetry.enrich(record.toModel()) }
            }

        override suspend fun find(taskId: String): DownloadTask? =
            taskDao.find(taskId)?.toModel()?.let(liveTelemetry::enrich)

        private suspend fun upsert(task: DownloadTask) {
            taskDao.upsertPreservingExecutionIntent(task.toEntity())
        }

        override suspend fun enqueue(request: DownloadRequest): DownloadEnqueueResult = taskMutationMutex.withLock {
            require(request.workshopId > 0L) { "Invalid Workshop item ID" }
            taskDao.findActiveForWorkshop(request.workshopId)?.toModel()?.let(liveTelemetry::enrich)?.let { activeTask ->
                if (activeTask.status in DOWNLOAD_WORK_STATUSES) {
                    try {
                        // A global successor wake repairs a persisted row whose execution window was lost
                        // to process death without creating per-task work.
                        workScheduler.wake()
                    } catch (error: Throwable) {
                        Log.e(
                            LOG_TAG,
                            "Unable to ensure formal Steam download taskId=${activeTask.id}, type=${error.javaClass.name}",
                        )
                        throw error
                    }
                }
                return DownloadEnqueueResult.AlreadyActive(activeTask)
            }
            val now = System.currentTimeMillis()
            // Do not hold the task mutation lock while waiting up to 30 seconds for Steam
            // restoration. The worker performs the authoritative credential resolution.
            val credential = credentialProvider.loadContentCredential()
            val task =
                DownloadTask(
                    id = request.taskId ?: UUID.randomUUID().toString(),
                    workshopId = request.workshopId,
                    title = request.title.ifBlank { "Workshop ${request.workshopId}" },
                    type = request.type,
                    status = DownloadStatus.QUEUED,
                    previewUrl = request.previewUrl,
                    totalBytes = request.expectedTotalBytes.coerceAtLeast(0L),
                    accountName = credential?.accountName,
                    credentialMode =
                        if (credential == null) {
                            DownloadCredentialMode.ANONYMOUS
                        } else {
                            DownloadCredentialMode.ACCOUNT
                        },
                    outputTreeUri = request.outputTreeUri,
                    exportFormat = request.exportFormat,
                    mpkgConversionPreset = request.mpkgConversionPreset,
                    message = queuedMessage(),
                    queuePosition = taskDao.nextQueuePosition(),
                    createdAt = now,
                    updatedAt = now,
                )
            val commandId = UUID.randomUUID().toString()
            taskDao.upsertWithCommand(
                task =
                    task.toEntity().copy(
                        desiredIntent = "RUN",
                        desiredIntentRevision = 1L,
                    ),
                command =
                    DownloadCommandEntity(
                        commandId = commandId,
                        taskId = task.id,
                        revision = 1L,
                        attempt = 0L,
                        command = "RUN",
                        createdAt = now,
                    ),
            )
            return try {
                workScheduler.wake()
                Log.i(LOG_TAG, "Queued formal Steam download taskId=${task.id}")
                DownloadTimingTelemetry.log(
                    event = DownloadTimingTelemetry.TASK_ENQUEUED,
                    taskId = task.id,
                    workshopId = task.workshopId,
                )
                DownloadEnqueueResult.Created(task)
            } catch (error: Throwable) {
                // The RUN command is already durable. WorkManager is only a wake-up adapter; reconciliation on
                // process start, connectivity change, or another command can deliver it later.
                Log.e(LOG_TAG, "Unable to wake download host for durable taskId=${task.id}", error)
                DownloadEnqueueResult.Created(task)
            }
        }

        override suspend fun persistConversionPlan(
            taskId: String,
            planJson: String?,
            planVersion: Int,
        ) = taskMutationMutex.withLock {
            require(planVersion > 0) { "Conversion plan version must be positive" }
            requireBoundedPersistedConversionJson(planJson)
            check(taskDao.persistConversionPlan(taskId, planJson, planVersion) == 1) {
                "Download task no longer exists"
            }
        }

        override suspend fun persistConversionReport(
            taskId: String,
            reportJson: String?,
        ) = taskMutationMutex.withLock {
            requireBoundedPersistedConversionJson(reportJson)
            check(taskDao.persistConversionReport(taskId, reportJson) == 1) {
                "Download task no longer exists"
            }
        }

        override suspend fun prewarm(workshopId: Long) {
            if (workshopId <= 0L) return
            val preferences = settingsRepository.preferences.first()
            val proxyUrl =
                preferences.downloadProxyUrl
                    .takeIf { preferences.downloadProxyEnabled }
                    .orEmpty()
            // What is worth warming is this client's target cache: the download worker's first step is the
            // same read. The depot side needs no warm-up here, because the Steam Content SDK resolves the
            // item and completes its own key/token handoff inside the task that downloads it.
            steamWorkshopContentClient.fetchContentTarget(workshopId, proxyUrl)
        }

        override suspend fun requestExport(
            taskId: String,
            format: ExportFormat,
        ) = taskMutationMutex.withLock {
            val task = requireNotNull(find(taskId)) { "Download task no longer exists" }
            require(DownloadAction.EXPORT in task.availableActions) {
                "Task status ${task.status} does not support export"
            }
            val configured = task.copy(exportFormat = format, updatedAt = System.currentTimeMillis())
            taskDao.upsertPreservingExecutionIntent(configured.toEntity())
            requestExport(configured)
        }

        override suspend fun requestAction(
            taskId: String,
            action: DownloadAction,
        ) = taskMutationMutex.withLock {
            val task = requireNotNull(find(taskId)) { "Download task no longer exists" }
            require(action == DownloadAction.CANCEL || action in task.availableActions) {
                "Task status ${task.status} does not support the ${action.name} action"
            }
            when (action) {
                DownloadAction.PAUSE -> requestPause(task)
                DownloadAction.RESUME,
                DownloadAction.RETRY,
                -> requestResumeOrRetry(task, action)
                DownloadAction.EXPORT -> requestExport(task)
                DownloadAction.CANCEL -> requestCancellation(task)
                DownloadAction.DELETE -> requestDeletion(task)
            }
        }

        private suspend fun requestPause(task: DownloadTask) {
            check(
                taskDao.appendIntentCommand(
                    taskId = task.id,
                    desiredIntent = "PAUSE",
                    message = context.getString(R.string.backend_download_pause_requested),
                    updatedAt = System.currentTimeMillis(),
                    allowedStatuses = DOWNLOAD_CONTROL_STATUSES,
                    commandId = UUID.randomUUID().toString(),
                ),
            ) { "Download state changed before pause could be requested" }
            try {
                workScheduler.wake()
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "Unable to wake host for durable PAUSE taskId=${task.id}", error)
            }
        }

        private suspend fun requestResumeOrRetry(
            task: DownloadTask,
            action: DownloadAction,
        ) {
            val recoveredTask =
                if (task.credentialMode == DownloadCredentialMode.LEGACY_UNKNOWN) {
                    val credential = credentialProvider.resolveContentCredential()
                    task.copy(
                        accountName = credential?.accountName,
                        credentialMode =
                            if (credential == null) {
                                DownloadCredentialMode.ANONYMOUS
                            } else {
                                DownloadCredentialMode.ACCOUNT
                            },
                    )
                } else {
                    task
                }
            if (action == DownloadAction.RETRY && recoveredTask.hasCompleteStagingDownload()) {
                val converting = recoveredTask.copy(
                    status = DownloadStatus.CONVERTING,
                    message = context.getString(R.string.backend_conversion_retrying),
                    updatedAt = System.currentTimeMillis(),
                )
                upsert(converting)
                try {
                    conversionScheduler.replace(task.id)
                } catch (error: Throwable) {
                    upsert(
                        converting.copy(
                            status = DownloadStatus.FAILED,
                            message = context.getString(
                                R.string.backend_download_start_failed,
                                error.javaClass.simpleName,
                            ),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    throw error
                }
                return
            }
            val resumed =
                recoveredTask.copy(
                    status = DownloadStatus.QUEUED,
                    message = context.getString(R.string.backend_download_resuming_queue),
                    updatedAt = System.currentTimeMillis(),
                )
            val current = requireNotNull(taskDao.find(task.id))
            val revision = current.desiredIntentRevision + 1L
            taskDao.upsertWithCommand(
                task =
                    resumed.toEntity().copy(
                        attempt = if (action == DownloadAction.RETRY) current.attempt + 1L else current.attempt,
                        desiredIntent = "RUN",
                        desiredIntentRevision = revision,
                        appliedIntentRevision = current.appliedIntentRevision,
                        quiescedIntentRevision = current.quiescedIntentRevision,
                        lastReceiptSequence = current.lastReceiptSequence,
                        engineEpoch = current.engineEpoch,
                    ),
                command =
                    DownloadCommandEntity(
                        commandId = UUID.randomUUID().toString(),
                        taskId = task.id,
                        revision = revision,
                        attempt = if (action == DownloadAction.RETRY) current.attempt + 1L else current.attempt,
                        command = "RUN",
                        createdAt = resumed.updatedAt,
                    ),
            )
            try {
                workScheduler.wake()
            } catch (error: Throwable) {
                // The RUN command is durable and startup reconciliation will deliver it later.
                Log.e(LOG_TAG, "Unable to wake host for durable RUN taskId=${task.id}", error)
            }
        }

        private suspend fun requestExport(task: DownloadTask) {
            val stagingDirectory = task.stagingDirectory?.let(::File)
            require(stagingDirectory?.isDirectory == true) { "Download staging files are missing; cannot export" }
            val converting = task.copy(
                status = DownloadStatus.CONVERTING,
                outputTreeUri = settingsRepository.preferences.first().outputTreeUri,
                outputUri = null,
                message = context.getString(R.string.backend_conversion_preparing),
                updatedAt = System.currentTimeMillis(),
            )
            upsert(converting)
            try {
                conversionScheduler.enqueue(task.id)
            } catch (error: Throwable) {
                upsert(
                    converting.copy(
                        status = DownloadStatus.FAILED,
                        message = context.getString(
                            R.string.backend_download_start_failed,
                            error.javaClass.simpleName,
                        ),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }

        private suspend fun requestCancellation(
            task: DownloadTask,
        ) {
            if (task.status == DownloadStatus.CONVERTING) {
                if (!FormalWorkshopConversionCancellation.beginRequest(task.id)) return
                try {
                    withContext(NonCancellable) {
                        check(
                            taskDao.requestConversionCancellation(
                                taskId = task.id,
                                message = context.getString(R.string.backend_conversion_cancelling),
                                updatedAt = System.currentTimeMillis(),
                            ),
                        ) { "Conversion state changed before cancellation could be requested" }
                        FormalWorkshopConversionCancellation.completeRequest(task.id)
                    }
                } catch (error: Throwable) {
                    FormalWorkshopConversionCancellation.abortRequest(task.id)
                    throw error
                }
                return
            }
            withContext(NonCancellable) {
                check(
                    taskDao.appendIntentCommand(
                        taskId = task.id,
                        desiredIntent = "CANCEL",
                        message = context.getString(R.string.backend_download_cancelling),
                        updatedAt = System.currentTimeMillis(),
                        allowedStatuses = DOWNLOAD_CONTROL_STATUSES + DownloadStatus.PAUSED.name,
                        commandId = UUID.randomUUID().toString(),
                    ),
                ) { "Download state changed before cancellation could be requested" }
                try {
                    workScheduler.wake()
                } catch (error: Throwable) {
                    Log.e(LOG_TAG, "Unable to wake host for durable CANCEL taskId=${task.id}", error)
                }
            }
        }

        private suspend fun requestDeletion(task: DownloadTask) {
            require(
                task.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED),
            ) { "An active download cannot be deleted; cancel the task first" }
            check(ConversionPlanStore(context.filesDir).delete(task.id)) { "Unable to delete conversion plan" }
            deleteManagedDownloadDirectories(context, task.id, task.stagingDirectory)
            check(taskDao.deleteTerminalWithCommands(task.id) == 1) {
                "Download task changed before it could be deleted"
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
            val mergedOrder =
                reorderable.map { record ->
                    if (record.taskId in requestedSet && orderedRequested.hasNext()) {
                        orderedRequested.next()
                    } else {
                        record.taskId
                    }
                }
            taskDao.updateQueueOrder(mergedOrder)
            downloadConcurrencyGovernor.updatePriorities(mergedOrder)
        }

        override suspend fun clearFinishedHistory(): Int = taskMutationMutex.withLock {
            val removableTasks =
                taskDao
                    .observeAll()
                    .first()
                    .filter { task ->
                        task.status in
                            setOf(
                                DownloadStatus.COMPLETED.name,
                                DownloadStatus.FAILED.name,
                                DownloadStatus.CANCELLED.name,
                            )
                    }.filterNot(FormalTaskRecordEntity::isRetainedLocalVideo)
            return deleteHistoryTasks(removableTasks)
        }

        override suspend fun clearCompletedHistory(): Int = taskMutationMutex.withLock {
            val removableTasks =
                taskDao
                    .listAll()
                    .filter { task -> task.status == DownloadStatus.COMPLETED.name }
                    .filterNot(FormalTaskRecordEntity::isRetainedLocalVideo)
            return deleteHistoryTasks(removableTasks)
        }

        override suspend fun retryFailedTasks(): Int {
            val failedTaskIds =
                taskDao.listAll()
                    .filter { task -> task.status == DownloadStatus.FAILED.name }
                    .map(FormalTaskRecordEntity::taskId)
            return failedTaskIds.count { taskId ->
                runCatching { requestAction(taskId, DownloadAction.RETRY) }.isSuccess
            }
        }

        private suspend fun deleteHistoryTasks(tasks: List<FormalTaskRecordEntity>): Int =
            tasks.sumOf { task ->
                check(ConversionPlanStore(context.filesDir).delete(task.taskId)) { "Unable to delete conversion plan" }
                val deleted = taskDao.deleteTerminalWithCommands(task.taskId)
                if (deleted > 0) {
                    deleteManagedDownloadDirectories(context, task.taskId, task.stagingDirectory)
                    File(context.cacheDir, "wallhub-conversion/${task.taskId}").deleteRecursively()
                }
                deleted
            }
    }

internal suspend fun SteamContentCredentialProvider.resolveContentCredential() =
    loadContentCredential() ?: restoreContentCredential()

internal fun FormalTaskRecordEntity.isRetainedLocalVideo(): Boolean =
    status == DownloadStatus.COMPLETED.name &&
        type == WorkshopType.VIDEO.name &&
        !stagingDirectory.isNullOrBlank()

internal fun DownloadTask.hasCompleteStagingDownload(): Boolean {
    if (totalBytes <= 0L || downloadedBytes < totalBytes) return false
    val directory = stagingDirectory?.let(::File)?.takeIf(File::isDirectory) ?: return false
    var hasFile = false
    directory.walkTopDown().forEach { file ->
        if (!file.isFile) return@forEach
        if (file.name.endsWith(PARTIAL_DOWNLOAD_SUFFIX, ignoreCase = true)) return false
        hasFile = true
    }
    return hasFile && hasCompletePresetDependency(directory)
}

internal fun hasCompletePresetDependency(directory: File): Boolean =
    PresetDependencyResolver.resolve(directory).isSuccess

internal fun FormalTaskRecordEntity.toModel(): DownloadTask =
    DownloadTask(
        id = taskId,
        workshopId = workshopId,
        title = title,
        type = type.toWorkshopType(),
        status = status.toDownloadStatus(),
        previewUrl = previewUrl,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        // Persisted throughput predates SDK live telemetry and must never be restored as current speed.
        bytesPerSecond = 0L,
        accountName = accountName,
        credentialMode = credentialMode.toDownloadCredentialMode(),
        message = message,
        outputLabel = outputLabel,
        stagingDirectory = stagingDirectory,
        contentManifestId = contentManifestId,
        appId = appId,
        outputTreeUri = outputTreeUri,
        outputUri = outputUri,
        exportFormat = exportFormat.toExportFormat(),
        mpkgConversionPreset = MpkgConversionPreset.fromStableId(conversionPreset),
        conversionPlanJson = conversionPlanJson,
        conversionReportJson = conversionReportJson,
        conversionPlanVersion = conversionPlanVersion,
        pendingControl =
            desiredIntent.toPendingControl().takeIf {
                desiredIntentRevision > appliedIntentRevision
            },
        isResumable = isResumable,
        queuePosition = queuePosition,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

internal fun DownloadTask.toEntity(): FormalTaskRecordEntity {
    requireBoundedPersistedConversionJson(conversionPlanJson)
    requireBoundedPersistedConversionJson(conversionReportJson)
    return FormalTaskRecordEntity(
        taskId = id,
        workshopId = workshopId,
        title = title,
        type = type.name,
        status = status.name,
        previewUrl = previewUrl,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        // Keep the legacy Room column inert; speed is process-local SDK network telemetry.
        bytesPerSecond = 0L,
        accountName = accountName,
        credentialMode = credentialMode.name,
        outputLabel = outputLabel,
        stagingDirectory = stagingDirectory,
        contentManifestId = contentManifestId,
        appId = appId,
        outputTreeUri = outputTreeUri,
        outputUri = outputUri,
        exportFormat = exportFormat.name,
        conversionPreset = mpkgConversionPreset.stableId,
        conversionPlanJson = conversionPlanJson,
        conversionReportJson = conversionReportJson,
        conversionPlanVersion = conversionPlanVersion,
        message = message,
        isResumable = isResumable,
        queuePosition = queuePosition,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun String.toWorkshopType(): WorkshopType =
    enumValues<WorkshopType>()
        .firstOrNull { it.name == this }
        ?: WorkshopType.UNKNOWN

private fun String.toDownloadStatus(): DownloadStatus =
    enumValues<DownloadStatus>()
        .firstOrNull { it.name == this }
        ?: DownloadStatus.FAILED

private fun String.toDownloadCredentialMode(): DownloadCredentialMode =
    enumValues<DownloadCredentialMode>()
        .firstOrNull { it.name == this }
        ?: DownloadCredentialMode.LEGACY_UNKNOWN

private fun String.toPendingControl(): DownloadAction? =
    when (this) {
        DownloadAction.PAUSE.name -> DownloadAction.PAUSE
        DownloadAction.CANCEL.name -> DownloadAction.CANCEL
        else -> null
    }

private fun String.toExportFormat(): ExportFormat =
    enumValues<ExportFormat>()
        .firstOrNull { it.name == this }
        ?: ExportFormat.AUTO

private fun deleteManagedStagingDirectory(
    context: Context,
    path: String?,
) {
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

/**
 * Removes every directory one task's download owns.
 *
 * The destination directory is what this app's own layout names, but an SDK-backed download stages its
 * partial bytes under the SDK's `.sc-staging` root, keyed by that same destination. A cancel or a delete
 * that removed only the destination would leave the checkpoint the SDK resumes from, so a later retry of
 * a task the person discarded would continue the bytes it was supposed to discard.
 */
private fun deleteManagedDownloadDirectories(
    context: Context,
    taskId: String,
    stagingDirectory: String?,
) {
    deleteManagedStagingDirectory(context, stagingDirectory)
    sdkStagingDirectory(context.filesDir, taskId)?.deleteRecursively()
}

private const val LOG_TAG = "WallHubDownload"
private const val PARTIAL_DOWNLOAD_SUFFIX = ".wallhub.part"
private val REORDERABLE_STATUSES =
    setOf(
        DownloadStatus.QUEUED.name,
        DownloadStatus.RESOLVING.name,
        DownloadStatus.DOWNLOADING.name,
        DownloadStatus.PAUSED.name,
    )
private val DOWNLOAD_CONTROL_STATUSES =
    listOf(
        DownloadStatus.QUEUED.name,
        DownloadStatus.RESOLVING.name,
        DownloadStatus.DOWNLOADING.name,
    )
private val DOWNLOAD_WORK_STATUSES =
    setOf(
        DownloadStatus.QUEUED,
        DownloadStatus.RESOLVING,
        DownloadStatus.DOWNLOADING,
    )
