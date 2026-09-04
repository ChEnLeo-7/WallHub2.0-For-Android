package com.wallhub.android.data.downloads

import android.util.Log
import com.wallhub.android.core.model.DepotChunkSpec
import com.wallhub.android.core.model.DepotFileFlag
import com.wallhub.android.core.model.DepotDownloader
import com.wallhub.android.core.model.DepotFileSpec
import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.data.steam.KSteamSessionRepository
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

internal enum class SteamDownloadPhase {
    CONNECTING,
    AUTHENTICATING,
    RESOLVING,
    DOWNLOADING,
}

internal enum class SteamDownloadControl {
    CONTINUE,
    PAUSE,
    CANCEL,
}

internal class SteamDownloadCancelledException : Exception("Steam download was cancelled")

internal data class SteamDownloadProgress(
    val phase: SteamDownloadPhase,
    val currentFile: String? = null,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
)

internal data class SteamContentDownloadResult(
    val rootDirectory: File,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val fileCount: Int,
    val usedAuthenticatedSession: Boolean,
)

internal fun resolveCdnRequestHost(
    virtualHost: String?,
    host: String?,
): String? =
    virtualHost?.trim()?.takeIf(String::isNotBlank)
        ?: host?.trim()?.takeIf(String::isNotBlank)

internal fun normalizeCdnAuthToken(token: String): String = token.trim().removePrefix("?")

internal fun orderChunksByOffset(chunks: List<DepotChunkSpec>): List<DepotChunkSpec> = chunks.sortedBy { it.offset }

internal enum class SteamStreamChunkPriority {
    FOREGROUND,
    PREFETCH,
}

/**
 * Shares the exact user-configured Steam CDN connection budget. Prefetch may borrow every idle
 * permit, while released permits always wake foreground reads before additional prefetch work.
 */
internal class ForegroundFirstPermitPool(
    maxPermits: Int,
) {
    private val maxPermits = maxPermits.coerceAtLeast(1)
    private val mutex = Mutex()
    private val foregroundWaiters = ArrayDeque<PermitWaiter>()
    private val prefetchWaiters = ArrayDeque<PermitWaiter>()
    private val promotedRequests = mutableSetOf<Long>()
    private val activeRequests = linkedMapOf<Long, SteamStreamChunkPriority>()
    private var availablePermits = this.maxPermits

    suspend fun <T> withPermit(
        priority: SteamStreamChunkPriority,
        requestId: Long? = null,
        block: suspend () -> T,
    ): T {
        acquire(priority, requestId)
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                release(requestId)
                requestId?.let { id -> mutex.withLock { promotedRequests.remove(id) } }
            }
        }
    }

    suspend fun promote(requestId: Long) {
        mutex.withLock {
            promotedRequests += requestId
            if (activeRequests[requestId] == SteamStreamChunkPriority.PREFETCH) {
                activeRequests[requestId] = SteamStreamChunkPriority.FOREGROUND
            }
            val waiter = prefetchWaiters.firstOrNull { it.requestId == requestId } ?: return@withLock
            prefetchWaiters.remove(waiter)
            foregroundWaiters.addLast(waiter)
            dispatchWaiterIfPossible()
        }
    }

    /** Returns an active speculative request that a blocked foreground read may preempt. */
    suspend fun activePrefetchRequestId(): Long? =
        mutex.withLock {
            activeRequests.entries.firstOrNull { it.value == SteamStreamChunkPriority.PREFETCH }?.key
        }

    private suspend fun acquire(
        priority: SteamStreamChunkPriority,
        requestId: Long?,
    ) {
        val waiter =
            mutex.withLock {
                val effectivePriority =
                    if (requestId != null && requestId in promotedRequests) {
                        SteamStreamChunkPriority.FOREGROUND
                    } else {
                        priority
                    }
                val canAcquire =
                    when (effectivePriority) {
                        SteamStreamChunkPriority.FOREGROUND -> availablePermits > 0
                        SteamStreamChunkPriority.PREFETCH ->
                            foregroundWaiters.isEmpty() && availablePermits > 0
                    }
                if (canAcquire) {
                    availablePermits -= 1
                    requestId?.let { id -> activeRequests[id] = effectivePriority }
                    null
                } else {
                    PermitWaiter(requestId, effectivePriority, CompletableDeferred()).also { queued ->
                        when (effectivePriority) {
                            SteamStreamChunkPriority.FOREGROUND -> foregroundWaiters.addLast(queued)
                            SteamStreamChunkPriority.PREFETCH -> prefetchWaiters.addLast(queued)
                        }
                    }
                }
            }
        if (waiter == null) return
        try {
            waiter.signal.await()
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                val removedFromQueue =
                    mutex.withLock {
                        foregroundWaiters.remove(waiter) || prefetchWaiters.remove(waiter)
                    }
                if (!removedFromQueue) release(requestId)
            }
            throw error
        }
    }

    private suspend fun release(requestId: Long?) {
        mutex.withLock {
            requestId?.let(activeRequests::remove)
            availablePermits = min(maxPermits, availablePermits + 1)
            dispatchWaiterIfPossible()
        }
    }

    private fun dispatchWaiterIfPossible() {
        while (availablePermits > 0) {
            val foreground = foregroundWaiters.pollFirst()
            if (foreground != null) {
                if (foreground.signal.complete(Unit)) {
                    availablePermits -= 1
                    foreground.requestId?.let { id -> activeRequests[id] = SteamStreamChunkPriority.FOREGROUND }
                    return
                }
                continue
            }
            val prefetch = prefetchWaiters.pollFirst() ?: return
            if (prefetch.signal.complete(Unit)) {
                availablePermits -= 1
                prefetch.requestId?.let { id -> activeRequests[id] = prefetch.priority }
                return
            }
        }
    }

    private data class PermitWaiter(
        val requestId: Long?,
        val priority: SteamStreamChunkPriority,
        val signal: CompletableDeferred<Unit>,
    )
}

internal fun findVerifiedChunkOffsets(
    file: File,
    chunks: List<DepotChunkSpec>,
): Set<Long> {
    if (!file.isFile) return emptySet()
    val verifiedOffsets = mutableSetOf<Long>()
    var buffer = ByteArray(0)
    RandomAccessFile(file, "r").use { input ->
        val fileLength = input.length()
        chunks.forEach { chunk ->
            val length = chunk.uncompressedLength
            val fits =
                chunk.offset >= 0L &&
                    length >= 0 &&
                    chunk.offset <= fileLength - length.toLong()
            if (!fits) return@forEach
            if (buffer.size != length) buffer = ByteArray(length)
            input.seek(chunk.offset)
            input.readFully(buffer, 0, length)
            if (steamAdler32(buffer) == chunk.checksum) {
                verifiedOffsets += chunk.offset
            }
        }
    }
    return verifiedOffsets
}

internal class StreamChunkRequest(
    val id: Long,
    // Pure prefetch completes with null after the bytes are committed to disk,
    // so completed requests never retain decoded chunk arrays.
    val deferred: Deferred<ByteArray?>,
    val job: Job,
    private val priorityState: AtomicReference<SteamStreamChunkPriority>,
) {
    val priority: SteamStreamChunkPriority
        get() = priorityState.get()

    fun promote() {
        priorityState.set(SteamStreamChunkPriority.FOREGROUND)
    }
}

@Singleton
internal class SteamContentDownloader
    @Inject
    constructor(
        private val sessionRepository: KSteamSessionRepository,
        private val depotDownloader: DepotDownloader,
        private val steamHttpClientFactory: SteamHttpClientFactory,
    ) {
    suspend fun download(
        target: WorkshopContentTarget,
        destinationDirectory: File,
        credential: SteamContentCredential?,
        options: SteamContentDownloadOptions = SteamContentDownloadOptions(),
        control: suspend () -> SteamDownloadControl = { SteamDownloadControl.CONTINUE },
        onProgress: suspend (SteamDownloadProgress) -> Unit,
    ): SteamContentDownloadResult =
        withContext(Dispatchers.IO) {
            require(target.appId > 0) { "Invalid Steam App ID" }
            require(target.contentManifestId > 0L) { "Invalid Steam manifest ID" }
            checkDownloadControl(control)
            val normalizedOptions = options.normalized()
            var publishedBytes = 0L
            var publishedFiles = 0
            val publishProgress: suspend (SteamDownloadProgress) -> Unit = { progress ->
                if (progress.phase == SteamDownloadPhase.DOWNLOADING) {
                    publishedBytes = maxOf(publishedBytes, progress.completedBytes)
                    publishedFiles = maxOf(publishedFiles, progress.completedFiles)
                    onProgress(
                        progress.copy(
                            completedBytes = publishedBytes,
                            completedFiles = publishedFiles,
                        ),
                    )
                } else {
                    onProgress(progress)
                }
            }
            withSteamCdnRecovery(
                onRetry = { attempt, error ->
                    Log.w(
                        STEAM_CONTENT_LOG_TAG,
                        "Recoverable Steam CDN failure; rebuilding session after attempt $attempt, " +
                            "type=${error.cause?.javaClass?.name ?: error.javaClass.name}",
                    )
                },
            ) {
                downloadOnce(
                    target = target,
                    destinationDirectory = destinationDirectory,
                    credential = credential,
                    options = normalizedOptions,
                    control = control,
                    onProgress = publishProgress,
                )
            }
        }

    private suspend fun downloadOnce(
        target: WorkshopContentTarget,
        destinationDirectory: File,
        credential: SteamContentCredential?,
        options: SteamContentDownloadOptions,
        control: suspend () -> SteamDownloadControl,
        onProgress: suspend (SteamDownloadProgress) -> Unit,
    ): SteamContentDownloadResult {
        onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.CONNECTING))
        val session =
            openContentSession(sessionRepository, credential) {
                onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.AUTHENTICATING))
            }
        val httpClient = createCdnHttpClient(options, steamHttpClientFactory)
        try {
            checkDownloadControl(control)
            onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.RESOLVING))
            val access = resolveContentAccess(session, target)
            val selector = CdnServerSelector()
            Log.i(
                STEAM_CONTENT_LOG_TAG,
                "Steam CDN chunkConcurrency=${options.chunkConcurrency}, " +
                    "pool=${access.servers.take(MAX_CDN_ATTEMPTS).joinToString { server ->
                        resolveCdnRequestHost(server.vHost, server.host) ?: "unknown"
                    }}",
            )
            val manifest =
                downloadManifest(
                    httpClient = httpClient,
                    servers = access.servers,
                    proxyServer = access.proxyServer,
                    depotId = target.depotId,
                    manifestId = target.contentManifestId,
                    requestCode = access.manifestRequestCode,
                    depotKey = access.depotKey,
                    authTokens = access.authTokens,
                    control = control,
                )
            check(manifest.files.size <= MAX_MANIFEST_FILE_COUNT) {
                "Steam manifest file count ${manifest.files.size} exceeds limit $MAX_MANIFEST_FILE_COUNT"
            }

            val files = manifest.files.filterNot { it.flags.contains(DepotFileFlag.Directory) }
            Log.i(
                STEAM_CONTENT_LOG_TAG,
                "Steam manifest files=${manifest.files.size}, downloadable=${files.size}, " +
                    "totalUncompressed=${manifest.totalUncompressedSize}",
            )
            check(files.isNotEmpty()) { "Steam returned an empty Workshop content manifest" }
            val filePlans =
                manifest.files.mapNotNull { manifestFile ->
                    currentCoroutineContext().ensureActive()
                    checkDownloadControl(control)
                    if (
                        manifestFile.flags.contains(DepotFileFlag.Directory) &&
                        (manifestFile.fileName.isBlank() || manifestFile.fileName == ".")
                    ) {
                        return@mapNotNull null
                    }
                    WorkshopStagingPath.resolve(destinationDirectory, manifestFile.fileName)
                    if (manifestFile.flags.contains(DepotFileFlag.Directory)) return@mapNotNull null
                    check(!manifestFile.flags.contains(DepotFileFlag.Symlink)) {
                        "Steam manifest symbolic links are not supported: ${manifestFile.fileName}"
                    }
                    ManifestFilePlan(
                        file = manifestFile,
                        chunks = orderChunksByOffset(manifestFile.chunks),
                    )
                }
            val totalBytes = validateManifestFilePlans(filePlans)
            destinationDirectory.mkdirs()
            check(destinationDirectory.isDirectory) { "Failed to create download staging directory" }
            val progressReporter =
                DownloadProgressReporter(
                    totalBytes = totalBytes,
                    totalFiles = files.size,
                    onProgress = onProgress,
                )
            manifest.files.filter { it.flags.contains(DepotFileFlag.Directory) }.forEach { directory ->
                if (directory.fileName.isBlank() || directory.fileName == ".") return@forEach
                val destination = WorkshopStagingPath.resolve(destinationDirectory, directory.fileName)
                destination.mkdirs()
                check(destination.isDirectory) { "Failed to create directory: ${directory.fileName}" }
            }
            downloadFilePlans(
                plans = filePlans,
                destinationDirectory = destinationDirectory,
                httpClient = httpClient,
                servers = access.servers,
                proxyServer = access.proxyServer,
                depotId = target.depotId,
                depotKey = access.depotKey,
                authTokens = access.authTokens,
                selector = selector,
                depotDownloader = depotDownloader,
                chunkConcurrency = options.chunkConcurrency,
                control = control,
                progressReporter = progressReporter,
            )
            val finalProgress = progressReporter.snapshot()
            return SteamContentDownloadResult(
                rootDirectory = destinationDirectory,
                downloadedBytes = finalProgress.downloadedBytes,
                totalBytes = totalBytes,
                fileCount = finalProgress.completedFiles,
                usedAuthenticatedSession = credential != null,
            )
        } finally {
            runCatching { httpClient.dispatcher.executorService.shutdown() }
        }
    }

    suspend fun openVideoStream(
        target: WorkshopContentTarget,
        credential: SteamContentCredential?,
        options: SteamContentDownloadOptions,
        cacheRootDirectory: File,
        cacheLimitBytes: Long,
    ): SteamContentVideoStream =
        withContext(Dispatchers.IO) {
            require(target.appId > 0) { "Invalid Steam App ID" }
            require(target.contentManifestId > 0L) { "Invalid Steam manifest ID" }
            val normalizedOptions = options.normalized()
            val session = openContentSession(sessionRepository, credential) {}
            val httpClient = createCdnHttpClient(normalizedOptions, steamHttpClientFactory)
            try {
                val access = resolveContentAccess(session, target)
                val manifest =
                    downloadManifest(
                        httpClient = httpClient,
                        servers = access.servers,
                        proxyServer = access.proxyServer,
                        depotId = target.depotId,
                        manifestId = target.contentManifestId,
                        requestCode = access.manifestRequestCode,
                        depotKey = access.depotKey,
                        authTokens = access.authTokens,
                        control = { SteamDownloadControl.CONTINUE },
                    )
                check(manifest.files.size <= MAX_MANIFEST_FILE_COUNT) {
                    "Steam manifest file count ${manifest.files.size} exceeds limit $MAX_MANIFEST_FILE_COUNT"
                }
                validateManifestFiles(manifest.files.filterNot { it.flags.contains(DepotFileFlag.Directory) })
                val videoFile =
                    manifest.files
                        .asSequence()
                        .filterNot { it.flags.contains(DepotFileFlag.Directory) }
                        .filterNot { it.flags.contains(DepotFileFlag.Symlink) }
                        .filter { it.fileName.videoFileExtension() in VIDEO_FILE_EXTENSIONS }
                        .maxByOrNull(DepotFileSpec::totalSize)
                        ?: error("No streamable video file found in the Steam depot")
                val orderedVideoChunks = orderChunksByOffset(videoFile.chunks)
                cacheRootDirectory.mkdirs()
                check(cacheRootDirectory.isDirectory) { "Failed to create streaming cache root directory" }
                SteamContentVideoStream(
                    title = target.title,
                    fileName = videoFile.fileName,
                    contentLength = videoFile.totalSize,
                    chunks = orderedVideoChunks,
                    streamCache =
                        SteamVideoStreamCache(
                            rootDirectory = cacheRootDirectory,
                            namespace = "${target.publishedFileId}-${target.contentManifestId}",
                            limitBytes = cacheLimitBytes.coerceAtLeast(STREAM_MIN_CACHE_LIMIT_BYTES),
                        ),
                    prefetchConcurrency = steamStreamPrefetchConcurrency(normalizedOptions.chunkConcurrency),
                    httpClient = httpClient,
                    session = session,
                    access = access,
                    depotId = target.depotId,
                    depotDownloader = depotDownloader,
                )
            } catch (error: Throwable) {
                runCatching { httpClient.dispatcher.executorService.shutdown() }
                throw error
            }
        }
}
