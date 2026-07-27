package com.wallhub.android.data.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.prototype.mpkg.WorkshopConverter
import com.wallhub.prototype.mpkg.WorkshopKind
import com.wallhub.prototype.mpkg.writeAtomically
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext

class FormalWorkshopConversionWorker(
    appContext: Context,
    params: WorkerParameters,
    private val taskDao: FormalTaskRecordDao,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return@withContext Result.failure()
        var task = taskDao.find(taskId) ?: return@withContext Result.failure()
        if (task.status == DownloadStatus.COMPLETED.name) {
            FormalWorkshopConversionCancellation.clear(taskId)
            return@withContext Result.success()
        }
        FormalWorkshopConversionCancellation.start(taskId)
        if (task.status == DownloadStatus.EXPORTING.name) {
            try {
                return@withContext failOrCancelBeforeConversion(
                    taskId = taskId,
                    task = task,
                    sourceDirectory = null,
                    message = "上次导出被系统中断，请检查输出目录后重试",
                )
            } finally {
                FormalWorkshopConversionCancellation.clear(taskId)
            }
        }
        val sourceDirectory: File
        try {
            sourceDirectory = task.stagingDirectory?.let(::File)
                ?.canonicalFile
                ?.takeIf(File::isDirectory)
                ?: error("下载暂存文件不存在，无法转换")
            val persistentRoot = File(applicationContext.filesDir, WORKSHOP_STAGING_DIRECTORY_NAME)
            val legacyRoot = File(applicationContext.cacheDir, WORKSHOP_STAGING_DIRECTORY_NAME)
            require(isManagedWorkshopStagingDirectory(persistentRoot, legacyRoot, sourceDirectory)) {
                "下载暂存目录无效"
            }
        } catch (error: Throwable) {
            try {
                return@withContext failOrCancelBeforeConversion(
                    taskId = taskId,
                    task = task,
                    sourceDirectory = null,
                    message = error.message ?: error.javaClass.simpleName,
                )
            } finally {
                FormalWorkshopConversionCancellation.clear(taskId)
            }
        }
        val temporaryDirectory = File(applicationContext.cacheDir, "wallhub-conversion/$taskId")
        if (task.requestedAction == DownloadAction.CANCEL.name) {
            FormalWorkshopConversionCancellation.clear(taskId)
            return@withContext cancelTask(task, sourceDirectory)
        }
        try {
            setForeground(createForegroundInfo())
            task = update(
                task,
                status = DownloadStatus.CONVERTING,
                requestedAction = null,
                message = "正在转换 ${task.title}…",
            )
            temporaryDirectory.deleteRecursively()
            check(temporaryDirectory.mkdirs() || temporaryDirectory.isDirectory) { "无法创建转换临时目录" }
            val workContext = currentCoroutineContext()
            val checkCancellation = {
                workContext.ensureActive()
                FormalWorkshopConversionCancellation.check(taskId)
            }
            val conversion = convert(
                task = task,
                sourceDirectory = sourceDirectory,
                temporaryDirectory = temporaryDirectory,
                checkCancellation = checkCancellation,
            )
            FormalWorkshopConversionCancellation.beginFinalization(taskId)
            withContext(NonCancellable) {
                task = update(
                    task,
                    status = DownloadStatus.EXPORTING,
                    message = if (task.outputTreeUri.isNullOrBlank()) {
                        "正在导出到 Download/WallHub…"
                    } else {
                        "正在导出到所选目录…"
                    },
                )
                val exportedFile = task.outputTreeUri
                    ?.takeIf(String::isNotBlank)
                    ?.let { outputTreeUri ->
                        ExportedFile(
                            uri = SafExportGateway.exportFile(
                                context = applicationContext,
                                outputTreeUri = Uri.parse(outputTreeUri),
                                source = conversion.outputFile,
                                outputName = conversion.outputFile.name,
                                mimeType = conversion.mimeType,
                            ),
                            label = "已导出到所选目录",
                        )
                    }
                    ?: PublicDownloadsExportGateway.exportFile(
                        context = applicationContext,
                        source = conversion.outputFile,
                        outputName = conversion.outputFile.name,
                        mimeType = conversion.mimeType,
                    )
                val keepVideoSourceForLocalPlayback = task.type.equals(
                    WorkshopType.VIDEO.name,
                    ignoreCase = true,
                )
                val completed = update(
                    task,
                    status = DownloadStatus.COMPLETED,
                    stagingDirectory = sourceDirectory.absolutePath,
                    outputUri = exportedFile.uri,
                    outputLabel = exportedFile.label,
                    message = conversion.message(exportedFile.uri),
                    requestedAction = null,
                    clearRequestedAction = true,
                )
                // Keep completed output recoverable even if best-effort source cleanup fails.
                if (!keepVideoSourceForLocalPlayback && sourceDirectory.deleteRecursively()) {
                    runCatching { taskDao.clearStagingDirectory(completed.taskId) }
                }
                Result.success()
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                val userCancelled = FormalWorkshopConversionCancellation.claimCancellation(taskId)
                val latest = taskDao.find(taskId)
                if (
                    latest?.requestedAction == DownloadAction.CANCEL.name ||
                    userCancelled
                ) {
                    cancelTask(latest ?: task, sourceDirectory)
                } else {
                    throw error
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                val userCancelled = FormalWorkshopConversionCancellation.claimCancellation(taskId)
                val latest = taskDao.find(taskId)
                if (latest?.requestedAction == DownloadAction.CANCEL.name || userCancelled) {
                    cancelTask(latest ?: task, sourceDirectory)
                } else {
                    fail(
                        task = latest ?: task,
                        message = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        } finally {
            FormalWorkshopConversionCancellation.clear(taskId)
            temporaryDirectory.deleteRecursively()
        }
    }

    private fun convert(
        task: FormalTaskRecordEntity,
        sourceDirectory: File,
        temporaryDirectory: File,
        checkCancellation: () -> Unit,
    ): ConversionResult {
        val contentHint = task.type.lowercase(Locale.ROOT)
        val targetFormat = task.exportFormat.toExportFormat()
        val detected = WorkshopConverter.detect(sourceDirectory, contentHint)
        return when (targetFormat) {
            ExportFormat.AUTO -> convertWithEngine(
                sourceDirectory = sourceDirectory,
                outputFile = File(temporaryDirectory, outputName(task, detected.outputExtension)),
                contentHint = contentHint,
                checkCancellation = checkCancellation,
            )

            ExportFormat.MPKG -> {
                require(detected != WorkshopKind.WEB) { "网站类壁纸只能导出 ZIP 压缩包" }
                convertWithEngine(
                    sourceDirectory = sourceDirectory,
                    outputFile = File(temporaryDirectory, outputName(task, "mpkg")),
                    contentHint = contentHint,
                    checkCancellation = checkCancellation,
                )
            }

            ExportFormat.ZIP -> {
                val outputFile = File(temporaryDirectory, outputName(task, "zip"))
                writeZip(sourceDirectory, outputFile, checkCancellation)
                ConversionResult(
                    outputFile = outputFile,
                    mimeType = "application/zip",
                    kindLabel = "ZIP",
                )
            }
        }
    }

    private fun convertWithEngine(
        sourceDirectory: File,
        outputFile: File,
        contentHint: String,
        checkCancellation: () -> Unit,
    ): ConversionResult {
        val report = WorkshopConverter.convert(
            inputDir = sourceDirectory,
            outputFile = outputFile,
            contentTypeHint = contentHint,
            checkCancellation = checkCancellation,
        )
        return ConversionResult(
            outputFile = report.outputFile,
            mimeType = if (report.kind == WorkshopKind.WEB) "application/zip" else "application/octet-stream",
            kindLabel = report.kind.outputExtension.uppercase(Locale.ROOT),
            convertedTextures = report.convertedTextures,
            copiedTextures = report.copiedTextures,
            warnings = report.warnings,
        )
    }

    private fun writeZip(
        sourceDirectory: File,
        outputFile: File,
        checkCancellation: () -> Unit,
    ) {
        val root = sourceDirectory.canonicalFile
        writeAtomically(outputFile) { temporaryFile ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporaryFile))).use { output ->
                root.walkTopDown()
                    .filter(File::isFile)
                    .onEach { checkCancellation() }
                    .map { file -> file.canonicalFile }
                    .filter { file -> file.toPath().startsWith(root.toPath()) }
                    .sortedBy { file -> root.toPath().relativize(file.toPath()).toString().lowercase(Locale.ROOT) }
                    .forEach { file ->
                        checkCancellation()
                        val path = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                        require(path.isSafeRelativePath()) { "ZIP 文件路径无效：$path" }
                        output.putNextEntry(ZipEntry(path))
                        BufferedInputStream(FileInputStream(file)).use { input ->
                            val buffer = ByteArray(COPY_BUFFER_SIZE)
                            while (true) {
                                checkCancellation()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                        output.closeEntry()
                    }
            }
        }
    }

    private suspend fun cancelTask(
        task: FormalTaskRecordEntity,
        sourceDirectory: File?,
    ): Result {
        sourceDirectory?.deleteRecursively()
        update(
            task,
            status = DownloadStatus.CANCELLED,
            downloadedBytes = 0L,
            bytesPerSecond = 0L,
            stagingDirectory = null,
            requestedAction = null,
            clearRequestedAction = true,
            message = "转换任务已取消，暂存文件已清理",
        )
        return Result.success()
    }

    private suspend fun failOrCancelBeforeConversion(
        taskId: String,
        task: FormalTaskRecordEntity,
        sourceDirectory: File?,
        message: String,
    ): Result = withContext(NonCancellable) {
        val userCancelled = FormalWorkshopConversionCancellation.claimCancellation(taskId)
        val latest = taskDao.find(taskId)
        if (latest?.requestedAction == DownloadAction.CANCEL.name || userCancelled) {
            cancelTask(latest ?: task, sourceDirectory)
        } else {
            fail(latest ?: task, message)
        }
    }

    private suspend fun fail(
        task: FormalTaskRecordEntity,
        message: String,
    ): Result {
        update(
            task,
            status = DownloadStatus.FAILED,
            bytesPerSecond = 0L,
            requestedAction = null,
            clearRequestedAction = true,
            message = message,
        )
        return Result.success()
    }

    private suspend fun update(
        previous: FormalTaskRecordEntity,
        status: DownloadStatus = previous.status.toDownloadStatus(),
        downloadedBytes: Long = previous.downloadedBytes,
        bytesPerSecond: Long = previous.bytesPerSecond,
        stagingDirectory: String? = previous.stagingDirectory,
        outputUri: String? = previous.outputUri,
        outputLabel: String? = previous.outputLabel,
        message: String? = previous.message,
        requestedAction: DownloadAction? = previous.requestedAction?.toDownloadAction(),
        clearRequestedAction: Boolean = false,
    ): FormalTaskRecordEntity {
        val current = taskDao.find(previous.taskId)
        val effectiveRequestedAction = if (clearRequestedAction) {
            null
        } else {
            current?.requestedAction ?: requestedAction?.name
        }
        val updated = previous.copy(
            status = status.name,
            downloadedBytes = downloadedBytes,
            bytesPerSecond = bytesPerSecond,
            stagingDirectory = stagingDirectory,
            outputUri = outputUri,
            outputLabel = outputLabel,
            message = message,
            requestedAction = effectiveRequestedAction,
            updatedAt = System.currentTimeMillis(),
        )
        taskDao.upsert(updated)
        return updated
    }

    private fun outputName(task: FormalTaskRecordEntity, extension: String): String {
        val title = task.title
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(MAX_OUTPUT_TITLE_LENGTH)
            .ifBlank { "wallhub-${task.workshopId}" }
        return "$title-${task.workshopId}.$extension"
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "WallHub 转换与导出",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("WallHub 正在转换")
            .setContentText("正在生成 MPKG 或 ZIP 文件")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private data class ConversionResult(
        val outputFile: File,
        val mimeType: String,
        val kindLabel: String,
        val convertedTextures: Int = 0,
        val copiedTextures: Int = 0,
        val warnings: List<String> = emptyList(),
    ) {
        fun message(outputUri: String): String = buildString {
            append("$kindLabel 已导出")
            if (convertedTextures > 0 || copiedTextures > 0) {
                append("；TEX 转换 $convertedTextures，保留 $copiedTextures")
            }
            if (warnings.isNotEmpty()) append("；${warnings.size} 项兼容性提示")
            append("；$outputUri")
        }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val UNIQUE_WORK_NAME_PREFIX = "wallhub_formal_conversion_"
        const val WORK_TAG_PREFIX = "wallhub_formal_conversion_"
        private const val NOTIFICATION_CHANNEL_ID = "wallhub_formal_conversion"
        private const val NOTIFICATION_ID = 4203
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private const val MAX_OUTPUT_TITLE_LENGTH = 72
    }
}

internal object FormalWorkshopConversionCancellation {
    private enum class State {
        ACTIVE,
        REQUEST_PERSISTING,
        CANCEL_REQUESTED,
        FINALIZING,
    }

    private val states = ConcurrentHashMap<String, State>()

    fun start(taskId: String) {
        states.putIfAbsent(taskId, State.ACTIVE)
    }

    suspend fun beginRequest(taskId: String): Boolean {
        while (true) {
            when (val current = states[taskId]) {
                State.REQUEST_PERSISTING -> yield()
                State.CANCEL_REQUESTED -> return false
                State.FINALIZING -> return false
                State.ACTIVE -> if (states.replace(taskId, current, State.REQUEST_PERSISTING)) return true
                null -> if (states.putIfAbsent(taskId, State.REQUEST_PERSISTING) == null) return true
            }
        }
    }

    fun completeRequest(taskId: String) {
        states.replace(taskId, State.REQUEST_PERSISTING, State.CANCEL_REQUESTED)
    }

    fun abortRequest(taskId: String) {
        states.replace(taskId, State.REQUEST_PERSISTING, State.ACTIVE)
    }

    fun check(taskId: String) {
        if (states[taskId] == State.CANCEL_REQUESTED) {
            throw CancellationException("Workshop conversion was cancelled")
        }
    }

    suspend fun beginFinalization(taskId: String) {
        currentCoroutineContext().ensureActive()
        while (true) {
            when (val current = states[taskId]) {
                State.CANCEL_REQUESTED -> throw CancellationException("Workshop conversion was cancelled")
                State.REQUEST_PERSISTING -> yield()
                State.FINALIZING -> return
                State.ACTIVE -> if (states.replace(taskId, current, State.FINALIZING)) return
                null -> if (states.putIfAbsent(taskId, State.FINALIZING) == null) return
            }
        }
    }

    suspend fun claimCancellation(taskId: String): Boolean {
        while (true) {
            when (val current = states[taskId]) {
                State.CANCEL_REQUESTED -> return true
                State.REQUEST_PERSISTING -> yield()
                State.FINALIZING -> return false
                State.ACTIVE -> if (states.replace(taskId, current, State.FINALIZING)) return false
                null -> if (states.putIfAbsent(taskId, State.FINALIZING) == null) return false
            }
        }
    }

    fun isRequested(taskId: String): Boolean = states[taskId] == State.CANCEL_REQUESTED

    fun clear(taskId: String) {
        states.remove(taskId)
    }
}

internal data class ExportedFile(
    val uri: String,
    val label: String,
)

internal object SafExportGateway {
    private const val COPY_BUFFER_SIZE = 1024 * 1024

    fun exportFile(
        context: Context,
        outputTreeUri: Uri,
        source: File,
        outputName: String,
        mimeType: String,
    ): String {
        val root = DocumentFile.fromTreeUri(context, outputTreeUri)
            ?: error("无法读取所选导出目录")
        val previous = root.findFile(outputName)
        val pendingName = ".wallhub-${UUID.randomUUID()}.tmp"
        val target = root.createFile(mimeType, pendingName)
            ?: error("无法在所选目录创建输出文件")
        var previousRenamed = false
        try {
            context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                BufferedInputStream(FileInputStream(source)).use { input ->
                    input.copyTo(output, bufferSize = COPY_BUFFER_SIZE)
                }
            } ?: error("无法写入导出文件")
            if (previous != null) {
                previousRenamed = previous.renameTo(".wallhub-backup-${UUID.randomUUID()}.tmp")
                check(previousRenamed) { "无法准备替换所选目录中的已有文件" }
            }
            if (!target.renameTo(outputName)) {
                if (previousRenamed) previous?.renameTo(outputName)
                error("无法完成所选目录中的文件替换")
            }
            if (previousRenamed) previous?.delete()
            return target.uri.toString()
        } catch (error: Throwable) {
            target.delete()
            if (previousRenamed && previous?.name != outputName) previous?.renameTo(outputName)
            throw error
        }
    }
}

internal object PublicDownloadsExportGateway {
    private const val DIRECTORY_NAME = "WallHub"
    private const val OUTPUT_LABEL = "Download/WallHub"

    fun exportFile(
        context: Context,
        source: File,
        outputName: String,
        mimeType: String,
    ): ExportedFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        exportWithMediaStore(context, source, outputName, mimeType)
    } else {
        exportWithLegacyStorage(context, source, outputName, mimeType)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportWithMediaStore(
        context: Context,
        source: File,
        outputName: String,
        mimeType: String,
    ): ExportedFile {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, outputName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$DIRECTORY_NAME/",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在 $OUTPUT_LABEL 创建导出文件")
        try {
            resolver.openOutputStream(targetUri, "w")?.use { output ->
                BufferedInputStream(FileInputStream(source)).use { input ->
                    input.copyTo(output, bufferSize = COPY_BUFFER_SIZE)
                }
            } ?: error("无法写入 $OUTPUT_LABEL")
            resolver.update(
                targetUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return ExportedFile(targetUri.toString(), OUTPUT_LABEL)
        } catch (error: Throwable) {
            resolver.delete(targetUri, null, null)
            throw error
        }
    }

    private fun exportWithLegacyStorage(
        context: Context,
        source: File,
        outputName: String,
        mimeType: String,
    ): ExportedFile {
        check(
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED,
        ) {
            "请允许存储权限后重试，才能导出到 $OUTPUT_LABEL"
        }
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DIRECTORY_NAME,
        )
        check(directory.exists() || directory.mkdirs()) { "无法创建 $OUTPUT_LABEL" }
        val target = File(directory, outputName)
        writeAtomically(target) { temporaryFile ->
            BufferedOutputStream(FileOutputStream(temporaryFile)).use { output ->
                BufferedInputStream(FileInputStream(source)).use { input ->
                    input.copyTo(output, bufferSize = COPY_BUFFER_SIZE)
                }
            }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(mimeType),
            null,
        )
        return ExportedFile(Uri.fromFile(target).toString(), OUTPUT_LABEL)
    }

    private const val COPY_BUFFER_SIZE = 1024 * 1024
}

private fun String.isSafeRelativePath(): Boolean = isNotBlank() &&
    !startsWith('/') &&
    !contains("../") &&
    !contains("..\\") &&
    this != "." &&
    this != ".."

private fun String.toDownloadStatus(): DownloadStatus = enumValues<DownloadStatus>()
    .firstOrNull { it.name == this }
    ?: DownloadStatus.FAILED

private fun String.toExportFormat(): ExportFormat = enumValues<ExportFormat>()
    .firstOrNull { it.name == this }
    ?: ExportFormat.AUTO

private fun String.toDownloadAction(): DownloadAction? = enumValues<DownloadAction>()
    .firstOrNull { it.name == this }
