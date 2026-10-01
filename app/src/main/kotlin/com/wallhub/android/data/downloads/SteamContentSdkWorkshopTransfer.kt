package com.wallhub.android.data.downloads

import android.util.Log
import com.wallhub.android.core.model.SteamContentCredential
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
internal interface SteamContentSdkRangeStream : Closeable {
    /** Reads into [destination] and returns the bytes written; zero means the end of the file. */
    fun readAt(
        position: Long,
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int
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

    fun resolveWorkshop(itemId: Long): SteamContentSdkResolvedItem

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
internal fun interface SteamContentSdkSessionOpener {
    suspend fun open(credential: SteamContentCredential): SteamContentSdkSession
}

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
            credential: SteamContentCredential?,
            options: SteamContentDownloadOptions,
            control: suspend () -> SteamDownloadControl,
            onProgress: suspend (SteamDownloadProgress) -> Unit,
        ): SteamContentDownloadResult =
            withContext(Dispatchers.IO) {
                require(target.publishedFileId > 0L) { "Invalid Workshop item ID" }
                val effectiveCredential = credential
                check(effectiveCredential != null) {
                    "The Steam Content SDK download path needs the stored Steam refresh token; none was read"
                }
                check(effectiveCredential.refreshToken.isNotBlank()) {
                    "The Steam Content SDK download path needs the stored Steam refresh token; none was read"
                }
                onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.CONNECTING))
                val lease = sessionBroker.acquire(effectiveCredential)
                try {
                    val session = lease.session
                    onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.RESOLVING))
                    val resolved = session.resolveWorkshop(target.publishedFileId)
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
                    val destination = sdkRelativeDestination(destinationDirectory, session.storageRoot)
                    onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.PREPARING_DOWNLOAD))
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
                        runTask(task, destinationDirectory, control, onProgress)
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
                } finally {
                    // The lease, not this download, owns the session: closing it here would take it away from
                    // the downloads that are still running beside this one.
                    lease.close()
                }
            }

        private suspend fun runTask(
            task: SteamContentSdkTask,
            destinationDirectory: File,
            control: suspend () -> SteamDownloadControl,
            onProgress: suspend (SteamDownloadProgress) -> Unit,
        ): SteamContentDownloadResult {
            var committedBytes = 0L
            var totalBytes = 0L
            var manifestResolved = false
            while (true) {
                currentCoroutineContext().ensureActive()
                when (control()) {
                    SteamDownloadControl.CANCEL -> {
                        cancelAndAwaitTerminal(task)
                        throw SteamDownloadCancelledException()
                    }

                    SteamDownloadControl.PAUSE -> {
                        pauseAndAwaitTerminal(task)
                        throw SteamDownloadPausedException()
                    }

                    SteamDownloadControl.CONTINUE -> Unit
                }
                val status = task.status()
                // Durable committed bytes are the one progress reading this contract publishes. The SDK's
                // counters are per run: a resumed run starts them at zero again and only moves committed
                // bytes when it reports a freshly fetched chunk, even though its journal already covers the
                // paused run's chunks. The max keeps one run's reading monotonic; carrying the task's
                // reading across runs is the worker's checkpoint floor, not this loop's.
                committedBytes = maxOf(committedBytes, status.committedBytes.coerceAtLeast(0L))
                totalBytes = maxOf(totalBytes, status.totalDecodedBytes.coerceAtLeast(0L))
                when (status.state) {
                    TaskState.COMPLETED -> {
                        val finalTotal = maxOf(totalBytes, committedBytes)
                        onProgress(
                            SteamDownloadProgress(
                                phase = SteamDownloadPhase.VERIFYING,
                                completedBytes = committedBytes,
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
                            downloadedBytes = committedBytes,
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
                                cancelAndAwaitTerminal(task)
                                throw SteamDownloadCancelledException()
                            }

                            SteamDownloadControl.PAUSE -> throw SteamDownloadPausedException()

                            SteamDownloadControl.CONTINUE -> task.resume()
                        }
                    }

                    else -> {
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
                        task.pollEvent(SDK_EVENT_POLL_MS)?.let { event ->
                            if (event.kind == TaskEventKind.MANIFEST_RESOLVED) manifestResolved = true
                            Log.i(
                                TAG,
                                "task event kind=${sdkTaskEventName(event.kind)} value=${event.value}",
                            )
                        }
                    }
                }
            }
        }

        /** Requests a durable pause and does not let the task handle close before the SDK confirms it. */
        private fun pauseAndAwaitTerminal(task: SteamContentSdkTask) {
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
                when (status.state) {
                    TaskState.PAUSED -> return
                    TaskState.CANCELLED -> throw SteamDownloadCancelledException()
                    TaskState.FAILED -> throw sdkFailure(task, status)
                    TaskState.COMPLETED -> error("The Steam Content SDK download completed before it paused")
                }
                task.pollEvent(SDK_EVENT_POLL_MS)
            }
        }

        /** Requests cancellation and waits until the SDK reports its terminal CANCELLED state. */
        private fun cancelAndAwaitTerminal(task: SteamContentSdkTask) {
            runCatching { task.cancel() }
            while (true) {
                val status = task.status()
                when (status.state) {
                    TaskState.CANCELLED -> return
                    TaskState.FAILED -> {
                        if (status.errorOrdinal == TaskFailure.CANCELLED) return
                        throw sdkFailure(task, status)
                    }
                    TaskState.COMPLETED -> return
                }
                task.pollEvent(SDK_EVENT_POLL_MS)
            }
        }

        private fun sdkFailure(
            task: SteamContentSdkTask,
            status: SteamContentSdkTaskStatus,
        ): Throwable {
            if (status.errorOrdinal == TaskFailure.CANCELLED) return SteamDownloadCancelledException()
            val diagnostic = runCatching { task.lastError() }.getOrNull()?.takeIf(String::isNotBlank)
            val reason = sdkFailureName(status.errorOrdinal)
            return IllegalStateException(
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
        else -> "kind $kind"
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
