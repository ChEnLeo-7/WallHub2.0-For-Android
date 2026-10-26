package com.wallhub.android.data.downloads

import android.util.Log
import com.wallhub.android.core.model.WALLPAPER_ENGINE_APP_ID
import io.steamcontent.sdk.TaskEventKind
import io.steamcontent.sdk.TaskFailure
import io.steamcontent.sdk.TaskState
import java.io.Closeable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One Workshop item the SDK resolved to the content identity a download accepts. */
internal data class SteamContentSdkResolvedItem(
    val itemId: Long,
    val appId: Long,
    val depotId: Long,
    val manifestId: Long,
    val resolved: Boolean,
)

/** The subset of the SDK task status this app reads. */
internal data class SteamContentSdkTaskStatus(
    val state: Int,
    val committedBytes: Long,
    val totalDecodedBytes: Long,
    val networkBytes: Long = 0L,
    val networkBytesPerSecond: Long = 0L,
    val firstContentReceived: Boolean = false,
    val errorOrdinal: Long,
)

/** The subset of the SDK task event this app reads. */
internal data class SteamContentSdkTaskEvent(
    val kind: Int,
    val value: Long,
)

/**
 * The SDK download task as this app uses it: status, redacted diagnostic, and the control calls.
 *
 * It exists so the mapping below is exercised by a JVM unit test without a device or the native library.
 */
internal interface SteamContentSdkTask : Closeable {
    fun status(): SteamContentSdkTaskStatus

    fun pollEvent(timeoutMs: Int): SteamContentSdkTaskEvent?

    fun lastError(): String?

    fun pause()

    fun resume()

    fun cancel()
}

/**
 * One opened SDK range stream over a single manifest file.
 *
 * It mirrors [SteamContentSdkTask]: the app's playback contract reads from this interface, so the
 * mapping onto it is exercised by a JVM unit test without a device or the native library.
 */
internal data class SteamContentSdkBufferSnapshot(
    val position: Long,
    val targetBytes: Long,
    val prefetchOffset: Long,
    val prefetchBytes: Long,
    val generation: Long,
    val cachedBytes: Long,
    val contiguousCachedBytes: Long = 0L,
    val cancelled: Boolean = false,
)

internal data class SteamContentSdkPlaybackDemand(
    val position: Long,
    val bufferedBytes: Long,
    val throughputBytesPerSecond: Long,
    val bitrateBitsPerSecond: Long,
    val rebuffered: Boolean = false,
)

internal data class SteamContentSdkStreamTelemetry(
    val fetchStarted: Long = 0L,
    val fetchCompleted: Long = 0L,
    val fetchFailed: Long = 0L,
    val deduplicated: Long = 0L,
    val foregroundWaits: Long = 0L,
    val staleCompletions: Long = 0L,
    val fetchMicros: Long = 0L,
    val queueMicros: Long = 0L,
    val lockMicros: Long = 0L,
    val decodeMicros: Long = 0L,
    val cacheHits: Long = 0L,
    val cacheMisses: Long = 0L,
    val cacheEvictions: Long = 0L,
    val foregroundFetchStarted: Long = 0L,
    val foregroundFetchCompleted: Long = 0L,
    val foregroundFetchFailed: Long = 0L,
    val foregroundFetchMicros: Long = 0L,
    val foregroundDecodeMicros: Long = 0L,
)

internal interface SteamContentSdkRangeStream : Closeable {
    /** Reads into [destination] and returns the bytes written; zero means the end of the file. */
    fun readAt(
        position: Long,
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    /** Performs the SDK's synchronous bounded rolling-prefetch update. */
    fun updatePlaybackDemand(demand: SteamContentSdkPlaybackDemand): SteamContentSdkBufferSnapshot

    fun bufferSnapshot(): SteamContentSdkBufferSnapshot

    fun telemetry(): SteamContentSdkStreamTelemetry = SteamContentSdkStreamTelemetry()

    fun cdnProvider(): com.wallhub.android.core.model.WorkshopVideoCdnProvider? = null

    fun currentCdnHost(): String? = null

    fun cancelPlaybackDemand()

    /** Interrupts an in-flight native chunk transport before close waits for its synchronized handle. */
    fun cancel()
}

/** One file of a manifest the SDK decoded, as the app reads it. */
internal data class SteamContentSdkManifestFile(
    val path: String,
    val size: Long,
    val flags: Int,
)

/**
 * One opened, restored SDK session.
 *
 * [storageRoot] is the directory the SDK resolves a relative download destination against; the app names
 * its own per-item directory relative to it rather than inventing a second layout.
 */
internal interface SteamContentSdkSession : Closeable {
    val storageRoot: File

    fun resolveWorkshop(
        itemId: Long,
        appIdHint: Long = 0L,
        contentManifestIdHint: Long = 0L,
    ): SteamContentSdkResolvedItem

    fun startDownload(
        appId: Long,
        depotId: Long,
        manifestId: Long,
        destination: String?,
        chunkConcurrency: Int,
    ): SteamContentSdkTask

    /**
     * Lists the files of the decoded manifest for one content identity.
     *
     * The SDK decodes the manifest with the Depot Key, so this is the only way the app can name a file of an
     * item whose public details do not name one. The paths come back exactly as the manifest states them.
     */
    fun manifestFiles(
        appId: Long,
        depotId: Long,
        manifestId: Long,
    ): List<SteamContentSdkManifestFile>

    /**
     * Opens a range stream over one file of the resolved content.
     *
     * The SDK resolves, authorizes, and serves the ranges itself; the app supplies only the content
     * identity and the in-manifest path. The returned stream is owned by the caller and must be closed
     * before the session it came from.
     */
    fun openRangeStream(
        appId: Long,
        depotId: Long,
        filePath: String,
        manifestId: Long,
        cacheCapacityBytes: Long,
    ): SteamContentSdkRangeStream
}

/**
 * Opens the SDK session one SDK-backed operation needs.
 *
 * The stored credential crosses this boundary unchanged and is never retained, logged, or copied. An
 * implementation must throw when the credential is absent or unusable instead of returning a session
 * that would silently fall back to another backend.
 */
/**
 * Serves one manifest-backed Workshop download with the Steam Content SDK.
 *
 * The SDK resolves the item to its app/depot/manifest and performs the transfer itself, so this class
 * never reconstructs a Depot Key, a CDN token, or a URL. Only what the SDK reports as durably committed
 * is published on the app's progress contract, and the task's own redacted diagnostic becomes the error
 * text when a run fails.
 *
 * A session is opened or borrowed for the run and closed once the last overlapping download releases it.
 * Steam refuses a second live session for one account, so overlapping downloads have to share one session -
 * they are not serialized on it. An earlier version held a lock around the whole download, which made the
 * product's own "concurrent downloads" setting unreachable on this path: every item after the first waited
 * for the one before it to finish.
 */
@Singleton
internal class SteamContentSdkWorkshopTransfer
    @Inject
    constructor(
        private val sessionBroker: SteamContentSdkSessionBroker,
    ) {

        suspend fun download(
            target: WorkshopContentTarget,
            destinationDirectory: File,
            options: SteamContentDownloadOptions,
            control: suspend () -> SteamDownloadControl,
            onProgress: suspend (SteamDownloadProgress) -> Unit,
        ): SteamContentDownloadResult {
            return try {
                downloadOnce(target, destinationDirectory, options, control, onProgress)
            } catch (failure: SteamContentSdkTaskFailureException) {
                val decision = downloadRecoveryDecision(failure)
                when (decision) {
                    DownloadRecoveryDecision.FAIL -> throw failure
                    DownloadRecoveryDecision.RETRY_DATA_PLANE_ONCE -> Unit
                    DownloadRecoveryDecision.WAIT_FOR_ACCOUNT_SESSION -> {
                        Log.i(TAG, "waiting for a newer account session after typed generation loss")
                        onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.RECOVERING_SESSION))
                    }
                }
                if (decision == DownloadRecoveryDecision.WAIT_FOR_ACCOUNT_SESSION) {
                    downloadOnce(
                        target,
                        destinationDirectory,
                        options,
                        control,
                        onProgress,
                        afterGeneration = failure.sessionGeneration,
                    )
                } else {
                    downloadOnce(target, destinationDirectory, options, control, onProgress)
                }
            }
        }

        private suspend fun downloadOnce(
            target: WorkshopContentTarget,
            destinationDirectory: File,
            options: SteamContentDownloadOptions,
            control: suspend () -> SteamDownloadControl,
            onProgress: suspend (SteamDownloadProgress) -> Unit,
            afterGeneration: Long? = null,
        ): SteamContentDownloadResult =
            withContext(Dispatchers.IO) {
                require(target.publishedFileId > 0L) { "Invalid Workshop item ID" }
                onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.CONNECTING))
                options.timingContext?.let { context ->
                    DownloadTimingTelemetry.log(DownloadTimingTelemetry.SDK_SESSION_ACQUIRE_STARTED, context)
                }
                val lease = sessionBroker.acquire(afterGeneration)
                options.timingContext?.let { context ->
                    DownloadTimingTelemetry.log(DownloadTimingTelemetry.SDK_SESSION_ACQUIRED, context)
                }
                try {
                    val session = lease.session
                    val destination = sdkRelativeDestination(destinationDirectory, session.storageRoot)
                    onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.RESOLVING))
                            options.timingContext?.let { context ->
                                DownloadTimingTelemetry.log(DownloadTimingTelemetry.SDK_WORKSHOP_RESOLVE_STARTED, context)
                            }
                            // Public Workshop details bind this manifest to the item. Wallpaper Engine's
                            // published Workshop contract fixes its Workshop depot to app 431960, so a complete
                            // identity can enter the authenticated manifest/download data plane directly instead
                            // of repeating Product Info. Other products and incomplete identities keep the SDK
                            // Workshop resolver and its validation.
                            val suppliedIdentity =
                                target.contentManifestId.takeIf { manifest ->
                                    manifest != 0L && target.appId == WALLPAPER_ENGINE_APP_ID
                                }?.let { manifest ->
                                    SteamContentSdkResolvedItem(
                                        itemId = target.publishedFileId,
                                        appId = WALLPAPER_ENGINE_APP_ID.toLong(),
                                        depotId = WALLPAPER_ENGINE_APP_ID.toLong(),
                                        manifestId = manifest,
                                        resolved = true,
                                    )
                                }
                            val resolved =
                                suppliedIdentity ?: session.resolveWorkshop(
                                    itemId = target.publishedFileId,
                                    appIdHint = target.appId.toLong().takeIf { it > 0L } ?: 0L,
                                    contentManifestIdHint = target.contentManifestId.takeIf { it != 0L } ?: 0L,
                                )
                            options.timingContext?.let { context ->
                                DownloadTimingTelemetry.log(DownloadTimingTelemetry.SDK_WORKSHOP_RESOLVED, context)
                            }
                            check(resolved.resolved) {
                                "The Steam Content SDK did not resolve Workshop item ${target.publishedFileId} to an app, depot, and manifest"
                            }
                            check(resolved.appId > 0L && resolved.depotId > 0L) {
                                "The Steam Content SDK resolved Workshop item ${target.publishedFileId} without a usable app or depot"
                            }
                            // One line per run so a parity reading can name the path that served it. The ids are
                            // public catalog numbers; no title, path, key, or token is logged.
                            Log.i(
                                TAG,
                                "download served by=steam-content-sdk app=${resolved.appId} depot=${resolved.depotId}",
                            )
                            onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.PREPARING_DOWNLOAD))
                            options.timingContext?.let { context ->
                                DownloadTimingTelemetry.log(DownloadTimingTelemetry.MANIFEST_REQUEST_STARTED, context)
                            }
                            val task =
                                session.startDownload(
                                    appId = resolved.appId,
                                    depotId = resolved.depotId,
                                    manifestId = resolved.manifestId,
                                    destination = destination,
                                    // The product's own "chunks in parallel per item" setting is the SDK's
                                    // in-flight budget now; zero would leave the engine's own order in place.
                                    chunkConcurrency = options.chunkConcurrency,
                                )
                            try {
                                return@withContext runTask(task, destinationDirectory, options, control, onProgress)
                            } catch (error: CancellationException) {
                                // The worker was stopped mid-transfer rather than asked to cancel through the
                                // control probe. Keep cleanup non-cancellable until the SDK confirms its own task
                                // reached CANCELLED; closing a still-running handle can release the app wrapper while
                                // the native transfer continues pulling bytes.
                                withContext(NonCancellable) {
                                    cancelAndAwaitTerminal(task)
                                }
                                throw error
                            } finally {
                                runCatching { task.close() }
                            }
                } catch (failure: SteamContentSdkTaskFailureException) {
                    if (
                        failure.ordinal == TaskFailure.SESSION_GENERATION_LOST ||
                        failure.ordinal == SDK_TASK_FAILURE_CONTENT_DIRECTORY_UNAVAILABLE ||
                        (
                            failure.ordinal == TaskFailure.AUTHORIZATION &&
                                failure.diagnostic == SESSION_UNAVAILABLE_DIAGNOSTIC
                        )
                    ) {
                        lease.reportSessionLoss()
                    }
                    throw failure.withSessionGeneration(lease.generation)
                } finally {
                    // The lease, not this download, owns the session: closing it here would take it away from
                    // the downloads that are still running beside this one.
                    lease.close()
                }
            }

        private suspend fun runTask(
            task: SteamContentSdkTask,
            destinationDirectory: File,
            options: SteamContentDownloadOptions,
            control: suspend () -> SteamDownloadControl,
            onProgress: suspend (SteamDownloadProgress) -> Unit,
        ): SteamContentDownloadResult {
            var committedBytes = 0L
            var totalBytes = 0L
            var manifestResolved = false
            var firstChunkNotified = false
            var lastActivityAtNanos = System.nanoTime()
            var lastNetworkBytes = 0L
            var lastCommittedReading = 0L
            val eventLog = SdkTaskEventLog()
            suspend fun acceptEvent(event: SteamContentSdkTaskEvent) {
                if (event.kind == TaskEventKind.MANIFEST_RESOLVED) {
                    manifestResolved = true
                    options.timingContext?.let { context ->
                        DownloadTimingTelemetry.logOnce(DownloadTimingTelemetry.MANIFEST_COMPLETED, context)
                    }
                }
                if (event.kind == TaskEventKind.CHUNK_PROGRESS && event.value > 0L && !firstChunkNotified) {
                    firstChunkNotified = true
                    options.onFirstChunkCommitted?.invoke()
                }
                sdkPreparationTimingName(event.kind)?.let { name ->
                    options.timingContext?.let { context -> DownloadTimingTelemetry.logSdk(name, context, event.value) }
                }
                eventLog.accept(event)
            }
            suspend fun drainEvents(waitMs: Int) {
                var event = task.pollEvent(waitMs)
                while (event != null) {
                    acceptEvent(event)
                    event = task.pollEvent(0)
                }
            }
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                when (control()) {
                    SteamDownloadControl.CANCEL -> {
                        cancelAndAwaitTerminal(task, ::acceptEvent)
                        throw SteamDownloadCancelledException()
                    }

                    SteamDownloadControl.PAUSE -> {
                        pauseAndAwaitTerminal(task, ::acceptEvent)
                        throw SteamDownloadPausedException()
                    }

                    SteamDownloadControl.CONTINUE -> Unit
                }
                val status = task.status()
                drainEvents(0)
                // Durable committed bytes are the one progress reading this contract publishes. After its
                // resume scan, the SDK includes every validated journal chunk in this status before it fetches
                // the remaining suffix. The max still protects the app contract from a stale status read.
                committedBytes = maxOf(committedBytes, status.committedBytes.coerceAtLeast(0L))
                totalBytes = maxOf(totalBytes, status.totalDecodedBytes.coerceAtLeast(0L))
                val currentNetworkBytes = status.networkBytes.coerceAtLeast(0L)
                if (committedBytes > lastCommittedReading || currentNetworkBytes > lastNetworkBytes) {
                    lastCommittedReading = committedBytes
                    lastNetworkBytes = currentNetworkBytes
                    lastActivityAtNanos = System.nanoTime()
                }
                when (status.state) {
                    TaskState.COMPLETED -> {
                        eventLog.flushPending()
                        // COMPLETED means publication succeeded for the whole decoded manifest. In particular,
                        // a resumed task can reach this state after fetching only the remaining suffix, so the
                        // result must name the fully published total rather than that run's last suffix count.
                        val finalTotal = maxOf(totalBytes, committedBytes)
                        onProgress(
                            SteamDownloadProgress(
                                phase = SteamDownloadPhase.VERIFYING,
                                completedBytes = finalTotal,
                                totalBytes = finalTotal,
                                completedFiles = 0,
                                totalFiles = 0,
                                networkBytes = status.networkBytes,
                                networkBytesPerSecond = status.networkBytesPerSecond,
                                firstContentReceived = status.firstContentReceived,
                            ),
                        )
                        return SteamContentDownloadResult(
                            rootDirectory = destinationDirectory,
                            downloadedBytes = finalTotal,
                            totalBytes = finalTotal,
                            fileCount = countPublishedFiles(destinationDirectory),
                            usedAuthenticatedSession = true,
                        )
                    }

                    TaskState.FAILED -> throw sdkFailure(task, status)

                    TaskState.CANCELLED -> throw SteamDownloadCancelledException()

                    TaskState.PAUSED -> {
                        // The app's pause signal is a control request, not a task state; the SDK only reports
                        // PAUSED on its own when a host already asked. Continue such a task rather than
                        // abandoning durable work, and let an explicit cancel still win.
                        when (control()) {
                            SteamDownloadControl.CANCEL -> {
                                cancelAndAwaitTerminal(task, ::acceptEvent)
                                throw SteamDownloadCancelledException()
                            }

                            SteamDownloadControl.PAUSE -> throw SteamDownloadPausedException()

                            SteamDownloadControl.CONTINUE -> task.resume()
                        }
                    }

                    else -> {
                        if (
                            status.state == TaskState.DOWNLOADING &&
                            (status.firstContentReceived || currentNetworkBytes > 0L) &&
                            System.nanoTime() - lastActivityAtNanos >= DOWNLOAD_NO_PROGRESS_TIMEOUT_NANOS
                        ) {
                            Log.w(TAG, "SDK download made no network or durable progress; pausing for bounded retry")
                            pauseAndAwaitTerminal(task, ::acceptEvent)
                            throw SteamContentSdkTaskFailureException(
                                ordinal = TaskFailure.NETWORK,
                                diagnostic = DOWNLOAD_NO_PROGRESS_DIAGNOSTIC,
                                message = "Steam Content SDK download stalled after content transfer began",
                            )
                        }
                        onProgress(
                            SteamDownloadProgress(
                                phase = sdkProgressPhase(status, manifestResolved),
                                completedBytes = committedBytes,
                                totalBytes = totalBytes,
                                networkBytes = status.networkBytes,
                                networkBytesPerSecond = status.networkBytesPerSecond,
                                firstContentReceived = status.firstContentReceived,
                            ),
                        )
                        // The SDK publishes the task events this loop currently drops, and the event kind is the
                        // only reading that splits the resolving phase: session obtain, the app's own workshop
                        // read, then the manager's request code, server list, depot key and per-record token
                        // calls, the manifest fetch and its decode, then the source opening. The name is the
                        // SDK's own kind constant, so an ordinal in a device reading cannot be mistaken for
                        // per-chunk progress: a session-backed run now publishes CHUNK_PROGRESS per committed
                        // chunk, and CHECKPOINT_COMMITTED is the durable checkpoint it publishes when it stops.
                        // Ordinals and one byte count only, no host, token or URL.
                        drainEvents(SDK_EVENT_POLL_MS)
                    }
                }
            }
            } finally {
                // Flush byte-event summaries before every exit: success, typed terminal states, control exits,
                // coroutine cancellation, SDK exceptions, progress callback failures, and unexpected errors.
                eventLog.close()
            }
        }

        /** Requests a durable pause and does not let the task handle close before the SDK confirms it. */
        private suspend fun pauseAndAwaitTerminal(
            task: SteamContentSdkTask,
            acceptEvent: suspend (SteamContentSdkTaskEvent) -> Unit = ::logSdkTaskEvent,
        ) {
            try {
                task.pause()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // A refused request can race a terminal state. Observe that state rather than converting a
                // completed or failed task into an app pause merely because the control probe ran first.
                Log.w(TAG, "the SDK refused a pause; reading its terminal state", error)
            }
            while (true) {
                val status = task.status()
                drainSdkTaskEvents(task, acceptEvent)
                when (status.state) {
                    TaskState.PAUSED -> return
                    TaskState.CANCELLED -> throw SteamDownloadCancelledException()
                    TaskState.FAILED -> throw sdkFailure(task, status)
                    TaskState.COMPLETED -> error("The Steam Content SDK download completed before it paused")
                }
                task.pollEvent(SDK_EVENT_POLL_MS)?.let { acceptEvent(it) }
            }
        }

        /** Requests cancellation and waits until the SDK reports its terminal CANCELLED state. */
        private suspend fun cancelAndAwaitTerminal(
            task: SteamContentSdkTask,
            acceptEvent: suspend (SteamContentSdkTaskEvent) -> Unit = ::logSdkTaskEvent,
        ) {
            runCatching { task.cancel() }
            while (true) {
                val status = task.status()
                drainSdkTaskEvents(task, acceptEvent)
                when (status.state) {
                    TaskState.CANCELLED -> return
                    TaskState.FAILED -> {
                        if (status.errorOrdinal == TaskFailure.CANCELLED) return
                        throw sdkFailure(task, status)
                    }
                    TaskState.COMPLETED -> return
                }
                task.pollEvent(SDK_EVENT_POLL_MS)?.let { acceptEvent(it) }
            }
        }

        private fun sdkFailure(
            task: SteamContentSdkTask,
            status: SteamContentSdkTaskStatus,
        ): Throwable {
            if (status.errorOrdinal == TaskFailure.CANCELLED) return SteamDownloadCancelledException()
            val diagnostic = runCatching { task.lastError() }.getOrNull()?.takeIf(String::isNotBlank)
            val reason = sdkFailureName(status.errorOrdinal)
            return SteamContentSdkTaskFailureException(
                ordinal = status.errorOrdinal,
                diagnostic = diagnostic,
                message =
                    buildString {
                        append("Steam Content SDK download failed")
                        if (reason != null) append(" (").append(reason).append(')')
                        if (diagnostic != null) append(": ").append(diagnostic)
                    },
            )
        }

        private companion object {
            const val SDK_EVENT_POLL_MS = 250
        }
    }

private suspend fun drainSdkTaskEvents(
    task: SteamContentSdkTask,
    acceptEvent: suspend (SteamContentSdkTaskEvent) -> Unit,
) {
    var event = task.pollEvent(0)
    while (event != null) {
        acceptEvent(event)
        event = task.pollEvent(0)
    }
}

private suspend fun logSdkTaskEvent(event: SteamContentSdkTaskEvent) {
    // Cleanup loops still drain byte events, but the owning runTask logger is the only place that aggregates them.
    // Re-logging them here would restore the per-chunk INFO flood during cancellation.
    if (event.kind != TaskEventKind.CHUNK_PROGRESS && event.kind != TaskEventKind.CHECKPOINT_COMMITTED) {
        AndroidTaskEventInfoLog.info("task event kind=${sdkTaskEventName(event.kind)} value=${event.value}")
    }
}

internal interface TaskEventInfoLog {
    fun info(message: String)
}

internal object AndroidTaskEventInfoLog : TaskEventInfoLog {
    override fun info(message: String) {
        Log.i(TAG, message)
    }
}

/**
 * Keeps draining every SDK event while bounding the two high-volume byte-event INFO lines.
 *
 * The first chunk remains an immediate breadcrumb. Later chunk/checkpoint readings are summarized at most once
 * per second, and any phase or terminal event flushes the pending summary before retaining its own line.
 */
internal class SdkTaskEventLog(
    private val logger: TaskEventInfoLog = AndroidTaskEventInfoLog,
    private val clockMs: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val intervalMs: Long = SDK_TASK_EVENT_LOG_INTERVAL_MS,
) : Closeable {
    private var firstChunkValue: Long? = null
    private var lastLoggedValue = 0L
    private var lastSummaryAtMs = Long.MIN_VALUE
    private var pendingCount = 0
    private var pendingChunkCount = 0
    private var pendingCheckpointCount = 0
    private var pendingLatestValue = 0L

    @Synchronized
    fun accept(event: SteamContentSdkTaskEvent) {
        if (event.kind == TaskEventKind.CHUNK_PROGRESS || event.kind == TaskEventKind.CHECKPOINT_COMMITTED) {
            val firstValue = firstChunkValue
            if (event.kind == TaskEventKind.CHUNK_PROGRESS && firstValue == null) {
                firstChunkValue = event.value
                lastLoggedValue = event.value
                lastSummaryAtMs = clockMs()
                logger.info("task event kind=CHUNK_PROGRESS value=${event.value}")
                return
            }
            pendingCount += 1
            if (event.kind == TaskEventKind.CHUNK_PROGRESS) pendingChunkCount += 1 else pendingCheckpointCount += 1
            pendingLatestValue = event.value
            val elapsed = if (lastSummaryAtMs == Long.MIN_VALUE) Long.MAX_VALUE else clockMs() - lastSummaryAtMs
            if (elapsed >= intervalMs) flushPending()
            return
        }

        flushPending()
        logger.info("task event kind=${sdkTaskEventName(event.kind)} value=${event.value}")
    }

    override fun close() = flushPending()

    @Synchronized
    fun flushPending() {
        if (pendingCount == 0) return
        logger.info(
            "task event kind=CHUNK_PROGRESS_CHECKPOINT aggregated=true count=$pendingCount " +
                "chunks=$pendingChunkCount checkpoints=$pendingCheckpointCount value=$pendingLatestValue " +
                "value_delta=${(pendingLatestValue - lastLoggedValue).coerceAtLeast(0L)}",
        )
        lastLoggedValue = pendingLatestValue
        pendingCount = 0
        pendingChunkCount = 0
        pendingCheckpointCount = 0
        lastSummaryAtMs = clockMs()
    }
}

private const val SDK_TASK_EVENT_LOG_INTERVAL_MS = 1_000L
private const val SESSION_UNAVAILABLE_DIAGNOSTIC = "content resolution failed at session_unavailable"
private const val DOWNLOAD_NO_PROGRESS_DIAGNOSTIC = "sdk_download_no_progress"
private const val DOWNLOAD_NO_PROGRESS_TIMEOUT_NANOS = 45_000_000_000L
// ABI minor 44 append-only ordinals. Kept locally so this adapter can compile against the prior preview AAR
// while staging the new native artifact; control flow still switches on stable numbers, never diagnostic text.
internal const val SDK_TASK_FAILURE_CONTENT_DIRECTORY_UNAVAILABLE = 12L
internal const val SDK_TASK_FAILURE_MANIFEST_TRANSPORT_UNAVAILABLE = 13L

internal enum class DownloadRecoveryDecision {
    WAIT_FOR_ACCOUNT_SESSION,
    RETRY_DATA_PLANE_ONCE,
    FAIL,
}

/** A bounded, typed decision; the caller always performs at most two SDK task attempts. */
internal fun downloadRecoveryDecision(failure: SteamContentSdkTaskFailureException): DownloadRecoveryDecision =
    when {
        failure.ordinal == TaskFailure.SESSION_GENERATION_LOST ||
            failure.ordinal == SDK_TASK_FAILURE_CONTENT_DIRECTORY_UNAVAILABLE ->
            DownloadRecoveryDecision.WAIT_FOR_ACCOUNT_SESSION
        failure.ordinal == SDK_TASK_FAILURE_MANIFEST_TRANSPORT_UNAVAILABLE ||
            (failure.ordinal == TaskFailure.NETWORK && failure.diagnostic == DOWNLOAD_NO_PROGRESS_DIAGNOSTIC) ->
            DownloadRecoveryDecision.RETRY_DATA_PLANE_ONCE
        // Compatibility for older SDKs which lacked SESSION_GENERATION_LOST. New flow never parses diagnostics.
        failure.ordinal == TaskFailure.AUTHORIZATION && failure.diagnostic == SESSION_UNAVAILABLE_DIAGNOSTIC ->
            DownloadRecoveryDecision.RETRY_DATA_PLANE_ONCE
        else -> DownloadRecoveryDecision.FAIL
    }

internal fun isRecoverableDownloadAttempt(failure: SteamContentSdkTaskFailureException): Boolean =
    downloadRecoveryDecision(failure) != DownloadRecoveryDecision.FAIL

/** A typed SDK task failure keeps retry policy on stable machine-readable fields. */
internal class SteamContentSdkTaskFailureException(
    val ordinal: Long,
    val diagnostic: String?,
    message: String,
    val sessionGeneration: Long? = null,
) : IllegalStateException(message) {
    fun withSessionGeneration(generation: Long): SteamContentSdkTaskFailureException =
        if (sessionGeneration == generation) {
            this
        } else {
            SteamContentSdkTaskFailureException(ordinal, diagnostic, message.orEmpty(), generation)
        }
}

/** Maps an SDK task state onto the phase vocabulary the app's progress contract already publishes. */
internal fun sdkProgressPhase(
    status: SteamContentSdkTaskStatus,
    manifestResolved: Boolean,
): SteamDownloadPhase =
    when {
        status.state == TaskState.RESOLVING && manifestResolved -> SteamDownloadPhase.PREPARING_DOWNLOAD
        status.state == TaskState.RESOLVING -> SteamDownloadPhase.RESOLVING
        status.state == TaskState.AUTHENTICATING -> SteamDownloadPhase.AUTHENTICATING
        status.state == TaskState.VERIFYING || status.state == TaskState.COMMITTING -> SteamDownloadPhase.VERIFYING
        status.state == TaskState.DOWNLOADING &&
            status.totalDecodedBytes > 0L &&
            status.committedBytes >= status.totalDecodedBytes -> SteamDownloadPhase.VERIFYING
        status.state == TaskState.DOWNLOADING -> SteamDownloadPhase.DOWNLOADING
        else -> SteamDownloadPhase.PREPARING_DOWNLOAD
    }

/** Names a stable SDK failure ordinal without carrying any SDK-owned material into a message. */
internal fun sdkFailureName(ordinal: Long): String? =
    when (ordinal) {
        TaskFailure.NONE -> null
        TaskFailure.CANCELLED -> "cancelled"
        TaskFailure.NETWORK -> "network"
        TaskFailure.STORAGE -> "storage"
        TaskFailure.CONTENT_CORRUPTED -> "content corrupted"
        TaskFailure.AUTHORIZATION -> "authorization"
        TaskFailure.INVALID_INPUT -> "invalid input"
        TaskFailure.INTERNAL -> "internal"
        // A source that refused this request and will keep refusing it. Its own family, because reading it as
        // "network" told the user to retry something that cannot succeed without the content or the account
        // changing.
        TaskFailure.CDN_REFUSED -> "cdn refused"
        TaskFailure.NO_PROGRESS -> "no progress"
        TaskFailure.ABORT_TIMEOUT -> "abort timeout"
        TaskFailure.SESSION_GENERATION_LOST -> "session generation lost"
        else -> "failure $ordinal"
    }

/**
 * Names an SDK task event kind by the SDK's own constant.
 *
 * A device reading that shows a bare ordinal cannot be acted on, and the two byte-carrying kinds read
 * very differently: [TaskEventKind.CHECKPOINT_COMMITTED] is the durable checkpoint a run publishes when
 * it stops, while [TaskEventKind.CHUNK_PROGRESS] is the per-chunk reading a session-backed run publishes
 * while it fetches. An unknown kind stays an ordinal because the SDK appends kinds and this app must not
 * rename a number it does not know.
 */
internal fun sdkTaskEventName(kind: Int): String =
    when (kind) {
        TaskEventKind.PHASE_CHANGED -> "PHASE_CHANGED"
        TaskEventKind.MANIFEST_RESOLVED -> "MANIFEST_RESOLVED"
        TaskEventKind.CHUNK_PROGRESS -> "CHUNK_PROGRESS"
        TaskEventKind.CHECKPOINT_COMMITTED -> "CHECKPOINT_COMMITTED"
        TaskEventKind.RETRY_SCHEDULED -> "RETRY_SCHEDULED"
        TaskEventKind.THROUGHPUT_UPDATED -> "THROUGHPUT_UPDATED"
        TaskEventKind.COMPLETED -> "COMPLETED"
        TaskEventKind.FAILED -> "FAILED"
        TaskEventKind.CANCELLED -> "CANCELLED"
        TaskEventKind.PAUSED -> "PAUSED"
        TaskEventKind.RESUMED -> "RESUMED"
        TaskEventKind.PREPARATION_RESOLUTION_STARTED -> "PREPARATION_RESOLUTION_STARTED"
        TaskEventKind.PREPARATION_MANIFEST_REQUEST_CODE_READY -> "PREPARATION_MANIFEST_REQUEST_CODE_READY"
        TaskEventKind.PREPARATION_CONTENT_SERVERS_READY -> "PREPARATION_CONTENT_SERVERS_READY"
        TaskEventKind.PREPARATION_DEPOT_KEY_READY -> "PREPARATION_DEPOT_KEY_READY"
        TaskEventKind.PREPARATION_CDN_TOKENS_READY -> "PREPARATION_CDN_TOKENS_READY"
        TaskEventKind.PREPARATION_MANIFEST_FETCHED -> "PREPARATION_MANIFEST_FETCHED"
        TaskEventKind.PREPARATION_MANIFEST_DECODED -> "PREPARATION_MANIFEST_DECODED"
        TaskEventKind.PREPARATION_EXECUTION_ADMITTED -> "PREPARATION_EXECUTION_ADMITTED"
        TaskEventKind.PREPARATION_RESUME_SCAN_COMPLETED -> "PREPARATION_RESUME_SCAN_COMPLETED"
        TaskEventKind.PREPARATION_FIRST_NETWORK_RECEIVED -> "PREPARATION_FIRST_NETWORK_RECEIVED"
        TaskEventKind.PREPARATION_FIRST_WRITE_COMPLETED -> "PREPARATION_FIRST_WRITE_COMPLETED"
        TaskEventKind.PREPARATION_FIRST_CHECKPOINT_COMPLETED -> "PREPARATION_FIRST_CHECKPOINT_COMPLETED"
        else -> "kind $kind"
    }

internal fun sdkPreparationTimingName(kind: Int): String? =
    when (kind) {
        TaskEventKind.PREPARATION_RESOLUTION_STARTED -> "sdk_resolution_started"
        TaskEventKind.PREPARATION_MANIFEST_REQUEST_CODE_READY -> "sdk_manifest_request_code_ready"
        TaskEventKind.PREPARATION_CONTENT_SERVERS_READY -> "sdk_content_servers_ready"
        TaskEventKind.PREPARATION_DEPOT_KEY_READY -> "sdk_depot_key_ready"
        TaskEventKind.PREPARATION_CDN_TOKENS_READY -> "sdk_cdn_tokens_ready"
        TaskEventKind.PREPARATION_MANIFEST_FETCHED -> "sdk_manifest_fetched"
        TaskEventKind.PREPARATION_MANIFEST_DECODED -> "sdk_manifest_decoded"
        TaskEventKind.PREPARATION_EXECUTION_ADMITTED -> "sdk_execution_admitted"
        TaskEventKind.PREPARATION_RESUME_SCAN_COMPLETED -> "sdk_resume_scan_completed"
        TaskEventKind.PREPARATION_FIRST_NETWORK_RECEIVED -> "sdk_first_network_received"
        TaskEventKind.PREPARATION_FIRST_WRITE_COMPLETED -> "sdk_first_write_completed"
        TaskEventKind.PREPARATION_FIRST_CHECKPOINT_COMPLETED -> "sdk_first_checkpoint_completed"
        else -> null
    }

/**
 * Names the app's existing per-item staging directory as a safe relative destination under the SDK's
 * storage root.
 *
 * The SDK accepts only a safe relative directory under its own storage root, so the app expresses the
 * very directory it already hands to the old transfer (`<filesDir>/wallhub-workshop/<taskId>`) relative
 * to that root. A directory that is not inside the root is a clear failure rather than an invented path.
 */
internal fun sdkRelativeDestination(
    destinationDirectory: File,
    storageRoot: File,
): String {
    val root = storageRoot.canonicalFileOrSelf()
    val destination = destinationDirectory.canonicalFileOrSelf()
    val prefix = root.path + File.separator
    require(destination.path.startsWith(prefix)) {
        "The Workshop staging directory is not inside the Steam Content SDK storage root"
    }
    val relative = destination.path.removePrefix(prefix).replace(File.separatorChar, '/')
    val segments = relative.split('/')
    require(segments.isNotEmpty() && segments.all { it.isNotEmpty() && it != "." && it != ".." }) {
        "The Workshop staging directory cannot be named relative to the Steam Content SDK storage root"
    }
    return relative
}

private fun File.canonicalFileOrSelf(): File = runCatching { canonicalFile }.getOrElse { absoluteFile }

/** Counts the files the SDK published, matching the old path's file-count reading for the completion message. */
private fun countPublishedFiles(directory: File): Int =
    runCatching { directory.walkTopDown().count { it.isFile } }.getOrDefault(0)

/** The tag the SDK download path names its one per-run line with. */
private const val TAG = "WallHubSdkTransfer"
