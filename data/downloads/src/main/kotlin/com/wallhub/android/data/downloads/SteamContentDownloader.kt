package com.wallhub.android.data.downloads

import android.util.Log
import `in`.dragonbra.javasteam.enums.EDepotFileFlag
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.cdn.Client as SteamCdnClient
import `in`.dragonbra.javasteam.steam.cdn.DepotChunk
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import `in`.dragonbra.javasteam.steam.handlers.steamuser.AnonymousLogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.ChatMode
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import `in`.dragonbra.javasteam.types.ChunkData
import `in`.dragonbra.javasteam.types.DepotManifest
import `in`.dragonbra.javasteam.types.FileData
import `in`.dragonbra.javasteam.util.Adler32 as SteamAdler32
import com.wallhub.android.core.model.SteamContentCredential
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Dispatcher
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import kotlin.math.max
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

internal fun resolveCdnRequestHost(virtualHost: String?, host: String?): String? =
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
        val waiter = mutex.withLock {
            val canAcquire = when (priority) {
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
            val removedFromQueue = mutex.withLock {
                foregroundWaiters.remove(waiter) || prefetchWaiters.remove(waiter)
            }
            if (!removedFromQueue) release()
            throw error
        }
    }

    private suspend fun release() {
        val waiter = mutex.withLock {
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

internal fun findVerifiedChunkOffsets(file: File, chunks: List<ChunkData>): Set<Long> {
    if (!file.isFile) return emptySet()
    val verifiedOffsets = mutableSetOf<Long>()
    var buffer = ByteArray(0)
    RandomAccessFile(file, "r").use { input ->
        val fileLength = input.length()
        chunks.forEach { chunk ->
            val length = chunk.uncompressedLength
            val fits = chunk.offset >= 0L &&
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

private data class StreamChunkRequest(
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
    ): SteamContentDownloadResult = withContext(Dispatchers.IO) {
        require(target.appId > 0) { "无效的 Steam App ID" }
        require(target.contentManifestId > 0L) { "无效的 Steam manifest ID" }
        checkDownloadControl(control)

        onProgress(SteamDownloadProgress(phase = SteamDownloadPhase.CONNECTING))
        val session = openContentSession(credential) {
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
                DOWNLOAD_LOG_TAG,
                "Steam CDN chunkConcurrency=${normalizedOptions.chunkConcurrency}, " +
                    "pool=${access.servers.take(CDN_PARALLEL_SERVER_COUNT).joinToString { server ->
                        resolveCdnRequestHost(server.vHost, server.host) ?: "unknown"
                    }}",
            )
            val manifest = downloadManifest(
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
            val totalBytes = manifest.totalUncompressedSize.takeIf { it > 0L }
                ?: files.sumOf { it.totalSize.coerceAtLeast(0L) }
            val progressReporter = DownloadProgressReporter(
                totalBytes = totalBytes,
                totalFiles = files.size,
                onProgress = onProgress,
            )
            val filePlans = manifest.files.mapNotNull { manifestFile ->
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
    ): SteamContentVideoStream = withContext(Dispatchers.IO) {
        require(target.appId > 0) { "无效的 Steam App ID" }
        require(target.contentManifestId > 0L) { "无效的 Steam manifest ID" }
        val normalizedOptions = options.normalized()
        val session = openContentSession(credential) {}
        val cdnClient = createCdnClient(normalizedOptions)
        try {
            val access = resolveContentAccess(session, target)
            val manifest = downloadManifest(
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
            val videoFile = manifest.files
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

    private suspend fun resolveContentAccess(
        session: SteamContentSession,
        target: WorkshopContentTarget,
    ): SteamContentAccess {
        val depotKey = session.apps.getDepotDecryptionKey(target.depotId, target.appId).await()
        check(depotKey.result == EResult.OK) { "Steam 未提供 depot key：${depotKey.result}" }
        val servers = session.content
            .getServersForSteamPipe(null, CDN_SERVER_LIMIT, session.callbackScope)
            .await()
            .filter { resolveCdnRequestHost(it.vHost, it.host) != null }
            .let(::prioritizeCdnServers)
        check(servers.isNotEmpty()) { "Steam 未返回可用的 CDN 服务器" }
        val requestCode = session.content
            .getManifestRequestCode(
                target.depotId,
                target.appId,
                target.contentManifestId,
                "public",
                null,
                session.callbackScope,
            )
            .await()
        return SteamContentAccess(
            depotKey = depotKey.depotKey,
            manifestRequestCode = requestCode,
            servers = servers,
            authTokens = CdnAuthTokenProvider(
                content = session.content,
                appId = target.appId,
                depotId = target.depotId,
                callbackScope = session.callbackScope,
                enabled = session.isAuthenticated,
            ),
        )
    }

    private fun prioritizeCdnServers(servers: List<Server>): List<Server> {
        val ranked = servers.sortedBy { it.weightedLoad }
        val secure = ranked.filter { it.protocol == Server.ConnectionProtocol.HTTPS }
        return secure + ranked.filterNot { it.protocol == Server.ConnectionProtocol.HTTPS }
    }

    internal inner class SteamContentVideoStream internal constructor(
        override val title: String,
        override val fileName: String,
        override val contentLength: Long,
        private val chunks: List<ChunkData>,
        private val cacheDirectory: File,
        private val cacheLimitBytes: Long,
        private val prefetchConcurrency: Int,
        private val cdnClient: SteamCdnClient,
        private val session: SteamContentSession,
        private val access: SteamContentAccess,
        private val depotId: Int,
    ) : com.wallhub.android.core.model.WorkshopVideoStreamSession {
        private val selector = CdnServerSelector()
        private val cacheMutex = Mutex()
        private val networkScheduler = ForegroundFirstPermitPool(prefetchConcurrency)
        private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val inFlightLock = Any()
        private val inFlightChunks = mutableMapOf<Long, StreamChunkRequest>()
        private val nextChunkRequestId = AtomicLong(0L)
        private val prefetchLock = Any()
        private val prefetchGeneration = AtomicLong(0L)
        private val lastReadEnd = AtomicLong(-1L)

        @Volatile
        private var latestCdnHost: String? = access.servers.firstNotNullOfOrNull { server ->
            resolveCdnRequestHost(server.vHost, server.host)
        }

        override val currentCdnHost: String?
            get() = latestCdnHost

        @Volatile
        private var startupPrefetchJob: Job? = null

        @Volatile
        private var aheadPrefetchJob: Job? = null

        private var furthestContiguousPrefetchEnd = -1L
        private var cachedBytes = -1L

        @Volatile
        private var closed = false

        init {
            scheduleStartupPrefetch(0L)
        }

        override suspend fun readAt(position: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
            check(!closed) { "SteamKit 在线播放会话已关闭" }
            require(position >= 0L) { "在线播放读取位置无效" }
            require(length >= 0) { "在线播放读取长度无效" }
            if (length == 0 || position >= contentLength) return@withContext ByteArray(0)

            val requestedLength = min(length.toLong(), contentLength - position).toInt()
            val endExclusive = position + requestedLength
            resetPrefetchForSeekIfNeeded(position)
            val result = ByteArray(requestedLength)
            var destinationOffset = 0
            chunks.forEach { chunk ->
                val chunkStart = chunk.offset
                val chunkEnd = chunkStart + chunk.uncompressedLength
                if (chunkEnd <= position || chunkStart >= endExclusive) return@forEach
                val data = loadChunk(chunk, SteamStreamChunkPriority.FOREGROUND)
                val sourceStart = max(position, chunkStart) - chunkStart
                val sourceEnd = min(endExclusive, chunkEnd) - chunkStart
                val copiedLength = (sourceEnd - sourceStart).toInt()
                data.copyInto(
                    destination = result,
                    destinationOffset = destinationOffset,
                    startIndex = sourceStart.toInt(),
                    endIndex = sourceEnd.toInt(),
                )
                destinationOffset += copiedLength
            }
            check(destinationOffset == requestedLength) { "Steam 视频分块存在缺失数据" }
            lastReadEnd.set(endExclusive - 1L)
            scheduleAheadPrefetch(endExclusive)
            result
        }

        override fun close() {
            if (closed) return
            closed = true
            synchronized(prefetchLock) {
                startupPrefetchJob?.cancel()
                aheadPrefetchJob?.cancel()
                startupPrefetchJob = null
                aheadPrefetchJob = null
            }
            fetchScope.cancel()
            runCatching { cdnClient.close() }
            session.close()
        }

        private suspend fun loadChunk(
            chunk: ChunkData,
            priority: SteamStreamChunkPriority,
        ): ByteArray {
            val cacheFile = File(cacheDirectory, "${chunk.offset}.chunk")
            readCachedChunk(cacheFile, chunk)?.let { return it }
            return requestChunk(chunk, priority).await()
        }

        private fun requestChunk(
            chunk: ChunkData,
            priority: SteamStreamChunkPriority,
        ): Deferred<ByteArray> = synchronized(inFlightLock) {
            val existing = inFlightChunks[chunk.offset]
            if (existing != null &&
                (priority == SteamStreamChunkPriority.PREFETCH ||
                    existing.priority == SteamStreamChunkPriority.FOREGROUND)
            ) {
                return@synchronized existing.deferred
            }
            existing?.deferred?.cancel()
            val requestId = nextChunkRequestId.incrementAndGet()
            val request = fetchScope.async(start = CoroutineStart.LAZY) {
                try {
                    val cacheFile = File(cacheDirectory, "${chunk.offset}.chunk")
                    readCachedChunk(cacheFile, chunk)?.let { return@async it }
                    val downloaded = networkScheduler.withPermit(priority) {
                        downloadChunk(
                            cdnClient = cdnClient,
                            servers = access.servers,
                            depotId = depotId,
                            chunk = chunk,
                            depotKey = access.depotKey,
                            authTokens = access.authTokens,
                            selector = selector,
                            control = { SteamDownloadControl.CONTINUE },
                            onSuccess = { server ->
                                latestCdnHost = resolveCdnRequestHost(server.vHost, server.host)
                            },
                        )
                    }
                    cacheMutex.withLock {
                        readCachedChunk(cacheFile, chunk) ?: downloaded.also {
                            writeCachedChunk(cacheFile, it)
                            evictCachedChunksIfNeeded()
                        }
                    }
                } finally {
                    synchronized(inFlightLock) {
                        if (inFlightChunks[chunk.offset]?.id == requestId) {
                            inFlightChunks.remove(chunk.offset)
                        }
                    }
                }
            }
            inFlightChunks[chunk.offset] = StreamChunkRequest(
                id = requestId,
                priority = priority,
                deferred = request,
            )
            request.start()
            request
        }

        private fun scheduleStartupPrefetch(position: Long) {
            if (closed || contentLength <= 0L) return
            val generation = prefetchGeneration.get()
            val startupRanges = steamStreamStartupPrefetchRanges(contentLength, position)
            synchronized(prefetchLock) {
                startupPrefetchJob?.cancel()
                furthestContiguousPrefetchEnd = maxOf(
                    furthestContiguousPrefetchEnd,
                    steamStreamContiguousPrefetchEnd(position, startupRanges),
                )
                startupPrefetchJob = fetchScope.launch {
                    startupRanges.forEach { range ->
                        if (closed || generation != prefetchGeneration.get()) return@launch
                        prefetchRange(range, generation)
                    }
                }
            }
        }

        private fun scheduleAheadPrefetch(afterExclusive: Long) {
            if (closed || afterExclusive >= contentLength) return
            val generation = prefetchGeneration.get()
            val range = synchronized(prefetchLock) {
                if (afterExclusive <= furthestContiguousPrefetchEnd) return
                steamStreamAheadPrefetchRange(contentLength, afterExclusive)?.also {
                    furthestContiguousPrefetchEnd = maxOf(furthestContiguousPrefetchEnd, it.endInclusive)
                }
            } ?: return
            synchronized(prefetchLock) {
                aheadPrefetchJob = fetchScope.launch {
                    prefetchRange(range, generation)
                }
            }
        }

        private fun resetPrefetchForSeekIfNeeded(position: Long) {
            val previousEnd = lastReadEnd.get()
            if (previousEnd < 0L ||
                (position in (previousEnd + 1L)..(previousEnd + STREAM_SEEK_RESET_BYTES))
            ) {
                return
            }
            prefetchGeneration.incrementAndGet()
            synchronized(prefetchLock) {
                startupPrefetchJob?.cancel()
                aheadPrefetchJob?.cancel()
                startupPrefetchJob = null
                aheadPrefetchJob = null
                furthestContiguousPrefetchEnd = position - 1L
            }
            cancelPrefetchRequests()
            scheduleStartupPrefetch(position)
        }

        private suspend fun prefetchRange(range: SteamStreamByteRange, generation: Long) = coroutineScope {
            val selectedChunks = chunks.filter { chunk ->
                val chunkEnd = chunk.offset + chunk.uncompressedLength - 1L
                chunkEnd >= range.start && chunk.offset <= range.endInclusive
            }
            if (selectedChunks.isEmpty()) return@coroutineScope
            val queue = Channel<ChunkData>(prefetchConcurrency)
            launch {
                try {
                    selectedChunks.forEach { chunk ->
                        if (closed || generation != prefetchGeneration.get()) return@launch
                        queue.send(chunk)
                    }
                } finally {
                    queue.close()
                }
            }
            repeat(min(prefetchConcurrency, selectedChunks.size)) {
                launch {
                    for (chunk in queue) {
                        if (closed || generation != prefetchGeneration.get()) break
                        runCatching { loadChunk(chunk, SteamStreamChunkPriority.PREFETCH) }.getOrElse { error ->
                            if (error is CancellationException) throw error
                            ByteArray(0)
                        }
                    }
                }
            }
        }

        private fun cancelPrefetchRequests() {
            val staleRequests = synchronized(inFlightLock) {
                inFlightChunks
                    .filterValues { request -> request.priority == SteamStreamChunkPriority.PREFETCH }
                    .keys
                    .mapNotNull(inFlightChunks::remove)
            }
            staleRequests.forEach { request -> request.deferred.cancel() }
        }

        private fun readCachedChunk(file: File, chunk: ChunkData): ByteArray? {
            if (!file.isFile || file.length() != chunk.uncompressedLength.toLong()) return null
            return runCatching { file.readBytes() }
                .getOrNull()
                ?.takeIf { it.size == chunk.uncompressedLength }
                ?.also { file.setLastModified(System.currentTimeMillis()) }
        }

        private fun writeCachedChunk(file: File, data: ByteArray) {
            val partial = File(file.parentFile, "${file.name}.part")
            partial.outputStream().use { output ->
                output.write(data)
                output.flush()
            }
            if (file.exists() && !file.delete()) {
                partial.delete()
                error("无法替换在线播放缓存分块")
            }
            check(partial.renameTo(file)) { "无法提交在线播放缓存分块" }
            file.setLastModified(System.currentTimeMillis())
            if (cachedBytes >= 0L) cachedBytes += data.size.toLong()
        }

        private fun evictCachedChunksIfNeeded() {
            if (cachedBytes < 0L) {
                cachedBytes = cacheDirectory.listFiles()
                    ?.filter { file -> file.isFile && file.name.endsWith(".chunk") }
                    ?.sumOf(File::length)
                    ?: 0L
            }
            if (cachedBytes <= cacheLimitBytes) return
            val files = cacheDirectory.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".chunk") }
                ?.sortedBy(File::lastModified)
                .orEmpty()
            var total = cachedBytes
            files.forEach { file ->
                if (total <= cacheLimitBytes) return
                val length = file.length()
                if (file.delete()) total -= length
            }
            cachedBytes = total
        }
    }

    private suspend fun downloadManifest(
        cdnClient: SteamCdnClient,
        servers: List<Server>,
        depotId: Int,
        manifestId: Long,
        requestCode: Long,
        depotKey: ByteArray,
        authTokens: CdnAuthTokenProvider,
        control: suspend () -> SteamDownloadControl,
    ): DepotManifest {
        var lastError: Throwable? = null
        val failures = mutableListOf<String>()
        servers.take(MAX_CDN_ATTEMPTS).forEach { server ->
            currentCoroutineContext().ensureActive()
            checkDownloadControl(control)
            var hasToken = false
            try {
                val token = authTokens.get(server)
                hasToken = token != null
                return cdnClient.downloadManifestFuture(
                    depotId,
                    manifestId,
                    requestCode,
                    server,
                    depotKey,
                    null,
                    token,
                ).await()
            } catch (error: Throwable) {
                lastError = error
                failures += describeServer(server, hasToken) + "：" +
                    (error.message ?: error.javaClass.simpleName)
            }
        }
        throw IllegalStateException(buildCdnError("manifest", failures, lastError), lastError)
    }

    private suspend fun downloadChunk(
        cdnClient: SteamCdnClient,
        servers: List<Server>,
        depotId: Int,
        chunk: ChunkData,
        depotKey: ByteArray,
        authTokens: CdnAuthTokenProvider,
        selector: CdnServerSelector,
        control: suspend () -> SteamDownloadControl,
        onSuccess: ((Server) -> Unit)? = null,
    ): ByteArray {
        var lastError: Throwable? = null
        val failures = mutableListOf<String>()
        selector.candidates(servers.take(MAX_CDN_ATTEMPTS)).forEach { server ->
            currentCoroutineContext().ensureActive()
            checkDownloadControl(control)
            var hasToken = false
            try {
                val encrypted = ByteArray(chunk.compressedLength)
                val decoded = ByteArray(chunk.uncompressedLength)
                val token = authTokens.get(server)
                hasToken = token != null
                cdnClient.downloadDepotChunkFuture(
                    depotId,
                    chunk,
                    server,
                    encrypted,
                    null,
                    null,
                    token,
                ).await()
                val written = DepotChunk.process(chunk, encrypted, decoded, depotKey)
                check(written == decoded.size) { "Steam chunk 解压长度不匹配" }
                onSuccess?.invoke(server)
                return decoded
            } catch (error: Throwable) {
                selector.recordFailure(server)
                lastError = error
                failures += describeServer(server, hasToken) + "：" +
                    (error.message ?: error.javaClass.simpleName)
            }
        }
        throw IllegalStateException(buildCdnError("chunk", failures, lastError), lastError)
    }

    private suspend fun downloadFilePlans(
        plans: List<ManifestFilePlan>,
        destinationDirectory: File,
        cdnClient: SteamCdnClient,
        servers: List<Server>,
        depotId: Int,
        depotKey: ByteArray,
        authTokens: CdnAuthTokenProvider,
        selector: CdnServerSelector,
        chunkConcurrency: Int,
        control: suspend () -> SteamDownloadControl,
        progressReporter: DownloadProgressReporter,
    ) = coroutineScope {
        var nextPlanIndex = 0
        val smallFileBatchSize = smallFilePipelineBatchSize(chunkConcurrency)
        while (nextPlanIndex < plans.size) {
            currentCoroutineContext().ensureActive()
            checkDownloadControl(control)
            val currentPlan = plans[nextPlanIndex]
            if (!currentPlan.isSmallFilePipelineCandidate()) {
                downloadFilePlan(
                    plan = currentPlan,
                    destinationDirectory = destinationDirectory,
                    cdnClient = cdnClient,
                    servers = servers,
                    depotId = depotId,
                    depotKey = depotKey,
                    authTokens = authTokens,
                    selector = selector,
                    chunkConcurrency = chunkConcurrency,
                    control = control,
                    progressReporter = progressReporter,
                )
                nextPlanIndex += 1
                continue
            }

            val batch = buildList {
                while (
                    nextPlanIndex < plans.size &&
                        size < smallFileBatchSize &&
                        plans[nextPlanIndex].isSmallFilePipelineCandidate()
                ) {
                    add(plans[nextPlanIndex])
                    nextPlanIndex += 1
                }
            }
            batch.map { plan ->
                async {
                    downloadFilePlan(
                        plan = plan,
                        destinationDirectory = destinationDirectory,
                        cdnClient = cdnClient,
                        servers = servers,
                        depotId = depotId,
                        depotKey = depotKey,
                        authTokens = authTokens,
                        selector = selector,
                        chunkConcurrency = 1,
                        control = control,
                        progressReporter = progressReporter,
                    )
                }
            }.awaitAll()
        }
    }

    private suspend fun downloadFilePlan(
        plan: ManifestFilePlan,
        destinationDirectory: File,
        cdnClient: SteamCdnClient,
        servers: List<Server>,
        depotId: Int,
        depotKey: ByteArray,
        authTokens: CdnAuthTokenProvider,
        selector: CdnServerSelector,
        chunkConcurrency: Int,
        control: suspend () -> SteamDownloadControl,
        progressReporter: DownloadProgressReporter,
    ) {
        currentCoroutineContext().ensureActive()
        checkDownloadControl(control)
        val manifestFile = plan.file
        val destination = WorkshopStagingPath.resolve(destinationDirectory, manifestFile.fileName)
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, "${destination.name}.wallhub.part")
        if (isCompletedFile(destination, manifestFile, plan.chunks)) {
            partial.delete()
            progressReporter.markExistingFileCompleted(
                fileName = manifestFile.fileName,
                bytes = manifestFile.totalSize,
            )
            return
        }
        if (destination.exists() && !destination.isFile) {
            error("Steam 暂存文件路径不是普通文件：${manifestFile.fileName}")
        }
        if (destination.isFile && !partial.exists()) {
            check(destination.renameTo(partial)) {
                "无法保留未完成的 Steam 文件：${manifestFile.fileName}"
            }
        } else if (destination.isFile && partial.exists()) {
            check(destination.delete()) { "无法清理过期文件：${manifestFile.fileName}" }
        }

        val verifiedOffsets = findVerifiedChunkOffsets(partial, plan.chunks)
        val recoveredBytes = plan.chunks
            .filter { it.offset in verifiedOffsets }
            .sumOf { it.uncompressedLength.toLong() }
        if (recoveredBytes > 0L) {
            progressReporter.addDownloadedBytes(manifestFile.fileName, recoveredBytes)
        }
        RandomAccessFile(partial, "rw").use { output ->
            output.setLength(manifestFile.totalSize)
            try {
                downloadChunksContinuously(
                    chunks = plan.chunks.filterNot { it.offset in verifiedOffsets },
                    output = output,
                    cdnClient = cdnClient,
                    servers = servers,
                    depotId = depotId,
                    depotKey = depotKey,
                    authTokens = authTokens,
                    selector = selector,
                    chunkConcurrency = chunkConcurrency,
                    control = control,
                ) { decodedChunk ->
                    progressReporter.addDownloadedBytes(
                        fileName = manifestFile.fileName,
                        bytes = decodedChunk.size.toLong(),
                    )
                }
            } finally {
                output.fd.sync()
            }
        }
        checkDownloadControl(control)
        check(partial.length() == manifestFile.totalSize) {
            "文件大小校验失败：${manifestFile.fileName}"
        }
        verifyFileHash(manifestFile, calculateFileHash(partial))
        if (destination.exists()) {
            check(destination.delete()) { "无法替换已存在的文件：${manifestFile.fileName}" }
        }
        check(partial.renameTo(destination)) { "无法提交下载文件：${manifestFile.fileName}" }
        progressReporter.markFileCompleted(manifestFile.fileName)
    }

    private suspend fun downloadChunksContinuously(
        chunks: List<ChunkData>,
        output: RandomAccessFile,
        cdnClient: SteamCdnClient,
        servers: List<Server>,
        depotId: Int,
        depotKey: ByteArray,
        authTokens: CdnAuthTokenProvider,
        selector: CdnServerSelector,
        chunkConcurrency: Int,
        control: suspend () -> SteamDownloadControl,
        onChunkWritten: suspend (ByteArray) -> Unit,
    ) = coroutineScope {
        if (chunks.isEmpty()) return@coroutineScope
        val queue = Channel<ChunkData>(chunkConcurrency)
        val completed = Channel<DownloadedChunk>(chunkConcurrency)
        launch {
            chunks.forEach { chunk ->
                currentCoroutineContext().ensureActive()
                checkDownloadControl(control)
                queue.send(chunk)
            }
            queue.close()
        }
        repeat(chunkConcurrency) {
            launch {
                for (chunk in queue) {
                    currentCoroutineContext().ensureActive()
                    checkDownloadControl(control)
                    val data = downloadChunk(
                        cdnClient = cdnClient,
                        servers = servers,
                        depotId = depotId,
                        chunk = chunk,
                        depotKey = depotKey,
                        authTokens = authTokens,
                        selector = selector,
                        control = control,
                    )
                    completed.send(DownloadedChunk(chunk.offset, data))
                }
            }
        }
        repeat(chunks.size) {
            val chunk = completed.receive()
            output.seek(chunk.offset)
            output.write(chunk.data)
            onChunkWritten(chunk.data)
        }
    }

    private fun verifyFileHash(file: FileData, calculatedHash: ByteArray) {
        if (file.fileHash.isNotEmpty()) {
            check(file.fileHash.contentEquals(calculatedHash)) {
                "文件哈希校验失败：${file.fileName}"
            }
        }
    }

    private fun isCompletedFile(
        file: File,
        manifestFile: FileData,
        chunks: List<ChunkData>,
    ): Boolean {
        if (!file.isFile || file.length() != manifestFile.totalSize) return false
        val verified = findVerifiedChunkOffsets(file, chunks)
        if (verified.size != chunks.size) return false
        return manifestFile.fileHash.isEmpty() || manifestFile.fileHash.contentEquals(calculateFileHash(file))
    }

    private suspend fun checkDownloadControl(control: suspend () -> SteamDownloadControl) {
        while (true) {
            when (control()) {
                SteamDownloadControl.CONTINUE -> return
                SteamDownloadControl.PAUSE -> delay(PAUSE_POLL_INTERVAL_MS)
                SteamDownloadControl.CANCEL -> throw SteamDownloadCancelledException()
            }
        }
    }

    private fun calculateFileHash(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun createCdnClient(options: SteamContentDownloadOptions): SteamCdnClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = options.chunkConcurrency * 2
            maxRequestsPerHost = options.chunkConcurrency
        }
        val httpClientBuilder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(
                ConnectionPool(
                    options.chunkConcurrency,
                    CDN_KEEP_ALIVE_MINUTES,
                    TimeUnit.MINUTES,
                ),
            )
            .connectTimeout(CDN_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(CDN_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(CDN_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
        options.proxyUrl.takeIf(String::isNotBlank)?.let { proxyUrl ->
            httpClientBuilder.proxy(parseProxy(proxyUrl))
        }
        val httpClient = httpClientBuilder.build()
        return SteamCdnClient(
            SteamClient(
                SteamConfiguration.create { configuration ->
                    configuration.withConnectionTimeout(CONNECT_TIMEOUT_MS)
                    configuration.withHttpClient(httpClient)
                },
            ),
        )
    }

    private fun describeServer(server: Server, hasToken: Boolean): String {
        val requestHost = resolveCdnRequestHost(server.vHost, server.host) ?: "未知 CDN"
        val endpointHost = server.host?.trim()?.takeIf(String::isNotBlank)
        val endpoint = if (endpointHost != null && !endpointHost.equals(requestHost, ignoreCase = true)) {
            "，节点：$endpointHost"
        } else {
            ""
        }
        return "$requestHost（CDN 令牌：${if (hasToken) "已附加" else "未附加"}$endpoint）"
    }

    private fun buildCdnError(
        kind: String,
        failures: List<String>,
        lastError: Throwable?,
    ): String = if (failures.isEmpty()) {
        "Steam CDN $kind 下载失败：${lastError?.message ?: "无可用 CDN 路由"}"
    } else {
        failures.take(MAX_CDN_ERROR_DETAILS)
            .joinToString(prefix = "Steam CDN $kind 下载失败：", separator = "；")
    }

    private fun parseProxy(raw: String): Proxy {
        val uri = runCatching { URI(raw.trim()) }.getOrElse {
            throw IllegalArgumentException("下载代理地址无效")
        }
        val host = uri.host?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("下载代理缺少主机名")
        val type = when (uri.scheme?.lowercase()) {
            "http", "https" -> Proxy.Type.HTTP
            "socks", "socks5" -> Proxy.Type.SOCKS
            else -> throw IllegalArgumentException("下载代理仅支持 HTTP(S) 或 SOCKS5")
        }
        val port = if (uri.port > 0) uri.port else if (type == Proxy.Type.SOCKS) 1080 else 8080
        return Proxy(type, InetSocketAddress(host, port))
    }

    private suspend fun openContentSession(
        credential: SteamContentCredential?,
        onAuthenticating: suspend () -> Unit,
    ): SteamContentSession {
        val configuration = SteamConfiguration.create { config ->
            config.withConnectionTimeout(CONNECT_TIMEOUT_MS)
        }
        val client = SteamClient(configuration)
        val callbackManager = CallbackManager(client)
        val user = client.getHandler(SteamUser::class.java) ?: error("SteamUser handler unavailable")
        val apps = client.getHandler(SteamApps::class.java) ?: error("SteamApps handler unavailable")
        val content = client.getHandler(SteamContent::class.java) ?: error("SteamContent handler unavailable")
        val connected = CompletableDeferred<Unit>()
        val loggedOn = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val subscriptions = mutableListOf<Closeable>()
        val sessionLabel = if (credential == null) "匿名 Steam 内容会话" else "已登录 Steam 内容会话"
        subscriptions += callbackManager.subscribe(ConnectedCallback::class.java) {
            connected.complete(Unit)
        }
        subscriptions += callbackManager.subscribe(DisconnectedCallback::class.java) {
            val failure = IllegalStateException("${sessionLabel}已断开")
            if (!connected.isCompleted) connected.completeExceptionally(failure)
            if (!loggedOn.isCompleted) loggedOn.completeExceptionally(failure)
        }
        subscriptions += callbackManager.subscribe(LoggedOnCallback::class.java) { callback ->
            if (callback.result == EResult.OK) {
                loggedOn.complete(Unit)
            } else {
                loggedOn.completeExceptionally(
                    IllegalStateException("${sessionLabel}登录失败：${callback.result}"),
                )
            }
        }
        val callbackJob = scope.launch {
            while (isActive) {
                runCatching { callbackManager.runWaitCallbacks(1_000L) }
                delay(1)
            }
        }
        try {
            client.connect()
            withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }
            if (credential == null) {
                user.logOnAnonymous(AnonymousLogOnDetails(null, EOSType.AndroidUnknown, "schinese"))
            } else {
                onAuthenticating()
                user.logOn(
                    LogOnDetails(
                        username = credential.accountName,
                        accessToken = credential.refreshToken,
                        shouldRememberPassword = true,
                        loginID = WALLPAPER_ENGINE_APP_ID,
                        machineName = "WallHub Android Download Worker",
                        chatMode = ChatMode.NEW_STEAM_CHAT,
                    ),
                )
            }
            withTimeout(LOGON_TIMEOUT_MS) { loggedOn.await() }
            return SteamContentSession(
                client = client,
                user = user,
                apps = apps,
                content = content,
                isAuthenticated = credential != null,
                callbackScope = scope,
                callbackJob = callbackJob,
                subscriptions = subscriptions,
            )
        } catch (error: Throwable) {
            callbackJob.cancel()
            subscriptions.forEach { subscription -> runCatching { subscription.close() } }
            runCatching { user.logOff() }
            runCatching { client.disconnect() }
            scope.cancel()
            throw error
        }
    }

    internal data class SteamContentAccess(
        val depotKey: ByteArray,
        val manifestRequestCode: Long,
        val servers: List<Server>,
        val authTokens: CdnAuthTokenProvider,
    )

    private data class DownloadedChunk(
        val offset: Long,
        val data: ByteArray,
    )

    private data class ManifestFilePlan(
        val file: FileData,
        val chunks: List<ChunkData>,
    ) {
        fun isSmallFilePipelineCandidate(): Boolean =
            isSmallFilePipelineCandidate(file.totalSize, chunks.size)
    }

    private class CdnServerSelector {
        private val nextPrimaryIndex = AtomicInteger()
        private val failedHosts = ConcurrentHashMap.newKeySet<String>()

        fun candidates(servers: List<Server>): List<Server> {
            if (servers.size < 2) return servers
            val available = servers.filterNot { server -> server.selectorKey() in failedHosts }
            val failed = servers.filter { server -> server.selectorKey() in failedHosts }
            if (available.size < 2) return available + failed
            val primaryCount = minOf(CDN_PARALLEL_SERVER_COUNT, available.size)
            val primary = available.take(primaryCount)
            val start = Math.floorMod(nextPrimaryIndex.getAndIncrement(), primaryCount)
            return primary.drop(start) + primary.take(start) + available.drop(primaryCount) + failed
        }

        fun recordFailure(server: Server) {
            failedHosts += server.selectorKey()
        }

        private fun Server.selectorKey(): String =
            (resolveCdnRequestHost(vHost, host) ?: host.orEmpty()).lowercase()
    }

    internal class CdnAuthTokenProvider(
        private val content: SteamContent,
        private val appId: Int,
        private val depotId: Int,
        private val callbackScope: CoroutineScope,
        private val enabled: Boolean,
    ) {
        private val cachedTokens = mutableMapOf<String, CachedCdnAuthToken>()
        private val pendingTokens = mutableMapOf<String, kotlinx.coroutines.Deferred<CachedCdnAuthToken>>()
        private val mutex = Mutex()

        suspend fun get(server: Server): String? {
            if (!enabled) return null
            val requestHost = resolveCdnRequestHost(server.vHost, server.host) ?: return null
            val cacheKey = requestHost.lowercase()
            var cachedToken: String? = null
            val pending = mutex.withLock {
                val now = System.currentTimeMillis()
                cachedTokens[cacheKey]
                    ?.takeIf { it.expiresAtMs > now + TOKEN_MIN_VALIDITY_MS }
                    ?.let { token ->
                        cachedToken = token.token
                        return@withLock null
                    }
                pendingTokens[cacheKey] ?: callbackScope.async {
                    val response = content
                        .getCDNAuthToken(appId, depotId, requestHost, callbackScope)
                        .await()
                    check(response.result == EResult.OK) { "Steam CDN 授权失败：${response.result}" }
                    CachedCdnAuthToken(
                        token = normalizeCdnAuthToken(response.token).takeIf(String::isNotBlank)
                            ?: error("Steam 未返回 CDN 授权 token"),
                        expiresAtMs = response.expiration?.time ?: Long.MAX_VALUE,
                    )
                }.also { request -> pendingTokens[cacheKey] = request }
            }
            cachedToken?.let { return it }
            val request = checkNotNull(pending)
            return try {
                val result = request.await()
                mutex.withLock {
                    cachedTokens[cacheKey] = result
                    if (pendingTokens[cacheKey] === request) pendingTokens.remove(cacheKey)
                }
                result.token
            } catch (error: Throwable) {
                mutex.withLock {
                    if (pendingTokens[cacheKey] === request) pendingTokens.remove(cacheKey)
                }
                throw error
            }
        }

        private data class CachedCdnAuthToken(
            val token: String,
            val expiresAtMs: Long,
        )
    }

    internal data class SteamContentSession(
        val client: SteamClient,
        val user: SteamUser,
        val apps: SteamApps,
        val content: SteamContent,
        val isAuthenticated: Boolean,
        val callbackScope: CoroutineScope,
        val callbackJob: Job,
        val subscriptions: List<Closeable>,
    ) {
        fun close() {
            callbackJob.cancel()
            subscriptions.forEach { subscription -> runCatching { subscription.close() } }
            runCatching { user.logOff() }
            runCatching { client.disconnect() }
            callbackScope.cancel()
        }
    }

    private fun String.videoFileExtension(): String {
        val fileName = substringAfterLast('/').substringAfterLast('\\')
        return fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    }

    private companion object {
        const val WALLPAPER_ENGINE_APP_ID = 431960
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val LOGON_TIMEOUT_MS = 30_000L
        const val CDN_SERVER_LIMIT = 20
        const val MAX_CDN_ATTEMPTS = 8
        const val MAX_CDN_ERROR_DETAILS = 3
        const val CDN_CONNECT_TIMEOUT_MS = 20_000L
        const val CDN_READ_TIMEOUT_MS = 60_000L
        const val CDN_WRITE_TIMEOUT_MS = 20_000L
        const val CDN_KEEP_ALIVE_MINUTES = 5L
        const val CDN_PARALLEL_SERVER_COUNT = 4
        const val DOWNLOAD_LOG_TAG = "WallHubDownload"
        const val HASH_BUFFER_SIZE = 1024 * 1024
        const val TOKEN_MIN_VALIDITY_MS = 30_000L
        const val PAUSE_POLL_INTERVAL_MS = 250L
        const val STREAM_MIN_CACHE_LIMIT_BYTES = 16L * 1024L * 1024L
        val VIDEO_FILE_EXTENSIONS = setOf("mp4", "webm", "mkv", "avi", "mov", "wmv", "m4v")
    }
}

internal const val SMALL_FILE_PIPELINE_MAX_BYTES = 512L * 1024L
internal const val SMALL_FILE_PIPELINE_MAX_CHUNKS = 2

internal data class SteamStreamByteRange(
    val start: Long,
    val endInclusive: Long,
)

internal fun steamStreamPrefetchConcurrency(configuredConcurrency: Int): Int =
    configuredConcurrency.coerceIn(1, STEAM_STREAM_MAX_PARALLEL_CHUNKS)

/**
 * Mirrors the web Depot stream warm-up order: a playable lead-in, MP4 metadata tail,
 * then a substantially larger initial playback buffer. Chunk-level request de-duplication
 * makes the deliberate overlap harmless for short videos.
 */
internal fun steamStreamStartupPrefetchRanges(
    contentLength: Long,
    startPosition: Long = 0L,
): List<SteamStreamByteRange> {
    val start = startPosition.coerceIn(0L, (contentLength - 1L).coerceAtLeast(0L))
    val first = steamStreamRange(contentLength, start, STEAM_STREAM_FIRST_RANGE_BYTES) ?: return emptyList()
    val ranges = mutableListOf(first)
    if (start == 0L) {
        steamStreamRange(
            contentLength = contentLength,
            start = (contentLength - STEAM_STREAM_TAIL_BYTES).coerceAtLeast(0L),
            length = STEAM_STREAM_TAIL_BYTES,
        )?.takeIf { it.start > 0L }?.let(ranges::add)
    }
    steamStreamRange(
        contentLength = contentLength,
        start = first.endInclusive + 1L,
        length = STEAM_STREAM_INITIAL_BUFFER_BYTES,
    )?.let(ranges::add)
    return ranges
}

internal fun steamStreamAheadPrefetchRange(
    contentLength: Long,
    afterExclusive: Long,
): SteamStreamByteRange? = steamStreamRange(
    contentLength = contentLength,
    start = afterExclusive,
    length = STEAM_STREAM_AHEAD_BYTES,
)

internal fun steamStreamContiguousPrefetchEnd(
    startPosition: Long,
    ranges: List<SteamStreamByteRange>,
): Long {
    var end = startPosition - 1L
    ranges.sortedBy(SteamStreamByteRange::start).forEach { range ->
        if (range.start > end + 1L) return end
        end = max(end, range.endInclusive)
    }
    return end
}

private fun steamStreamRange(
    contentLength: Long,
    start: Long,
    length: Long,
): SteamStreamByteRange? {
    if (contentLength <= 0L || length <= 0L || start !in 0 until contentLength) return null
    return SteamStreamByteRange(
        start = start,
        endInclusive = min(contentLength - 1L, start + length - 1L),
    )
}

private const val STEAM_STREAM_MEBIBYTE = 1024L * 1024L
private const val STEAM_STREAM_FIRST_RANGE_BYTES = 2L * STEAM_STREAM_MEBIBYTE
private const val STEAM_STREAM_TAIL_BYTES = 8L * STEAM_STREAM_MEBIBYTE
private const val STEAM_STREAM_INITIAL_BUFFER_BYTES = 32L * STEAM_STREAM_MEBIBYTE
private const val STEAM_STREAM_AHEAD_BYTES = 64L * STEAM_STREAM_MEBIBYTE
private const val STEAM_STREAM_MAX_PARALLEL_CHUNKS = 32
private const val STREAM_SEEK_RESET_BYTES = 2L * STEAM_STREAM_MEBIBYTE

internal fun isSmallFilePipelineCandidate(fileSize: Long, chunkCount: Int): Boolean =
    fileSize in 0..SMALL_FILE_PIPELINE_MAX_BYTES &&
        chunkCount in 0..SMALL_FILE_PIPELINE_MAX_CHUNKS

internal fun smallFilePipelineBatchSize(chunkConcurrency: Int): Int = chunkConcurrency.coerceAtLeast(1)

/** Serializes progress delivery while several small files download concurrently. */
internal class DownloadProgressReporter(
    private val totalBytes: Long,
    private val totalFiles: Int,
    private val onProgress: suspend (SteamDownloadProgress) -> Unit,
) {
    private val mutex = Mutex()
    private var downloadedBytes = 0L
    private var completedFiles = 0

    suspend fun addDownloadedBytes(fileName: String, bytes: Long) {
        publish(
            fileName = fileName,
            addedBytes = bytes,
            completedFile = false,
        )
    }

    suspend fun markExistingFileCompleted(fileName: String, bytes: Long) {
        publish(
            fileName = fileName,
            addedBytes = bytes,
            completedFile = true,
        )
    }

    suspend fun markFileCompleted(fileName: String) {
        publish(
            fileName = fileName,
            addedBytes = 0L,
            completedFile = true,
        )
    }

    suspend fun snapshot(): DownloadProgressSnapshot = mutex.withLock {
        DownloadProgressSnapshot(
            downloadedBytes = downloadedBytes,
            completedFiles = completedFiles,
        )
    }

    private suspend fun publish(
        fileName: String,
        addedBytes: Long,
        completedFile: Boolean,
    ) {
        mutex.lock()
        try {
            downloadedBytes += addedBytes.coerceAtLeast(0L)
            if (completedFile) completedFiles += 1
            onProgress(
                SteamDownloadProgress(
                    phase = SteamDownloadPhase.DOWNLOADING,
                    currentFile = fileName,
                    completedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    completedFiles = completedFiles,
                    totalFiles = totalFiles,
                ),
            )
        } finally {
            mutex.unlock()
        }
    }
}

internal data class DownloadProgressSnapshot(
    val downloadedBytes: Long,
    val completedFiles: Int,
)

internal data class SteamContentDownloadOptions(
    val chunkConcurrency: Int = 24,
    val proxyUrl: String = "",
) {
    fun normalized(): SteamContentDownloadOptions = copy(
        chunkConcurrency = chunkConcurrency.coerceIn(12, 48),
        proxyUrl = proxyUrl.trim(),
    )
}

internal object WorkshopStagingPath {
    fun resolve(rootDirectory: File, relativePath: String): File {
        val normalized = relativePath.replace('\\', '/')
        val segments = normalized.split('/')
        require(normalized.isNotBlank()) { "Steam manifest 包含空文件路径" }
        require(!normalized.startsWith('/')) { "Steam manifest 包含绝对文件路径" }
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Steam manifest 包含不安全文件路径"
        }
        val root = rootDirectory.canonicalFile
        val target = File(root, normalized).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) {
            "Steam manifest 文件路径超出暂存目录"
        }
        return target
    }
}
