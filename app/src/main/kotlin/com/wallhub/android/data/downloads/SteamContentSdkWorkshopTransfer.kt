package com.wallhub.android.data.downloads

import android.util.Log
import com.wallhub.android.core.model.SteamContentCredential
import io.steamcontent.sdk.TaskFailure
import io.steamcontent.sdk.TaskState
import java.io.Closeable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private val sessionOpener: SteamContentSdkSessionOpener,
    ) : WorkshopContentTransfer {
        /** One session shared by every download that overlaps in time. */
        private class SessionLease(
            val key: String,
            val session: SteamContentSdkSession,
        ) {
            /** Downloads currently holding it; the last one out closes the session. */
            var users = 0
        }

        private val sessionMutex = Mutex()
        private val sessionLeases = mutableMapOf<String, SessionLease>()

        /**
         * Borrows, or opens, the session for one account and counts one more holder of it.
         *
         * The lease is what decouples the session's lifetime from a single download: the first download to
         * finish must not close a session its peers are still reading, and the last one out must close a
         * session this path opened rather than leaking it. A session the session domain owns ignores `close`,
         * so borrowing costs nothing here.
         */
        private suspend fun acquireSession(credential: SteamContentCredential): SessionLease =
            sessionMutex.withLock {
                val key = credential.accountName.orEmpty().lowercase()
                val existing = sessionLeases[key]
                if (existing != null) {
                    existing.users += 1
                    existing
                } else {
                    val opened = sessionOpener.open(credential)
                    SessionLease(key, opened).also { lease ->
                        lease.users = 1
                        sessionLeases[key] = lease
                    }
                }
            }

        /** Releases one holder, closing the session when it was the last one. */
        private suspend fun releaseSession(lease: SessionLease) {
            sessionMutex.withLock {
                lease.users -= 1
                if (lease.users > 0) return@withLock
                sessionLeases.remove(lease.key)
                runCatching { lease.session.close() }
            }
        }

        override suspend fun download(
            target: WorkshopContentTarget,
            destinationDirectory: File,
            credential: SteamContentCredential?,
            options: SteamContentDownloadOptions,
            control: suspend () -> SteamDownloadControl,
            onProgress: suspend (SteamDownloadProgress) -> Unit,
        ): SteamContentDownloadResult =
            withContext(Dispatchers.IO) {
                require(target.appId > 0) { "Invalid Steam App ID" }
                require(target.contentManifestId != 0L) { "Invalid Steam manifest ID" }
                val effectiveCredential = credential
                check(effectiveCredential != null) {
                    "The Steam Content SDK download path needs the stored Steam refresh token; none was read"
                }
                check(effectiveCredential.refreshToken.isNotBlank()) {
                    "The Steam Content SDK download path needs the stored Steam refresh token; none was read"
                }
                onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.CONNECTING))
                val lease = acquireSession(effectiveCredential)
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
                        // control probe, so closing the task is not enough: the SDK's own transfer is
                        // still running and would keep pulling bytes after the app stopped watching.
                        runCatching { task.cancel() }
                        throw error
                    } finally {
                        runCatching { task.close() }
                    }
                } finally {
                    // The lease, not this download, owns the session: closing it here would take it away from
                    // the downloads that are still running beside this one.
                    releaseSession(lease)
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
            while (true) {
                currentCoroutineContext().ensureActive()
                when (control()) {
                    SteamDownloadControl.CANCEL -> {
                        task.cancel()
                        throw SteamDownloadCancelledException()
                    }

                    SteamDownloadControl.PAUSE -> {
                        task.pause()
                        throw SteamDownloadPausedException()
                    }

                    SteamDownloadControl.CONTINUE -> Unit
                }
                val status = task.status()
                committedBytes = maxOf(committedBytes, status.committedBytes.coerceAtLeast(0L))
                totalBytes = maxOf(totalBytes, status.totalDecodedBytes.coerceAtLeast(0L))
                when (status.state) {
                    TaskState.COMPLETED -> {
                        val finalTotal = maxOf(totalBytes, committedBytes)
                        onProgress(
                            SteamDownloadProgress(
                                phase = SteamDownloadPhase.DOWNLOADING,
                                completedBytes = committedBytes,
                                totalBytes = finalTotal,
                                completedFiles = 0,
                                totalFiles = 0,
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
                                task.cancel()
                                throw SteamDownloadCancelledException()
                            }

                            SteamDownloadControl.PAUSE -> throw SteamDownloadPausedException()

                            SteamDownloadControl.CONTINUE -> task.resume()
                        }
                    }

                    else -> {
                        onProgress(
                            SteamDownloadProgress(
                                phase = sdkProgressPhase(status.state) ?: SteamDownloadPhase.DOWNLOADING,
                                completedBytes = committedBytes,
                                totalBytes = totalBytes,
                            ),
                        )
                        task.pollEvent(SDK_EVENT_POLL_MS)
                    }
                }
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
internal fun sdkProgressPhase(state: Int): SteamDownloadPhase? =
    when (state) {
        TaskState.QUEUED -> SteamDownloadPhase.CONNECTING
        TaskState.RESOLVING -> SteamDownloadPhase.RESOLVING
        TaskState.AUTHENTICATING -> SteamDownloadPhase.AUTHENTICATING
        TaskState.DOWNLOADING, TaskState.VERIFYING, TaskState.COMMITTING -> SteamDownloadPhase.DOWNLOADING
        else -> null
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
        else -> "failure $ordinal"
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
