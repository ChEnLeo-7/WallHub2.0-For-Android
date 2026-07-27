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
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.WorkshopType
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

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

internal fun resolveWorkshopStagingDirectory(
    persistentRoot: File,
    legacyRoot: File,
    taskId: String,
    persistedDirectory: String?,
): File {
    val persistentTaskDirectory = managedTaskDirectory(persistentRoot, taskId)
    val legacyTaskDirectory = managedTaskDirectory(legacyRoot, taskId)
    val persistedPath = persistedDirectory
        ?.let { path -> runCatching { File(path).canonicalFile.path }.getOrNull() }
    val shouldReuseLegacy = legacyTaskDirectory.isDirectory &&
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

private fun managedTaskDirectory(root: File, taskId: String): File {
    val canonicalRoot = root.canonicalFile
    val candidate = File(canonicalRoot, taskId).canonicalFile
    check(candidate.parentFile?.path == canonicalRoot.path) { "Steam 下载暂存目录无效" }
    return candidate
}

class FormalWorkshopDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
    private val taskDao: FormalTaskRecordDao,
    private val credentialProvider: SteamContentCredentialProvider,
    private val conversionScheduler: ConversionWorkScheduler,
    private val settingsRepository: SettingsRepository,
    private val downloadConcurrencyGovernor: DownloadConcurrencyGovernor,
    private val steamHttpClientFactory: com.wallhub.android.data.steamaccess.SteamHttpClientFactory,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return@withContext Result.failure()
        var task = taskDao.find(taskId) ?: return@withContext Result.failure()
        Log.i(DOWNLOAD_LOG_TAG, "Started formal Steam download worker taskId=$taskId")
        if (task.status in setOf(DownloadStatus.COMPLETED.name, DownloadStatus.CANCELLED.name)) {
            return@withContext Result.success()
        }
        ActiveFormalWorkshopDownloadWorkers.markActive(taskId)
        var stagingDirectory: File? = null
        try {
            setForeground(createForegroundInfo())
            task = persist(
                task,
                status = DownloadStatus.RESOLVING,
                message = "正在读取 Steam 公共创意工坊详情…",
            )
            val downloadPreferences = settingsRepository.preferences.first()
            val activeProxyUrl = downloadPreferences.downloadProxyUrl
                .takeIf { downloadPreferences.downloadProxyEnabled }
                .orEmpty()
            val target = SteamWorkshopContentApi(
                steamHttpClientFactory.newBuilder().applyDownloadProxy(activeProxyUrl),
            )
                .fetchContentTarget(task.workshopId)
            val credential = credentialProvider.loadContentCredential()
            if (
                !task.accountName.isNullOrBlank() &&
                    !task.accountName.equals(credential?.accountName, ignoreCase = true)
            ) {
                persist(
                    task,
                    status = DownloadStatus.PAUSED,
                    requestedAction = null,
                    message = "任务绑定 Steam 账户 ${task.accountName}，请切换到该账户后继续",
                )
                return@withContext Result.success()
            }
            val sessionLabel = credential?.let { "已登录 Steam 账户 ${it.accountName}" } ?: "匿名 Steam 账户"
            val persistentRoot = File(applicationContext.filesDir, WORKSHOP_STAGING_DIRECTORY_NAME).canonicalFile
            val legacyRoot = File(applicationContext.cacheDir, WORKSHOP_STAGING_DIRECTORY_NAME).canonicalFile
            val resolvedDirectory = resolveWorkshopStagingDirectory(
                persistentRoot = persistentRoot,
                legacyRoot = legacyRoot,
                taskId = taskId,
                persistedDirectory = task.stagingDirectory,
            )
            stagingDirectory = resolvedDirectory
            check(isManagedWorkshopStagingDirectory(persistentRoot, legacyRoot, resolvedDirectory)) {
                "Steam 下载暂存目录无效"
            }
            val manifestChanged = task.contentManifestId > 0L &&
                task.contentManifestId != target.contentManifestId
            if (manifestChanged && resolvedDirectory.exists()) {
                resolvedDirectory.deleteRecursively()
            }
            check(resolvedDirectory.exists() || resolvedDirectory.mkdirs()) {
                "无法创建 Workshop 下载暂存目录"
            }
            check(resolvedDirectory.isDirectory) { "Workshop 下载暂存路径不是目录" }
            task = persist(
                task,
                title = target.title,
                type = target.contentTypeHint.toWorkshopType().name,
                appId = target.appId,
                contentManifestId = target.contentManifestId,
                stagingDirectory = resolvedDirectory.absolutePath,
                totalBytes = target.expectedSize,
                status = DownloadStatus.RESOLVING,
                message = "已解析 ${target.title}，正在建立 $sessionLabel 内容会话…",
            )

            var previousPhase: SteamDownloadPhase? = null
            var lastPersistedAt = 0L
            var lastSpeedAt = System.currentTimeMillis()
            var lastSpeedBytes = task.downloadedBytes
            val controlProbe = TaskControlProbe(taskDao, taskId)
            val download = downloadConcurrencyGovernor.withSlot(
                taskId = taskId,
                priority = task.queuePosition,
                limit = downloadPreferences.maxConcurrentDownloads,
            ) {
                SteamContentDownloader().download(
                    target = target,
                    destinationDirectory = resolvedDirectory,
                    credential = credential,
                    options = SteamContentDownloadOptions(
                        chunkConcurrency = downloadPreferences.chunkDownloadConcurrency,
                        proxyUrl = activeProxyUrl,
                    ),
                    control = controlProbe::current,
                ) { progress ->
                val now = System.currentTimeMillis()
                val enteringDownloadPhase = progress.phase == SteamDownloadPhase.DOWNLOADING &&
                    previousPhase != SteamDownloadPhase.DOWNLOADING
                val shouldPersist = progress.phase != previousPhase ||
                    now - lastPersistedAt >= PROGRESS_PERSIST_INTERVAL_MS ||
                    (progress.totalBytes > 0L && progress.completedBytes >= progress.totalBytes)
                if (shouldPersist) {
                    val speed = if (
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
                    task = persist(
                        task,
                        status = progress.toDownloadStatus(),
                        downloadedBytes = if (progress.phase == SteamDownloadPhase.DOWNLOADING) {
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
            }
            awaitTaskControl(controlProbe)
            val shouldConvertAndExport = true
            task = persist(
                task,
                status = if (shouldConvertAndExport) DownloadStatus.CONVERTING else DownloadStatus.COMPLETED,
                downloadedBytes = download.downloadedBytes,
                totalBytes = download.totalBytes,
                bytesPerSecond = 0L,
                outputLabel = null,
                message = buildString {
                    append("下载完成：${download.fileCount} 个文件，")
                    append(formatMegabytes(download.downloadedBytes))
                    append(if (download.usedAuthenticatedSession) "；使用已登录 Steam 账户" else "；使用匿名 Steam 账户")
                    append("；正在转换并导出")
                },
            )
            if (shouldConvertAndExport) conversionScheduler.enqueue(taskId)
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
                message = "下载任务已取消，暂存文件已清理",
            )
            Result.success()
        } catch (error: CancellationException) {
            persist(
                task,
                status = DownloadStatus.QUEUED,
                message = "下载被系统中断，保留暂存数据等待恢复",
            )
            throw error
        } catch (error: Throwable) {
            Log.e(
                DOWNLOAD_LOG_TAG,
                "Formal Steam download worker failed taskId=$taskId, type=${error.javaClass.name}",
            )
            persist(
                task,
                status = DownloadStatus.FAILED,
                bytesPerSecond = 0L,
                message = error.message ?: error.javaClass.simpleName,
            )
            Result.success()
        } finally {
            ActiveFormalWorkshopDownloadWorkers.markInactive(taskId)
        }
    }

    private suspend fun awaitTaskControl(controlProbe: TaskControlProbe) {
        while (true) {
            when (controlProbe.current()) {
                SteamDownloadControl.CONTINUE -> return
                SteamDownloadControl.PAUSE -> delay(PAUSE_POLL_INTERVAL_MS)
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
        status: DownloadStatus = previous.status.toDownloadStatus(),
        downloadedBytes: Long = previous.downloadedBytes,
        totalBytes: Long = previous.totalBytes,
        bytesPerSecond: Long = previous.bytesPerSecond,
        outputLabel: String? = previous.outputLabel,
        message: String? = previous.message,
        requestedAction: DownloadAction? = null,
    ): FormalTaskRecordEntity {
        val persisted = taskDao.find(previous.taskId)
        val effectiveAction = requestedAction?.name ?: persisted?.requestedAction ?: previous.requestedAction
        val effectiveStatus = when (effectiveAction) {
            DownloadAction.PAUSE.name -> DownloadStatus.PAUSED
            DownloadAction.CANCEL.name -> DownloadStatus.CANCELLED
            else -> status
        }
        val effectiveMessage = when (effectiveAction) {
            DownloadAction.PAUSE.name -> "正在暂停下载，已完成数据会被保留"
            DownloadAction.CANCEL.name -> "正在取消下载并清理暂存文件"
            else -> message
        }
        val updated = previous.copy(
            title = title,
            type = type,
            appId = appId,
            contentManifestId = contentManifestId,
            stagingDirectory = stagingDirectory,
            accountName = accountName,
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

    private fun SteamDownloadProgress.toDownloadStatus(): DownloadStatus = when (phase) {
        SteamDownloadPhase.CONNECTING,
        SteamDownloadPhase.AUTHENTICATING,
        SteamDownloadPhase.RESOLVING,
        -> DownloadStatus.RESOLVING

        SteamDownloadPhase.DOWNLOADING -> DownloadStatus.DOWNLOADING
    }

    private fun SteamDownloadProgress.toMessage(usingAuthenticatedSession: Boolean): String = when (phase) {
        SteamDownloadPhase.CONNECTING -> {
            if (usingAuthenticatedSession) "正在建立已登录 Steam 内容会话…" else "正在建立匿名 Steam 内容会话…"
        }

        SteamDownloadPhase.AUTHENTICATING -> "正在使用已登录 Steam 账户验证内容访问…"
        SteamDownloadPhase.RESOLVING -> "正在获取 depot key、manifest 与 CDN 路由…"
        SteamDownloadPhase.DOWNLOADING -> {
            val fileLabel = currentFile?.let { "：$it" }.orEmpty()
            "正在下载 $completedFiles/$totalFiles 个文件$fileLabel"
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "WallHub 下载",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("WallHub 正在下载")
            .setContentText("Steam 创意工坊内容下载中")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun formatMegabytes(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        val megabytes = safeBytes / BYTES_PER_MEGABYTE
        return if (megabytes > MEGABYTES_PER_GIGABYTE) {
            String.format(java.util.Locale.getDefault(), "%.1f GB", safeBytes / BYTES_PER_GIGABYTE)
        } else {
            String.format(java.util.Locale.getDefault(), "%.1f MB", megabytes)
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

        suspend fun current(): SteamDownloadControl = mutex.withLock {
            val now = System.currentTimeMillis()
            if (now >= nextRefreshAt) {
                cachedControl = when (taskDao.find(taskId)?.requestedAction) {
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
        private const val NOTIFICATION_CHANNEL_ID = "wallhub_workshop_download"
        private const val NOTIFICATION_ID = 4202
        private const val PROGRESS_PERSIST_INTERVAL_MS = 750L
        private const val CONTROL_POLL_INTERVAL_MS = 300L
        private const val PAUSE_POLL_INTERVAL_MS = 250L
        private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
        private const val MEGABYTES_PER_GIGABYTE = 1024.0
        private const val BYTES_PER_GIGABYTE = BYTES_PER_MEGABYTE * MEGABYTES_PER_GIGABYTE
    }
}

private fun String.toDownloadStatus(): DownloadStatus = enumValues<DownloadStatus>()
    .firstOrNull { it.name == this }
    ?: DownloadStatus.FAILED

private fun String?.toWorkshopType(): WorkshopType = when (this?.lowercase()) {
    "video" -> WorkshopType.VIDEO
    "web", "website" -> WorkshopType.WEB
    "scene" -> WorkshopType.SCENE
    else -> WorkshopType.UNKNOWN
}
