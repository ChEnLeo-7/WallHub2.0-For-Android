package com.wallhub.android.data.downloads

import android.content.Context
import android.util.Log
import com.wallhub.android.R
import com.wallhub.android.core.database.DownloadCommandDao
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.format.formatByteSize
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadCredentialMode
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.WorkshopType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File

internal const val WORKSHOP_STAGING_DIRECTORY_NAME = "wallhub-workshop"

/**
 * The directory the Steam Content SDK keeps its own staging under, beside this app's files.
 *
 * The SDK stages under `.sc-staging/<destination>` inside its storage root, and this app names that root
 * as its files directory. The partial bytes of an SDK-backed download therefore do not live in the
 * destination directory the app hands the SDK: a cancel that deletes only the destination leaves the
 * checkpoint a later retry resumes from, so the cancel would keep bytes the person discarded.
 */
private const val SDK_STAGING_DIRECTORY_NAME = ".sc-staging"
private const val DOWNLOAD_LOG_TAG = "WallHubDownload"

/**
 * The SDK's own staging directory for one task's destination, or null when that name cannot be trusted.
 *
 * Only a directory that really is `<storage root>/.sc-staging/wallhub-workshop/<taskId>` is returned. The
 * id comes from the task record, so it must never be able to name a directory the SDK never wrote.
 */
internal fun sdkStagingDirectory(
    storageRoot: File,
    taskId: String,
): File? {
    if (taskId.isBlank()) return null
    return runCatching {
        val root = File(storageRoot, SDK_STAGING_DIRECTORY_NAME).canonicalFile
        val parent = File(root, WORKSHOP_STAGING_DIRECTORY_NAME).canonicalFile
        File(parent, taskId).canonicalFile.takeIf { it.parentFile?.path == parent.path }
    }.getOrNull()
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

internal suspend fun resolveDownloadTargetAndCredential(
    fetchTarget: suspend () -> WorkshopContentTarget,
    resolveCredential: suspend () -> SteamContentCredential?,
): Pair<WorkshopContentTarget, SteamContentCredential?> = supervisorScope {
    val targetDeferred = async { fetchTarget() }
    val credentialDeferred =
        async(start = CoroutineStart.UNDISPATCHED) {
            try {
                Result.success(resolveCredential())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }
    val target = targetDeferred.await()
    if (target.fileUrl.isNotBlank()) {
        credentialDeferred.cancel()
        target to null
    } else {
        target to credentialDeferred.await().getOrThrow()
    }
}

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

@javax.inject.Singleton
internal class FormalDownloadRunner
    @javax.inject.Inject
    constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext private val applicationContext: Context,
        private val taskDao: FormalTaskRecordDao,
        private val commandDao: DownloadCommandDao,
        private val credentialProvider: SteamContentCredentialProvider,
        private val conversionScheduler: ConversionWorkScheduler,
        private val settingsRepository: SettingsRepository,
        private val stagingManager: DownloadStagingManager,
        private val projections: FormalTaskProjectionStore,
        private val settlement: FormalDownloadAttemptSettlement,
        private val downloadConcurrencyGovernor: DownloadConcurrencyGovernor,
        private val steamWorkshopContentClient: SteamWorkshopContentClient,
        private val presetDependencies: WorkshopPresetDependencyDownloader,
    ) {
        suspend fun run(taskId: String): DownloadHostRunResult =
            withContext(Dispatchers.IO) {
                val found = taskDao.find(taskId)
                if (found == null) {
                    Log.w(DOWNLOAD_LOG_TAG, "doWork task record missing taskId=$taskId")
                    return@withContext DownloadHostRunResult.FAILURE
                }
                var task: FormalTaskRecordEntity = found
                val controlInbox = DurableDownloadControlInbox(commandDao, taskId)
                Log.i(DOWNLOAD_LOG_TAG, "Started host-owned formal Steam runner taskId=$taskId")
                val timingContext = DownloadTimingContext(taskId = taskId, workshopId = task.workshopId)
                DownloadTimingTelemetry.log(
                    event = DownloadTimingTelemetry.WORKER_STARTED,
                    context = timingContext,
                )
                when (controlInbox.current()) {
                    SteamDownloadControl.CANCEL ->
                        return@withContext settlement.cancel(taskId, task.stagingDirectory?.let(::File))
                    SteamDownloadControl.PAUSE -> return@withContext settlement.pause(task)
                    SteamDownloadControl.CONTINUE -> Unit
                }
                if (task.status in setOf(DownloadStatus.COMPLETED.name, DownloadStatus.CANCELLED.name)) {
                    return@withContext DownloadHostRunResult.SUCCESS
                }
                var stagingDirectory: File? = null
                try {
                    // No setForeground() here: this ROM treats the WorkManager FGS as
                    // typeless and force-stops it after ~4 seconds, which cancelled the
                    // worker mid-run. A plain JobScheduler job survives; DownloadHostRunResult.RETRY
                    // covers any residual system stops.
                    val downloadPreferences = settingsRepository.preferences.first()
                    task =
                        projections.persist(
                            task,
                            status = DownloadStatus.QUEUED,
                            message = applicationContext.getString(R.string.backend_download_queued),
                        )
                    downloadConcurrencyGovernor.withSlot(
                        taskId = taskId,
                        priority = task.queuePosition,
                        limit = downloadPreferences.maxConcurrentDownloads,
                    ) {
                        DownloadTimingTelemetry.log(
                            event = DownloadTimingTelemetry.RESOLVING_STARTED,
                            context = timingContext,
                        )
                        task =
                            projections.persist(
                                task,
                                status = DownloadStatus.RESOLVING,
                                message = applicationContext.getString(R.string.backend_download_reading_workshop_details),
                            )
                        val activeProxyUrl =
                            downloadPreferences.downloadProxyUrl
                                .takeIf { downloadPreferences.downloadProxyEnabled }
                                .orEmpty()
                        val (target, credential) =
                            resolveDownloadTargetAndCredential(
                                fetchTarget = {
                                    steamWorkshopContentClient.fetchContentTarget(
                                        publishedFileId = task.workshopId,
                                        proxyUrl = activeProxyUrl,
                                    )
                                },
                                resolveCredential = credentialProvider::resolveContentCredential,
                            )
                        val directDownload = target.fileUrl.isNotBlank()
                        if (!directDownload && task.credentialMode == DownloadCredentialMode.LEGACY_UNKNOWN.name) {
                            projections.persist(
                                task,
                                status = DownloadStatus.PAUSED,
                                message = applicationContext.getString(R.string.backend_download_switch_account, "unknown account"),
                            )
                            return@withSlot DownloadHostRunResult.SUCCESS
                        }
                        if (
                            !directDownload &&
                            task.credentialMode == DownloadCredentialMode.ACCOUNT.name &&
                            (!task.accountName.isNullOrBlank() &&
                                !task.accountName.equals(credential?.accountName, ignoreCase = true))
                        ) {
                            projections.persist(
                                task,
                                status = DownloadStatus.PAUSED,
                                message = applicationContext.getString(R.string.backend_download_switch_account, task.accountName),
                            )
                            return@withSlot DownloadHostRunResult.SUCCESS
                        }
                        val effectiveCredential = credential
                        if (effectiveCredential != null && task.credentialMode != DownloadCredentialMode.ACCOUNT.name) {
                            task =
                                projections.persist(
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
                        val persistentRoot = stagingManager.persistentRoot
                        val legacyRoot = stagingManager.legacyRoot
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
                            task.contentManifestId != 0L &&
                                task.contentManifestId != target.contentManifestId
                        if (manifestChanged && resolvedDirectory.exists()) {
                            resolvedDirectory.deleteRecursively()
                        }
                        check(resolvedDirectory.exists() || resolvedDirectory.mkdirs()) {
                            "Failed to create Workshop download staging directory"
                        }
                        check(resolvedDirectory.isDirectory) { "Workshop download staging path is not a directory" }
                        // The SDK keys its resume journal by the content identity, so a run whose identity
                        // is unchanged finds the paused run's committed chunks and skips them. Its committed
                        // counter still starts at zero for the new run and only moves when a freshly fetched
                        // chunk is reported, so the checkpoint this task already holds is the floor the app
                        // publishes against. Without that floor a resumed run would move the durable reading
                        // back to zero and read as a restart. A run that cannot reuse the checkpoint - a
                        // changed identity, or staging the SDK no longer has - has no floor and starts from
                        // whatever the SDK reports.
                        val reusableCheckpointBytes = task.downloadedBytes.coerceAtLeast(0L)
                        val sdkStagingPresent =
                            stagingManager.sdkDirectory(taskId)?.isDirectory == true
                        val durableCheckpointBytes =
                            if (manifestChanged || !sdkStagingPresent) 0L else reusableCheckpointBytes
                        if (durableCheckpointBytes > 0L) {
                            Log.i(
                                DOWNLOAD_LOG_TAG,
                                "taskId=$taskId resume checkpoint=$durableCheckpointBytes bytes " +
                                    "staging=${resolvedDirectory.absolutePath}",
                            )
                        }
                        task =
                            projections.persist(
                                task,
                                title = target.title,
                                type = target.contentTypeHint.toWorkshopType().name,
                                previewUrl = target.previewUrl.takeIf(String::isNotBlank) ?: task.previewUrl,
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

                        val progressProjector =
                            FormalDownloadProgressProjector(
                                context = applicationContext,
                                initialTask = task,
                                durableCheckpointBytes = durableCheckpointBytes,
                                authenticated = credential != null,
                                timingContext = timingContext,
                            ) { previous, write ->
                                projections.persist(
                                    previous,
                                    status = write.status,
                                    downloadedBytes = write.downloadedBytes,
                                    totalBytes = write.totalBytes,
                                    bytesPerSecond = write.bytesPerSecond,
                                    message = write.message,
                                )
                            }
                        val download =
                            steamWorkshopContentClient.download(
                                target = target,
                                destinationDirectory = resolvedDirectory,
                                credential = effectiveCredential,
                                options =
                                    SteamContentDownloadOptions(
                                        chunkConcurrency = downloadPreferences.chunkDownloadConcurrency,
                                        proxyUrl = activeProxyUrl,
                                        timingContext = timingContext,
                                        onFirstChunkCommitted = {
                                            DownloadTimingTelemetry.logOnce(
                                                event = DownloadTimingTelemetry.FIRST_CHUNK_COMMITTED,
                                                context = timingContext,
                                            )
                                        },
                                    ),
                                control = controlInbox::current,
                            ) { progress ->
                                progressProjector.accept(progress)
                            }
                        task = progressProjector.task
                        val dependencyDownload =
                            presetDependencies.download(
                                sourceDirectory = resolvedDirectory,
                                rootWorkshopId = task.workshopId,
                                credential = credential.takeIf { task.credentialMode == DownloadCredentialMode.ACCOUNT.name },
                                options =
                                    SteamContentDownloadOptions(
                                        chunkConcurrency = downloadPreferences.chunkDownloadConcurrency,
                                        proxyUrl = activeProxyUrl,
                                    ),
                                proxyUrl = activeProxyUrl,
                                control = controlInbox::current,
                            ) { completedBytes, totalBytes, dependencyTitle ->
                                progressProjector.acceptDependency(
                                    primaryDownloadedBytes = download.downloadedBytes,
                                    primaryTotalBytes = download.totalBytes,
                                    completedBytes = completedBytes,
                                    totalBytes = totalBytes,
                                    dependencyTitle = dependencyTitle,
                                )
                            }
                        task = progressProjector.task
                        awaitTaskControl(controlInbox)
                        val conversionMessage =
                            applicationContext.resources.getQuantityString(
                                if (download.usedAuthenticatedSession) {
                                    R.plurals.backend_download_complete_authenticated
                                } else {
                                    R.plurals.backend_download_complete_anonymous
                                },
                                download.fileCount,
                                download.fileCount,
                                formatByteSize(download.downloadedBytes + dependencyDownload.downloadedBytes),
                            )
                        val transitioned =
                            taskDao.transitionToConverting(
                                taskId = taskId,
                                downloadedBytes = download.downloadedBytes + dependencyDownload.downloadedBytes,
                                totalBytes = download.totalBytes + dependencyDownload.totalBytes,
                                message = conversionMessage,
                                updatedAt = System.currentTimeMillis(),
                            )
                        if (transitioned != 1) {
                            when (controlInbox.current()) {
                                SteamDownloadControl.PAUSE -> throw SteamDownloadPausedException()
                                SteamDownloadControl.CANCEL -> throw SteamDownloadCancelledException()
                                SteamDownloadControl.CONTINUE -> return@withSlot DownloadHostRunResult.SUCCESS
                            }
                        }
                        task = requireNotNull(taskDao.find(taskId))
                        conversionScheduler.enqueue(taskId)
                        DownloadHostRunResult.SUCCESS
                    }
                } catch (_: SteamDownloadPausedException) {
                    settlement.pause(task)
                } catch (_: SteamDownloadCancelledException) {
                    settlement.cancel(taskId, stagingDirectory)
                } catch (error: CancellationException) {
                    Log.w(DOWNLOAD_LOG_TAG, "Formal Steam download cancelled taskId=$taskId", error)
                    val retryable =
                        withContext(kotlinx.coroutines.NonCancellable) {
                            val latest = taskDao.find(taskId)
                            val currentControl = controlInbox.current()
                            val userCancelled =
                                isUserCancellation(
                                    currentRequestedAction =
                                        DownloadAction.CANCEL.name.takeIf {
                                            currentControl == SteamDownloadControl.CANCEL
                                        },
                                    currentStatus = latest?.status,
                                )
                            if (userCancelled) {
                                settlement.cancel(taskId, stagingDirectory)
                                false
                            } else {
                                settlement.interrupted(task)
                                true
                            }
                        }
                    if (retryable) return@withContext DownloadHostRunResult.RETRY
                    throw error
                } catch (error: Throwable) {
                    settlement.failed(task, error)
                }
            }

        private suspend fun awaitTaskControl(controlProbe: DurableDownloadControlInbox) {
            while (true) {
                when (controlProbe.current()) {
                    SteamDownloadControl.CONTINUE -> return
                    SteamDownloadControl.PAUSE -> throw SteamDownloadPausedException()
                    SteamDownloadControl.CANCEL -> throw SteamDownloadCancelledException()
                }
            }
        }

        private companion object {
            private const val PAUSE_POLL_INTERVAL_MS = 250L
        }
    }

/**
 * How long the last measured rate stands after the SDK stops reporting newly committed bytes.
 *
 * A window that committed nothing is not a measurement of zero: committed bytes advance only when the
 * SDK has durably recorded a chunk, so its own commit granularity, not the link, is what leaves a
 * window empty. Past this silence the transfer is no longer demonstrably moving and the rate reads as
 * unknown (zero) rather than as a stale measurement.
 */
internal const val DOWNLOAD_SPEED_STALE_AFTER_MS = 10_000L

/**
 * The rate to publish for one progress reading.
 *
 * A new committed reading is measured over the whole interval since the previous one, so a coarse
 * commit cadence still yields a real average instead of a rate sampled from a window nothing happened
 * in. A window with no new committed bytes keeps the last measurement until
 * [DOWNLOAD_SPEED_STALE_AFTER_MS] of silence, and a rate nobody ever measured stays zero: zero is the
 * app's "unknown", which the card hides rather than renders.
 */
internal fun downloadSpeedBytesPerSecond(
    phase: SteamDownloadPhase,
    completedBytes: Long,
    previousBytes: Long,
    elapsedMs: Long,
    previousSpeed: Long,
): Long =
    when {
        phase != SteamDownloadPhase.DOWNLOADING || elapsedMs <= 0L -> previousSpeed
        completedBytes > previousBytes ->
            ((completedBytes - previousBytes) * 1_000L / elapsedMs).coerceAtLeast(0L)
        elapsedMs >= DOWNLOAD_SPEED_STALE_AFTER_MS -> 0L
        else -> previousSpeed
    }

/**
 * Whether an interrupted run must be treated as the user's cancel rather than a system stop.
 *
 * Reading only the pending request is what let a cancel be mistaken for a system stop: the cancel
 * path starts a run that observes the request and clears it while acknowledging it, so the run that
 * was actually interrupted must also treat an already-acknowledged CANCELLED row as the user's
 * cancel instead of re-queueing the task.
 */
internal fun isUserCancellation(
    currentRequestedAction: String?,
    currentStatus: String?,
): Boolean =
    currentRequestedAction == DownloadAction.CANCEL.name ||
        currentStatus == DownloadStatus.CANCELLED.name

/**
 * Whether a progress write may replace the row's current state.
 *
 * A cancellation acknowledged while this run was unwinding is terminal for this run too. Publishing
 * progress over that row is what made a cancelled item go back to reading as downloading.
 */
internal fun mayPublishDownloadProgress(
    currentStatus: String?,
    nextStatus: DownloadStatus,
): Boolean =
    currentStatus != DownloadStatus.CANCELLED.name || nextStatus == DownloadStatus.CANCELLED

/**
 * The durable reading one run publishes, against the checkpoint the task already holds.
 *
 * The SDK reports committed bytes for a single run: a resumed run's counter starts at zero again and
 * only moves when it reports a freshly fetched chunk, even though the journal already covers everything
 * the paused run committed. Publishing that counter as it comes would move the task's durable reading
 * back to zero, so a resume would read as a restart and the card would show no progress until a new
 * chunk landed. The checkpoint the task already holds is the floor. A run whose checkpoint cannot be
 * reused passes zero, and then what the SDK reports is the whole reading.
 */
internal fun durableDownloadedBytes(
    checkpointBytes: Long,
    reportedBytes: Long,
): Long = maxOf(checkpointBytes.coerceAtLeast(0L), reportedBytes.coerceAtLeast(0L))

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
