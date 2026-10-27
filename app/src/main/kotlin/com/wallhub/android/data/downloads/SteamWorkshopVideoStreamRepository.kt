package com.wallhub.android.data.downloads

import android.content.Context
import android.util.Log
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.WorkshopVideoOpenRequest
import com.wallhub.android.core.model.WorkshopVideoStreamRepository
import com.wallhub.android.core.model.WorkshopVideoStreamSession
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class SteamWorkshopVideoStreamRepository private constructor(
    private val cacheRootDirectory: File,
    private val settingsRepository: SettingsRepository,
    private val steamHttpClientFactory: SteamHttpClientFactory?,
    private val contentTargetFetcher: suspend (Long) -> WorkshopContentTarget,
    private val playbackCoordinator: PlaybackDownloadCoordinator,
    private val sdkSessionBroker: SteamContentSdkSessionBroker,
    private val closeScheduler: VideoStreamCloseScheduler,
) : WorkshopVideoStreamRepository {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
        steamHttpClientFactory: SteamHttpClientFactory,
        playbackCoordinator: PlaybackDownloadCoordinator,
        sdkSessionBroker: SteamContentSdkSessionBroker,
        closeScheduler: CoroutineVideoStreamCloseScheduler,
    ) : this(
        cacheRootDirectory = File(context.cacheDir, STREAM_CACHE_DIRECTORY),
        settingsRepository = settingsRepository,
        steamHttpClientFactory = steamHttpClientFactory,
        contentTargetFetcher = { workshopId ->
            val preferences = settingsRepository.preferences.first()
            val proxyUrl = preferences.downloadProxyUrl.takeIf { preferences.downloadProxyEnabled }.orEmpty()
            SteamWorkshopContentApi(steamHttpClientFactory.newBuilder().applyDownloadProxy(proxyUrl))
                .fetchContentTarget(workshopId)
        },
        playbackCoordinator = playbackCoordinator,
        sdkSessionBroker = sdkSessionBroker,
        closeScheduler = closeScheduler,
    )

    internal constructor(
        cacheRootDirectory: File,
        settingsRepository: SettingsRepository,
        contentTargetFetcher: suspend (Long) -> WorkshopContentTarget,
        playbackCoordinator: PlaybackDownloadCoordinator,
        sdkSessionBroker: SteamContentSdkSessionBroker,
        closeScheduler: VideoStreamCloseScheduler = DirectVideoStreamCloseScheduler,
    ) : this(
        cacheRootDirectory = cacheRootDirectory,
        settingsRepository = settingsRepository,
        steamHttpClientFactory = null,
        contentTargetFetcher = contentTargetFetcher,
        playbackCoordinator = playbackCoordinator,
        sdkSessionBroker = sdkSessionBroker,
        closeScheduler = closeScheduler,
    )

    override suspend fun open(request: WorkshopVideoOpenRequest): WorkshopVideoStreamSession {
        var openedStream: WorkshopVideoStreamSession? = null
        var closeReservation: VideoStreamCloseReservation? = null
        val operationId = NEXT_OPERATION_ID.incrementAndGet()
        val totalStarted = System.nanoTime()
        try {
            return withContext(Dispatchers.IO) {
                    require(request.workshopId > 0L) { "Invalid Workshop item ID" }
                    val reservation =
                        checkNotNull(closeScheduler.tryReserve()) {
                            "Too many video streams are awaiting native cleanup"
                        }
                    closeReservation = reservation
                    val preferences = settingsRepository.preferences.first()
                    val activeProxyUrl = preferences.downloadProxyUrl.takeIf { preferences.downloadProxyEnabled }.orEmpty()
                    val targetStarted = System.nanoTime()
                    val suppliedTarget = request.toContentTargetOrNull()
                    val target = suppliedTarget ?: contentTargetFetcher(request.workshopId)
                    logPhase(operationId, "target", targetStarted, if (suppliedTarget == null) "fetched" else "supplied")
                    check(target.contentTypeHint == "video") { "This item is not a streamable video wallpaper" }
                    // A direct-file item is a file Steam publishes at a URL: there is no depot to resolve,
                    // so the product's own HTTP range reader serves it, exactly as the direct-file download
                    // path does. Every depot-backed item is resolved and served by the SDK range arm, which
                    // reads no Depot Key, CDN token, or URL of ours.
                    val stream =
                        when (val delivery = workshopDelivery(target)) {
                            is WorkshopDelivery.DirectFile -> {
                                val directOpenStarted = System.nanoTime()
                                logSkippedPhase(operationId, "session")
                                logSkippedPhase(operationId, "resolve")
                                logSkippedPhase(operationId, "manifest")
                                SteamRemoteVideoStream.open(
                                    title = target.title,
                                    fileName = directVideoFileName(target, delivery.remoteUrl),
                                    remoteUrl = delivery.remoteUrl,
                                    expectedSize = target.expectedSize,
                                    clientBuilder =
                                        checkNotNull(steamHttpClientFactory).newBuilder().applyDownloadProxy(activeProxyUrl),
                                ).also {
                                    logPhase(operationId, "open", directOpenStarted, "direct")
                                }
                            }
                            is WorkshopDelivery.DepotContent -> openSdkVideoStream(target, request, operationId)
                        }
                    val coordinated =
                        CoordinatedVideoStreamSession(
                            delegate = stream,
                            playbackLease = playbackCoordinator.registerPlayback(),
                            closeReservation = reservation,
                        )
                    closeReservation = null
                    openedStream = coordinated
                    return@withContext coordinated
                }.also { openedStream = null }
            } finally {
                openedStream?.close()
                closeReservation?.close()
                Log.i(TAG, "video_prepare op=$operationId phase=total elapsed_ms=${elapsedMillis(totalStarted)}")
            }
        }

        override suspend fun clearCache(): Long =
            withContext(Dispatchers.IO) {
                SteamVideoStreamCache.clearRoot(cacheRootDirectory)
            }

        /**
         * Opens one playback session on the SDK's depot range stream.
         *
         * The SDK resolves the item to its app, depot, and manifest and authorizes the ranges itself, so
         * this path reads no Depot Key, CDN token, or URL. The session is opened for the length of
         * playback and is released with the stream, so an SDK playback session never outlives the screen
         * that started it. Every failure is a failure - there is no fall-through to the old backend.
         */
        private suspend fun openSdkVideoStream(
            target: WorkshopContentTarget,
            request: WorkshopVideoOpenRequest,
            operationId: Long,
        ): WorkshopVideoStreamSession {
            val sessionStarted = System.nanoTime()
            val sessionLease = sdkSessionBroker.acquire()
            logPhase(operationId, "session_lease", sessionStarted, "complete")
            val session = sessionLease.session
            try {
                val resolveStarted = System.nanoTime()
                val suppliedIdentity =
                    request.consumerAppId?.takeIf { it > 0L }?.let { appId ->
                        request.depotId?.takeIf { it > 0L }?.let { depotId ->
                            request.manifestId?.takeIf { it != 0L }?.let { manifestId ->
                                SteamContentSdkResolvedItem(
                                    itemId = request.workshopId,
                                    appId = appId,
                                    depotId = depotId,
                                    manifestId = manifestId,
                                    resolved = true,
                                )
                            }
                        }
                    }
                val resolved =
                    suppliedIdentity ?: session.resolveWorkshop(
                        itemId = target.publishedFileId,
                        appIdHint = request.consumerAppId ?: target.appId.toLong().takeIf { it > 0L } ?: 0L,
                        contentManifestIdHint = request.manifestId ?: target.contentManifestId.takeIf { it != 0L } ?: 0L,
                    )
                logPhase(
                    operationId,
                    "resolve",
                    resolveStarted,
                    if (suppliedIdentity == null) "sdk_lookup" else "trusted_detail_identity",
                )
                check(resolved.resolved) {
                    "The Steam Content SDK did not resolve Workshop item ${target.publishedFileId} to an app, depot, and manifest"
                }
                check(resolved.appId > 0L && resolved.depotId > 0L) {
                    "The Steam Content SDK resolved Workshop item ${target.publishedFileId} without a usable app or depot"
                }
                // Steam public `filename` names the published payload and is not proof of an internal Depot
                // path. Always select the video from the authenticated decoded manifest; skipping this would
                // trade startup latency for incorrect files. This starts only after an explicit Play click.
                val manifestStarted = System.nanoTime()
                val videoFile =
                    selectStreamableVideoFile(
                        session.manifestFiles(resolved.appId, resolved.depotId, resolved.manifestId),
                    ) ?: error(
                        "The Steam Content SDK manifest for Workshop item ${target.publishedFileId} " +
                            "has no streamable video file",
                    )
                logPhase(operationId, "manifest", manifestStarted, "listed")
                // One line per open so a parity reading can name the path that served the ranges. The ids
                // are public catalog numbers; no title, path, key, or token is logged.
                Log.i(
                    "SteamVideoStream",
                    "playback served by=steam-content-sdk app=${resolved.appId} depot=${resolved.depotId}",
                )
                val rangeOpenStarted = System.nanoTime()
                val stream =
                    session.openRangeStream(
                        appId = resolved.appId,
                        depotId = resolved.depotId,
                        filePath = videoFile.path,
                        manifestId = resolved.manifestId,
                        // This is process memory owned by one live stream, not the user's media-cache budget.
                        // Keep that setting intact for a future durable disk cache instead of translating it
                        // into an SDK allocation whose per-stream ceiling is 64 MiB.
                        cacheCapacityBytes = MOBILE_STREAM_MEMORY_CACHE_BYTES,
                    )
                logPhase(operationId, "open", rangeOpenStarted, "sdk_range")
                return SteamContentSdkVideoStreamSession(
                    sessionLease = sessionLease,
                    stream = stream,
                    title = target.title,
                    fileName = videoFile.path,
                    contentLength = videoFile.size,
                )
            } catch (error: Throwable) {
                runCatching { sessionLease.close() }
                throw error
            }
        }
    }

/** Ends playback admission immediately, signals cancellation, and moves native handle disposal off the caller thread. */
internal class CoordinatedVideoStreamSession(
    private val delegate: WorkshopVideoStreamSession,
    private val playbackLease: Closeable,
    private val closeReservation: VideoStreamCloseReservation,
) : WorkshopVideoStreamSession by delegate {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { delegate.cancelPendingReads() }
        runCatching { playbackLease.close() }
        closeReservation.schedule(delegate::close)
    }
}

private fun WorkshopVideoOpenRequest.toContentTargetOrNull(): WorkshopContentTarget? {
    val hasDirectIdentity = !directUrl.isNullOrBlank()
    val hasDepotIdentity =
        consumerAppId?.let { it > 0L } == true &&
            manifestId?.let { it != 0L } == true
    if (!hasDirectIdentity && !hasDepotIdentity) return null
    val appId = consumerAppId?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: 0
    return WorkshopContentTarget(
        publishedFileId = workshopId,
        title = title.ifBlank { "Workshop $workshopId" },
        appId = appId,
        contentManifestId = manifestId ?: 0L,
        expectedSize = expectedSizeBytes?.coerceAtLeast(0L) ?: 0L,
        contentTypeHint = "video",
        fileUrl = directUrl.orEmpty(),
        rawFileName = fileName.orEmpty(),
    )
}

private fun logPhase(
    operationId: Long,
    phase: String,
    startedNanos: Long,
    source: String,
) {
    Log.i(TAG, "video_prepare op=$operationId phase=$phase elapsed_ms=${elapsedMillis(startedNanos)} source=$source")
}

private fun logSkippedPhase(
    operationId: Long,
    phase: String,
) {
    Log.i(TAG, "video_prepare op=$operationId phase=$phase elapsed_ms=0 source=not_required")
}

private fun elapsedMillis(startedNanos: Long): Long =
    (System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000L

private const val TAG = "SteamVideoStream"
private val NEXT_OPERATION_ID = AtomicLong(0L)
private const val STREAM_CACHE_DIRECTORY = "steam-video-stream"

/** Fixed process-memory budget for one mobile SDK stream; deliberately independent of disk-cache settings. */
private const val MOBILE_STREAM_MEMORY_CACHE_BYTES = 32L * 1024L * 1024L
