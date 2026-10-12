package com.wallhub.android.data.downloads

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
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
import com.wallhub.android.R
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.prototype.mpkg.ConversionPlan
import com.wallhub.prototype.mpkg.ConversionPlanJsonCodec
import com.wallhub.prototype.mpkg.ConversionReportJsonCodec
import com.wallhub.prototype.mpkg.DeviceCapabilityProfile
import com.wallhub.prototype.mpkg.StaleConversionSourceException
import com.wallhub.prototype.mpkg.WorkshopConverter
import com.wallhub.prototype.mpkg.WorkshopKind
import com.wallhub.prototype.mpkg.writeAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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

internal class FormalWorkshopConversionWorker(
    appContext: Context,
        params: WorkerParameters,
        private val taskDao: FormalTaskRecordDao,
        private val downloadConcurrencyGovernor: DownloadConcurrencyGovernor,
        private val playbackCoordinator: PlaybackDownloadCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val taskId = inputData.getString(KEY_TASK_ID) ?: return@withContext Result.failure()
            var task = taskDao.find(taskId) ?: return@withContext Result.failure()
            if (
                task.status == DownloadStatus.COMPLETED.name &&
                task.exportBackupUri.isNullOrBlank()
            ) {
                FormalWorkshopConversionCancellation.clear(taskId)
                return@withContext Result.success()
            }
            FormalWorkshopConversionCancellation.start(taskId)
            val temporaryDirectory = File(applicationContext.cacheDir, "wallhub-conversion/$taskId")
            if (
                task.status == DownloadStatus.EXPORTING.name ||
                task.exportTransactionState != ExportTransactionState.NONE.name
            ) {
                val recovered = reconcileInterruptedExport(task)
                if (recovered != null) {
                    FormalWorkshopConversionCancellation.clear(taskId)
                    return@withContext recovered
                }
                task = requireNotNull(taskDao.find(taskId))
            }
            val sourceDirectory: File
            try {
                sourceDirectory = task.stagingDirectory
                    ?.let(::File)
                    ?.canonicalFile
                    ?.takeIf(File::isDirectory)
                    ?: error("Download staging files are missing; cannot convert")
                val persistentRoot = File(applicationContext.filesDir, WORKSHOP_STAGING_DIRECTORY_NAME)
                val legacyRoot = File(applicationContext.cacheDir, WORKSHOP_STAGING_DIRECTORY_NAME)
                require(isManagedWorkshopStagingDirectory(persistentRoot, legacyRoot, sourceDirectory)) {
                    "Invalid download staging directory"
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
            if (task.isCancellationRequested()) {
                temporaryDirectory.deleteRecursively()
                FormalWorkshopConversionCancellation.clear(taskId)
                return@withContext cancelTask(task, sourceDirectory)
            }
            try {
                setForeground(createForegroundInfo())
                task =
                    update(
                        task,
                        status = DownloadStatus.CONVERTING,
                        message = applicationContext.getString(R.string.backend_conversion_progress, task.title),
                    )
                temporaryDirectory.deleteRecursively()
                check(temporaryDirectory.mkdirs() || temporaryDirectory.isDirectory) { "Failed to create conversion temporary directory" }
                val workContext = currentCoroutineContext()
                val checkCancellation = {
                    workContext.ensureActive()
                    FormalWorkshopConversionCancellation.check(taskId)
                }
                val conversion =
                    DownloadDiskReservations.withReservation(
                        destinationDirectory = temporaryDirectory,
                        pendingBytes = estimateConversionDiskBytes(sourceDirectory),
                    ) {
                        playbackCoordinator.awaitBackgroundWorkAllowed()
                        downloadConcurrencyGovernor.withConversionSlot {
                            convert(
                                task = task,
                                sourceDirectory = sourceDirectory,
                                temporaryDirectory = temporaryDirectory,
                                checkCancellation = checkCancellation,
                            )
                        }
                    }
                FormalWorkshopConversionCancellation.beginFinalization(taskId)
                withContext(NonCancellable) {
                    task =
                        update(
                            task,
                            status = DownloadStatus.EXPORTING,
                            message =
                                if (task.outputTreeUri.isNullOrBlank()) {
                                    applicationContext.getString(R.string.backend_conversion_exporting_downloads)
                                } else {
                                    applicationContext.getString(R.string.backend_conversion_exporting_selected)
                                },
                        )
                    val transaction =
                        ExportTransaction(
                            state = ExportTransactionState.PREPARED,
                            outputName = conversion.outputFile.name,
                            mimeType = conversion.mimeType,
                        )
                    task = update(task, exportTransaction = transaction)
                    val persistTransaction: suspend (ExportTransaction) -> Unit = { next ->
                        task = update(task, exportTransaction = next)
                    }
                    val exportedFile =
                        task.outputTreeUri
                            ?.takeIf(String::isNotBlank)
                            ?.let { outputTreeUri ->
                                ExportedFile(
                                    uri =
                                        SafExportGateway.exportFile(
                                            context = applicationContext,
                                            outputTreeUri = Uri.parse(outputTreeUri),
                                            source = conversion.outputFile,
                                            transaction = transaction,
                                            persistTransaction = persistTransaction,
                                        ),
                                    label = applicationContext.getString(R.string.backend_conversion_selected_label),
                                )
                            }
                            ?: PublicDownloadsExportGateway.exportFile(
                                context = applicationContext,
                                source = conversion.outputFile,
                                transaction = transaction,
                                persistTransaction = persistTransaction,
                            )
                    val keepVideoSourceForLocalPlayback =
                        task.type.equals(
                            WorkshopType.VIDEO.name,
                            ignoreCase = true,
                        )
                    val completed =
                        update(
                            task,
                            status = DownloadStatus.COMPLETED,
                            stagingDirectory = sourceDirectory.absolutePath,
                            outputUri = exportedFile.uri,
                            outputLabel = exportedFile.label,
                            message = conversion.message(applicationContext, exportedFile.uri),
                        )
                    // Keep completed output recoverable even if best-effort source cleanup fails.
                    if (!keepVideoSourceForLocalPlayback && sourceDirectory.deleteRecursively()) {
                        runCatching { taskDao.clearStagingDirectory(completed.taskId) }
                    }
                    if (completed.exportBackupUri == null) Result.success() else Result.retry()
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    val userCancelled = FormalWorkshopConversionCancellation.claimCancellation(taskId)
                    val latest = taskDao.find(taskId)
                    if (
                        latest?.isCancellationRequested() == true ||
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
                    if (latest?.isCancellationRequested() == true || userCancelled) {
                        cancelTask(latest ?: task, sourceDirectory)
                    } else if (
                        latest?.status == DownloadStatus.EXPORTING.name &&
                        latest.exportTransactionState != ExportTransactionState.NONE.name
                    ) {
                        // Keep the durable export transaction authoritative. A retry will reconcile a
                        // published target or clean an incomplete artifact without duplicating output.
                        Result.retry()
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

    private suspend fun reconcileInterruptedExport(task: FormalTaskRecordEntity): Result? {
        val transaction = task.exportTransaction()
        if (transaction.state == ExportTransactionState.NONE || transaction.outputName == null) {
            return fail(
                task,
                applicationContext.getString(R.string.backend_conversion_previous_interrupted),
            )
        }
        val isSaf = !task.outputTreeUri.isNullOrBlank()
        val snapshot =
            if (isSaf) {
                SafExportGateway.inspect(applicationContext, transaction)
            } else {
                PublicDownloadsExportGateway.inspect(applicationContext, transaction)
            }
        return when (decideExportRecovery(transaction.state.name, snapshot, transaction.outputName)) {
            ExportRecoveryDecision.PROJECT_COMPLETED -> {
                val targetUri = requireNotNull(transaction.targetUri)
                val kindLabel = transaction.outputName.substringAfterLast('.', "MPKG").uppercase(Locale.ROOT)
                val finalizedTransaction =
                    if (isSaf) SafExportGateway.cleanCommittedBackup(applicationContext, transaction) else transaction
                update(
                    task,
                    status = DownloadStatus.COMPLETED,
                    outputUri = targetUri,
                    outputLabel =
                        if (isSaf) {
                            applicationContext.getString(R.string.backend_conversion_selected_label)
                        } else {
                            PublicDownloadsExportGateway.OUTPUT_LABEL
                        },
                    message =
                        applicationContext.getString(
                            R.string.backend_conversion_complete,
                            kindLabel,
                            "",
                            "",
                            targetUri,
                        ),
                    exportTransaction = finalizedTransaction.copy(state = ExportTransactionState.COMMITTED),
                )
                if (finalizedTransaction.backupUri == null) Result.success() else Result.retry()
            }

            ExportRecoveryDecision.CLEAN_AND_RESTART -> {
                if (isSaf) {
                    SafExportGateway.cleanInterrupted(applicationContext, transaction)
                } else {
                    PublicDownloadsExportGateway.cleanInterrupted(applicationContext, transaction)
                }
                update(task, exportTransaction = ExportTransaction())
                null
            }

            ExportRecoveryDecision.FAIL_RECOVERABLY ->
                fail(task, applicationContext.getString(R.string.backend_conversion_previous_interrupted))
        }
    }

    private suspend fun convert(
        task: FormalTaskRecordEntity,
        sourceDirectory: File,
        temporaryDirectory: File,
        checkCancellation: () -> Unit,
    ): ConversionResult {
        val contentHint = task.type.lowercase(Locale.ROOT)
        val targetFormat = task.exportFormat.toExportFormat()
        val detected = WorkshopConverter.detect(sourceDirectory, contentHint)
        return when (targetFormat) {
            ExportFormat.AUTO ->
                convertWithEngine(
                    task = task,
                    sourceDirectory = sourceDirectory,
                    outputFile = File(temporaryDirectory, outputName(task, detected.outputExtension)),
                    contentHint = contentHint,
                    checkCancellation = checkCancellation,
                )

            ExportFormat.MPKG -> {
                require(detected != WorkshopKind.WEB) { "Web wallpapers can only be exported as ZIP archives" }
                convertWithEngine(
                    task = task,
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
                    mimeType = WorkshopExportMimeTypes.ZIP,
                    kindLabel = "ZIP",
                )
            }
        }
    }

    private fun estimateConversionDiskBytes(sourceDirectory: File): Long {
        var total = 0L
        sourceDirectory.walkTopDown().filter(File::isFile).forEach { file ->
            val length = file.length().coerceAtLeast(0L)
            require(total <= MAX_CONVERSION_OUTPUT_BYTES - length) {
                "Workshop content exceeds the $MAX_CONVERSION_OUTPUT_BYTES-byte conversion limit"
            }
            total += length
        }
        require(total <= Long.MAX_VALUE / CONVERSION_DISK_MULTIPLIER) { "Workshop conversion disk estimate overflow" }
        return total * CONVERSION_DISK_MULTIPLIER
    }

    private suspend fun convertWithEngine(
        task: FormalTaskRecordEntity,
        sourceDirectory: File,
        outputFile: File,
        contentHint: String,
        checkCancellation: () -> Unit,
    ): ConversionResult {
        val kind = WorkshopConverter.detect(sourceDirectory, contentHint)
        val report =
            if (kind == WorkshopKind.SCENE) {
                val preset = MpkgConversionPreset.fromStableId(task.conversionPreset)
                val planStore = ConversionPlanStore(applicationContext.filesDir)
                val plan = loadStoredPlan(task, preset, planStore)
                executeRegeneratingStalePlan(
                    initialPlan = plan,
                    generate = {
                        WorkshopConverter.plan(
                            inputDir = sourceDirectory,
                            preset = preset,
                            device = DeviceCapabilityProfile(Runtime.getRuntime().maxMemory()),
                        ).also { generated ->
                            persistPlanReference(task.taskId, generated, planStore)
                            check(taskDao.persistConversionReport(task.taskId, null) == 1) {
                                "Download task disappeared before its conversion report was cleared"
                            }
                        }
                    },
                    execute = { currentPlan ->
                        WorkshopConverter.execute(
                            plan = currentPlan,
                            inputDir = sourceDirectory,
                            outputFile = outputFile,
                            contentTypeHint = contentHint,
                            checkCancellation = checkCancellation,
                        )
                    },
                ).also { completed ->
                    check(taskDao.persistConversionReport(task.taskId, ConversionReportJsonCodec.encode(completed)) == 1) {
                        "Download task disappeared before its conversion report was persisted"
                    }
                }
            } else {
                WorkshopConverter.convert(
                    inputDir = sourceDirectory,
                    outputFile = outputFile,
                    contentTypeHint = contentHint,
                    checkCancellation = checkCancellation,
                )
            }
        return ConversionResult(
            outputFile = report.outputFile,
            mimeType = if (report.kind == WorkshopKind.WEB) WorkshopExportMimeTypes.ZIP else WorkshopExportMimeTypes.MPKG,
            kindLabel = report.kind.outputExtension.uppercase(Locale.ROOT),
            convertedTextures = report.convertedTextures,
            copiedTextures = report.copiedTextures,
            warnings = report.warnings,
        )
    }

    private suspend fun loadStoredPlan(
        task: FormalTaskRecordEntity,
        preset: MpkgConversionPreset,
        store: ConversionPlanStore,
    ): ConversionPlan? {
        val stored = task.conversionPlanJson ?: return null
        val referenced =
            runCatching { ConversionPlanReference.fromJson(stored) }
                .mapCatching { reference -> store.read(task.taskId, reference) }
                .getOrNull()
        if (referenced != null) return referenced.takeIf { it.presetId == preset.stableId }
        if (stored.toByteArray(Charsets.UTF_8).size > MAX_LEGACY_INLINE_PLAN_BYTES) return null
        val legacy = runCatching { ConversionPlanJsonCodec.decode(stored) }.getOrNull() ?: return null
        if (legacy.presetId != preset.stableId) return null
        persistPlanReference(task.taskId, legacy, store)
        return legacy
    }

    private suspend fun persistPlanReference(
        taskId: String,
        plan: ConversionPlan,
        store: ConversionPlanStore,
    ) {
        val reference = store.write(taskId, plan)
        check(taskDao.persistConversionPlan(taskId, reference.toJson(), plan.version) == 1) {
            "Download task disappeared before its conversion plan was persisted"
        }
    }

    private fun writeZip(
        sourceDirectory: File,
        outputFile: File,
        checkCancellation: () -> Unit,
    ) {
        val root = sourceDirectory.canonicalFile
        writeAtomically(outputFile) { temporaryFile ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporaryFile))).use { output ->
                root
                    .walkTopDown()
                    .filter(File::isFile)
                    .onEach { checkCancellation() }
                    .map { file -> file.canonicalFile }
                    .filter { file -> file.toPath().startsWith(root.toPath()) }
                    .filter { file -> !file.toRelativeString(root).startsWith(".wallhub-download-state") }
                    .sortedBy { file ->
                        root
                            .toPath()
                            .relativize(file.toPath())
                            .toString()
                            .lowercase(Locale.ROOT)
                    }.forEach { file ->
                        checkCancellation()
                        val path =
                            root
                                .toPath()
                                .relativize(file.toPath())
                                .toString()
                                .replace('\\', '/')
                        require(path.isSafeRelativePath()) { "Invalid ZIP file path: $path" }
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
            message = applicationContext.getString(R.string.backend_conversion_cancelled_cleaned),
        )
        check(taskDao.settleConversionCancellation(task.taskId) == 1) {
            "Conversion cancellation intent changed before settlement"
        }
        return Result.success()
    }

    private suspend fun failOrCancelBeforeConversion(
        taskId: String,
        task: FormalTaskRecordEntity,
        sourceDirectory: File?,
        message: String,
    ): Result =
        withContext(NonCancellable) {
            val userCancelled = FormalWorkshopConversionCancellation.claimCancellation(taskId)
            val latest = taskDao.find(taskId)
            if (latest?.isCancellationRequested() == true || userCancelled) {
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
        exportTransaction: ExportTransaction = previous.exportTransaction(),
    ): FormalTaskRecordEntity {
        val updated =
            previous.copy(
                status = status.name,
                downloadedBytes = downloadedBytes,
                bytesPerSecond = bytesPerSecond,
                stagingDirectory = stagingDirectory,
                outputUri = outputUri,
                outputLabel = outputLabel,
                message = message,
                exportTransactionState = exportTransaction.state.name,
                exportTargetUri = exportTransaction.targetUri,
                exportBackupUri = exportTransaction.backupUri,
                exportOutputName = exportTransaction.outputName,
                exportMimeType = exportTransaction.mimeType,
                updatedAt = System.currentTimeMillis(),
            )
        return taskDao.upsertPreservingExecutionIntent(updated, preserveExportTransaction = false)
    }

    private fun outputName(
        task: FormalTaskRecordEntity,
        extension: String,
    ): String {
        val title =
            task.title
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
                applicationContext.getString(R.string.backend_conversion_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification =
            NotificationCompat
                .Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_wallhub_notification)
                .setContentTitle(applicationContext.getString(R.string.backend_conversion_notification_title))
                .setContentText(applicationContext.getString(R.string.backend_conversion_notification_text))
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
        fun message(
            context: Context,
            outputUri: String,
        ): String {
            val textureSummary =
                if (convertedTextures > 0 || copiedTextures > 0) {
                    context.getString(R.string.backend_conversion_texture_summary, convertedTextures, copiedTextures)
                } else {
                    ""
                }
            val warningSummary =
                if (warnings.isNotEmpty()) {
                    context.resources.getQuantityString(
                        R.plurals.backend_conversion_warning_summary,
                        warnings.size,
                        warnings.size,
                    )
                } else {
                    ""
                }
            return context.getString(
                R.string.backend_conversion_complete,
                kindLabel,
                textureSummary,
                warningSummary,
                outputUri,
            )
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

    suspend fun exportFile(
        context: Context,
        outputTreeUri: Uri,
        source: File,
        transaction: ExportTransaction,
        persistTransaction: suspend (ExportTransaction) -> Unit,
    ): String {
        val outputName = requireNotNull(transaction.outputName)
        val mimeType = requireNotNull(transaction.mimeType)
        val root =
            DocumentFile.fromTreeUri(context, outputTreeUri)
                ?: error("Failed to access the selected export directory")
        val previous = root.findFile(outputName)
        val target =
            root.createFile(mimeType, ".wallhub-${UUID.randomUUID()}.tmp")
                ?: error("Failed to create an output file in the selected directory")
        var durable =
            transaction.copy(
                state = ExportTransactionState.WRITING,
                targetUri = target.uri.toString(),
            )
        persistTransaction(durable)
        try {
            context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                BufferedInputStream(FileInputStream(source)).use { input ->
                    input.copyTo(output, bufferSize = COPY_BUFFER_SIZE)
                }
            } ?: error("Failed to write the export file")
            if (previous != null) {
                durable = durable.copy(backupUri = previous.uri.toString())
                persistTransaction(durable)
                check(previous.renameTo(".wallhub-backup-${UUID.randomUUID()}.tmp")) {
                    "Failed to prepare the existing file for replacement in the selected directory"
                }
            }
            check(target.renameTo(outputName)) { "Failed to replace the file in the selected directory" }
            durable = durable.copy(state = ExportTransactionState.COMMITTED)
            persistTransaction(durable)
            // Publication is already durable. Keep the persisted backup URI for restart cleanup if
            // this provider cannot remove the old document immediately.
            if (previous != null && previous.delete()) {
                durable = durable.copy(backupUri = null)
                persistTransaction(durable)
            }
            return target.uri.toString()
        } catch (error: Throwable) {
            if (target.name == outputName) throw error
            target.delete()
            val restored =
                durable.backupUri?.let { backupUri ->
                    val backup = DocumentFile.fromSingleUri(context, Uri.parse(backupUri))
                    backup != null && (backup.name == outputName || backup.renameTo(outputName))
                } ?: true
            persistTransaction(durable.afterRollback(restored))
            if (!restored) {
                throw IllegalStateException(
                    "Failed to restore the previous export; backup remains at ${durable.backupUri}",
                    error,
                )
            }
            throw error
        }
    }

    fun cleanCommittedBackup(
        context: Context,
        transaction: ExportTransaction,
    ): ExportTransaction {
        val backupUri = transaction.backupUri ?: return transaction
        val backup = DocumentFile.fromSingleUri(context, Uri.parse(backupUri))
        val removed = backup == null || !backup.exists() || backup.delete()
        return if (removed) transaction.copy(backupUri = null) else transaction
    }

    fun inspect(
        context: Context,
        transaction: ExportTransaction,
    ): ExportArtifactSnapshot {
        val target = transaction.targetUri?.let { DocumentFile.fromSingleUri(context, Uri.parse(it)) }
        return ExportArtifactSnapshot(
            exists = target?.exists() == true,
            isPending = null,
            displayName = target?.name,
        )
    }

    fun cleanInterrupted(
        context: Context,
        transaction: ExportTransaction,
    ) {
        transaction.targetUri
            ?.let { DocumentFile.fromSingleUri(context, Uri.parse(it)) }
            ?.takeIf { it.name != transaction.outputName }
            ?.let { target ->
                check(!target.exists() || target.delete()) {
                    "Failed to remove interrupted export; temporary document remains at ${target.uri}"
                }
            }
        transaction.backupUri?.let { backupUri ->
            val outputName = requireNotNull(transaction.outputName)
            val backup = DocumentFile.fromSingleUri(context, Uri.parse(backupUri))
            check(backup != null && (backup.name == outputName || backup.renameTo(outputName))) {
                "Failed to restore the previous export; backup remains at $backupUri"
            }
        }
    }
}

internal object PublicDownloadsExportGateway {
    private const val DIRECTORY_NAME = "WallHub"
    internal const val OUTPUT_LABEL = "Download/WallHub"

    suspend fun exportFile(
        context: Context,
        source: File,
        transaction: ExportTransaction,
        persistTransaction: suspend (ExportTransaction) -> Unit,
    ): ExportedFile =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportWithMediaStore(context, source, transaction, persistTransaction)
        } else {
            exportWithLegacyStorage(context, source, transaction, persistTransaction)
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun exportWithMediaStore(
        context: Context,
        source: File,
        transaction: ExportTransaction,
        persistTransaction: suspend (ExportTransaction) -> Unit,
    ): ExportedFile {
        val outputName = requireNotNull(transaction.outputName)
        val mimeType = requireNotNull(transaction.mimeType)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, outputName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DIRECTORY_NAME/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val resolver = context.contentResolver
        val targetUri =
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Failed to create an export file in $OUTPUT_LABEL")
        var durable = transaction.copy(state = ExportTransactionState.WRITING, targetUri = targetUri.toString())
        persistTransaction(durable)
        try {
            resolver.openOutputStream(targetUri, "w")?.use { output ->
                BufferedInputStream(FileInputStream(source)).use { input ->
                    input.copyTo(output, bufferSize = COPY_BUFFER_SIZE)
                }
            } ?: error("Failed to write to $OUTPUT_LABEL")
            check(
                resolver.update(
                    targetUri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                ) == 1,
            ) { "Failed to publish the export in $OUTPUT_LABEL" }
            durable = durable.copy(state = ExportTransactionState.COMMITTED)
            persistTransaction(durable)
            return ExportedFile(targetUri.toString(), OUTPUT_LABEL)
        } catch (error: Throwable) {
            resolver.delete(targetUri, null, null)
            throw error
        }
    }

    private suspend fun exportWithLegacyStorage(
        context: Context,
        source: File,
        transaction: ExportTransaction,
        persistTransaction: suspend (ExportTransaction) -> Unit,
    ): ExportedFile {
        val outputName = requireNotNull(transaction.outputName)
        val mimeType = requireNotNull(transaction.mimeType)
        check(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            "Storage permission is required to export to $OUTPUT_LABEL; grant it and try again"
        }
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIRECTORY_NAME)
        check(directory.exists() || directory.mkdirs()) { "Failed to create $OUTPUT_LABEL" }
        val target = File(directory, outputName)
        val targetUri = Uri.fromFile(target)
        persistTransaction(transaction.copy(state = ExportTransactionState.WRITING, targetUri = targetUri.toString()))
        writeAtomically(target) { temporaryFile ->
            BufferedOutputStream(FileOutputStream(temporaryFile)).use { output ->
                BufferedInputStream(FileInputStream(source)).use { input -> input.copyTo(output, bufferSize = COPY_BUFFER_SIZE) }
            }
        }
        persistTransaction(transaction.copy(state = ExportTransactionState.COMMITTED, targetUri = targetUri.toString()))
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
        return ExportedFile(targetUri.toString(), OUTPUT_LABEL)
    }

    fun inspect(
        context: Context,
        transaction: ExportTransaction,
    ): ExportArtifactSnapshot {
        val targetUri = transaction.targetUri ?: return ExportArtifactSnapshot(false, null, null)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val file = Uri.parse(targetUri).path?.let(::File)
            return ExportArtifactSnapshot(
                exists = transaction.state == ExportTransactionState.COMMITTED && file?.isFile == true,
                isPending = null,
                displayName = file?.name,
            )
        }
        return runCatching {
            context.contentResolver.query(
                Uri.parse(targetUri),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use ExportArtifactSnapshot(false, null, null)
                ExportArtifactSnapshot(
                    exists = true,
                    isPending = cursor.getInt(1) != 0,
                    displayName = cursor.getString(0),
                )
            } ?: ExportArtifactSnapshot(false, null, null)
        }.getOrDefault(ExportArtifactSnapshot(false, null, null))
    }

    fun cleanInterrupted(
        context: Context,
        transaction: ExportTransaction,
    ) {
        val targetUri = transaction.targetUri ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.delete(Uri.parse(targetUri), null, null)
        } else if (transaction.state != ExportTransactionState.COMMITTED) {
            Uri.parse(targetUri).path?.let(::File)?.delete()
        }
    }

    private const val COPY_BUFFER_SIZE = 1024 * 1024
}

private fun String.isSafeRelativePath(): Boolean =
    isNotBlank() &&
        !startsWith('/') &&
        !contains("../") &&
        !contains("..\\") &&
        this != "." &&
        this != ".."

private fun String.toDownloadStatus(): DownloadStatus =
    enumValues<DownloadStatus>()
        .firstOrNull { it.name == this }
        ?: DownloadStatus.FAILED

private fun String.toExportFormat(): ExportFormat =
    enumValues<ExportFormat>()
        .firstOrNull { it.name == this }
        ?: ExportFormat.AUTO

private fun FormalTaskRecordEntity.isCancellationRequested(): Boolean =
    desiredIntent == DownloadAction.CANCEL.name

internal object WorkshopExportMimeTypes {
    const val MPKG = "application/vnd.mpkg"
    const val ZIP = "application/zip"
}

internal enum class ExportTransactionState {
    NONE,
    PREPARED,
    WRITING,
    COMMITTED,
}

internal data class ExportTransaction(
    val state: ExportTransactionState = ExportTransactionState.NONE,
    val targetUri: String? = null,
    val backupUri: String? = null,
    val outputName: String? = null,
    val mimeType: String? = null,
) {
    fun afterRollback(restoredBackup: Boolean): ExportTransaction =
        if (restoredBackup) ExportTransaction() else this
}

internal data class ExportArtifactSnapshot(
    val exists: Boolean,
    val isPending: Boolean?,
    val displayName: String?,
)

internal enum class ExportRecoveryDecision {
    PROJECT_COMPLETED,
    CLEAN_AND_RESTART,
    FAIL_RECOVERABLY,
}

internal fun decideExportRecovery(
    transactionState: String,
    target: ExportArtifactSnapshot,
    expectedOutputName: String,
): ExportRecoveryDecision {
    val state = runCatching { ExportTransactionState.valueOf(transactionState) }.getOrDefault(ExportTransactionState.NONE)
    if (target.exists && target.isPending != true && target.displayName == expectedOutputName) {
        return ExportRecoveryDecision.PROJECT_COMPLETED
    }
    if (state == ExportTransactionState.PREPARED || state == ExportTransactionState.WRITING || state == ExportTransactionState.COMMITTED) {
        return ExportRecoveryDecision.CLEAN_AND_RESTART
    }
    return ExportRecoveryDecision.FAIL_RECOVERABLY
}

internal inline fun <T> executeRegeneratingStalePlan(
    initialPlan: ConversionPlan?,
    generate: () -> ConversionPlan,
    execute: (ConversionPlan) -> T,
): T {
    var plan = initialPlan
    var regenerated = false
    while (true) {
        val currentPlan = plan ?: generate()
        try {
            return execute(currentPlan)
        } catch (stale: StaleConversionSourceException) {
            if (regenerated) throw stale
            regenerated = true
            plan = null
        }
    }
}

private fun FormalTaskRecordEntity.exportTransaction(): ExportTransaction =
    ExportTransaction(
        state = runCatching { ExportTransactionState.valueOf(exportTransactionState) }.getOrDefault(ExportTransactionState.NONE),
        targetUri = exportTargetUri,
        backupUri = exportBackupUri,
        outputName = exportOutputName,
        mimeType = exportMimeType,
    )

private const val MAX_CONVERSION_OUTPUT_BYTES = 4L * 1024L * 1024L * 1024L
private const val MAX_LEGACY_INLINE_PLAN_BYTES = 128 * 1024
private const val CONVERSION_DISK_MULTIPLIER = 2L
