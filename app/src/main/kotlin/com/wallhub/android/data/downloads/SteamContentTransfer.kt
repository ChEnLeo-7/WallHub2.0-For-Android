package com.wallhub.android.data.downloads

import android.util.Log
import bruhcollective.itaysonlab.ksteam.SteamClient
import bruhcollective.itaysonlab.ksteam.models.account.AuthorizationState
import com.wallhub.android.core.model.DepotChunkSpec
import com.wallhub.android.core.model.DepotFileFlag
import com.wallhub.android.core.model.DepotFileSpec
import com.wallhub.android.core.model.DepotManifestSpec
import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.android.core.model.WorkshopVideoBufferState
import com.wallhub.android.data.steam.KSteamSessionRepository
import com.wallhub.android.core.model.WorkshopVideoFullCacheState
import com.wallhub.android.core.model.WorkshopVideoFullCacheStatus
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.BufferedOutputStream
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
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class KSteamContentSession(
    val repository: KSteamSessionRepository,
    val client: SteamClient,
    val isAuthenticated: Boolean,
)

/**
 * Opens a depot-capable CM session: the signed-in kSteam engine when [credential] is present,
 * otherwise the shared anonymous CM client. Both support signed service calls and the raw
 * depot-key exchange, so anonymous public-depot downloads keep working.
 */
internal suspend fun openContentSession(
    repository: KSteamSessionRepository,
    credential: SteamContentCredential?,
    onAuthenticating: suspend () -> Unit = {},
): KSteamContentSession {
    if (credential != null) {
        onAuthenticating()
    }
    val client =
        repository.steamContentClient(authenticated = credential != null)
            ?: error("Steam content session is unavailable")
    return KSteamContentSession(
        repository = repository,
        client = client,
        isAuthenticated = credential != null && client.clientAuthState.value is AuthorizationState.Success,
    )
}

internal suspend fun resolveContentAccess(
    session: KSteamContentSession,
    target: WorkshopContentTarget,
): SteamContentAccess {
    val client = session.client
    val depotKey =
        try {
            session.repository.steamDepotDecryptionKey(
                client = client,
                depotId = target.depotId,
                appId = target.appId,
            )
        } catch (error: IllegalStateException) {
            throw SteamDepotAccessException(target.depotId, error.message ?: "unavailable")
        }
    val servers =
        client
            .steamCdnServers(cellId = client.configuration.cellId, maxServers = CDN_SERVER_LIMIT)
            .filter { server -> resolveCdnRequestHost(server.vHost, server.host) != null }
            .let(::prioritizeCdnServers)
    check(servers.isNotEmpty()) { "Steam returned no available CDN servers" }
    val requestCode =
        client.steamManifestRequestCode(
            depotId = target.depotId,
            appId = target.appId,
            manifestId = target.contentManifestId,
        )
    return SteamContentAccess(
        depotKey = depotKey,
        manifestRequestCode = requestCode,
        servers = servers,
        authTokens =
            CdnAuthTokenProvider(
                client = client,
                appId = target.appId,
                depotId = target.depotId,
                enabled = session.isAuthenticated,
            ),
    )
}

internal class SteamDepotAccessException(
    depotId: Int,
    detail: String,
) : IllegalStateException("Steam did not provide depot key $depotId: $detail")

internal fun prioritizeCdnServers(servers: List<CdnServer>): List<CdnServer> {
    val ranked = servers.sortedBy { it.weightedLoad }
    return ranked.filter { it.https } + ranked.filterNot { it.https }
}

internal class SteamContentVideoStream internal constructor(
    override val title: String,
    override val fileName: String,
    override val contentLength: Long,
    private val chunks: List<DepotChunkSpec>,
    private val streamCache: SteamVideoStreamCache,
    private val prefetchConcurrency: Int,
    private val httpClient: OkHttpClient,
    private val session: KSteamContentSession,
    private val access: SteamContentAccess,
    private val depotId: Int,
    private val depotDownloader: DepotDownloader,
) : com.wallhub.android.core.model.WorkshopVideoStreamSession {
    private val cdnHttpClient = httpClient
    private val selector = CdnServerSelector()
    private val adaptiveBufferPolicy =
        SteamStreamAdaptiveBufferPolicy(
            contentLength = contentLength,
            cacheBudgetBytes = streamCache.prefetchCapacityBytes,
        )
    // Match Webview's maxParallel: one permit covers the complete chunk lifecycle,
    // rather than only the HTTP request portion.
    private val pipelineConcurrencyScheduler = ForegroundFirstPermitPool(prefetchConcurrency)
    private val pipelineBudgetBytes = steamStreamMemoryBudgetBytes(Runtime.getRuntime().maxMemory())
    private val pipelineScheduler =
        DownloadMemoryBudget.withFixedCapacity(
            pipelineBudgetBytes,
        )
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // JavaSteam VZipUtil retains an 8 MiB ThreadLocal window per decoder thread.
    // A dedicated bounded pool prevents Dispatchers.IO workers from each retaining
    // their own window while network task concurrency remains exactly user-configured.
    private val decodeThreadCount =
        steamStreamDecodeThreads(
            configuredConcurrency = prefetchConcurrency,
            availableProcessors = Runtime.getRuntime().availableProcessors(),
        )
    private val decodeDispatcher =
        Executors.newFixedThreadPool(decodeThreadCount) { runnable ->
            Thread(runnable, "WallHub-Steam-Decode").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val inFlightLock = Any()
    private val inFlightChunks = mutableMapOf<Long, StreamChunkRequest>()
    private val nextChunkRequestId = AtomicLong(0L)
    private val prefetchLock = Any()
    private val prefetchGeneration = AtomicLong(0L)
    private val lastReadEnd = AtomicLong(-1L)
    private val dynamicAheadBytes = AtomicLong(STREAM_INITIAL_BUFFER_BYTES)
    private val dynamicTargetDurationMs = AtomicLong(0L)
    private val latestReadPosition = AtomicLong(0L)
    private val lastBufferMetricsLogAtMs = AtomicLong(0L)
    private val latestPlaybackClock = AtomicReference(SteamStreamPlaybackClock())
    private val latestAdaptiveDecision =
        AtomicReference(
            SteamAdaptiveBufferDecision(
                targetDurationMs = 0L,
                targetBytes = STREAM_INITIAL_BUFFER_BYTES,
                requiredBytesPerSecond = 0.0,
                safeThroughputBytesPerSecond = 0.0,
                bandwidthLimited = false,
            ),
        )
    private val contiguousBufferTracker = SteamContiguousBufferTracker(chunks)
    private val mutablePlaybackBufferState = MutableStateFlow(WorkshopVideoBufferState())
    private val mutableFullCacheState = MutableStateFlow(WorkshopVideoFullCacheState(totalBytes = contentLength))

    override val playbackBufferState = mutablePlaybackBufferState.asStateFlow()
    override val fullCacheState = mutableFullCacheState.asStateFlow()

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
                playbackSpeed = playbackSpeed.coerceAtLeast(STEAM_STREAM_MIN_PLAYBACK_SPEED),
                playbackPositionMs = playbackPositionMs,
                bufferedPositionMs = bufferedPositionMs,
                durationMs = durationMs,
            ),
        )
        val decision =
            adaptiveBufferPolicy.evaluate(
                readPosition = latestReadPosition.get(),
                playbackPositionMs = playbackPositionMs,
                bufferedPositionMs = bufferedPositionMs,
                durationMs = durationMs,
                playbackSpeed = playbackSpeed,
            )
        latestAdaptiveDecision.set(decision)
        val mediaBufferedMs = (bufferedPositionMs - playbackPositionMs).coerceAtLeast(0L)
        val averageBytesPerMillisecond =
            if (contentLength > 0L && durationMs > 0L) contentLength.toDouble() / durationMs.toDouble() else 0.0
        val mediaBufferedBytes =
            kotlin.math.ceil(averageBytesPerMillisecond * mediaBufferedMs.toDouble())
                .toLong()
                .coerceAtLeast(0L)
        val diskTargetBytes = (decision.targetBytes - mediaBufferedBytes).coerceAtLeast(1L)
        dynamicAheadBytes.set(diskTargetBytes)
        dynamicTargetDurationMs.set(decision.targetDurationMs)
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
                    "targetBytes=${decision.targetBytes}, " +
                    "requiredMbps=${"%.1f".format(decision.requiredBytesPerSecond * 8.0 / 1_000_000.0)}, " +
                    "safeMbps=${"%.1f".format(decision.safeThroughputBytesPerSecond * 8.0 / 1_000_000.0)}, " +
                    "bandwidthLimited=${decision.bandwidthLimited}, " +
                    "speed=$playbackSpeed, durationMs=$durationMs",
            )
        }
    }

    @Volatile
    private var aheadPrefetchJob: Job? = null

    @Volatile
    private var initialPrefetchJob: Job? = null

    @Volatile
    private var fullCacheJob: Job? = null

    @Volatile
    private var fullCachePinned = false

    @Volatile
    private var aheadBufferedEndInclusive = -1L

    private val prefetchFrontier = StreamPrefetchFrontier()
    private val transferMetrics = SteamChunkTransferMetrics()

    @Volatile
    private var closed = false

    init {
        streamCache.setEvictionListener(::onCacheChunkEvicted)
        Log.i(
            STEAM_CONTENT_LOG_TAG,
            "Steam stream fixedChunkConcurrency=$prefetchConcurrency, " +
                "decodeThreads=$decodeThreadCount; adaptive buffering follows range demand and measured throughput",
        )
        scheduleInitialPrefetch()
    }

    override suspend fun readAt(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): Int =
        withContext(Dispatchers.IO) {
            check(!closed) { "SteamKit streaming session is closed" }
            require(position >= 0L) { "Invalid streaming read position" }
            require(length >= 0) { "Invalid streaming read length" }
            require(destinationOffset >= 0 && destinationOffset <= destination.size - length) {
                "Invalid streaming read destination"
            }
            if (length == 0 || position >= contentLength) return@withContext 0

            val requestedLength = min(length.toLong(), contentLength - position).toInt()
            val endExclusive = position + requestedLength
            resetPrefetchForSeekIfNeeded(position)
            // Fill the remaining user-configured request slots immediately instead of
            // waiting for the foreground chunk to finish before starting prefetch.
            scheduleAheadPrefetch(endExclusive)
            var written = 0
            var chunkIndex = firstSteamStreamChunkIndex(chunks, position)
            while (chunkIndex < chunks.size) {
                val chunk = chunks[chunkIndex]
                val chunkStart = chunk.offset
                val chunkEnd = chunkStart + chunk.uncompressedLength
                if (chunkStart >= endExclusive) break
                val sourceStart = max(position, chunkStart) - chunkStart
                val sourceEnd = min(endExclusive, chunkEnd) - chunkStart
                val copiedLength = (sourceEnd - sourceStart).toInt()
                loadChunkSliceInto(
                    chunk = chunk,
                    sourceOffset = sourceStart.toInt(),
                    destination = destination,
                    destinationOffset = destinationOffset + written,
                    length = copiedLength,
                    priority = SteamStreamChunkPriority.FOREGROUND,
                )
                written += copiedLength
                chunkIndex += 1
            }
            check(written == requestedLength) { "Steam video chunk has missing data" }
            lastReadEnd.set(endExclusive - 1L)
            latestReadPosition.set(endExclusive)
            scheduleAheadPrefetch(endExclusive)
            written
        }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(prefetchLock) {
            aheadPrefetchJob?.cancel()
            aheadPrefetchJob = null
        }
        initialPrefetchJob?.cancel()
        fullCacheJob?.cancel()
        fetchScope.cancel()
        decodeDispatcher.close()
        streamCache.close()
    }

    private suspend fun loadChunkSliceInto(
        chunk: DepotChunkSpec,
        sourceOffset: Int,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
        priority: SteamStreamChunkPriority,
    ) {
        if (
            streamCache.readSliceInto(
                chunkOffset = chunk.offset,
                chunkLength = chunk.uncompressedLength,
                expectedChecksum = chunk.checksum,
                sourceOffset = sourceOffset,
                destination = destination,
                destinationOffset = destinationOffset,
                length = length,
            )
        ) {
            onVerifiedChunkCached(chunk)
            return
        }
        val downloaded = withSteamCdnRecovery {
            requestChunk(chunk, priority).deferred.await()
        }
        if (length == 0) return
        if (downloaded != null) {
            downloaded.copyInto(destination, destinationOffset, sourceOffset, sourceOffset + length)
            return
        }
        check(
            streamCache.readSliceInto(
                chunkOffset = chunk.offset,
                chunkLength = chunk.uncompressedLength,
                expectedChecksum = chunk.checksum,
                sourceOffset = sourceOffset,
                destination = destination,
                destinationOffset = destinationOffset,
                length = length,
            ),
        ) { "Steam video chunk was committed but is unavailable" }
    }

    private suspend fun requestChunk(
        chunk: DepotChunkSpec,
        priority: SteamStreamChunkPriority,
    ): StreamChunkRequest {
        if (priority == SteamStreamChunkPriority.FOREGROUND) {
            val matchingRequest = synchronized(inFlightLock) { inFlightChunks[chunk.offset] }
            if (matchingRequest == null) preemptBlockingPrefetch()
        }
        var reusedRequest: StreamChunkRequest? = null
        val chunkRequest = synchronized(inFlightLock) {
            val existing = inFlightChunks[chunk.offset]
            if (existing != null) {
                if (priority == SteamStreamChunkPriority.FOREGROUND) {
                    existing.promote()
                }
                reusedRequest = existing
                return@synchronized existing
            }
            val requestId = nextChunkRequestId.incrementAndGet()
            val result = CompletableDeferred<ByteArray?>()
            val networkCompleted = CompletableDeferred<Unit>()
            val priorityState = AtomicReference(priority)
            val request =
                fetchScope.launch(start = CoroutineStart.LAZY) {
                    var metricsRequestStarted = false
                    try {
                        val pipelineBytes =
                            steamStreamChunkPipelineBytes(chunk.compressedLength, chunk.uncompressedLength)
                                .coerceAtMost(pipelineBudgetBytes)
                        pipelineConcurrencyScheduler.withPermit(priorityState.get(), requestId) {
                            pipelineScheduler.withPermit(
                                requestedBytes = pipelineBytes,
                                priority = priorityState.get(),
                                requestId = requestId,
                                order = chunk.offset,
                            ) {
                                val startedAtNanos = System.nanoTime()
                                transferMetrics.requestStarted()
                                metricsRequestStarted = true
                                var encryptedPayload: ByteArray? =
                                    downloadEncryptedChunk(
                                        httpClient = cdnHttpClient,
                                        servers = access.servers,
                                        depotId = depotId,
                                        chunk = chunk,
                                        authTokens = access.authTokens,
                                        selector = selector,
                                        control = { SteamDownloadControl.CONTINUE },
                                        onSuccess = { server ->
                                            latestCdnHost = resolveCdnRequestHost(server.vHost, server.host)
                                        },
                                    )
                                val elapsedNanos = System.nanoTime() - startedAtNanos
                                adaptiveBufferPolicy.recordTransfer(
                                    deliveredBytes = chunk.uncompressedLength,
                                    elapsedNanos = elapsedNanos,
                                    completedAtNanos = System.nanoTime(),
                                )
                                transferMetrics
                                    .requestCompleted(chunk.compressedLength, startedAtNanos)
                                    ?.let { metrics ->
                                        Log.d(
                                            STEAM_CONTENT_LOG_TAG,
                                            "Steam stream chunks=${metrics.completedChunks}, " +
                                                "aggregate=${"%.1f".format(metrics.aggregateMbps)}Mbps, " +
                                                "active=${metrics.activeRequests}, peak=${metrics.peakActiveRequests}, " +
                                                "configuredConcurrency=$prefetchConcurrency",
                                        )
                                    }
                                metricsRequestStarted = false
                                networkCompleted.complete(Unit)
                                val payload = withContext(decodeDispatcher) {
                                    depotDownloader.decodeChunk(
                                        chunk,
                                        checkNotNull(encryptedPayload),
                                        access.depotKey,
                                    ).getOrThrow()
                                }
                                encryptedPayload = null
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
                            }
                        }
                        // Prefetch consumers only need availability; retaining the decoded
                        // payload in Deferred objects quickly exhausts a small Android heap.
                        if (!result.isCompleted) result.complete(null)
                    } catch (error: Throwable) {
                        networkCompleted.completeExceptionally(error)
                        if (metricsRequestStarted) transferMetrics.requestFailed()
                        synchronized(inFlightLock) {
                            if (inFlightChunks[chunk.offset]?.id == requestId) {
                                inFlightChunks.remove(chunk.offset)
                            }
                        }
                        result.completeExceptionally(error)
                        if (error is CancellationException || error is VirtualMachineError) throw error
                    } finally {
                        synchronized(inFlightLock) {
                            if (inFlightChunks[chunk.offset]?.id == requestId) {
                                inFlightChunks.remove(chunk.offset)
                            }
                        }
                    }
                }
            val streamRequest =
                StreamChunkRequest(
                    id = requestId,
                    deferred = result,
                    networkCompleted = networkCompleted,
                    job = request,
                    priorityState = priorityState,
                )
            inFlightChunks[chunk.offset] = streamRequest
            request.start()
            streamRequest
        }
        if (priority == SteamStreamChunkPriority.FOREGROUND) {
            reusedRequest?.let { request ->
                pipelineConcurrencyScheduler.promote(request.id)
                pipelineScheduler.promote(request.id)
            }
        }
        return chunkRequest
    }

    /**
     * Webview cancels speculative work when it occupies the connection needed by a new Range.
     * Do the same here, but only for a prefetch that currently owns a network permit; queued
     * work is already ordered behind foreground demand and decoded/committing work no longer
     * blocks the CDN connection.
     */
    private suspend fun preemptBlockingPrefetch() {
        val requestId = pipelineConcurrencyScheduler.activePrefetchRequestId() ?: return
        val blockingRequest =
            synchronized(inFlightLock) {
                inFlightChunks.values.firstOrNull { request ->
                    request.id == requestId && request.priority == SteamStreamChunkPriority.PREFETCH
                }
            } ?: return
        Log.d(STEAM_CONTENT_LOG_TAG, "Steam stream foreground preempting prefetch request=$requestId")
        blockingRequest.job.cancel(CancellationException("Foreground Steam stream demand"))
    }

    private fun scheduleAheadPrefetch(afterExclusive: Long) {
        if (closed || afterExclusive >= contentLength) return
        val generation = prefetchGeneration.get()
        val targetAheadBytes = dynamicAheadBytes.get()
        protectPlaybackWindow(afterExclusive, targetAheadBytes)
        synchronized(prefetchLock) {
            if (aheadPrefetchJob?.isActive == true) return
            val remainingAheadBytes =
                (aheadBufferedEndInclusive - afterExclusive + 1L).coerceAtLeast(0L)
            if (remainingAheadBytes >= targetAheadBytes) return
            val range =
                steamStreamMissingRange(
                    contentLength = contentLength,
                    consumedPosition = afterExclusive,
                    bufferedEndInclusive = aheadBufferedEndInclusive,
                    targetAheadBytes = targetAheadBytes,
                )
            if (range == null) return
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
        initialPrefetchJob?.cancel()
        initialPrefetchJob = null
        synchronized(prefetchLock) {
            aheadPrefetchJob?.cancel()
            aheadPrefetchJob = null
            aheadBufferedEndInclusive = position - 1L
            prefetchFrontier.reset()
            contiguousBufferTracker.reset(position)
        }
        publishPlaybackBufferState()
    }

    private suspend fun prefetchRange(
        range: SteamStreamByteRange,
        generation: Long,
    ): Boolean = coroutineScope {
        val selectedChunks = steamStreamChunksInRange(chunks, range.start, range.endInclusive).mapNotNull { chunk ->
                when (prefetchFrontier.plan(chunk.offset)) {
                    StreamPrefetchPlan.COMPLETE -> null
                    StreamPrefetchPlan.NEW -> PlannedStreamChunk(chunk, ownsPlan = true)
                    StreamPrefetchPlan.IN_FLIGHT -> PlannedStreamChunk(chunk, ownsPlan = false)
                }
        }
        if (selectedChunks.isEmpty()) return@coroutineScope true
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        // Keep the complete download -> decrypt -> decompress -> commit pipeline bounded.
        // Replacing a task as soon as its network stage finishes creates an unbounded staged
        // queue on fast links and leaves the foreground waiting for the next decoded chunk.
        val completedStages = Channel<Long>(Channel.UNLIMITED)
        val jobs = mutableListOf<Deferred<Unit>>()
        var nextChunkIndex = 0
        var inFlightBytes = 0L

        fun launchChunk(plannedChunk: PlannedStreamChunk) {
            val chunk = plannedChunk.chunk
            val pipelineBytes =
                steamStreamChunkPipelineBytes(
                    compressedBytes = chunk.compressedLength,
                    uncompressedBytes = chunk.uncompressedLength,
                )
            jobs +=
                async {
                    try {
                        if (closed || generation != prefetchGeneration.get()) return@async
                        val cached =
                            streamCache.readSlice(
                                chunkOffset = chunk.offset,
                                chunkLength = chunk.uncompressedLength,
                                expectedChecksum = chunk.checksum,
                                offset = 0,
                                length = 0,
                            )
                        if (cached != null) {
                            onVerifiedChunkCached(chunk)
                        } else {
                            withSteamCdnRecovery {
                                val request = requestChunk(chunk, SteamStreamChunkPriority.PREFETCH)
                                request.deferred.await()
                            }
                        }
                        prefetchFrontier.complete(chunk.offset)
                    } catch (error: Throwable) {
                        failed.set(true)
                        if (plannedChunk.ownsPlan) prefetchFrontier.fail(chunk.offset)
                        if (error is CancellationException || error is VirtualMachineError) throw error
                    } finally {
                        completedStages.trySend(pipelineBytes)
                    }
                }
        }
        try {
            fun canLaunch(bytes: Long): Boolean =
                nextChunkIndex < selectedChunks.size &&
                    (inFlightBytes == 0L || inFlightBytes + bytes <= pipelineBudgetBytes)

            // Match Webview's bounded in-flight byte window. A single chunk larger than
            // the budget is still allowed so an unusually large manifest cannot deadlock.
            while (nextChunkIndex < selectedChunks.size) {
                val next = selectedChunks[nextChunkIndex].chunk
                val nextBytes = steamStreamChunkPipelineBytes(next.compressedLength, next.uncompressedLength)
                if (!canLaunch(nextBytes)) break
                launchChunk(selectedChunks[nextChunkIndex++])
                inFlightBytes += nextBytes
            }
            while (nextChunkIndex < selectedChunks.size) {
                inFlightBytes -= completedStages.receive()
                val next = selectedChunks[nextChunkIndex].chunk
                val nextBytes = steamStreamChunkPipelineBytes(next.compressedLength, next.uncompressedLength)
                if (canLaunch(nextBytes)) {
                    launchChunk(selectedChunks[nextChunkIndex++])
                    inFlightBytes += nextBytes
                }
            }
            jobs.awaitAll()
        } finally {
            completedStages.close()
            selectedChunks.filter(PlannedStreamChunk::ownsPlan).forEach { planned ->
                prefetchFrontier.fail(planned.chunk.offset)
            }
        }
        !failed.get() && generation == prefetchGeneration.get()
    }

    private fun onVerifiedChunkCached(chunk: DepotChunkSpec) {
        val completedEndInclusive = contiguousBufferTracker.markCompleted(chunk)
        synchronized(prefetchLock) {
            aheadBufferedEndInclusive = max(aheadBufferedEndInclusive, completedEndInclusive)
        }
        publishPlaybackBufferState()
    }

    private fun protectPlaybackWindow(
        consumedPosition: Long,
        targetAheadBytes: Long,
    ) {
        if (contentLength <= 0L || consumedPosition !in 0 until contentLength) {
            streamCache.protectChunkOffsets(emptySet())
            return
        }
        val endInclusive = min(contentLength - 1L, consumedPosition + targetAheadBytes.coerceAtLeast(1L) - 1L)
        val protectedOffsets =
            if (fullCachePinned) {
                chunks.map(DepotChunkSpec::offset)
            } else {
                steamStreamChunksInRange(chunks, consumedPosition, endInclusive).map(DepotChunkSpec::offset)
            }
        streamCache.protectChunkOffsets(protectedOffsets)
    }

    private fun onCacheChunkEvicted(chunkOffset: Long) {
        val readPosition = latestReadPosition.get()
        if (chunkOffset < readPosition || chunkOffset > contiguousBufferTracker.bufferedEndInclusive()) return
        prefetchGeneration.incrementAndGet()
        synchronized(prefetchLock) {
            aheadPrefetchJob?.cancel()
            aheadPrefetchJob = null
            aheadBufferedEndInclusive = readPosition - 1L
            prefetchFrontier.reset()
            contiguousBufferTracker.reset(readPosition)
        }
        publishPlaybackBufferState()
        scheduleAheadPrefetch(readPosition)
    }

    private fun publishPlaybackBufferState() {
        val clock = latestPlaybackClock.get()
        if (clock.durationMs <= 0L) {
            mutablePlaybackBufferState.value = WorkshopVideoBufferState()
            return
        }
        val decision = latestAdaptiveDecision.get()
        val speed = clock.playbackSpeed.coerceAtLeast(STEAM_STREAM_MIN_PLAYBACK_SPEED).toDouble()
        val bytesPerMillisecond =
            max(
                averageBytesPerMillisecond(contentLength, clock.durationMs),
                decision.requiredBytesPerSecond / speed / 1_000.0,
            )
        if (bytesPerMillisecond <= 0.0) return
        val mediaBufferedMs = (clock.bufferedPositionMs - clock.playbackPositionMs).coerceAtLeast(0L)
        val contiguousEndInclusive = contiguousBufferTracker.bufferedEndInclusive()
        val diskAheadBytes =
            (contiguousEndInclusive - latestReadPosition.get() + 1L).coerceAtLeast(0L)
        val diskAheadMediaMs = (diskAheadBytes / bytesPerMillisecond).toLong().coerceAtLeast(0L)
        val remainingMediaDurationMs = (clock.durationMs - clock.playbackPositionMs).coerceAtLeast(0L)
        val availableMediaDurationMs =
            (mediaBufferedMs + diskAheadMediaMs).coerceIn(0L, remainingMediaDurationMs)
        val availableDurationMs = (availableMediaDurationMs / speed).toLong().coerceAtLeast(0L)
        val remainingDurationMs = (remainingMediaDurationMs / speed).toLong().coerceAtLeast(0L)
        val cacheCapacityDurationMs =
            (streamCache.prefetchCapacityBytes / bytesPerMillisecond / speed).toLong().coerceAtLeast(1L)
        val targetDurationMs =
            min(dynamicTargetDurationMs.get(), min(cacheCapacityDurationMs, remainingDurationMs))
        mutablePlaybackBufferState.value =
            WorkshopVideoBufferState(
                bufferedPositionMs =
                    (clock.playbackPositionMs + availableMediaDurationMs)
                        .coerceAtMost(clock.durationMs),
                availableDurationMs = availableDurationMs,
                targetDurationMs = targetDurationMs,
                targetReached =
                    availableDurationMs + STEAM_STREAM_BUFFER_READY_TOLERANCE_MS >= targetDurationMs,
                bandwidthLimited = decision.bandwidthLimited,
            )
    }

    private fun scheduleInitialPrefetch() {
        val generation = prefetchGeneration.get()
        val plan = steamInitialPrefetchPlan(contentLength)
        initialPrefetchJob =
            fetchScope.launch {
                plan.first?.let { first ->
                    if (closed || generation != prefetchGeneration.get()) return@launch
                    prefetchRange(first, generation)
                }
                if (closed || generation != prefetchGeneration.get()) return@launch
                // Once the header is usable, tail metadata must not delay contiguous
                // startup bytes. Both ranges share the same foreground-first scheduler.
                coroutineScope {
                    listOfNotNull(plan.tail, plan.initial)
                        .map { range -> async { prefetchRange(range, generation) } }
                        .awaitAll()
                }
            }
    }

    override fun startFullCache() {
        if (closed || fullCacheJob?.isActive == true || fullCachePinned) return
        if (contentLength > streamCache.prefetchCapacityBytes) {
            mutableFullCacheState.value =
                WorkshopVideoFullCacheState(
                    status = WorkshopVideoFullCacheStatus.ERROR,
                    totalBytes = contentLength,
                    errorCode = "STREAM_FULL_CACHE_LIMIT",
                )
            return
        }
        fullCachePinned = true
        streamCache.protectChunkOffsets(chunks.map(DepotChunkSpec::offset))
        mutableFullCacheState.value =
            WorkshopVideoFullCacheState(
                status = WorkshopVideoFullCacheStatus.CACHING,
                totalBytes = contentLength,
            )
        fullCacheJob =
            fetchScope.launch {
                var cachedBytes = 0L
                try {
                    chunks.forEach { chunk ->
                        currentCoroutineContext().ensureActive()
                        val cached =
                            streamCache.readSlice(
                                chunkOffset = chunk.offset,
                                chunkLength = chunk.uncompressedLength,
                                expectedChecksum = chunk.checksum,
                                offset = 0,
                                length = 0,
                            ) != null
                        if (!cached) requestChunk(chunk, SteamStreamChunkPriority.PREFETCH).deferred.await()
                        cachedBytes += chunk.uncompressedLength.toLong()
                        mutableFullCacheState.value =
                            WorkshopVideoFullCacheState(
                                status = WorkshopVideoFullCacheStatus.CACHING,
                                cachedBytes = min(cachedBytes, contentLength),
                                totalBytes = contentLength,
                            )
                    }
                    mutableFullCacheState.value =
                        WorkshopVideoFullCacheState(
                            status = WorkshopVideoFullCacheStatus.COMPLETE,
                            cachedBytes = contentLength,
                            totalBytes = contentLength,
                        )
                } catch (error: CancellationException) {
                    if (!closed) {
                        fullCachePinned = false
                        mutableFullCacheState.value =
                            WorkshopVideoFullCacheState(
                                status = WorkshopVideoFullCacheStatus.CANCELLED,
                                cachedBytes = min(cachedBytes, contentLength),
                                totalBytes = contentLength,
                            )
                        protectPlaybackWindow(latestReadPosition.get(), dynamicAheadBytes.get())
                    }
                    throw error
                } catch (error: Throwable) {
                    fullCachePinned = false
                    mutableFullCacheState.value =
                        WorkshopVideoFullCacheState(
                            status = WorkshopVideoFullCacheStatus.ERROR,
                            cachedBytes = min(cachedBytes, contentLength),
                            totalBytes = contentLength,
                            errorCode = "STREAM_FULL_CACHE_FAILED",
                        )
                    protectPlaybackWindow(latestReadPosition.get(), dynamicAheadBytes.get())
                }
            }
    }

    override fun cancelFullCache() {
        if (mutableFullCacheState.value.status != WorkshopVideoFullCacheStatus.CACHING) return
        fullCacheJob?.cancel()
        fullCacheJob = null
    }

}

internal data class SteamStreamPlaybackClock(
    val playbackSpeed: Float = 1f,
    val playbackPositionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/** Advances only when every preceding Steam chunk from the current seek point is verified. */
internal class SteamContiguousBufferTracker(
    private val chunks: List<DepotChunkSpec>,
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
    fun markCompleted(chunk: DepotChunkSpec): Long {
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
    val chunk: DepotChunkSpec,
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
    chunks: List<DepotChunkSpec>,
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

internal fun steamStreamChunksInRange(
    chunks: List<DepotChunkSpec>,
    start: Long,
    endInclusive: Long,
): List<DepotChunkSpec> {
    if (chunks.isEmpty() || start < 0L || endInclusive < start) return emptyList()
    val firstIndex = firstSteamStreamChunkIndex(chunks, start)
    if (firstIndex >= chunks.size) return emptyList()
    val selected = ArrayList<DepotChunkSpec>()
    var index = firstIndex
    while (index < chunks.size) {
        val chunk = chunks[index]
        if (chunk.offset > endInclusive) break
        selected += chunk
        index += 1
    }
    return selected
}

internal suspend fun downloadManifest(
    httpClient: OkHttpClient,
    servers: List<CdnServer>,
    depotId: Int,
    manifestId: Long,
    requestCode: Long,
    depotKey: ByteArray,
    authTokens: CdnAuthTokenProvider,
    control: suspend () -> SteamDownloadControl,
): DepotManifestSpec {
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
            val request =
                Request
                    .Builder()
                    .url(
                        buildSteamCdnCommand(
                            server = server,
                            command = server.depotManifestUrl(depotId, manifestId, requestCode),
                            query = token,
                        ),
                    ).build()
            val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw SteamCdnHttpException("Steam CDN returned ${resp.code} for the depot manifest")
                }
                val compressed =
                    resp.body.byteStream().use { stream -> stream.readBytesBounded(MAX_MANIFEST_RESPONSE_BYTES) }
                check(compressed.isNotEmpty()) { "Steam manifest response is empty" }
                val manifestData =
                    ZipInputStream(ByteArrayInputStream(compressed)).use { archive ->
                        requireNotNull(archive.nextEntry) { "Steam manifest response ZIP is empty" }
                        archive.readBytesBounded(MAX_MANIFEST_DECOMPRESSED_BYTES)
                    }
                val manifest = parseDepotManifest(manifestData)
                check(!manifest.filenamesEncrypted || manifest.decryptFilenames(depotKey)) {
                    "Failed to decrypt file names in the Steam manifest"
                }
                return manifest
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SteamDownloadCancelledException) {
            throw error
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            lastError = error
            errors += error
            failures += describeServer(server, hasToken) + "\uFF1A" +
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
    httpClient: OkHttpClient,
    servers: List<CdnServer>,
    depotId: Int,
    chunk: DepotChunkSpec,
    depotKey: ByteArray,
    authTokens: CdnAuthTokenProvider,
    selector: CdnServerSelector,
    depotDownloader: DepotDownloader,
    control: suspend () -> SteamDownloadControl,
    onSuccess: ((CdnServer) -> Unit)? = null,
): ByteArray {
    val encrypted =
        downloadEncryptedChunk(
            httpClient = httpClient,
            servers = servers,
            depotId = depotId,
            chunk = chunk,
            authTokens = authTokens,
            selector = selector,
            control = control,
            onSuccess = onSuccess,
        )
    return depotDownloader.decodeChunk(chunk, encrypted, depotKey).getOrThrow()
}

internal suspend fun downloadEncryptedChunk(
    httpClient: OkHttpClient,
    servers: List<CdnServer>,
    depotId: Int,
    chunk: DepotChunkSpec,
    authTokens: CdnAuthTokenProvider,
    selector: CdnServerSelector,
    control: suspend () -> SteamDownloadControl,
    onSuccess: ((CdnServer) -> Unit)? = null,
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
            // Streaming into the caller-owned destination avoids duplicating the response
            // buffer for every concurrent chunk request on a constrained Android heap.
            val downloadedBytes =
                downloadEncryptedChunkStreaming(
                    httpClient = httpClient,
                    server = server,
                    depotId = depotId,
                    chunk = chunk,
                    cdnAuthToken = token,
                    destination = encrypted,
                ) {
                    checkDownloadControl(control)
                }
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

internal suspend fun downloadEncryptedChunkToSpool(
    httpClient: OkHttpClient,
    servers: List<CdnServer>,
    depotId: Int,
    chunk: DepotChunkSpec,
    destination: File,
    authTokens: CdnAuthTokenProvider,
    selector: CdnServerSelector,
    control: suspend () -> SteamDownloadControl,
    onSuccess: ((CdnServer) -> Unit)? = null,
): Int {
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
            val token = authTokens.get(server)
            hasToken = token != null
            val downloadedBytes =
                downloadEncryptedChunkStreamingToFile(
                    httpClient = httpClient,
                    server = server,
                    depotId = depotId,
                    chunk = chunk,
                    cdnAuthToken = token,
                    destination = destination,
                )
            selector.recordSuccess(server, downloadedBytes, System.nanoTime() - attemptStartedAtNanos)
            onSuccess?.invoke(server)
            return downloadedBytes
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
            destination.delete()
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

/**
 * Downloads one encrypted Steam depot chunk without buffering the complete HTTP response.
 * JavaSteam 1.8.0's implementation calls ResponseBody.bytes(), which duplicates the
 * destination buffer for every in-flight request and can exhaust the Android heap.
 */
private suspend fun downloadEncryptedChunkStreaming(
    httpClient: OkHttpClient,
    server: CdnServer,
    depotId: Int,
    chunk: DepotChunkSpec,
    cdnAuthToken: String?,
    destination: ByteArray,
    beforeRead: suspend () -> Unit,
): Int {
    val chunkId = requireNotNull(chunk.chunkID) { "Chunk must have a ChunkID." }
    require(destination.size == chunk.compressedLength) {
        "Steam chunk destination must match compressed length"
    }
    val chunkIdHex = chunkId.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val command = "depot/$depotId/chunk/$chunkIdHex"
    val request =
        if (ClientLancache.useLanCacheServer) {
            ClientLancache.buildLancacheRequest(server, command, cdnAuthToken)
        } else {
            Request.Builder()
                .url(buildSteamCdnCommand(server, command, cdnAuthToken))
                .build()
        }
    val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
    response.use { resp ->
        if (!resp.isSuccessful) {
            throw SteamCdnHttpException(
                "Steam CDN returned ${resp.code} (${resp.message})",
                resp,
            )
        }
        val expected =
            resp.body.contentLength().takeIf { it > 0L }?.toInt()
                ?: chunk.compressedLength
        check(expected == chunk.compressedLength) {
            "Steam chunk Content-Length mismatch: $expected != ${chunk.compressedLength}"
        }
        var offset = 0
        resp.body.byteStream().use { input ->
            while (offset < expected) {
                beforeRead()
                val count = input.read(destination, offset, expected - offset)
                if (count < 0) break
                if (count == 0) continue
                offset += count
            }
        }
        check(offset == expected) {
            "Steam chunk length mismatch after streaming: $offset != $expected"
        }
        return offset
    }
}

private suspend fun downloadEncryptedChunkStreamingToFile(
    httpClient: OkHttpClient,
    server: CdnServer,
    depotId: Int,
    chunk: DepotChunkSpec,
    cdnAuthToken: String?,
    destination: File,
): Int = suspendCancellableCoroutine { continuation ->
    val chunkId = requireNotNull(chunk.chunkID) { "Chunk must have a ChunkID." }
    val chunkIdHex = chunkId.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val command = "depot/$depotId/chunk/$chunkIdHex"
    val request =
        if (ClientLancache.useLanCacheServer) {
            ClientLancache.buildLancacheRequest(server, command, cdnAuthToken)
        } else {
            Request.Builder().url(buildSteamCdnCommand(server, command, cdnAuthToken)).build()
        }
    val call = httpClient.newCall(request)
    continuation.invokeOnCancellation {
        call.cancel()
        destination.delete()
    }
    call.enqueue(
        object : Callback {
            override fun onFailure(
                call: Call,
                e: IOException,
            ) {
                destination.delete()
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(
                call: Call,
                response: Response,
            ) {
                try {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            throw SteamCdnHttpException(
                                "Steam CDN returned ${resp.code} (${resp.message})",
                                resp,
                            )
                        }
                        val expected =
                            resp.body.contentLength().takeIf { it > 0L }
                                ?: chunk.compressedLength.toLong()
                        check(expected == chunk.compressedLength.toLong()) {
                            "Steam chunk Content-Length mismatch: $expected != ${chunk.compressedLength}"
                        }
                        destination.parentFile?.mkdirs()
                        var written = 0L
                        val transferBuffer = ByteArray(STEAM_STREAM_NETWORK_COPY_BUFFER_BYTES)
                        resp.body.byteStream().use { input ->
                            BufferedOutputStream(
                                destination.outputStream(),
                                STEAM_STREAM_NETWORK_COPY_BUFFER_BYTES,
                            ).use { output ->
                                while (written < expected) {
                                    if (!continuation.isActive) throw CancellationException("Steam chunk request cancelled")
                                    val count =
                                        input.read(
                                            transferBuffer,
                                            0,
                                            min(transferBuffer.size.toLong(), expected - written).toInt(),
                                        )
                                    if (count < 0) break
                                    if (count == 0) continue
                                    output.write(transferBuffer, 0, count)
                                    written += count
                                }
                            }
                        }
                        check(written == expected && destination.length() == expected) {
                            "Steam chunk length mismatch after streaming: $written != $expected"
                        }
                        if (continuation.isActive) continuation.resume(written.toInt())
                    }
                } catch (error: Throwable) {
                    destination.delete()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        },
    )
}

/** Returns the OkHttp client configured by createCdnClient (proxy, TLS and dispatcher). */
private fun buildSteamCdnCommand(
    server: CdnServer,
    command: String,
    query: String?,
): HttpUrl {
    val scheme = if (server.https) "https" else "http"
    val builder =
        HttpUrl.Builder()
            .scheme(scheme)
            .host(server.vHost ?: server.host ?: "")
            .port(server.port)
            .addPathSegments(command.trimStart('/'))
    query?.trimStart('?')?.takeIf { it.isNotEmpty() }?.split('&')?.forEach { parameter ->
        val keyValue = parameter.split('=', limit = 2)
        if (keyValue.size == 2) {
            builder.addQueryParameter(keyValue[0], keyValue[1])
        } else if (keyValue[0].isNotEmpty()) {
            builder.addQueryParameter(keyValue[0], "")
        }
    }
    return builder.build()
}

internal class SteamCdnHttpException(message: String) : IOException(message)

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
    httpClient: OkHttpClient,
    servers: List<CdnServer>,
    depotId: Int,
    depotKey: ByteArray,
    authTokens: CdnAuthTokenProvider,
    selector: CdnServerSelector,
    depotDownloader: DepotDownloader,
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
                        httpClient = httpClient,
                        servers = servers,
                        depotId = depotId,
                        depotKey = depotKey,
                        authTokens = authTokens,
                        selector = selector,
                        depotDownloader = depotDownloader,
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
    httpClient: OkHttpClient,
    servers: List<CdnServer>,
    depotId: Int,
    depotKey: ByteArray,
    authTokens: CdnAuthTokenProvider,
    selector: CdnServerSelector,
    depotDownloader: DepotDownloader,
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
            httpClient = httpClient,
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
    chunks: List<DepotChunkSpec>,
    output: RandomAccessFile,
    httpClient: OkHttpClient,
    servers: List<CdnServer>,
    depotId: Int,
    depotKey: ByteArray,
    authTokens: CdnAuthTokenProvider,
    selector: CdnServerSelector,
    depotDownloader: DepotDownloader,
    chunkConcurrency: Int,
    control: suspend () -> SteamDownloadControl,
    onChunkWritten: suspend (ByteArray) -> Unit,
) = coroutineScope {
    if (chunks.isEmpty()) return@coroutineScope
    val queue = Channel<DepotChunkSpec>(chunkConcurrency)
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
                    httpClient = httpClient,
                    servers = servers,
                    depotId = depotId,
                    chunk = chunk,
                    depotKey = depotKey,
                    authTokens = authTokens,
                    selector = selector,
                    depotDownloader = depotDownloader,
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
    file: DepotFileSpec,
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
    manifestFile: DepotFileSpec,
    chunks: List<DepotChunkSpec>,
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

internal fun createCdnHttpClient(options: SteamContentDownloadOptions): OkHttpClient {
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
                chain.proceed(
                    if (secureUrl == request.url) request else request.newBuilder().url(secureUrl).build(),
                )
            }
    httpClientBuilder.applyDownloadProxy(options.proxyUrl)
    return httpClientBuilder.build()
}

internal fun HttpUrl.upgradeSteamCdnUrl(): HttpUrl =
    if (scheme == "http") {
        newBuilder().scheme("https").port(HTTPS_PORT).build()
    } else {
        this
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
    server: CdnServer,
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

internal data class SteamContentAccess(
    val depotKey: ByteArray,
    val manifestRequestCode: Long,
    val servers: List<CdnServer>,
    val authTokens: CdnAuthTokenProvider,
)

internal data class DownloadedChunk(
    val offset: Long,
    val data: ByteArray,
    val written: CompletableDeferred<Unit>,
)

internal data class ManifestFilePlan(
    val file: DepotFileSpec,
    val chunks: List<DepotChunkSpec>,
) {
    fun isSmallFilePipelineCandidate(): Boolean = isSmallFilePipelineCandidate(file.totalSize, chunks.size)
}

internal class CdnServerSelector {
    private val nextTieBreakerIndex = AtomicInteger()
    private val failedHosts = mutableMapOf<String, Long>()
    private val performance = mutableMapOf<String, CdnHostPerformance>()

    @Synchronized
    fun candidates(servers: List<CdnServer>): List<CdnServer> {
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
                    compareByDescending<IndexedValue<CdnServer>> { indexed ->
                        val stats = performance[indexed.value.selectorKey()]
                        val estimated =
                            stats?.bytesPerSecond?.takeIf { stats.samples > 0 }
                                ?: bestKnownBytesPerSecond * CDN_UNSAMPLED_EXPLORATION_MULTIPLIER
                        estimated / ((stats?.activeRequests ?: 0) + 1).toDouble()
                    }.thenBy(IndexedValue<CdnServer>::index),
                ).map(IndexedValue<CdnServer>::value)
        return ranked + failed
    }

    @Synchronized
    fun recordStart(server: CdnServer) {
        performance.getOrPut(server.selectorKey(), ::CdnHostPerformance).activeRequests += 1
    }

    @Synchronized
    fun recordFailure(server: CdnServer) {
        performance[server.selectorKey()]?.let { stats ->
            stats.activeRequests = (stats.activeRequests - 1).coerceAtLeast(0)
        }
        failedHosts[server.selectorKey()] = System.currentTimeMillis() + CDN_FAILURE_COOLDOWN_MS
    }

    @Synchronized
    fun recordCancelled(server: CdnServer) {
        performance[server.selectorKey()]?.let { stats ->
            stats.activeRequests = (stats.activeRequests - 1).coerceAtLeast(0)
        }
    }

    @Synchronized
    fun recordSuccess(
        server: CdnServer,
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

    private fun isPenalized(server: CdnServer, now: Long): Boolean {
        val key = server.selectorKey()
        val expiry = failedHosts[key] ?: return false
        if (expiry <= now) {
            failedHosts.remove(key, expiry)
            return false
        }
        return true
    }

    private fun CdnServer.selectorKey(): String = (resolveCdnRequestHost(vHost, host) ?: host.orEmpty()).lowercase()

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
    private val client: SteamClient,
    private val appId: Int,
    private val depotId: Int,
    private val enabled: Boolean,
) {
    private data class CachedCdnAuthToken(
        val token: String,
        val expiresAtMs: Long,
    )

    private val cachedTokens = mutableMapOf<String, CachedCdnAuthToken>()
    private val pendingTokens = mutableMapOf<String, kotlinx.coroutines.Deferred<CachedCdnAuthToken>>()
    private val mutex = Mutex()
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun get(server: CdnServer): String? {
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
                        client.steamCdnAuthToken(appId = appId, depotId = depotId, hostName = requestHost)
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
}

internal fun String.videoFileExtension(): String {
    val fileName = substringAfterLast('/').substringAfterLast('\\')
    return fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
}

internal const val WALLPAPER_ENGINE_APP_ID = 431960
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

internal fun validateManifestFiles(files: List<DepotFileSpec>): Long =
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

internal fun validateManifestChunk(chunk: DepotChunkSpec) {
    val boundary = manifestChunkBoundary(chunk)
    require(boundary.compressedLength in 0..MAX_MANIFEST_CHUNK_BYTES) {
        "Steam manifest compressed chunk length is invalid: ${boundary.compressedLength}"
    }
    require(boundary.uncompressedLength in 0..MAX_MANIFEST_CHUNK_BYTES) {
        "Steam manifest uncompressed chunk length is invalid: ${boundary.uncompressedLength}"
    }
}

private fun manifestChunkBoundary(chunk: DepotChunkSpec): ManifestChunkBoundary =
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

internal fun steamStreamStagingWindowBytes(
    compressedLengths: List<Int>,
    concurrency: Int,
): Long {
    if (compressedLengths.isEmpty()) return 0L
    require(compressedLengths.all { it >= 0 }) { "Steam chunk compressed length must not be negative" }
    val windowSize = concurrency.coerceIn(1, compressedLengths.size)
    var current = compressedLengths.take(windowSize).sumOf(Int::toLong)
    var peak = current
    for (index in windowSize until compressedLengths.size) {
        current += compressedLengths[index].toLong() - compressedLengths[index - windowSize].toLong()
        peak = max(peak, current)
    }
    return peak
}

internal fun steamStreamStagingPipelineBytes(
    compressedLengths: List<Int>,
    concurrency: Int,
): Long =
    steamStreamStagingWindowBytes(compressedLengths, concurrency)
        .let { windowBytes ->
            if (windowBytes > Long.MAX_VALUE / STEAM_STREAM_STAGING_PIPELINE_WINDOWS) {
                Long.MAX_VALUE
            } else {
                windowBytes * STEAM_STREAM_STAGING_PIPELINE_WINDOWS
            }
        }

internal fun steamStreamChunkPipelineBytes(
    @Suppress("UNUSED_PARAMETER") compressedBytes: Int,
    uncompressedBytes: Int,
): Long {
    require(uncompressedBytes >= 0) { "Uncompressed Steam chunk size must not be negative" }
    return uncompressedBytes.toLong().coerceAtLeast(1L)
}

internal fun steamStreamMemoryBudgetBytes(
    @Suppress("UNUSED_PARAMETER") maxHeapBytes: Long,
): Long = STEAM_STREAM_DEFAULT_CHUNK_BUFFER_BYTES

internal fun steamStreamDecodeThreads(
    configuredConcurrency: Int,
    availableProcessors: Int,
): Int =
    min(
        configuredConcurrency.coerceIn(1, STEAM_STREAM_MAX_PARALLEL_CHUNKS),
        min(availableProcessors.coerceAtLeast(1), STEAM_STREAM_MAX_DECODE_THREADS),
    )

private fun averageBytesPerMillisecond(
    contentLength: Long,
    durationMs: Long,
): Double =
    if (contentLength > 0L && durationMs > 0L) {
        contentLength.toDouble() / durationMs.toDouble()
    } else {
        0.0
    }

internal fun steamStreamMissingRange(
    contentLength: Long,
    consumedPosition: Long,
    bufferedEndInclusive: Long,
    targetAheadBytes: Long,
): SteamStreamByteRange? {
    if (contentLength <= 0L ||
        consumedPosition !in 0 until contentLength ||
        targetAheadBytes <= 0L
    ) {
        return null
    }
    val remainingBytes = (bufferedEndInclusive - consumedPosition + 1L).coerceAtLeast(0L)
    if (remainingBytes >= targetAheadBytes) return null
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
private const val STEAM_STREAM_BUFFER_READY_TOLERANCE_MS = 250L
internal const val STEAM_STREAM_MAX_PARALLEL_CHUNKS = 32
internal const val STEAM_STREAM_DEFAULT_CHUNK_BUFFER_BYTES = 64L * 1024L * 1024L
internal const val STEAM_STREAM_MAX_DECODE_THREADS = 8
private const val STEAM_STREAM_NETWORK_COPY_BUFFER_BYTES = 64 * 1024
private const val STEAM_STREAM_STAGING_PIPELINE_WINDOWS = 2L
internal const val STREAM_SEEK_RESET_BYTES = 2L * STEAM_STREAM_MEBIBYTE
private const val STREAM_METRICS_LOG_INTERVAL_CHUNKS = 16L
private const val STREAM_METRICS_WINDOW_NANOS = 5_000_000_000L
private const val STEAM_STREAM_BUFFER_LOG_INTERVAL_MS = 5_000L
private const val STEAM_STREAM_MIN_PLAYBACK_SPEED = 0.1f

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
