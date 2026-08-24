package com.wallhub.android.data.downloads

import android.util.Log
import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.core.model.WorkshopVideoBufferState
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
import kotlinx.coroutines.asCoroutineDispatcher
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import `in`.dragonbra.javasteam.steam.cdn.Client as SteamCdnClient

internal suspend fun resolveContentAccess(
    session: SteamContentSession,
    target: WorkshopContentTarget,
): SteamContentAccess {
    val depotKey = session.apps.getDepotDecryptionKey(target.depotId, target.appId).await()
    if (depotKey.result != EResult.OK) throw SteamDepotAccessException(target.depotId, depotKey.result)
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

internal class SteamDepotAccessException(
    depotId: Int,
    val result: EResult,
) : IllegalStateException("Steam did not provide depot key $depotId: $result")

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
    private val streamCache: SteamVideoStreamCache,
    private val prefetchConcurrency: Int,
    private val cdnClient: SteamCdnClient,
    private val session: SteamContentSession,
    private val access: SteamContentAccess,
    private val depotId: Int,
) : com.wallhub.android.core.model.WorkshopVideoStreamSession {
    private val selector = CdnServerSelector()
    // Keep the configured request count, but cap aggregate in-memory decoded payloads so
    // large Steam chunks cannot exhaust the app heap on high-concurrency devices.
    private val networkScheduler = ForegroundFirstPermitPool(prefetchConcurrency)
    private val memoryScheduler =
        DownloadMemoryBudget.withFixedCapacity(
            steamStreamMemoryBudgetBytes(Runtime.getRuntime().maxMemory()),
        )
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // JavaSteam VZipUtil retains an 8 MiB ThreadLocal window per decoder thread.
    // A dedicated bounded pool prevents Dispatchers.IO workers from each retaining
    // their own window while network task concurrency remains exactly user-configured.
    private val decodeDispatcher =
        Executors.newFixedThreadPool(STEAM_STREAM_DECODE_THREADS) { runnable ->
            Thread(runnable, "WallHub-Steam-Decode").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val inFlightLock = Any()
    private val inFlightChunks = mutableMapOf<Long, StreamChunkRequest>()
    private val nextChunkRequestId = AtomicLong(0L)
    private val stagedRequestCount = AtomicInteger(0)
    private val prefetchLock = Any()
    private val prefetchGeneration = AtomicLong(0L)
    private val lastReadEnd = AtomicLong(-1L)
    private val dynamicAheadBytes = AtomicLong(STEAM_STREAM_AHEAD_BYTES)
    private val dynamicLowWaterBytes = AtomicLong(STEAM_STREAM_LOW_WATER_BYTES)
    private val latestReadPosition = AtomicLong(0L)
    private val lastBufferMetricsLogAtMs = AtomicLong(0L)
    private val byteRateEstimator = SteamStreamByteRateEstimator(contentLength)
    private val latestPlaybackClock = AtomicReference(SteamStreamPlaybackClock())
    private val latestEstimatedBytesPerMillisecond = AtomicReference(0.0)
    private val contiguousBufferTracker = SteamContiguousBufferTracker(chunks)
    private val mutablePlaybackBufferState = MutableStateFlow(WorkshopVideoBufferState())

    override val playbackBufferState = mutablePlaybackBufferState.asStateFlow()

    @Volatile
    private var latestCdnHost: String? =
        access.servers.firstNotNullOfOrNull { server ->
            resolveCdnRequestHost(server.vHost, server.host)
        }

    override val currentCdnHost: String?
        get() = latestCdnHost

    override fun updatePlaybackDemand(
        playbackSpeed: Float,
        playbackPositionMs: Long,
        bufferedPositionMs: Long,
        durationMs: Long,
    ) {
        latestPlaybackClock.set(
            SteamStreamPlaybackClock(
                playbackPositionMs = playbackPositionMs,
                bufferedPositionMs = bufferedPositionMs,
                durationMs = durationMs,
            ),
        )
        val estimatedBytesPerMillisecond =
            byteRateEstimator.update(
                readPosition = latestReadPosition.get(),
                playbackPositionMs = playbackPositionMs,
                bufferedPositionMs = bufferedPositionMs,
                durationMs = durationMs,
            )
        latestEstimatedBytesPerMillisecond.set(estimatedBytesPerMillisecond)
        val window =
            steamStreamTimeBufferWindow(
                contentLength = contentLength,
                durationMs = durationMs,
                maximumAheadBytes = streamCache.prefetchCapacityBytes,
                observedBytesPerMillisecond = estimatedBytesPerMillisecond,
            )
        val mediaBufferedMs = (bufferedPositionMs - playbackPositionMs).coerceAtLeast(0L)
        val mediaBufferedBytes =
            kotlin.math.ceil(estimatedBytesPerMillisecond * mediaBufferedMs.toDouble())
                .toLong()
                .coerceAtLeast(0L)
        val diskTargetBytes = (window.targetBytes - mediaBufferedBytes).coerceAtLeast(1L)
        val diskLowWaterBytes =
            (window.lowWaterBytes - mediaBufferedBytes).coerceIn(1L, diskTargetBytes)
        // Media3 and the verified disk range form one logical buffer. Subtract
        // Media3's decode-ready bytes so the displayed/refill watermarks remain
        // 15 seconds and 8 seconds in total rather than stacking both layers.
        dynamicLowWaterBytes.set(diskLowWaterBytes)
        dynamicAheadBytes.set(diskTargetBytes)
        // A periodic playback report also retries a failed/paused refill. The range
        // calculator remains the sole 10-second low-water gate.
        val afterExclusive = lastReadEnd.get() + 1L
        if (afterExclusive > 0L) scheduleAheadPrefetch(afterExclusive)
        publishPlaybackBufferState()
        val nowMs = System.currentTimeMillis()
        val previousLogAtMs = lastBufferMetricsLogAtMs.get()
        if (nowMs - previousLogAtMs >= STEAM_STREAM_BUFFER_LOG_INTERVAL_MS &&
            lastBufferMetricsLogAtMs.compareAndSet(previousLogAtMs, nowMs)
        ) {
            Log.d(
                STEAM_CONTENT_LOG_TAG,
                "Steam stream playbackPositionMs=$playbackPositionMs, mediaBufferedMs=$mediaBufferedMs, " +
                    "contiguousAvailableMs=${playbackBufferState.value.availableDurationMs}, " +
                    "targetReached=${playbackBufferState.value.targetReached}, " +
                    "diskTargetBytes=${window.targetBytes}, " +
                    "diskLowWaterBytes=${window.lowWaterBytes}, " +
                    "estimatedMbps=${"%.1f".format(estimatedBytesPerMillisecond * 8_000.0 / 1_000_000.0)}, " +
                    "speed=$playbackSpeed, durationMs=$durationMs",
            )
        }
    }

    @Volatile
    private var aheadPrefetchJob: Job? = null

    @Volatile
    private var aheadBufferedEndInclusive = -1L

    @Volatile
    private var refillActive = true

    private val prefetchFrontier = StreamPrefetchFrontier()
    private val transferMetrics = SteamChunkTransferMetrics()

    @Volatile
    private var closed = false

    init {
        Log.i(
            STEAM_CONTENT_LOG_TAG,
            "Steam stream fixedChunkConcurrency=$prefetchConcurrency; scheduling follows the user setting",
        )
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
            // Fill the remaining user-configured request slots immediately instead of
            // waiting for the foreground chunk to finish before starting prefetch.
            scheduleAheadPrefetch(endExclusive)
            val result = ByteArray(requestedLength)
            var destinationOffset = 0
            var chunkIndex = firstSteamStreamChunkIndex(chunks, position)
            while (chunkIndex < chunks.size) {
                val chunk = chunks[chunkIndex]
                val chunkStart = chunk.offset
                val chunkEnd = chunkStart + chunk.uncompressedLength
                if (chunkStart >= endExclusive) break
                val sourceStart = max(position, chunkStart) - chunkStart
                val sourceEnd = min(endExclusive, chunkEnd) - chunkStart
                val copiedLength = (sourceEnd - sourceStart).toInt()
                val data = loadChunkSlice(
                    chunk = chunk,
                    offset = sourceStart.toInt(),
                    length = copiedLength,
                    priority = SteamStreamChunkPriority.FOREGROUND,
                )
                data.copyInto(
                    destination = result,
                    destinationOffset = destinationOffset,
                )
                destinationOffset += copiedLength
                chunkIndex += 1
            }
            check(destinationOffset == requestedLength) { "Steam video chunk has missing data" }
            lastReadEnd.set(endExclusive - 1L)
            latestReadPosition.set(endExclusive)
            scheduleAheadPrefetch(endExclusive)
            result
        }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(prefetchLock) {
            aheadPrefetchJob?.cancel()
            aheadPrefetchJob = null
        }
        fetchScope.cancel()
        decodeDispatcher.close()
        runCatching { cdnClient.close() }
        session.close()
    }

    private suspend fun loadChunkSlice(
        chunk: ChunkData,
        offset: Int,
        length: Int,
        priority: SteamStreamChunkPriority,
    ): ByteArray {
        streamCache.readSlice(chunk.offset, chunk.uncompressedLength, chunk.checksum, offset, length)?.let { cached ->
            onVerifiedChunkCached(chunk)
            return cached
        }
        val downloaded = withSteamCdnRecovery {
            requestChunk(chunk, priority).await()
        }
        if (length == 0) return ByteArray(0)
        return downloaded.copyOfRange(offset, offset + length)
    }

    private suspend fun requestChunk(
        chunk: ChunkData,
        priority: SteamStreamChunkPriority,
    ): Deferred<ByteArray> {
        var reusedRequest: StreamChunkRequest? = null
        val deferred = synchronized(inFlightLock) {
            val existing = inFlightChunks[chunk.offset]
            if (existing != null) {
                if (priority == SteamStreamChunkPriority.FOREGROUND) {
                    existing.promote()
                }
                reusedRequest = existing
                return@synchronized existing.deferred
            }
            val requestId = nextChunkRequestId.incrementAndGet()
            val result = CompletableDeferred<ByteArray>()
            val priorityState = AtomicReference(priority)
            val request =
                fetchScope.launch(start = CoroutineStart.LAZY) {
                    var metricsRequestStarted = false
                    var stagedEncrypted: SteamEncryptedChunkSpool? = null
                    try {
                        val downloaded =
                            run {
                                // The exact user setting governs active CDN requests. Release
                                // the network permit as soon as the compressed payload arrives;
                                // high-memory LZMA work must not reduce network concurrency.
                                var encryptedPayload: ByteArray? =
                                    networkScheduler.withPermit(priorityState.get(), requestId) {
                                        val startedAtNanos = System.nanoTime()
                                        transferMetrics.requestStarted()
                                        metricsRequestStarted = true
                                        downloadEncryptedChunk(
                                            cdnClient = cdnClient,
                                            servers = access.servers,
                                            depotId = depotId,
                                            chunk = chunk,
                                            authTokens = access.authTokens,
                                            selector = selector,
                                            control = { SteamDownloadControl.CONTINUE },
                                            onSuccess = { server ->
                                                latestCdnHost = resolveCdnRequestHost(server.vHost, server.host)
                                            },
                                        ).also {
                                            transferMetrics
                                                .requestCompleted(chunk.compressedLength, startedAtNanos)
                                                ?.let { metrics ->
                                                    Log.d(
                                                        STEAM_CONTENT_LOG_TAG,
                                                        "Steam stream chunks=${metrics.completedChunks}, " +
                                                            "aggregate=${"%.1f".format(metrics.aggregateMbps)}Mbps, " +
                                                            "active=${metrics.activeRequests}, peak=${metrics.peakActiveRequests}, " +
                                                            "staged=${stagedRequestCount.get()}, " +
                                                            "configuredConcurrency=$prefetchConcurrency",
                                                    )
                                                }
                                            metricsRequestStarted = false
                                        }
                                    }
                                if (priorityState.get() == SteamStreamChunkPriority.PREFETCH) {
                                    stagedEncrypted =
                                        streamCache.stageEncrypted(
                                            requestId = requestId,
                                            chunkOffset = chunk.offset,
                                            data = checkNotNull(encryptedPayload),
                                        )
                                    stagedRequestCount.incrementAndGet()
                                    // Do not retain the network array while waiting in the
                                    // decode queue; the encrypted spool is bounded by the
                                    // requested playback range and removed after processing.
                                    encryptedPayload = null
                                }
                                val memoryReservation = estimatedSteamChunkPeakMemoryBytes(
                                    chunk.compressedLength,
                                    chunk.uncompressedLength,
                                ).coerceAtLeast(STEAM_STREAM_MIN_REQUEST_MEMORY_BYTES)
                                    .coerceAtMost(steamStreamMemoryBudgetBytes(Runtime.getRuntime().maxMemory()))
                                memoryScheduler.withPermit(
                                    requestedBytes = memoryReservation,
                                    priority = priorityState.get(),
                                    requestId = requestId,
                                    order = chunk.offset,
                                ) {
                                    val payload = withContext(decodeDispatcher) {
                                        val encryptedForDecode =
                                            encryptedPayload
                                                ?: streamCache.readStaged(checkNotNull(stagedEncrypted))
                                        decodeDepotChunk(chunk, encryptedForDecode, access.depotKey)
                                    }
                                    // A promoted foreground reader can consume authenticated
                                    // bytes immediately. Pure prefetch remains pending until the
                                    // chunk is durably available to the contiguous disk buffer.
                                    if (priorityState.get() == SteamStreamChunkPriority.FOREGROUND) {
                                        result.complete(payload)
                                    }
                                    try {
                                        streamCache.commitVerified(chunk.offset, chunk.checksum, payload)
                                        onVerifiedChunkCached(chunk)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: VirtualMachineError) {
                                        throw error
                                    } catch (error: Throwable) {
                                        Log.w(STEAM_CONTENT_LOG_TAG, "Steam stream cache commit failed at ${chunk.offset}", error)
                                        if (priorityState.get() == SteamStreamChunkPriority.PREFETCH) {
                                            throw IOException("Steam stream cache commit failed at ${chunk.offset}", error)
                                        }
                                    }
                                    payload
                                }
                            }
                        // DepotChunk.process authenticated the payload and the cache commit
                        // completed while its memory reservation was still held.
                        if (!result.isCompleted) result.complete(downloaded)
                    } catch (error: Throwable) {
                        if (metricsRequestStarted) transferMetrics.requestFailed()
                        synchronized(inFlightLock) {
                            if (inFlightChunks[chunk.offset]?.id == requestId) {
                                inFlightChunks.remove(chunk.offset)
                            }
                        }
                        result.completeExceptionally(error)
                        if (error is CancellationException || error is VirtualMachineError) throw error
                    } finally {
                        stagedEncrypted?.let { spool ->
                            spool.delete()
                            stagedRequestCount.decrementAndGet()
                        }
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
                    deferred = result,
                    job = request,
                    priorityState = priorityState,
                )
            request.start()
            result
        }
        if (priority == SteamStreamChunkPriority.FOREGROUND) {
            reusedRequest?.let { request ->
                networkScheduler.promote(request.id)
                memoryScheduler.promote(request.id)
            }
        }
        return deferred
    }

    private fun scheduleAheadPrefetch(afterExclusive: Long) {
        if (closed || afterExclusive >= contentLength) return
        val generation = prefetchGeneration.get()
        synchronized(prefetchLock) {
            if (aheadPrefetchJob?.isActive == true) return
            val targetAheadBytes = dynamicAheadBytes.get()
            val lowWaterBytes = dynamicLowWaterBytes.get().coerceAtMost(targetAheadBytes)
            val remainingAheadBytes =
                (aheadBufferedEndInclusive - afterExclusive + 1L).coerceAtLeast(0L)
            if (!refillActive && remainingAheadBytes < lowWaterBytes) refillActive = true
            if (!refillActive) return
            val range =
                steamStreamRefillRange(
                    contentLength = contentLength,
                    consumedPosition = afterExclusive,
                    bufferedEndInclusive = aheadBufferedEndInclusive,
                    targetAheadBytes = targetAheadBytes,
                    // Once a refill starts, target itself becomes the stop line;
                    // crossing back above 8 seconds must not end a 15-second fill.
                    lowWaterBytes = targetAheadBytes,
                )
            if (range == null) {
                refillActive = false
                return
            }
            aheadPrefetchJob =
                fetchScope.launch {
                    val completed = prefetchRange(range, generation)
                    synchronized(prefetchLock) {
                        aheadPrefetchJob = null
                    }
                    if (completed && generation == prefetchGeneration.get()) {
                        scheduleAheadPrefetch(latestReadPosition.get())
                    }
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
        val stalePrefetchJobs =
            synchronized(inFlightLock) {
                inFlightChunks
                    .filterValues { request -> request.priority == SteamStreamChunkPriority.PREFETCH }
                    .map { (offset, request) ->
                        inFlightChunks.remove(offset)
                        request.job
                    }
            }
        stalePrefetchJobs.forEach(Job::cancel)
        synchronized(prefetchLock) {
            aheadPrefetchJob?.cancel()
            aheadPrefetchJob = null
            aheadBufferedEndInclusive = position - 1L
            refillActive = true
            prefetchFrontier.reset()
            contiguousBufferTracker.reset(position)
        }
        publishPlaybackBufferState()
    }

    private suspend fun prefetchRange(
        range: SteamStreamByteRange,
        generation: Long,
    ): Boolean = coroutineScope {
        val selectedChunks = chunks.mapNotNull { chunk ->
                val chunkEnd = chunk.offset + chunk.uncompressedLength - 1L
                if (chunkEnd < range.start || chunk.offset > range.endInclusive) return@mapNotNull null
                when (prefetchFrontier.plan(chunk.offset)) {
                    StreamPrefetchPlan.COMPLETE -> null
                    StreamPrefetchPlan.NEW -> PlannedStreamChunk(chunk, ownsPlan = true)
                    StreamPrefetchPlan.IN_FLIGHT -> PlannedStreamChunk(chunk, ownsPlan = false)
                }
            }
        if (selectedChunks.isEmpty()) return@coroutineScope true
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            // Launch the playback range as independently staged requests. Network
            // concurrency remains fixed by networkScheduler, while requests that
            // already downloaded may wait for decode/cache without consuming one of
            // those network slots. Batching bounds coroutine metadata for manifests
            // containing unusually tiny chunks; staged bytes remain range-bounded.
            selectedChunks.chunked(STEAM_STREAM_MAX_STAGED_REQUESTS).forEach { batch ->
                batch
                    .map { plannedChunk ->
                        async {
                            if (closed || generation != prefetchGeneration.get()) return@async
                            val chunk = plannedChunk.chunk
                            try {
                                loadChunkSlice(chunk, 0, 0, SteamStreamChunkPriority.PREFETCH)
                                prefetchFrontier.complete(chunk.offset)
                            } catch (error: Throwable) {
                                failed.set(true)
                                if (plannedChunk.ownsPlan) prefetchFrontier.fail(chunk.offset)
                                if (error is CancellationException || error is VirtualMachineError) throw error
                            }
                        }
                    }.awaitAll()
            }
        } finally {
            selectedChunks.filter(PlannedStreamChunk::ownsPlan).forEach { planned ->
                prefetchFrontier.fail(planned.chunk.offset)
            }
        }
        !failed.get() && generation == prefetchGeneration.get()
    }

    private fun onVerifiedChunkCached(chunk: ChunkData) {
        aheadBufferedEndInclusive = contiguousBufferTracker.markCompleted(chunk)
        publishPlaybackBufferState()
    }

    private fun publishPlaybackBufferState() {
        val clock = latestPlaybackClock.get()
        if (clock.durationMs <= 0L) {
            mutablePlaybackBufferState.value = WorkshopVideoBufferState()
            return
        }
        val averageBytesPerMillisecond = averageBytesPerMillisecond(contentLength, clock.durationMs)
        if (averageBytesPerMillisecond <= 0.0) return
        val mediaBufferedMs = (clock.bufferedPositionMs - clock.playbackPositionMs).coerceAtLeast(0L)
        val contiguousEndInclusive = contiguousBufferTracker.bufferedEndInclusive()
        val diskAheadBytes =
            (contiguousEndInclusive - latestReadPosition.get() + 1L).coerceAtLeast(0L)
        val diskAheadMs = (diskAheadBytes / averageBytesPerMillisecond).toLong().coerceAtLeast(0L)
        val remainingDurationMs = (clock.durationMs - clock.playbackPositionMs).coerceAtLeast(0L)
        val availableDurationMs =
            (mediaBufferedMs + diskAheadMs).coerceIn(0L, remainingDurationMs)
        val cacheCapacityDurationMs =
            (streamCache.prefetchCapacityBytes / averageBytesPerMillisecond).toLong().coerceAtLeast(1L)
        val targetDurationMs =
            min(STEAM_STREAM_TARGET_BUFFER_MS, min(cacheCapacityDurationMs, remainingDurationMs))
        val lowWaterDurationMs = min(STEAM_STREAM_LOW_WATER_BUFFER_MS, targetDurationMs)
        mutablePlaybackBufferState.value =
            WorkshopVideoBufferState(
                bufferedPositionMs =
                    (clock.playbackPositionMs + availableDurationMs)
                        .coerceAtMost(clock.durationMs),
                availableDurationMs = availableDurationMs,
                targetDurationMs = targetDurationMs,
                lowWaterDurationMs = lowWaterDurationMs,
                targetReached =
                    availableDurationMs + STEAM_STREAM_BUFFER_READY_TOLERANCE_MS >= targetDurationMs,
            )
    }

}

internal data class SteamStreamPlaybackClock(
    val playbackPositionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/** Advances only when every preceding Steam chunk from the current seek point is verified. */
internal class SteamContiguousBufferTracker(
    private val chunks: List<ChunkData>,
) {
    private val completedOffsets = mutableSetOf<Long>()
    private var nextChunkIndex = 0
    private var startPosition = 0L
    private var endInclusive = -1L

    @Synchronized
    fun reset(position: Long) {
        completedOffsets.clear()
        startPosition = position.coerceAtLeast(0L)
        endInclusive = startPosition - 1L
        nextChunkIndex = firstSteamStreamChunkIndex(chunks, startPosition)
    }

    @Synchronized
    fun markCompleted(chunk: ChunkData): Long {
        completedOffsets += chunk.offset
        while (nextChunkIndex < chunks.size) {
            val next = chunks[nextChunkIndex]
            if (next.offset !in completedOffsets) break
            completedOffsets.remove(next.offset)
            endInclusive = max(endInclusive, next.offset + next.uncompressedLength - 1L)
            nextChunkIndex += 1
        }
        return endInclusive
    }

    @Synchronized
    fun bufferedEndInclusive(): Long = endInclusive
}

internal data class PlannedStreamChunk(
    val chunk: ChunkData,
    val ownsPlan: Boolean,
)

internal data class SteamChunkTransferSnapshot(
    val completedChunks: Long,
    val aggregateMbps: Double,
    val activeRequests: Int,
    val peakActiveRequests: Int,
)

/**
 * Observes the real Depot request layer without feeding back into the fixed user concurrency.
 */
internal class SteamChunkTransferMetrics {
    private var completedChunks = 0L
    private val streamStartedAtNanos = System.nanoTime()
    private val rollingSamples = ArrayDeque<SteamChunkTransferSample>()
    private var rollingBytes = 0L
    private var activeRequests = 0
    private var peakActiveRequests = 0

    @Synchronized
    fun requestStarted() {
        activeRequests += 1
        peakActiveRequests = max(peakActiveRequests, activeRequests)
    }

    @Synchronized
    fun requestCompleted(
        compressedBytes: Int,
        @Suppress("UNUSED_PARAMETER") startedAtNanos: Long,
    ): SteamChunkTransferSnapshot? {
        activeRequests = (activeRequests - 1).coerceAtLeast(0)
        if (compressedBytes <= 0) return null
        val completedAtNanos = System.nanoTime()
        rollingSamples.addLast(SteamChunkTransferSample(completedAtNanos, compressedBytes))
        rollingBytes += compressedBytes
        val cutoffNanos = completedAtNanos - STREAM_METRICS_WINDOW_NANOS
        while (rollingSamples.firstOrNull()?.completedAtNanos?.let { it < cutoffNanos } == true) {
            rollingBytes -= rollingSamples.removeFirst().bytes
        }
        completedChunks += 1L
        return if (completedChunks % STREAM_METRICS_LOG_INTERVAL_CHUNKS == 0L) {
            val elapsedNanos =
                min(
                    STREAM_METRICS_WINDOW_NANOS,
                    completedAtNanos - streamStartedAtNanos,
                ).coerceAtLeast(1L)
            SteamChunkTransferSnapshot(
                completedChunks = completedChunks,
                aggregateMbps = rollingBytes.toDouble() * 8_000.0 / elapsedNanos.toDouble(),
                activeRequests = activeRequests,
                peakActiveRequests = peakActiveRequests,
            )
        } else {
            null
        }
    }

    @Synchronized
    fun requestFailed() {
        activeRequests = (activeRequests - 1).coerceAtLeast(0)
    }
}

internal enum class StreamPrefetchPlan {
    NEW,
    IN_FLIGHT,
    COMPLETE,
}

internal class StreamPrefetchFrontier {
    private val planned = mutableSetOf<Long>()
    private val completed = mutableSetOf<Long>()

    @Synchronized
    fun plan(offset: Long): StreamPrefetchPlan =
        when {
            offset in completed -> StreamPrefetchPlan.COMPLETE
            planned.add(offset) -> StreamPrefetchPlan.NEW
            else -> StreamPrefetchPlan.IN_FLIGHT
        }

    @Synchronized
    fun complete(offset: Long) {
        planned.remove(offset)
        completed += offset
    }

    @Synchronized
    fun fail(offset: Long) {
        planned.remove(offset)
    }

    @Synchronized
    fun reset() {
        planned.clear()
        completed.clear()
    }
}

internal fun firstSteamStreamChunkIndex(
    chunks: List<ChunkData>,
    position: Long,
): Int {
    var low = 0
    var high = chunks.size
    while (low < high) {
        val middle = (low + high) ushr 1
        val chunk = chunks[middle]
        if (chunk.offset + chunk.uncompressedLength <= position) low = middle + 1 else high = middle
    }
    return low
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
    val errors = mutableListOf<Throwable>()
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: SteamDownloadCancelledException) {
            throw error
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            lastError = error
            errors += error
            failures += describeServer(server, hasToken) + "：" +
                (error.message ?: error.javaClass.simpleName)
        }
    }
    throw SteamCdnTransferException(
        message = buildCdnError("manifest", failures, lastError),
        cause = lastError,
        recoverable = errors.isNotEmpty() && errors.all(::hasIoCause),
    )
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
    val encrypted =
        downloadEncryptedChunk(
            cdnClient = cdnClient,
            servers = servers,
            depotId = depotId,
            chunk = chunk,
            authTokens = authTokens,
            selector = selector,
            control = control,
            onSuccess = onSuccess,
        )
    return decodeDepotChunk(chunk, encrypted, depotKey)
}

internal suspend fun downloadEncryptedChunk(
    cdnClient: SteamCdnClient,
    servers: List<Server>,
    depotId: Int,
    chunk: ChunkData,
    authTokens: CdnAuthTokenProvider,
    selector: CdnServerSelector,
    control: suspend () -> SteamDownloadControl,
    onSuccess: ((Server) -> Unit)? = null,
): ByteArray {
    validateManifestChunk(chunk)
    var lastError: Throwable? = null
    val errors = mutableListOf<Throwable>()
    val failures = mutableListOf<String>()
    selector.candidates(servers.take(MAX_CDN_ATTEMPTS)).forEach { server ->
        currentCoroutineContext().ensureActive()
        checkDownloadControl(control)
        var hasToken = false
        val attemptStartedAtNanos = System.nanoTime()
        selector.recordStart(server)
        try {
            val encrypted = ByteArray(chunk.compressedLength)
            val token = authTokens.get(server)
            hasToken = token != null
            val downloadedBytes =
                cdnClient.downloadDepotChunk(
                    depotId,
                    chunk,
                    server,
                    encrypted,
                    null,
                    null,
                    token,
                )
            check(downloadedBytes == encrypted.size) { "Steam chunk compressed length mismatch" }
            selector.recordSuccess(
                server = server,
                bytes = encrypted.size,
                elapsedNanos = System.nanoTime() - attemptStartedAtNanos,
            )
            onSuccess?.invoke(server)
            return encrypted
        } catch (error: CancellationException) {
            selector.recordCancelled(server)
            throw error
        } catch (error: SteamDownloadCancelledException) {
            selector.recordCancelled(server)
            throw error
        } catch (error: VirtualMachineError) {
            selector.recordCancelled(server)
            throw error
        } catch (error: Throwable) {
            selector.recordFailure(server)
            lastError = error
            errors += error
            failures += describeServer(server, hasToken) + "：" +
                (error.message ?: error.javaClass.simpleName)
        }
    }
    throw SteamCdnTransferException(
        message = buildCdnError("chunk", failures, lastError),
        cause = lastError,
        recoverable = errors.isNotEmpty() && errors.all(::hasIoCause),
    )
}

internal fun decodeDepotChunk(
    chunk: ChunkData,
    encrypted: ByteArray,
    depotKey: ByteArray,
): ByteArray {
    val decoded = ByteArray(chunk.uncompressedLength)
    val written = DepotChunk.process(chunk, encrypted, decoded, depotKey)
    check(written == decoded.size) { "Steam chunk decompressed length mismatch" }
    return decoded
}

internal class SteamCdnTransferException(
    message: String,
    cause: Throwable?,
    val recoverable: Boolean,
) : IllegalStateException(message, cause)

internal fun isRecoverableSteamCdnFailure(error: Throwable): Boolean {
    val transferError = generateSequence(error) { it.cause }.filterIsInstance<SteamCdnTransferException>().firstOrNull()
        ?: return false
    return transferError.recoverable
}

private fun hasIoCause(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { it is IOException }

internal suspend fun <T> withSteamCdnRecovery(
    maxAttempts: Int = CDN_TRANSFER_MAX_ATTEMPTS,
    delayBeforeRetry: suspend (Int) -> Unit = { retryNumber ->
        delay(CDN_TRANSFER_RETRY_DELAYS_MS.getOrElse(retryNumber - 1) { CDN_TRANSFER_RETRY_DELAYS_MS.last() })
    },
    onRetry: (Int, Throwable) -> Unit = { _, _ -> },
    block: suspend (Int) -> T,
): T {
    require(maxAttempts >= 1) { "Steam CDN attempt count must be positive" }
    var attempt = 1
    while (true) {
        try {
            return block(attempt)
        } catch (error: CancellationException) {
            throw error
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            if (attempt >= maxAttempts || !isRecoverableSteamCdnFailure(error)) throw error
            onRetry(attempt, error)
            delayBeforeRetry(attempt)
            attempt += 1
        }
    }
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
    validateManifestFilePlans(plans)
    DownloadDiskReservations.withReservation(destinationDirectory, remainingDownloadBytes(destinationDirectory, plans)) {
        var nextPlanIndex = 0
        val smallFileBatchSize = smallFilePipelineBatchSize(chunkConcurrency)
        while (nextPlanIndex < plans.size) {
            currentCoroutineContext().ensureActive()
            checkDownloadControl(control)
            val currentPlan = plans[nextPlanIndex]
            val batch =
                buildList {
                    while (
                        nextPlanIndex < plans.size &&
                        size < (if (currentPlan.isSmallFilePipelineCandidate()) smallFileBatchSize else LARGE_FILE_PIPELINE_SIZE) &&
                        plans[nextPlanIndex].isSmallFilePipelineCandidate() == currentPlan.isSmallFilePipelineCandidate()
                    ) {
                        add(plans[nextPlanIndex++])
                    }
                }
            val perFileConcurrency =
                if (currentPlan.isSmallFilePipelineCandidate()) 1 else (chunkConcurrency / batch.size).coerceAtLeast(1)
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
                        chunkConcurrency = perFileConcurrency,
                        control = control,
                        progressReporter = progressReporter,
                    )
                }
            }.awaitAll()
        }
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
    validateManifestFilePlans(listOf(plan))
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
            onChunkWritten = { decodedChunk ->
                progressReporter.addDownloadedBytes(
                    fileName = manifestFile.fileName,
                    bytes = decodedChunk.size.toLong(),
                )
            },
        )
        output.fd.sync()
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
                val written = CompletableDeferred<Unit>()
                completed.send(DownloadedChunk(chunk.offset, data, written))
                written.await()
            }
        }
    }
    repeat(chunks.size) {
        val chunk = completed.receive()
        output.seek(chunk.offset)
        output.write(chunk.data)
        onChunkWritten(chunk.data)
        chunk.written.complete(Unit)
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
            .addInterceptor { chain ->
                val request = chain.request()
                val secureUrl = request.url.upgradeSteamCdnUrl()
                val response = chain.proceed(
                    if (secureUrl == request.url) request else request.newBuilder().url(secureUrl).build(),
                )
                if (request.url.isSteamManifestRequest()) response.withBoundedManifestBody() else response
            }
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

internal fun HttpUrl.upgradeSteamCdnUrl(): HttpUrl =
    if (scheme == "http") {
        newBuilder().scheme("https").port(HTTPS_PORT).build()
    } else {
        this
    }

internal fun HttpUrl.isSteamManifestRequest(): Boolean =
    encodedPathSegments.any { it.equals("manifest", ignoreCase = true) }

internal fun Response.withBoundedManifestBody(): Response {
    if (!isSuccessful) {
        body.close()
        return newBuilder().body(ByteArray(0).toResponseBody(null)).build()
    }
    val responseBody = body
    val compressed = responseBody.byteStream().use { it.readBytesBounded(MAX_MANIFEST_RESPONSE_BYTES) }
    val manifest =
        ZipInputStream(ByteArrayInputStream(compressed)).use { archive ->
            requireNotNull(archive.nextEntry) { "Steam manifest response ZIP is empty" }
            archive.readBytesBounded(MAX_MANIFEST_DECOMPRESSED_BYTES)
        }
    val sanitized =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { archive ->
                archive.putNextEntry(ZipEntry("manifest.bin"))
                archive.write(manifest)
                archive.closeEntry()
            }
            bytes.toByteArray()
        }
    responseBody.close()
    return newBuilder().body(sanitized.toResponseBody(responseBody.contentType())).build()
}

internal fun java.io.InputStream.readBytesBounded(maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "Invalid response byte limit: $maxBytes" }
    val output = ByteArrayOutputStream(min(8192, maxBytes))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        require(total <= maxBytes - read) { "Steam manifest response exceeds $maxBytes bytes" }
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
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
                    loginID = nextContentSteamLoginId(),
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
    val written: CompletableDeferred<Unit>,
)

internal data class ManifestFilePlan(
    val file: FileData,
    val chunks: List<ChunkData>,
) {
    fun isSmallFilePipelineCandidate(): Boolean = isSmallFilePipelineCandidate(file.totalSize, chunks.size)
}

internal class CdnServerSelector {
    private val nextTieBreakerIndex = AtomicInteger()
    private val failedHosts = mutableMapOf<String, Long>()
    private val performance = mutableMapOf<String, CdnHostPerformance>()

    @Synchronized
    fun candidates(servers: List<Server>): List<Server> {
        if (servers.size < 2) return servers
        val now = System.currentTimeMillis()
        val available = servers.filterNot { server -> isPenalized(server, now) }
        val failed = servers.filter { server -> isPenalized(server, now) }
        if (available.size < 2) return available + failed
        val start = Math.floorMod(nextTieBreakerIndex.getAndIncrement(), available.size)
        val rotated = available.drop(start) + available.take(start)
        val bestKnownBytesPerSecond =
            performance.values.maxOfOrNull(CdnHostPerformance::bytesPerSecond)?.takeIf { it > 0.0 }
                ?: CDN_UNSAMPLED_BASELINE_BYTES_PER_SECOND
        val ranked =
            rotated
                .withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<Server>> { indexed ->
                        val stats = performance[indexed.value.selectorKey()]
                        val estimated =
                            stats?.bytesPerSecond?.takeIf { stats.samples > 0 }
                                ?: bestKnownBytesPerSecond * CDN_UNSAMPLED_EXPLORATION_MULTIPLIER
                        estimated / ((stats?.activeRequests ?: 0) + 1).toDouble()
                    }.thenBy(IndexedValue<Server>::index),
                ).map(IndexedValue<Server>::value)
        return ranked + failed
    }

    @Synchronized
    fun recordStart(server: Server) {
        performance.getOrPut(server.selectorKey(), ::CdnHostPerformance).activeRequests += 1
    }

    @Synchronized
    fun recordFailure(server: Server) {
        performance[server.selectorKey()]?.let { stats ->
            stats.activeRequests = (stats.activeRequests - 1).coerceAtLeast(0)
        }
        failedHosts[server.selectorKey()] = System.currentTimeMillis() + CDN_FAILURE_COOLDOWN_MS
    }

    @Synchronized
    fun recordCancelled(server: Server) {
        performance[server.selectorKey()]?.let { stats ->
            stats.activeRequests = (stats.activeRequests - 1).coerceAtLeast(0)
        }
    }

    @Synchronized
    fun recordSuccess(
        server: Server,
        bytes: Int,
        elapsedNanos: Long,
    ) {
        val key = server.selectorKey()
        val stats = performance.getOrPut(key, ::CdnHostPerformance)
        stats.activeRequests = (stats.activeRequests - 1).coerceAtLeast(0)
        if (bytes > 0 && elapsedNanos > 0L) {
            val sampleBytesPerSecond = bytes.toDouble() * 1_000_000_000.0 / elapsedNanos.toDouble()
            stats.bytesPerSecond =
                if (stats.samples == 0) {
                    sampleBytesPerSecond
                } else {
                    stats.bytesPerSecond * (1.0 - CDN_THROUGHPUT_SAMPLE_WEIGHT) +
                        sampleBytesPerSecond * CDN_THROUGHPUT_SAMPLE_WEIGHT
                }
            stats.samples += 1
        }
        failedHosts.remove(key)
    }

    private fun isPenalized(server: Server, now: Long): Boolean {
        val key = server.selectorKey()
        val expiry = failedHosts[key] ?: return false
        if (expiry <= now) {
            failedHosts.remove(key, expiry)
            return false
        }
        return true
    }

    private fun Server.selectorKey(): String = (resolveCdnRequestHost(vHost, host) ?: host.orEmpty()).lowercase()

    private companion object {
        const val CDN_FAILURE_COOLDOWN_MS = 30_000L
        const val CDN_UNSAMPLED_BASELINE_BYTES_PER_SECOND = 1_000_000.0
        const val CDN_UNSAMPLED_EXPLORATION_MULTIPLIER = 2.0
        const val CDN_THROUGHPUT_SAMPLE_WEIGHT = 0.25
    }
}

private data class SteamChunkTransferSample(
    val completedAtNanos: Long,
    val bytes: Int,
)

private class CdnHostPerformance(
    var activeRequests: Int = 0,
    var bytesPerSecond: Double = 0.0,
    var samples: Int = 0,
)

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
internal const val CONTENT_STEAM_LOGIN_ID_BASE = 0x57484300
private val nextContentLoginId = AtomicInteger(CONTENT_STEAM_LOGIN_ID_BASE)

internal fun nextContentSteamLoginId(): Int = nextContentLoginId.updateAndGet { current ->
    if (current == Int.MAX_VALUE) CONTENT_STEAM_LOGIN_ID_BASE else current + 1
}

internal data class ManifestChunkBoundary(
    val offset: Long,
    val compressedLength: Long,
    val uncompressedLength: Long,
)

internal data class ManifestFileBoundary(
    val path: String,
    val length: Long,
    val chunks: List<ManifestChunkBoundary>,
)

internal fun validateManifestBoundaries(files: List<ManifestFileBoundary>): Long {
    require(files.isNotEmpty()) { "Steam manifest contains no downloadable files" }
    require(files.size <= MAX_MANIFEST_FILE_COUNT) {
        "Steam manifest file count ${files.size} exceeds limit $MAX_MANIFEST_FILE_COUNT"
    }
    var totalBytes = 0L
    var totalChunks = 0
    files.forEach { file ->
        require(file.length in 0..MAX_MANIFEST_FILE_BYTES) {
            "Steam manifest file exceeds $MAX_MANIFEST_FILE_BYTES bytes: ${file.path} (${file.length})"
        }
        require(totalBytes <= MAX_MANIFEST_TASK_BYTES - file.length) {
            "Steam manifest total size exceeds $MAX_MANIFEST_TASK_BYTES bytes"
        }
        totalBytes += file.length
        var previousEnd = 0L
        file.chunks.sortedBy(ManifestChunkBoundary::offset).forEach { chunk ->
            require(totalChunks < MAX_MANIFEST_CHUNK_COUNT) {
                "Steam manifest chunk count exceeds $MAX_MANIFEST_CHUNK_COUNT"
            }
            totalChunks += 1
            require(chunk.offset >= 0L) { "Steam manifest chunk has a negative offset: ${file.path}" }
            require(chunk.compressedLength >= 0L) {
                "Steam manifest chunk has a negative compressed length: ${file.path} at ${chunk.offset}"
            }
            require(chunk.compressedLength <= MAX_MANIFEST_CHUNK_BYTES) {
                "Steam manifest compressed chunk exceeds $MAX_MANIFEST_CHUNK_BYTES bytes: ${file.path} at ${chunk.offset}"
            }
            require(chunk.uncompressedLength > 0L) {
                "Steam manifest chunk has a non-positive uncompressed length: ${file.path} at ${chunk.offset}"
            }
            require(chunk.uncompressedLength <= MAX_MANIFEST_CHUNK_BYTES) {
                "Steam manifest uncompressed chunk exceeds $MAX_MANIFEST_CHUNK_BYTES bytes: ${file.path} at ${chunk.offset}"
            }
            require(chunk.compressedLength <= MAX_MANIFEST_CHUNK_MEMORY_BYTES - chunk.uncompressedLength) {
                "Steam manifest chunk memory peak exceeds $MAX_MANIFEST_CHUNK_MEMORY_BYTES bytes: ${file.path} at ${chunk.offset}"
            }
            require(chunk.offset <= file.length - chunk.uncompressedLength) {
                "Steam manifest chunk range exceeds file ${file.path}: offset=${chunk.offset}, " +
                    "length=${chunk.uncompressedLength}, fileLength=${file.length}"
            }
            require(chunk.offset == previousEnd) {
                "Steam manifest chunks do not continuously cover ${file.path} at offset ${chunk.offset}"
            }
            previousEnd = chunk.offset + chunk.uncompressedLength
        }
        require(previousEnd == file.length) { "Steam manifest chunks do not cover all of ${file.path}" }
    }
    return totalBytes
}

internal fun validateManifestFilePlans(plans: List<ManifestFilePlan>): Long =
    validateManifestFiles(plans.map(ManifestFilePlan::file))

internal fun validateManifestFiles(files: List<FileData>): Long =
    validateManifestBoundaries(
        files.map { file ->
            ManifestFileBoundary(
                path = file.fileName,
                length = file.totalSize,
                chunks =
                    file.chunks.map(::manifestChunkBoundary),
            )
        },
    )

internal fun validateManifestChunk(chunk: ChunkData) {
    val boundary = manifestChunkBoundary(chunk)
    require(boundary.compressedLength in 0..MAX_MANIFEST_CHUNK_BYTES) {
        "Steam manifest compressed chunk length is invalid: ${boundary.compressedLength}"
    }
    require(boundary.uncompressedLength in 0..MAX_MANIFEST_CHUNK_BYTES) {
        "Steam manifest uncompressed chunk length is invalid: ${boundary.uncompressedLength}"
    }
}

private fun manifestChunkBoundary(chunk: ChunkData): ManifestChunkBoundary =
    ManifestChunkBoundary(
        offset = chunk.offset,
        compressedLength = chunk.compressedLength.toLong(),
        uncompressedLength = chunk.uncompressedLength.toLong(),
    )

internal fun requiredDownloadSpace(
    pendingBytes: Long,
    usableSpace: Long,
): Long {
    require(pendingBytes >= 0L) { "Steam download pending size is negative: $pendingBytes" }
    require(usableSpace >= 0L) { "Destination usable space is negative: $usableSpace" }
    if (pendingBytes == 0L) return 0L
    val percentageReserve = pendingBytes / DOWNLOAD_SPACE_RESERVE_DIVISOR
    val reserve = maxOf(MIN_DOWNLOAD_SPACE_RESERVE_BYTES, percentageReserve).coerceAtMost(MAX_DOWNLOAD_SPACE_RESERVE_BYTES)
    require(pendingBytes <= Long.MAX_VALUE - reserve) { "Steam download disk requirement is too large" }
    val required = pendingBytes + reserve
    require(usableSpace >= required) {
        "Insufficient destination space: need $required bytes ($pendingBytes download + $reserve safety reserve), " +
            "but only $usableSpace bytes are usable"
    }
    return required
}

internal fun remainingDownloadBytes(
    destinationDirectory: File,
    plans: List<ManifestFilePlan>,
): Long =
    plans.sumOf { plan ->
        val destination = WorkshopStagingPath.resolve(destinationDirectory, plan.file.fileName)
        if (isCompletedFile(destination, plan.file, plan.chunks)) {
            0L
        } else {
            val partial = File(destination.parentFile, "${destination.name}.wallhub.part")
            val verified = findVerifiedChunkOffsets(partial, plan.chunks)
            plan.chunks
                .filterNot { it.offset in verified }
                .sumOf { it.uncompressedLength.toLong() }
        }
    }

internal object DownloadDiskReservations {
    private val mutex = Mutex()
    private val reservedByVolume = mutableMapOf<String, Long>()

    suspend fun <T> withReservation(
        destinationDirectory: File,
        pendingBytes: Long,
        block: suspend () -> T,
    ): T {
        val storageRoot =
            generateSequence(destinationDirectory.absoluteFile) { it.parentFile }.firstOrNull(File::exists)
                ?: error("Unable to locate destination storage for ${destinationDirectory.absolutePath}")
        val key = "${storageRoot.canonicalFile.toPath().root}:${storageRoot.totalSpace}"
        val required = requiredDownloadSpace(pendingBytes, Long.MAX_VALUE)
        mutex.withLock {
            val alreadyReserved = reservedByVolume[key] ?: 0L
            require(alreadyReserved <= storageRoot.usableSpace && required <= storageRoot.usableSpace - alreadyReserved) {
                "Insufficient destination space: need $required bytes with $alreadyReserved bytes reserved by other downloads, " +
                    "but only ${storageRoot.usableSpace} bytes are usable"
            }
            reservedByVolume[key] = alreadyReserved + required
        }
        return try {
            block()
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                mutex.withLock {
                    val remaining = (reservedByVolume[key] ?: required) - required
                    if (remaining > 0L) reservedByVolume[key] = remaining else reservedByVolume.remove(key)
                }
            }
        }
    }
}
internal const val HTTPS_PORT = 443
internal const val CONNECT_TIMEOUT_MS = 20_000L
internal const val LOGON_TIMEOUT_MS = 30_000L
internal const val CDN_SERVER_LIMIT = 20
internal const val MAX_CDN_ATTEMPTS = 8
internal const val MAX_CDN_ERROR_DETAILS = 3
internal const val CDN_CONNECT_TIMEOUT_MS = 20_000L
internal const val CDN_READ_TIMEOUT_MS = 60_000L
internal const val CDN_WRITE_TIMEOUT_MS = 20_000L
internal const val CDN_KEEP_ALIVE_MINUTES = 5L
internal const val CDN_TRANSFER_MAX_ATTEMPTS = 3
private val CDN_TRANSFER_RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L)
internal const val STEAM_CONTENT_LOG_TAG = "WallHubDownload"
internal const val HASH_BUFFER_SIZE = 1024 * 1024
internal const val TOKEN_MIN_VALIDITY_MS = 30_000L
internal const val PAUSE_POLL_INTERVAL_MS = 250L
internal const val STREAM_MIN_CACHE_LIMIT_BYTES = 16L * 1024L * 1024L
internal val VIDEO_FILE_EXTENSIONS = setOf("mp4", "webm", "mkv", "m4v")

internal const val SMALL_FILE_PIPELINE_MAX_BYTES = 512L * 1024L
internal const val SMALL_FILE_PIPELINE_MAX_CHUNKS = 2
internal const val LARGE_FILE_PIPELINE_SIZE = 4
internal const val MAX_MANIFEST_FILE_COUNT = 100_000
internal const val MAX_MANIFEST_FILE_BYTES = 64L * 1024L * 1024L * 1024L
internal const val MAX_MANIFEST_TASK_BYTES = 256L * 1024L * 1024L * 1024L
internal const val MAX_MANIFEST_CHUNK_BYTES = 64L * 1024L * 1024L
internal const val MAX_MANIFEST_CHUNK_MEMORY_BYTES = 64L * 1024L * 1024L
internal const val MAX_MANIFEST_RESPONSE_BYTES = 8 * 1024 * 1024
internal const val MAX_MANIFEST_DECOMPRESSED_BYTES = 32 * 1024 * 1024
internal const val MAX_MANIFEST_CHUNK_COUNT = 250_000
internal const val MIN_DOWNLOAD_SPACE_RESERVE_BYTES = 512L * 1024L * 1024L
internal const val MAX_DOWNLOAD_SPACE_RESERVE_BYTES = 4L * 1024L * 1024L * 1024L
private const val DOWNLOAD_SPACE_RESERVE_DIVISOR = 20L

internal data class SteamStreamByteRange(
    val start: Long,
    val endInclusive: Long,
)

internal fun steamStreamPrefetchConcurrency(
    configuredConcurrency: Int,
    @Suppress("UNUSED_PARAMETER") maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
): Int = configuredConcurrency.coerceIn(1, STEAM_STREAM_MAX_PARALLEL_CHUNKS)

internal fun steamStreamMemoryBudgetBytes(maxHeapBytes: Long): Long =
    (maxHeapBytes / STEAM_STREAM_MEMORY_HEAP_DIVISOR).coerceIn(
        STEAM_STREAM_MEMORY_MIN_BYTES,
        STEAM_STREAM_MEMORY_MAX_BYTES,
    )

internal fun steamStreamAheadPrefetchRange(
    contentLength: Long,
    afterExclusive: Long,
    aheadBytes: Long = STEAM_STREAM_AHEAD_BYTES,
): SteamStreamByteRange? =
    steamStreamRange(
        contentLength = contentLength,
        start = afterExclusive,
        length = aheadBytes,
    )

internal data class SteamStreamTimeBufferWindow(
    val targetBytes: Long,
    val lowWaterBytes: Long,
)

/** Tracks the local byte-to-media-time relationship so VBR peaks never receive less
 * disk headroom than the whole-file average. Large playback discontinuities reset the
 * sample baseline instead of being mistaken for a bitrate spike. */
internal class SteamStreamByteRateEstimator(
    private val contentLength: Long,
) {
    private var lastReadPosition = -1L
    private var lastPlaybackPositionMs = -1L
    private var lastBufferedPositionMs = -1L
    private var smoothedBytesPerMillisecond: Double? = null

    @Synchronized
    fun update(
        readPosition: Long,
        playbackPositionMs: Long,
        bufferedPositionMs: Long,
        durationMs: Long,
    ): Double {
        val average = averageBytesPerMillisecond(contentLength, durationMs)
        if (average <= 0.0) return 0.0
        val playbackDiscontinuity =
            lastPlaybackPositionMs >= 0L &&
                (playbackPositionMs < lastPlaybackPositionMs ||
                    playbackPositionMs - lastPlaybackPositionMs > STEAM_STREAM_ESTIMATOR_SEEK_THRESHOLD_MS)
        val readDiscontinuity = lastReadPosition >= 0L && readPosition < lastReadPosition
        val bufferDiscontinuity = lastBufferedPositionMs >= 0L && bufferedPositionMs < lastBufferedPositionMs
        if (playbackDiscontinuity || readDiscontinuity || bufferDiscontinuity) {
            lastReadPosition = readPosition
            lastPlaybackPositionMs = playbackPositionMs
            lastBufferedPositionMs = bufferedPositionMs
            smoothedBytesPerMillisecond = null
            return average
        }

        if (lastReadPosition >= 0L && lastBufferedPositionMs >= 0L) {
            val deltaBytes = readPosition - lastReadPosition
            val deltaBufferedMs = bufferedPositionMs - lastBufferedPositionMs
            if (deltaBytes > 0L && deltaBufferedMs > 0L) {
                val sample =
                    (deltaBytes.toDouble() / deltaBufferedMs.toDouble())
                        .coerceIn(
                            average * STEAM_STREAM_ESTIMATOR_MIN_RATIO,
                            average * STEAM_STREAM_ESTIMATOR_MAX_RATIO,
                        )
                smoothedBytesPerMillisecond =
                    smoothedBytesPerMillisecond?.let { previous ->
                        previous * (1.0 - STEAM_STREAM_ESTIMATOR_SAMPLE_WEIGHT) +
                            sample * STEAM_STREAM_ESTIMATOR_SAMPLE_WEIGHT
                    } ?: sample
                lastReadPosition = readPosition
                lastBufferedPositionMs = bufferedPositionMs
            }
        } else {
            lastReadPosition = readPosition
            lastBufferedPositionMs = bufferedPositionMs
        }
        lastPlaybackPositionMs = playbackPositionMs
        return max(average, smoothedBytesPerMillisecond ?: average)
    }
}

private fun averageBytesPerMillisecond(
    contentLength: Long,
    durationMs: Long,
): Double =
    if (contentLength > 0L && durationMs > 0L) {
        contentLength.toDouble() / durationMs.toDouble()
    } else {
        0.0
    }

/**
 * Converts the product's time-based buffering policy to a Steam byte range only at
 * the transport boundary. This prevents a fixed byte budget from collapsing to a
 * few seconds on high-bitrate Workshop videos.
 */
internal fun steamStreamTimeBufferWindow(
    contentLength: Long,
    durationMs: Long,
    maximumAheadBytes: Long,
    targetBufferMs: Long = STEAM_STREAM_TARGET_BUFFER_MS,
    lowWaterBufferMs: Long = STEAM_STREAM_LOW_WATER_BUFFER_MS,
    observedBytesPerMillisecond: Double = 0.0,
): SteamStreamTimeBufferWindow {
    val maximum = maximumAheadBytes.coerceAtLeast(1L)
    val fallbackTarget = min(STEAM_STREAM_AHEAD_BYTES, maximum)
    val fallbackLowWater = min(STEAM_STREAM_LOW_WATER_BYTES, fallbackTarget)
    if (contentLength <= 0L || durationMs <= 0L || targetBufferMs <= 0L || lowWaterBufferMs <= 0L) {
        return SteamStreamTimeBufferWindow(fallbackTarget, fallbackLowWater)
    }
    val bytesPerMillisecond =
        max(
            contentLength.toDouble() / durationMs.toDouble(),
            observedBytesPerMillisecond.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
        )
    val targetBytes =
        kotlin.math.ceil(bytesPerMillisecond * targetBufferMs)
            .toLong()
            .coerceIn(1L, maximum)
    val lowWaterBytes =
        kotlin.math.ceil(bytesPerMillisecond * lowWaterBufferMs)
            .toLong()
            .coerceIn(1L, targetBytes)
    return SteamStreamTimeBufferWindow(targetBytes, lowWaterBytes)
}

internal fun steamStreamRefillRange(
    contentLength: Long,
    consumedPosition: Long,
    bufferedEndInclusive: Long,
    targetAheadBytes: Long,
    lowWaterBytes: Long,
): SteamStreamByteRange? {
    if (contentLength <= 0L ||
        consumedPosition !in 0 until contentLength ||
        targetAheadBytes <= 0L ||
        lowWaterBytes <= 0L
    ) {
        return null
    }
    val remainingBytes = (bufferedEndInclusive - consumedPosition + 1L).coerceAtLeast(0L)
    if (remainingBytes >= lowWaterBytes.coerceAtMost(targetAheadBytes)) return null
    val start = max(consumedPosition, bufferedEndInclusive + 1L)
    val endInclusive = min(contentLength - 1L, consumedPosition + targetAheadBytes - 1L)
    return if (start <= endInclusive) SteamStreamByteRange(start, endInclusive) else null
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
internal const val STEAM_STREAM_AHEAD_BYTES = 64L * STEAM_STREAM_MEBIBYTE
internal const val STEAM_STREAM_LOW_WATER_BYTES = 16L * STEAM_STREAM_MEBIBYTE
internal const val STEAM_STREAM_TARGET_BUFFER_MS = 15_000L
internal const val STEAM_STREAM_LOW_WATER_BUFFER_MS = 8_000L
private const val STEAM_STREAM_BUFFER_READY_TOLERANCE_MS = 250L
internal const val STEAM_STREAM_MAX_PARALLEL_CHUNKS = 48
// Keep at most ~48 MiB of compressed + decoded chunk payloads resident. This is
// independent of request concurrency and prevents 64 MiB chunks from multiplying
// into an app-wide OOM on devices with a 192 MiB heap.
internal const val STEAM_STREAM_MEMORY_HEAP_DIVISOR = 4L
internal const val STEAM_STREAM_MEMORY_MIN_BYTES = 24L * 1024L * 1024L
internal const val STEAM_STREAM_MEMORY_MAX_BYTES = 96L * 1024L * 1024L
// SteamKit's ResponseBody.bytes() and XZ/LZMA decoder allocate backing arrays and
// dictionaries that are not represented by manifest compressed/uncompressed sizes.
// Reserve the 8 MiB VZip ThreadLocal window plus payload copies per decode. Four
// fixed workers remain within a 48 MiB stream budget on a 192 MiB app heap while
// the independently spooled network stage keeps the exact user request concurrency.
internal const val STEAM_STREAM_MIN_REQUEST_MEMORY_BYTES = 12L * 1024L * 1024L
internal const val STEAM_STREAM_DECODE_THREADS = 4
internal const val STEAM_STREAM_MAX_STAGED_REQUESTS = 8_192
internal const val STREAM_SEEK_RESET_BYTES = 2L * STEAM_STREAM_MEBIBYTE
private const val STREAM_METRICS_LOG_INTERVAL_CHUNKS = 16L
private const val STREAM_METRICS_WINDOW_NANOS = 5_000_000_000L
private const val STEAM_STREAM_BUFFER_LOG_INTERVAL_MS = 5_000L
private const val STEAM_STREAM_ESTIMATOR_SEEK_THRESHOLD_MS = 5_000L
private const val STEAM_STREAM_ESTIMATOR_MIN_RATIO = 0.5
private const val STEAM_STREAM_ESTIMATOR_MAX_RATIO = 4.0
private const val STEAM_STREAM_ESTIMATOR_SAMPLE_WEIGHT = 0.25

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
    private var lastPublishedAt = 0L
    private val callbackMutex = Mutex()

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
        val progress = mutex.withLock {
            downloadedBytes += addedBytes.coerceAtLeast(0L)
            if (completedFile) completedFiles += 1
            val now = System.currentTimeMillis()
            if (!completedFile && now - lastPublishedAt < PROGRESS_REPORT_INTERVAL_MS) return@withLock null
            lastPublishedAt = now
            SteamDownloadProgress(
                phase = SteamDownloadPhase.DOWNLOADING,
                currentFile = fileName,
                completedBytes = downloadedBytes,
                totalBytes = totalBytes,
                completedFiles = completedFiles,
                totalFiles = totalFiles,
            )
        }
        progress?.let { value -> callbackMutex.withLock { onProgress(value) } }
    }
}

private const val PROGRESS_REPORT_INTERVAL_MS = 250L

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
            chunkConcurrency = safeChunkConcurrency(chunkConcurrency, Runtime.getRuntime().maxMemory()),
            proxyUrl = proxyUrl.trim(),
        )
}

internal fun safeChunkConcurrency(
    configuredConcurrency: Int,
    @Suppress("UNUSED_PARAMETER") maxHeapBytes: Long,
): Int = configuredConcurrency.coerceIn(MIN_CONFIGURED_CHUNK_CONCURRENCY, MAX_CONFIGURED_CHUNK_CONCURRENCY)

internal const val MIN_CONFIGURED_CHUNK_CONCURRENCY = 12
internal const val MAX_CONFIGURED_CHUNK_CONCURRENCY = 48

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
