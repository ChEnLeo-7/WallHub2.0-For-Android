package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EResult
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import `in`.dragonbra.javasteam.steam.cdn.Client as SteamCdnClient

internal suspend fun resolveContentAccess(
    session: SteamContentSession,
    target: WorkshopContentTarget,
): SteamContentAccess {
    val depotKey = session.apps.getDepotDecryptionKey(target.depotId, target.appId).await()
    check(depotKey.result == EResult.OK) { "Steam did not provide a depot key: ${depotKey.result}" }
    val servers =
        session.content
            .getServersForSteamPipe(null, CDN_SERVER_LIMIT, session.callbackScope)
            .await()
            .filter { resolveCdnRequestHost(it.vHost, it.host) != null }
            .let(::prioritizeCdnServers)
    check(servers.isNotEmpty()) { "Steam returned no available CDN servers" }
    val requestCode =
        session.content
            .getManifestRequestCode(
                target.depotId,
                target.appId,
                target.contentManifestId,
                "public",
                null,
                session.callbackScope,
            ).await()
    return SteamContentAccess(
        depotKey = depotKey.depotKey,
        manifestRequestCode = requestCode,
        servers = servers,
        authTokens =
            CdnAuthTokenProvider(
                content = session.content,
                appId = target.appId,
                depotId = target.depotId,
                callbackScope = session.callbackScope,
                enabled = session.isAuthenticated,
            ),
    )
}

internal fun prioritizeCdnServers(servers: List<Server>): List<Server> {
    val ranked = servers.sortedBy { it.weightedLoad }
    val secure = ranked.filter { it.protocol == Server.ConnectionProtocol.HTTPS }
    return secure + ranked.filterNot { it.protocol == Server.ConnectionProtocol.HTTPS }
}

internal class SteamContentVideoStream internal constructor(
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
    private var latestCdnHost: String? =
        access.servers.firstNotNullOfOrNull { server ->
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

    override suspend fun readAt(
        position: Long,
        length: Int,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            check(!closed) { "SteamKit streaming session is closed" }
            require(position >= 0L) { "Invalid streaming read position" }
            require(length >= 0) { "Invalid streaming read length" }
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
            check(destinationOffset == requestedLength) { "Steam video chunk has missing data" }
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
    ): Deferred<ByteArray> =
        synchronized(inFlightLock) {
            val existing = inFlightChunks[chunk.offset]
            if (existing != null &&
                (
                    priority == SteamStreamChunkPriority.PREFETCH ||
                        existing.priority == SteamStreamChunkPriority.FOREGROUND
                )
            ) {
                return@synchronized existing.deferred
            }
            existing?.deferred?.cancel()
            val requestId = nextChunkRequestId.incrementAndGet()
            val request =
                fetchScope.async(start = CoroutineStart.LAZY) {
                    try {
                        val cacheFile = File(cacheDirectory, "${chunk.offset}.chunk")
                        readCachedChunk(cacheFile, chunk)?.let { return@async it }
                        val downloaded =
                            networkScheduler.withPermit(priority) {
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
            inFlightChunks[chunk.offset] =
                StreamChunkRequest(
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
            furthestContiguousPrefetchEnd =
                maxOf(
                    furthestContiguousPrefetchEnd,
                    steamStreamContiguousPrefetchEnd(position, startupRanges),
                )
            startupPrefetchJob =
                fetchScope.launch {
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
        val range =
            synchronized(prefetchLock) {
                if (afterExclusive <= furthestContiguousPrefetchEnd) return
                steamStreamAheadPrefetchRange(contentLength, afterExclusive)?.also {
                    furthestContiguousPrefetchEnd = maxOf(furthestContiguousPrefetchEnd, it.endInclusive)
                }
            } ?: return
        synchronized(prefetchLock) {
            aheadPrefetchJob =
                fetchScope.launch {
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

    private suspend fun prefetchRange(
        range: SteamStreamByteRange,
        generation: Long,
    ) = coroutineScope {
        val selectedChunks =
            chunks.filter { chunk ->
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
        val staleRequests =
            synchronized(inFlightLock) {
                inFlightChunks
                    .filterValues { request -> request.priority == SteamStreamChunkPriority.PREFETCH }
                    .keys
                    .mapNotNull(inFlightChunks::remove)
            }
        staleRequests.forEach { request -> request.deferred.cancel() }
    }

    private fun readCachedChunk(
        file: File,
        chunk: ChunkData,
    ): ByteArray? {
        if (!file.isFile || file.length() != chunk.uncompressedLength.toLong()) return null
        return runCatching { file.readBytes() }
            .getOrNull()
            ?.takeIf { it.size == chunk.uncompressedLength }
            ?.also { file.setLastModified(System.currentTimeMillis()) }
    }

    private fun writeCachedChunk(
        file: File,
        data: ByteArray,
    ) {
        val partial = File(file.parentFile, "${file.name}.part")
        partial.outputStream().use { output ->
            output.write(data)
            output.flush()
        }
        if (file.exists() && !file.delete()) {
            partial.delete()
            error("Failed to replace streaming cache chunk")
        }
        check(partial.renameTo(file)) { "Failed to commit streaming cache chunk" }
        file.setLastModified(System.currentTimeMillis())
        if (cachedBytes >= 0L) cachedBytes += data.size.toLong()
    }

    private fun evictCachedChunksIfNeeded() {
        if (cachedBytes < 0L) {
            cachedBytes = cacheDirectory
                .listFiles()
                ?.filter { file -> file.isFile && file.name.endsWith(".chunk") }
                ?.sumOf(File::length)
                ?: 0L
        }
        if (cachedBytes <= cacheLimitBytes) return
        val files =
            cacheDirectory
                .listFiles()
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

internal suspend fun downloadManifest(
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
            return cdnClient
                .downloadManifestFuture(
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

internal suspend fun downloadChunk(
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
            cdnClient
                .downloadDepotChunkFuture(
                    depotId,
                    chunk,
                    server,
                    encrypted,
                    null,
                    null,
                    token,
                ).await()
            val written = DepotChunk.process(chunk, encrypted, decoded, depotKey)
            check(written == decoded.size) { "Steam chunk decompressed length mismatch" }
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

internal suspend fun downloadFilePlans(
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

        val batch =
            buildList {
                while (
                    nextPlanIndex < plans.size &&
                    size < smallFileBatchSize &&
                    plans[nextPlanIndex].isSmallFilePipelineCandidate()
                ) {
                    add(plans[nextPlanIndex])
                    nextPlanIndex += 1
                }
            }
        batch
            .map { plan ->
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

internal suspend fun downloadFilePlan(
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
        error("Steam staging file path is not a regular file: ${manifestFile.fileName}")
    }
    if (destination.isFile && !partial.exists()) {
        check(destination.renameTo(partial)) {
            "Failed to preserve incomplete Steam file: ${manifestFile.fileName}"
        }
    } else if (destination.isFile && partial.exists()) {
        check(destination.delete()) { "Failed to delete stale file: ${manifestFile.fileName}" }
    }

    val verifiedOffsets = findVerifiedChunkOffsets(partial, plan.chunks)
    val recoveredBytes =
        plan.chunks
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
        "File size verification failed: ${manifestFile.fileName}"
    }
    verifyFileHash(manifestFile, calculateFileHash(partial))
    if (destination.exists()) {
        check(destination.delete()) { "Failed to replace existing file: ${manifestFile.fileName}" }
    }
    check(partial.renameTo(destination)) { "Failed to commit downloaded file: ${manifestFile.fileName}" }
    progressReporter.markFileCompleted(manifestFile.fileName)
}

internal suspend fun downloadChunksContinuously(
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
                val data =
                    downloadChunk(
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

internal fun verifyFileHash(
    file: FileData,
    calculatedHash: ByteArray,
) {
    if (file.fileHash.isNotEmpty()) {
        check(file.fileHash.contentEquals(calculatedHash)) {
            "File hash verification failed: ${file.fileName}"
        }
    }
}

internal fun isCompletedFile(
    file: File,
    manifestFile: FileData,
    chunks: List<ChunkData>,
): Boolean {
    if (!file.isFile || file.length() != manifestFile.totalSize) return false
    val verified = findVerifiedChunkOffsets(file, chunks)
    if (verified.size != chunks.size) return false
    return manifestFile.fileHash.isEmpty() || manifestFile.fileHash.contentEquals(calculateFileHash(file))
}

internal suspend fun checkDownloadControl(control: suspend () -> SteamDownloadControl) {
    while (true) {
        when (control()) {
            SteamDownloadControl.CONTINUE -> return
            SteamDownloadControl.PAUSE -> delay(PAUSE_POLL_INTERVAL_MS)
            SteamDownloadControl.CANCEL -> throw SteamDownloadCancelledException()
        }
    }
}

internal fun calculateFileHash(file: File): ByteArray {
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

internal fun createCdnClient(options: SteamContentDownloadOptions): SteamCdnClient {
    val dispatcher =
        Dispatcher().apply {
            maxRequests = options.chunkConcurrency * 2
            maxRequestsPerHost = options.chunkConcurrency
        }
    val httpClientBuilder =
        OkHttpClient
            .Builder()
            .dispatcher(dispatcher)
            .connectionPool(
                ConnectionPool(
                    options.chunkConcurrency,
                    CDN_KEEP_ALIVE_MINUTES,
                    TimeUnit.MINUTES,
                ),
            ).connectTimeout(CDN_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(CDN_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(CDN_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
    httpClientBuilder.applyDownloadProxy(options.proxyUrl)
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

internal fun describeServer(
    server: Server,
    hasToken: Boolean,
): String {
    val requestHost = resolveCdnRequestHost(server.vHost, server.host) ?: "unknown CDN"
    val endpointHost = server.host?.trim()?.takeIf(String::isNotBlank)
    val endpoint =
        if (endpointHost != null && !endpointHost.equals(requestHost, ignoreCase = true)) {
            ", endpoint: $endpointHost"
        } else {
            ""
        }
    return "$requestHost (CDN token: ${if (hasToken) "attached" else "not attached"}$endpoint)"
}

internal fun buildCdnError(
    kind: String,
    failures: List<String>,
    lastError: Throwable?,
): String =
    if (failures.isEmpty()) {
        "Steam CDN $kind download failed: ${lastError?.message ?: "no available CDN route"}"
    } else {
        failures
            .take(MAX_CDN_ERROR_DETAILS)
            .joinToString(prefix = "Steam CDN $kind download failed: ", separator = "; ")
    }

internal suspend fun openContentSession(
    credential: SteamContentCredential?,
    onAuthenticating: suspend () -> Unit,
): SteamContentSession {
    val configuration =
        SteamConfiguration.create { config ->
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
    val sessionLabel = if (credential == null) "Anonymous Steam content session" else "Authenticated Steam content session"
    subscriptions +=
        callbackManager.subscribe(ConnectedCallback::class.java) {
            connected.complete(Unit)
        }
    subscriptions +=
        callbackManager.subscribe(DisconnectedCallback::class.java) {
            val failure = IllegalStateException("$sessionLabel disconnected")
            if (!connected.isCompleted) connected.completeExceptionally(failure)
            if (!loggedOn.isCompleted) loggedOn.completeExceptionally(failure)
        }
    subscriptions +=
        callbackManager.subscribe(LoggedOnCallback::class.java) { callback ->
            if (callback.result == EResult.OK) {
                loggedOn.complete(Unit)
            } else {
                loggedOn.completeExceptionally(
                    IllegalStateException("$sessionLabel login failed: ${callback.result}"),
                )
            }
        }
    val callbackJob =
        scope.launch {
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

internal data class DownloadedChunk(
    val offset: Long,
    val data: ByteArray,
)

internal data class ManifestFilePlan(
    val file: FileData,
    val chunks: List<ChunkData>,
) {
    fun isSmallFilePipelineCandidate(): Boolean = isSmallFilePipelineCandidate(file.totalSize, chunks.size)
}

internal class CdnServerSelector {
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

    private fun Server.selectorKey(): String = (resolveCdnRequestHost(vHost, host) ?: host.orEmpty()).lowercase()
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
        val pending =
            mutex.withLock {
                val now = System.currentTimeMillis()
                cachedTokens[cacheKey]
                    ?.takeIf { it.expiresAtMs > now + TOKEN_MIN_VALIDITY_MS }
                    ?.let { token ->
                        cachedToken = token.token
                        return@withLock null
                    }
                pendingTokens[cacheKey] ?: callbackScope
                    .async {
                        val response =
                            content
                                .getCDNAuthToken(appId, depotId, requestHost, callbackScope)
                                .await()
                        check(response.result == EResult.OK) { "Steam CDN authorization failed: ${response.result}" }
                        CachedCdnAuthToken(
                            token =
                                normalizeCdnAuthToken(response.token).takeIf(String::isNotBlank)
                                    ?: error("Steam returned no CDN authorization token"),
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

internal fun String.videoFileExtension(): String {
    val fileName = substringAfterLast('/').substringAfterLast('\\')
    return fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
}

internal const val WALLPAPER_ENGINE_APP_ID = 431960
internal const val CONNECT_TIMEOUT_MS = 20_000L
internal const val LOGON_TIMEOUT_MS = 30_000L
internal const val CDN_SERVER_LIMIT = 20
internal const val MAX_CDN_ATTEMPTS = 8
internal const val MAX_CDN_ERROR_DETAILS = 3
internal const val CDN_CONNECT_TIMEOUT_MS = 20_000L
internal const val CDN_READ_TIMEOUT_MS = 60_000L
internal const val CDN_WRITE_TIMEOUT_MS = 20_000L
internal const val CDN_KEEP_ALIVE_MINUTES = 5L
internal const val CDN_PARALLEL_SERVER_COUNT = 4
internal const val STEAM_CONTENT_LOG_TAG = "WallHubDownload"
internal const val HASH_BUFFER_SIZE = 1024 * 1024
internal const val TOKEN_MIN_VALIDITY_MS = 30_000L
internal const val PAUSE_POLL_INTERVAL_MS = 250L
internal const val STREAM_MIN_CACHE_LIMIT_BYTES = 16L * 1024L * 1024L
internal val VIDEO_FILE_EXTENSIONS = setOf("mp4", "webm", "mkv", "avi", "mov", "wmv", "m4v")

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
): SteamStreamByteRange? =
    steamStreamRange(
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

internal fun steamStreamRange(
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

internal const val STEAM_STREAM_MEBIBYTE = 1024L * 1024L
internal const val STEAM_STREAM_FIRST_RANGE_BYTES = 2L * STEAM_STREAM_MEBIBYTE
internal const val STEAM_STREAM_TAIL_BYTES = 8L * STEAM_STREAM_MEBIBYTE
internal const val STEAM_STREAM_INITIAL_BUFFER_BYTES = 32L * STEAM_STREAM_MEBIBYTE
internal const val STEAM_STREAM_AHEAD_BYTES = 64L * STEAM_STREAM_MEBIBYTE
internal const val STEAM_STREAM_MAX_PARALLEL_CHUNKS = 32
internal const val STREAM_SEEK_RESET_BYTES = 2L * STEAM_STREAM_MEBIBYTE

internal fun isSmallFilePipelineCandidate(
    fileSize: Long,
    chunkCount: Int,
): Boolean =
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

    suspend fun addDownloadedBytes(
        fileName: String,
        bytes: Long,
    ) {
        publish(
            fileName = fileName,
            addedBytes = bytes,
            completedFile = false,
        )
    }

    suspend fun markExistingFileCompleted(
        fileName: String,
        bytes: Long,
    ) {
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

    suspend fun snapshot(): DownloadProgressSnapshot =
        mutex.withLock {
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
    fun normalized(): SteamContentDownloadOptions =
        copy(
            chunkConcurrency = chunkConcurrency.coerceIn(12, 48),
            proxyUrl = proxyUrl.trim(),
        )
}

internal object WorkshopStagingPath {
    fun resolve(
        rootDirectory: File,
        relativePath: String,
    ): File {
        val normalized = relativePath.replace('\\', '/')
        val segments = normalized.split('/')
        require(normalized.isNotBlank()) { "Steam manifest contains an empty file path" }
        require(!normalized.startsWith('/')) { "Steam manifest contains an absolute file path" }
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Steam manifest contains an unsafe file path"
        }
        val root = rootDirectory.canonicalFile
        val target = File(root, normalized).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) {
            "Steam manifest file path escapes the staging directory"
        }
        return target
    }
}
