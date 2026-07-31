package com.wallhub.android.data.downloads

import android.util.Log
import com.wallhub.android.core.model.SteamContentCredential
import `in`.dragonbra.javasteam.enums.EDepotFileFlag
import `in`.dragonbra.javasteam.types.ChunkData
import `in`.dragonbra.javasteam.types.FileData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque
import kotlin.math.min
import `in`.dragonbra.javasteam.util.Adler32 as SteamAdler32

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

internal class SteamDownloadCancelledException : Exception("Steam 下载已取消")

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

internal fun orderChunksByOffset(chunks: List<ChunkData>): List<ChunkData> = chunks.sortedBy { it.offset }

internal enum class SteamStreamChunkPriority {
    FOREGROUND,
    PREFETCH,
}

/**
 * Shares the Steam CDN connection budget without allowing cache warm-up to stall an active
 * player read. One connection is always reserved for foreground work and released permits wake
 * foreground waiters before cache prefetch waiters.
 */
internal class ForegroundFirstPermitPool(
    maxPermits: Int,
) {
    private val maxPermits = maxPermits.coerceAtLeast(1)
    private val reservedForegroundPermits = 1.coerceAtMost(this.maxPermits)
    private val mutex = Mutex()
    private val foregroundWaiters = ArrayDeque<CompletableDeferred<Unit>>()
    private val prefetchWaiters = ArrayDeque<CompletableDeferred<Unit>>()
    private var availablePermits = this.maxPermits

    suspend fun <T> withPermit(
        priority: SteamStreamChunkPriority,
        block: suspend () -> T,
    ): T {
        acquire(priority)
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(priority: SteamStreamChunkPriority) {
        val waiter =
            mutex.withLock {
                val canAcquire =
                    when (priority) {
                        SteamStreamChunkPriority.FOREGROUND -> availablePermits > 0
                        SteamStreamChunkPriority.PREFETCH ->
                            foregroundWaiters.isEmpty() && availablePermits > reservedForegroundPermits
                    }
                if (canAcquire) {
                    availablePermits -= 1
                    null
                } else {
                    CompletableDeferred<Unit>().also { deferred ->
                        when (priority) {
                            SteamStreamChunkPriority.FOREGROUND -> foregroundWaiters.addLast(deferred)
                            SteamStreamChunkPriority.PREFETCH -> prefetchWaiters.addLast(deferred)
                        }
                    }
                }
            }
        if (waiter == null) return
        try {
            waiter.await()
        } catch (error: CancellationException) {
            val removedFromQueue =
                mutex.withLock {
                    foregroundWaiters.remove(waiter) || prefetchWaiters.remove(waiter)
                }
            if (!removedFromQueue) release()
            throw error
        }
    }

    private suspend fun release() {
        val waiter =
            mutex.withLock {
                foregroundWaiters.pollFirst()
                    ?: prefetchWaiters.pollFirst()
                    ?: run {
                        availablePermits = min(maxPermits, availablePermits + 1)
                        null
                    }
            }
        waiter?.complete(Unit)
    }
}

internal fun findVerifiedChunkOffsets(
    file: File,
    chunks: List<ChunkData>,
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
            if (SteamAdler32.calculate(buffer) == chunk.checksum) {
                verifiedOffsets += chunk.offset
            }
        }
    }
    return verifiedOffsets
}

internal data class StreamChunkRequest(
    val id: Long,
    val priority: SteamStreamChunkPriority,
    val deferred: Deferred<ByteArray>,
)

internal class SteamContentDownloader {
    suspend fun download(
        target: WorkshopContentTarget,
        destinationDirectory: File,
        credential: SteamContentCredential?,
        options: SteamContentDownloadOptions = SteamContentDownloadOptions(),
        control: suspend () -> SteamDownloadControl = { SteamDownloadControl.CONTINUE },
        onProgress: suspend (SteamDownloadProgress) -> Unit,
    ): SteamContentDownloadResult =
        withContext(Dispatchers.IO) {
            require(target.appId > 0) { "无效的 Steam App ID" }
            require(target.contentManifestId > 0L) { "无效的 Steam manifest ID" }
            checkDownloadControl(control)

            onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.CONNECTING))
            val session =
                openContentSession(credential) {
                    onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.AUTHENTICATING))
                }
            val normalizedOptions = options.normalized()
            val cdnClient = createCdnClient(normalizedOptions)
            try {
                checkDownloadControl(control)
                onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.RESOLVING))
                val access = resolveContentAccess(session, target)
                val selector = CdnServerSelector()
                Log.i(
                    STEAM_CONTENT_LOG_TAG,
                    "Steam CDN chunkConcurrency=${normalizedOptions.chunkConcurrency}, " +
                        "pool=${access.servers.take(CDN_PARALLEL_SERVER_COUNT).joinToString { server ->
                            resolveCdnRequestHost(server.vHost, server.host) ?: "unknown"
                        }}",
                )
                val manifest =
                    downloadManifest(
                        cdnClient = cdnClient,
                        servers = access.servers,
                        depotId = target.depotId,
                        manifestId = target.contentManifestId,
                        requestCode = access.manifestRequestCode,
                        depotKey = access.depotKey,
                        authTokens = access.authTokens,
                        control = control,
                    )
                check(!manifest.filenamesEncrypted || manifest.decryptFilenames(access.depotKey)) {
                    "无法解密 Steam manifest 中的文件名"
                }

                destinationDirectory.mkdirs()
                check(destinationDirectory.isDirectory) { "无法创建下载暂存目录" }
                val files = manifest.files.filterNot { it.flags.contains(EDepotFileFlag.Directory) }
                val totalBytes =
                    manifest.totalUncompressedSize.takeIf { it > 0L }
                        ?: files.sumOf { it.totalSize.coerceAtLeast(0L) }
                val progressReporter =
                    DownloadProgressReporter(
                        totalBytes = totalBytes,
                        totalFiles = files.size,
                        onProgress = onProgress,
                    )
                val filePlans =
                    manifest.files.mapNotNull { manifestFile ->
                        currentCoroutineContext().ensureActive()
                        checkDownloadControl(control)
                        if (
                            manifestFile.flags.contains(EDepotFileFlag.Directory) &&
                            (manifestFile.fileName.isBlank() || manifestFile.fileName == ".")
                        ) {
                            return@mapNotNull null
                        }
                        val destination = WorkshopStagingPath.resolve(destinationDirectory, manifestFile.fileName)
                        if (manifestFile.flags.contains(EDepotFileFlag.Directory)) {
                            destination.mkdirs()
                            check(destination.isDirectory) { "无法创建目录：${manifestFile.fileName}" }
                            return@mapNotNull null
                        }
                        check(!manifestFile.flags.contains(EDepotFileFlag.Symlink)) {
                            "当前不处理 Steam manifest 符号链接：${manifestFile.fileName}"
                        }
                        ManifestFilePlan(
                            file = manifestFile,
                            chunks = orderChunksByOffset(manifestFile.chunks),
                        )
                    }
                downloadFilePlans(
                    plans = filePlans,
                    destinationDirectory = destinationDirectory,
                    cdnClient = cdnClient,
                    servers = access.servers,
                    depotId = target.depotId,
                    depotKey = access.depotKey,
                    authTokens = access.authTokens,
                    selector = selector,
                    chunkConcurrency = normalizedOptions.chunkConcurrency,
                    control = control,
                    progressReporter = progressReporter,
                )
                val finalProgress = progressReporter.snapshot()

                SteamContentDownloadResult(
                    rootDirectory = destinationDirectory,
                    downloadedBytes = finalProgress.downloadedBytes,
                    totalBytes = totalBytes,
                    fileCount = finalProgress.completedFiles,
                    usedAuthenticatedSession = credential != null,
                )
            } finally {
                runCatching { cdnClient.close() }
                session.close()
            }
        }

    suspend fun openVideoStream(
        target: WorkshopContentTarget,
        credential: SteamContentCredential?,
        options: SteamContentDownloadOptions,
        cacheDirectory: File,
        cacheLimitBytes: Long,
    ): SteamContentVideoStream =
        withContext(Dispatchers.IO) {
            require(target.appId > 0) { "无效的 Steam App ID" }
            require(target.contentManifestId > 0L) { "无效的 Steam manifest ID" }
            val normalizedOptions = options.normalized()
            val session = openContentSession(credential) {}
            val cdnClient = createCdnClient(normalizedOptions)
            try {
                val access = resolveContentAccess(session, target)
                val manifest =
                    downloadManifest(
                        cdnClient = cdnClient,
                        servers = access.servers,
                        depotId = target.depotId,
                        manifestId = target.contentManifestId,
                        requestCode = access.manifestRequestCode,
                        depotKey = access.depotKey,
                        authTokens = access.authTokens,
                        control = { SteamDownloadControl.CONTINUE },
                    )
                check(!manifest.filenamesEncrypted || manifest.decryptFilenames(access.depotKey)) {
                    "无法解密 Steam manifest 中的视频文件名"
                }
                val videoFile =
                    manifest.files
                        .asSequence()
                        .filterNot { it.flags.contains(EDepotFileFlag.Directory) }
                        .filterNot { it.flags.contains(EDepotFileFlag.Symlink) }
                        .filter { it.fileName.videoFileExtension() in VIDEO_FILE_EXTENSIONS }
                        .maxByOrNull(FileData::totalSize)
                        ?: error("未在 Steam Depot 中找到可在线播放的视频文件")
                cacheDirectory.mkdirs()
                check(cacheDirectory.isDirectory) { "无法创建在线播放缓存目录" }
                SteamContentVideoStream(
                    title = target.title,
                    fileName = videoFile.fileName,
                    contentLength = videoFile.totalSize,
                    chunks = orderChunksByOffset(videoFile.chunks),
                    cacheDirectory = cacheDirectory,
                    cacheLimitBytes = cacheLimitBytes.coerceAtLeast(STREAM_MIN_CACHE_LIMIT_BYTES),
                    prefetchConcurrency = steamStreamPrefetchConcurrency(normalizedOptions.chunkConcurrency),
                    cdnClient = cdnClient,
                    session = session,
                    access = access,
                    depotId = target.depotId,
                )
            } catch (error: Throwable) {
                runCatching { cdnClient.close() }
                session.close()
                throw error
            }
        }
}
