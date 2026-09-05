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
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.format.formatByteSize
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadCredentialMode
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.WorkshopType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal const val WORKSHOP_STAGING_DIRECTORY_NAME = "wallhub-workshop"
private const val DOWNLOAD_LOG_TAG = "WallHubDownload"

internal object ActiveFormalWorkshopDownloadWorkers {
    private val taskIds = ConcurrentHashMap.newKeySet<String>()

    fun markActive(taskId: String) {
        taskIds += taskId
    }

    fun markInactive(taskId: String) {
        taskIds -= taskId
    }

    fun isActive(taskId: String): Boolean = taskId in taskIds
}

internal fun resolveDownloadTaskId(
    inputTaskId: String?,
    tags: Set<String>,
): String? =
    inputTaskId?.takeIf(String::isNotBlank)
        ?: tags
            .firstOrNull { it.startsWith(FormalWorkshopDownloadWorker.WORK_TAG_PREFIX) }
            ?.removePrefix(FormalWorkshopDownloadWorker.WORK_TAG_PREFIX)
            ?.takeIf(String::isNotBlank)

internal fun resolveWorkshopStagingDirectory(
    persistentRoot: File,
    legacyRoot: File,
    taskId: String,
    persistedDirectory: String?,
): File {
    val persistentTaskDirectory = managedTaskDirectory(persistentRoot, taskId)
    val legacyTaskDirectory = managedTaskDirectory(legacyRoot, taskId)
    val persistedPath =
        persistedDirectory
            ?.let { path -> runCatching { File(path).canonicalFile.path }.getOrNull() }
    val shouldReuseLegacy =
        legacyTaskDirectory.isDirectory &&
            (persistedPath == legacyTaskDirectory.path || !persistentTaskDirectory.exists())
    if (!shouldReuseLegacy) return persistentTaskDirectory
    persistentTaskDirectory.parentFile?.mkdirs()
    if (!persistentTaskDirectory.exists() && legacyTaskDirectory.renameTo(persistentTaskDirectory)) {
        return persistentTaskDirectory
    }
    return legacyTaskDirectory
}

internal fun isManagedWorkshopStagingDirectory(
    persistentRoot: File,
    legacyRoot: File,
    directory: File,
): Boolean {
    val candidate = runCatching { directory.canonicalFile }.getOrNull() ?: return false
    return candidate.parentFile?.path == persistentRoot.canonicalFile.path ||
        candidate.parentFile?.path == legacyRoot.canonicalFile.path
}

private fun managedTaskDirectory(
    root: File,
    taskId: String,
): File {
    val canonicalRoot = root.canonicalFile
    val candidate = File(canonicalRoot, taskId).canonicalFile
    check(candidate.parentFile?.path == canonicalRoot.path) { "Invalid Steam download staging directory" }
    return candidate
}

class FormalWorkshopDownloadWorker
    internal constructor(
        appContext: Context,
        params: WorkerParameters,
        private val taskDao: FormalTaskRecordDao,
        private val credentialProvider: SteamContentCredentialProvider,
        private val conversionScheduler: ConversionWorkScheduler,
        private val settingsRepository: SettingsRepository,
        private val downloadConcurrencyGovernor: DownloadConcurrencyGovernor,
        private val steamWorkshopContentClient: SteamWorkshopContentClient,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result =
            withContext(Dispatchers.IO) {
                // Some Android 16 ROM deliveries strip WorkManager input data. The task-specific
                // tag still identifies this request without racing another queued worker.
                var taskId = resolveDownloadTaskId(inputData.getString(KEY_TASK_ID), tags)
                if (taskId == null) {
                    taskId = taskDao.findOldestQueued()?.taskId
                    Log.w(
                        DOWNLOAD_LOG_TAG,
                        "doWork identity missing; adopting legacy queued task=$taskId",
                    )
                }
                if (taskId == null) {
                    Log.w(DOWNLOAD_LOG_TAG, "doWork missing input data; keys=${inputData.keyValueMap.keys}")
                    return@withContext Result.failure()
                }
                val found = taskDao.find(taskId)
                if (found == null) {
                    Log.w(DOWNLOAD_LOG_TAG, "doWork task record missing taskId=$taskId")
                    return@withContext Result.failure()
                }
                var task: FormalTaskRecordEntity = found
                Log.i(DOWNLOAD_LOG_TAG, "Started formal Steam download worker taskId=$taskId")
                if (task.status in setOf(DownloadStatus.COMPLETED.name, DownloadStatus.CANCELLED.name)) {
                    return@withContext Result.success()
                }
                if (task.requestedAction == DownloadAction.CANCEL.name) {
                    task.stagingDirectory
                        ?.let(::File)
                        ?.takeIf(::isManagedStagingDirectory)
                        ?.deleteRecursively()
                    persist(
                        task,
                        status = DownloadStatus.CANCELLED,
                        stagingDirectory = null,
                        downloadedBytes = 0L,
                        bytesPerSecond = 0L,
                        message = applicationContext.getString(R.string.backend_download_cancelled_cleaned),
                        clearRequestedAction = true,
                    )
                    return@withContext Result.success()
                }
                ActiveFormalWorkshopDownloadWorkers.markActive(taskId)
                var stagingDirectory: File? = null
                var contentTransportLease: java.io.Closeable? = null
                try {
                    // No setForeground() here: this ROM treats the WorkManager FGS as
                    // typeless and force-stops it after ~4 seconds, which cancelled the
                    // worker mid-run. A plain JobScheduler job survives; Result.retry()
                    // covers any residual system stops.
                    val downloadPreferences = settingsRepository.preferences.first()
                    contentTransportLease = steamWorkshopContentClient.acquireContentTransportLease()
                    task =
                        persist(
                            task,
                            status = DownloadStatus.QUEUED,
                            message = applicationContext.getString(R.string.backend_download_queued),
                        )
                    downloadConcurrencyGovernor.withSlot(
                        taskId = taskId,
                        priority = task.queuePosition,
                        limit = downloadPreferences.maxConcurrentDownloads,
                    ) {
                        task =
                            persist(
                                task,
                                status = DownloadStatus.RESOLVING,
                                message = applicationContext.getString(R.string.backend_download_reading_workshop_details),
                            )
                        val activeProxyUrl =
                            downloadPreferences.downloadProxyUrl
                                .takeIf { downloadPreferences.downloadProxyEnabled }
                                .orEmpty()
                        val target =
                            steamWorkshopContentClient.fetchContentTarget(
                                publishedFileId = task.workshopId,
                                proxyUrl = activeProxyUrl,
                            )
                        val credential = credentialProvider.resolveContentCredential()
                        if (task.credentialMode == DownloadCredentialMode.LEGACY_UNKNOWN.name) {
                            persist(
                                task,
                                status = DownloadStatus.PAUSED,
                                requestedAction = null,
                                message = applicationContext.getString(R.string.backend_download_switch_account, "unknown account"),
                            )
                            return@withSlot Result.success()
                        }
                        if (
                            task.credentialMode == DownloadCredentialMode.ACCOUNT.name &&
                            (!task.accountName.isNullOrBlank() &&
                                !task.accountName.equals(credential?.accountName, ignoreCase = true))
                        ) {
                            persist(
                                task,
                                status = DownloadStatus.PAUSED,
                                requestedAction = null,
                                message = applicationContext.getString(R.string.backend_download_switch_account, task.accountName),
                            )
                            return@withSlot Result.success()
                        }
                        val effectiveCredential = credential
                        if (effectiveCredential != null && task.credentialMode != DownloadCredentialMode.ACCOUNT.name) {
                            task =
                                persist(
                                    task,
                                    accountName = effectiveCredential.accountName,
                                    credentialMode = DownloadCredentialMode.ACCOUNT.name,
                                    message = applicationContext.getString(R.string.backend_download_resolved_connecting, task.title, effectiveCredential.accountName),
                                )
                        }
                        val sessionLabel =
                            effectiveCredential?.let {
                                applicationContext.getString(R.string.backend_steam_signed_in_account, it.accountName)
                            } ?: applicationContext.getString(R.string.backend_steam_anonymous_account)
                        val persistentRoot = File(applicationContext.filesDir, WORKSHOP_STAGING_DIRECTORY_NAME).canonicalFile
                        val legacyRoot = File(applicationContext.cacheDir, WORKSHOP_STAGING_DIRECTORY_NAME).canonicalFile
                        val resolvedDirectory =
                            resolveWorkshopStagingDirectory(
                                persistentRoot = persistentRoot,
                                legacyRoot = legacyRoot,
                                taskId = taskId,
                                persistedDirectory = task.stagingDirectory,
                            )
                        stagingDirectory = resolvedDirectory
                        check(isManagedWorkshopStagingDirectory(persistentRoot, legacyRoot, resolvedDirectory)) {
                            "Invalid Steam download staging directory"
                        }
                        val manifestChanged =
                            task.contentManifestId > 0L &&
                                task.contentManifestId != target.contentManifestId
                        if (manifestChanged && resolvedDirectory.exists()) {
                            resolvedDirectory.deleteRecursively()
                        }
                        check(resolvedDirectory.exists() || resolvedDirectory.mkdirs()) {
                            "Failed to create Workshop download staging directory"
                        }
                        check(resolvedDirectory.isDirectory) { "Workshop download staging path is not a directory" }
                        task =
                            persist(
                                task,
                                title = target.title,
                                type = target.contentTypeHint.toWorkshopType().name,
                                appId = target.appId,
                                contentManifestId = target.contentManifestId,
                                stagingDirectory = resolvedDirectory.absolutePath,
                                totalBytes = target.expectedSize,
                                status = DownloadStatus.RESOLVING,
                                message =
                                    applicationContext.getString(
                                        R.string.backend_download_resolved_connecting,
                                        target.title,
                                        sessionLabel,
                                    ),
                            )

                        var previousPhase: SteamDownloadPhase? = null
                        var lastPersistedAt = 0L
                        var lastSpeedAt = System.currentTimeMillis()
                        var lastSpeedBytes = task.downloadedBytes
                        val controlProbe = TaskControlProbe(taskDao, taskId)
                        val download =
                            steamWorkshopContentClient.download(
                                target = target,
                                destinationDirectory = resolvedDirectory,
                                credential = effectiveCredential,
                                options =
                                    SteamContentDownloadOptions(
                                        chunkConcurrency = downloadPreferences.chunkDownloadConcurrency,
                                        proxyUrl = activeProxyUrl,
                                    ),
                                control = controlProbe::current,
                            ) { progress ->
                                val now = System.currentTimeMillis()
                                val enteringDownloadPhase =
                                    progress.phase == SteamDownloadPhase.DOWNLOADING &&
                                        previousPhase != SteamDownloadPhase.DOWNLOADING
                                if (progress.phase != previousPhase) {
                                    Log.i(
                                        DOWNLOAD_LOG_TAG,
                                        "taskId=$taskId phase ${previousPhase} -> ${progress.phase} " +
                                            "(${progress.completedBytes}/${progress.totalBytes} bytes)",
                                    )
                                }
                                val shouldPersist =
                                    progress.phase != previousPhase ||
                                        now - lastPersistedAt >= PROGRESS_PERSIST_INTERVAL_MS ||
                                        (progress.totalBytes > 0L && progress.completedBytes >= progress.totalBytes)
                                if (shouldPersist) {
                                    val speed =
                                        if (
                                            progress.phase == SteamDownloadPhase.DOWNLOADING &&
                                            !enteringDownloadPhase &&
                                            now > lastSpeedAt &&
                                            progress.completedBytes >= lastSpeedBytes
                                        ) {
                                            ((progress.completedBytes - lastSpeedBytes) * 1_000L / (now - lastSpeedAt))
                                                .coerceAtLeast(0L)
                                        } else {
                                            task.bytesPerSecond
                                        }
                                    task =
                                        persist(
                                            task,
                                            status = progress.toDownloadStatus(),
                                            downloadedBytes =
                                                if (progress.phase == SteamDownloadPhase.DOWNLOADING) {
                                                    progress.completedBytes
                                                } else {
                                                    task.downloadedBytes
                                                },
                                            totalBytes = progress.totalBytes.takeIf { it > 0L } ?: task.totalBytes,
                                            bytesPerSecond = speed,
                                            message = progress.toMessage(credential != null),
                                        )
                                    if (progress.phase == SteamDownloadPhase.DOWNLOADING) {
                                        lastSpeedAt = now
                                        lastSpeedBytes = progress.completedBytes
                                    }
                                    previousPhase = progress.phase
                                    lastPersistedAt = now
                                }
                            }
                        val dependencyDownload =
                            downloadPresetDependencies(
                                sourceDirectory = resolvedDirectory,
                                rootWorkshopId = task.workshopId,
                                credential = credential.takeIf { task.credentialMode == DownloadCredentialMode.ACCOUNT.name },
                                options =
                                    SteamContentDownloadOptions(
                                        chunkConcurrency = downloadPreferences.chunkDownloadConcurrency,
                                        proxyUrl = activeProxyUrl,
                                    ),
                                proxyUrl = activeProxyUrl,
                                control = controlProbe::current,
                            ) { completedBytes, totalBytes, dependencyTitle ->
                                val now = System.currentTimeMillis()
                                if (now - lastPersistedAt >= PROGRESS_PERSIST_INTERVAL_MS || completedBytes >= totalBytes) {
                                    task =
                                        persist(
                                            task,
                                            status = DownloadStatus.DOWNLOADING,
                                            downloadedBytes = download.downloadedBytes + completedBytes,
                                            totalBytes = download.totalBytes + totalBytes,
                                            bytesPerSecond = 0L,
                                            message = "正在下载依赖项目：$dependencyTitle",
                                        )
                                    lastPersistedAt = now
                                }
                            }
                        awaitTaskControl(controlProbe)
                        task =
                            persist(
                                task,
                                status = DownloadStatus.CONVERTING,
                                downloadedBytes = download.downloadedBytes + dependencyDownload.downloadedBytes,
                                totalBytes = download.totalBytes + dependencyDownload.totalBytes,
                                bytesPerSecond = 0L,
                                outputLabel = null,
                                message =
                                    applicationContext.resources.getQuantityString(
                                        if (download.usedAuthenticatedSession) {
                                            R.plurals.backend_download_complete_authenticated
                                        } else {
                                            R.plurals.backend_download_complete_anonymous
                                        },
                                        download.fileCount,
                                        download.fileCount,
                                        formatByteSize(download.downloadedBytes + dependencyDownload.downloadedBytes),
                                    ),
                            )
                        conversionScheduler.enqueue(taskId)
                        Result.success()
                    }
                } catch (error: SteamDownloadPausedException) {
                    persist(
                        task,
                        status = DownloadStatus.PAUSED,
                        requestedAction = null,
                        message = applicationContext.getString(R.string.backend_download_paused),
                    )
                    Result.success()
                } catch (error: SteamDownloadCancelledException) {
                    stagingDirectory?.takeIf(::isManagedStagingDirectory)?.deleteRecursively()
                    persist(
                        task,
                        stagingDirectory = null,
                        downloadedBytes = 0L,
                        bytesPerSecond = 0L,
                        status = DownloadStatus.CANCELLED,
                        requestedAction = null,
                        clearRequestedAction = true,
                        message = applicationContext.getString(R.string.backend_download_cancelled_cleaned),
                    )
                    Result.success()
                } catch (error: CancellationException) {
                    Log.w(
                        DOWNLOAD_LOG_TAG,
                        "Formal Steam download worker cancelled taskId=$taskId",
                        error,
                    )
                    var retryable = false
                    withContext(kotlinx.coroutines.NonCancellable) {
                        val cancellationRequested =
                            taskDao.find(taskId)?.requestedAction == DownloadAction.CANCEL.name
                        if (cancellationRequested) {
                            stagingDirectory?.takeIf(::isManagedStagingDirectory)?.deleteRecursively()
                            persist(
                                task,
                                stagingDirectory = null,
                                downloadedBytes = 0L,
                                bytesPerSecond = 0L,
                                status = DownloadStatus.CANCELLED,
                                requestedAction = null,
                                clearRequestedAction = true,
                                message = applicationContext.getString(R.string.backend_download_cancelled_cleaned),
                            )
                        } else {
                            // The system (or the ROM's job policy) stopped the worker; keep
                            // the partial staging directory and let WorkManager retry the
                            // run so the task cannot get stuck as QUEUED forever.
                            retryable = true
                            persist(
                                task,
                                status = DownloadStatus.QUEUED,
                                message = applicationContext.getString(R.string.backend_download_interrupted),
                            )
                        }
                    }
                    if (retryable) {
                        Result.retry()
                    } else {
                        throw error
                    }
                } catch (error: Throwable) {
                    Log.e(
                        DOWNLOAD_LOG_TAG,
                        "Formal Steam download worker failed taskId=$taskId, type=${error.javaClass.name}",
                        error,
                    )
                    persist(
                        task,
                        status = DownloadStatus.FAILED,
                        bytesPerSecond = 0L,
                        message = error.message ?: error.javaClass.simpleName,
                    )
                    Result.success()
                } finally {
                    contentTransportLease?.close()
                    ActiveFormalWorkshopDownloadWorkers.markInactive(taskId)
                }
            }

        private suspend fun downloadPresetDependencies(
            sourceDirectory: File,
            rootWorkshopId: Long,
            credential: com.wallhub.android.core.model.SteamContentCredential?,
            options: SteamContentDownloadOptions,
            proxyUrl: String,
            control: suspend () -> SteamDownloadControl,
            onProgress: suspend (completedBytes: Long, totalBytes: Long, dependencyTitle: String) -> Unit,
        ): DependencyDownloadResult {
            var currentDirectory = sourceDirectory
            var downloadedBytes = 0L
            var totalBytes = 0L
            val visited = linkedSetOf(rootWorkshopId)
            repeat(MAX_PRESET_DEPENDENCY_DEPTH) {
                val dependencyId = readPresetDependencyId(currentDirectory) ?: return DependencyDownloadResult(downloadedBytes, totalBytes)
                check(visited.add(dependencyId)) { "Workshop preset dependency cycle detected: $dependencyId" }
                val target = steamWorkshopContentClient.fetchContentTarget(dependencyId, proxyUrl)
                check(target.contentTypeHint.equals("scene", ignoreCase = true)) {
                    "Workshop preset dependency $dependencyId is not a scene wallpaper"
                }
                val dependencyDirectory = resolvePresetDependencyDirectory(currentDirectory, dependencyId)
                val completedBefore = downloadedBytes
                val totalBefore = totalBytes
                val result =
                    steamWorkshopContentClient.download(
                        target = target,
                        destinationDirectory = dependencyDirectory,
                        credential = credential,
                        options = options,
                        control = control,
                    ) { progress ->
                        if (progress.phase == SteamDownloadPhase.DOWNLOADING) {
                            onProgress(
                                completedBefore + progress.completedBytes,
                                totalBefore + progress.totalBytes,
                                target.title,
                            )
                        }
                    }
                downloadedBytes += result.downloadedBytes
                totalBytes += result.totalBytes
                currentDirectory = dependencyDirectory
            }
            check(readPresetDependencyId(currentDirectory) == null) {
                "Workshop preset dependency depth exceeds $MAX_PRESET_DEPENDENCY_DEPTH"
            }
            return DependencyDownloadResult(downloadedBytes, totalBytes)
        }

        private fun readPresetDependencyId(directory: File): Long? {
            val projectFile = File(directory, "project.json")
            if (!projectFile.isFile) return null
            val project = JSONObject(readProjectJson(projectFile))
            if (!project.has("preset")) return null
            return project.opt("dependency")
                ?.toString()
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: error("Workshop preset has no valid dependency ID")
        }

        private suspend fun awaitTaskControl(controlProbe: TaskControlProbe) {
            while (true) {
                when (controlProbe.current()) {
                    SteamDownloadControl.CONTINUE -> return
                    SteamDownloadControl.PAUSE -> throw SteamDownloadPausedException()
                    SteamDownloadControl.CANCEL -> throw SteamDownloadCancelledException()
                }
            }
        }

        private suspend fun persist(
            previous: FormalTaskRecordEntity,
            title: String = previous.title,
            type: String = previous.type,
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
            requestedAction: DownloadAction? = null,
            clearRequestedAction: Boolean = false,
        ): FormalTaskRecordEntity {
            val persisted = taskDao.find(previous.taskId)
            val effectiveAction =
                if (clearRequestedAction) {
                    null
                } else {
                    requestedAction?.name ?: persisted?.requestedAction ?: previous.requestedAction
                }
            val effectiveStatus =
                when (effectiveAction) {
                    DownloadAction.PAUSE.name -> DownloadStatus.PAUSED
                    DownloadAction.CANCEL.name -> DownloadStatus.CANCELLED
                    else -> status
                }
            val effectiveMessage =
                when (effectiveAction) {
                    DownloadAction.PAUSE.name -> applicationContext.getString(R.string.backend_download_pausing)
                    DownloadAction.CANCEL.name -> applicationContext.getString(R.string.backend_download_cancelling)
                    else -> message
                }
            val updated =
                previous.copy(
                    title = title,
                    type = type,
                    appId = appId,
                    contentManifestId = contentManifestId,
                    stagingDirectory = stagingDirectory,
                    accountName = accountName,
                    credentialMode = credentialMode,
                    status = effectiveStatus.name,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    bytesPerSecond = bytesPerSecond,
                    outputLabel = outputLabel,
                    message = effectiveMessage,
                    requestedAction = effectiveAction,
                    updatedAt = System.currentTimeMillis(),
                )
            taskDao.upsert(updated)
            return updated
        }

        private fun SteamDownloadProgress.toDownloadStatus(): DownloadStatus =
            when (phase) {
                SteamDownloadPhase.CONNECTING,
                SteamDownloadPhase.AUTHENTICATING,
                SteamDownloadPhase.RESOLVING,
                -> DownloadStatus.RESOLVING

                SteamDownloadPhase.DOWNLOADING -> DownloadStatus.DOWNLOADING
            }

        private fun SteamDownloadProgress.toMessage(usingAuthenticatedSession: Boolean): String =
            when (phase) {
                SteamDownloadPhase.CONNECTING -> {
                    applicationContext.getString(
                        if (usingAuthenticatedSession) {
                            R.string.backend_download_connecting_authenticated
                        } else {
                            R.string.backend_download_connecting_anonymous
                        },
                    )
                }

                SteamDownloadPhase.AUTHENTICATING ->
                    applicationContext.getString(R.string.backend_download_authenticating)
                SteamDownloadPhase.RESOLVING -> applicationContext.getString(R.string.backend_download_resolving_content)
                SteamDownloadPhase.DOWNLOADING -> {
                    if (currentFile.isNullOrBlank()) {
                        applicationContext.getString(
                            R.string.backend_download_progress,
                            completedFiles,
                            totalFiles,
                        )
                    } else {
                        applicationContext.getString(
                            R.string.backend_download_progress_file,
                            completedFiles,
                            totalFiles,
                            currentFile,
                        )
                    }
                }
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

        private fun isManagedStagingDirectory(directory: File): Boolean =
            isManagedWorkshopStagingDirectory(
                persistentRoot = File(applicationContext.filesDir, WORKSHOP_STAGING_DIRECTORY_NAME),
                legacyRoot = File(applicationContext.cacheDir, WORKSHOP_STAGING_DIRECTORY_NAME),
                directory = directory,
            )

        private class TaskControlProbe(
            private val taskDao: FormalTaskRecordDao,
            private val taskId: String,
        ) {
            private val mutex = Mutex()
            private var cachedControl = SteamDownloadControl.CONTINUE
            private var nextRefreshAt = 0L

            suspend fun current(): SteamDownloadControl =
                mutex.withLock {
                    val now = System.currentTimeMillis()
                    if (now >= nextRefreshAt) {
                        cachedControl =
                            when (taskDao.find(taskId)?.requestedAction) {
                                DownloadAction.PAUSE.name -> SteamDownloadControl.PAUSE
                                DownloadAction.CANCEL.name -> SteamDownloadControl.CANCEL
                                else -> SteamDownloadControl.CONTINUE
                            }
                        nextRefreshAt = now + CONTROL_POLL_INTERVAL_MS
                    }
                    cachedControl
                }
        }

        companion object {
            const val KEY_TASK_ID = "task_id"
            const val UNIQUE_DOWNLOAD_WORK_PREFIX = "wallhub_formal_workshop_download_"
            const val WORK_TAG_PREFIX = "wallhub_formal_workshop_download_task_"
            private const val NOTIFICATION_CHANNEL_ID = "wallhub_workshop_download"
            private const val NOTIFICATION_ID = 4202
            private const val PROGRESS_PERSIST_INTERVAL_MS = 750L
            private const val CONTROL_POLL_INTERVAL_MS = 300L
            private const val PAUSE_POLL_INTERVAL_MS = 250L
            private const val MAX_PRESET_DEPENDENCY_DEPTH = 4
        }
    }

private data class DependencyDownloadResult(
    val downloadedBytes: Long,
    val totalBytes: Long,
)

internal fun resolvePresetDependencyDirectory(
    currentDirectory: File,
    dependencyId: Long,
): File {
    require(dependencyId > 0L) { "Invalid Workshop preset dependency ID" }
    val dependencyRoot = File(currentDirectory, ".wallhub-dependencies").canonicalFile
    val dependencyDirectory = File(dependencyRoot, dependencyId.toString()).canonicalFile
    check(dependencyDirectory.toPath().startsWith(dependencyRoot.toPath())) {
        "Invalid Workshop preset dependency directory"
    }
    return dependencyDirectory
}

private fun String.toDownloadStatus(): DownloadStatus =
    enumValues<DownloadStatus>()
        .firstOrNull { it.name == this }
        ?: DownloadStatus.FAILED

private fun String?.toWorkshopType(): WorkshopType =
    when (this?.lowercase()) {
        "video" -> WorkshopType.VIDEO
        "web", "website" -> WorkshopType.WEB
        "scene" -> WorkshopType.SCENE
        else -> WorkshopType.UNKNOWN
    }
