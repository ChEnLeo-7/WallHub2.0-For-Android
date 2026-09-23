package com.wallhub.android.data.downloads

import android.content.Context
import android.util.Log
import com.wallhub.android.BuildConfig
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.WorkshopVideoStreamRepository
import com.wallhub.android.core.model.WorkshopVideoStreamSession
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class SteamWorkshopVideoStreamRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val credentialProvider: SteamContentCredentialProvider,
        private val settingsRepository: SettingsRepository,
        private val steamHttpClientFactory: SteamHttpClientFactory,
        private val contentDownloader: SteamContentDownloader,
        private val playbackCoordinator: PlaybackDownloadCoordinator,
        private val sdkSessionOpener: Lazy<SteamContentSdkSessionOpener>,
    ) : WorkshopVideoStreamRepository {
        private val cacheRootDirectory: File
            get() = File(context.cacheDir, STREAM_CACHE_DIRECTORY)

        override suspend fun open(workshopId: Long): WorkshopVideoStreamSession {
            var openedStream: WorkshopVideoStreamSession? = null
            try {
                return withContext(Dispatchers.IO) {
                    require(workshopId > 0L) { "Invalid Workshop item ID" }
                    val preferences = settingsRepository.preferences.first()
                    val activeProxyUrl = preferences.downloadProxyUrl.takeIf { preferences.downloadProxyEnabled }.orEmpty()
                    val target =
                        SteamWorkshopContentApi(
                            steamHttpClientFactory.newBuilder().applyDownloadProxy(activeProxyUrl),
                        ).fetchContentTarget(workshopId)
                    check(target.contentTypeHint == "video") { "This item is not a streamable video wallpaper" }
                    // The SDK arm serves every range itself, so it comes before the direct-link fast
                    // path: with the flag on, playback is the SDK's data plane and nothing falls back.
                    if (selectWorkshopVideoStreamPath(BuildConfig.USE_SDK_RANGE_STREAM) ==
                        WorkshopVideoStreamPath.STEAM_CONTENT_SDK
                    ) {
                        val sdkSession = openSdkVideoStream(target)
                        openedStream = sdkSession
                        return@withContext sdkSession
                    }
                    // Fast path: when Steam publishes a direct video file URL, stream it
                    // with plain HTTP range requests and skip the manifest, depot key,
                    // and CDN negotiation round trips entirely.
                    val remoteUrl = remoteVideoUrlOrNull(target)
                    if (remoteUrl != null) {
                        val remoteSession =
                            runCatching {
                                SteamRemoteVideoStream.open(
                                    title = target.title,
                                    fileName =
                                        target.rawFileName.ifBlank {
                                            remoteUrl.substringAfterLast('/').substringBefore('?')
                                        },
                                    remoteUrl = remoteUrl,
                                    expectedSize = target.expectedSize,
                                    clientBuilder =
                                        steamHttpClientFactory.newBuilder().applyDownloadProxy(activeProxyUrl),
                                )
                            }.onFailure { error ->
                                if (error is CancellationException) throw error
                                Log.w(
                                    "SteamVideoStream",
                                    "Remote file_url stream unavailable, falling back to depot chunks: ${error.message}",
                                )
                            }.getOrNull()
                        if (remoteSession != null) {
                            val coordinated = CoordinatedVideoStreamSession(remoteSession, playbackCoordinator.registerPlayback())
                            openedStream = coordinated
                            return@withContext coordinated
                        }
                    }
                    var lastError: Throwable? = null
                    repeat(VIDEO_STREAM_OPEN_ATTEMPTS) { attempt ->
                        try {
                            return@withContext contentDownloader.openVideoStream(
                                target = target,
                                credential =
                                    if (attempt == 0) {
                                        credentialProvider.loadContentCredential()
                                    } else {
                                        credentialProvider.restoreContentCredential()
                                    },
                                options =
                                    SteamContentDownloadOptions(
                                        chunkConcurrency = preferences.chunkDownloadConcurrency,
                                        proxyUrl = activeProxyUrl,
                                    ),
                                cacheRootDirectory = cacheRootDirectory,
                                cacheLimitBytes = preferences.mediaCacheLimitMb.toLong() * 1024L * 1024L,
                            ).let { stream ->
                                CoordinatedVideoStreamSession(stream, playbackCoordinator.registerPlayback())
                            }.also { openedStream = it }
                        } catch (error: SteamDepotAccessException) {
                            // A restored credential can unlock the depot on the retry pass.
                            lastError = error
                            if (attempt + 1 >= VIDEO_STREAM_OPEN_ATTEMPTS) {
                                throw error
                            }
                            delay(VIDEO_STREAM_RETRY_DELAY_MS)
                        }
                    }
                    throw lastError ?: IllegalStateException("Steam video stream could not be opened")
                }.also { openedStream = null }
            } finally {
                openedStream?.close()
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
        private suspend fun openSdkVideoStream(target: WorkshopContentTarget): WorkshopVideoStreamSession {
            val credential =
                credentialProvider.loadContentCredential()
                    ?: credentialProvider.restoreContentCredential()
            check(credential != null && credential.refreshToken.isNotBlank()) {
                "The Steam Content SDK range path needs the stored Steam refresh token; none was read"
            }
            val filePath = target.rawFileName
            check(filePath.isNotBlank()) {
                "The Steam Content SDK range path needs the item's in-manifest file name; none was read"
            }
            val session = sdkSessionOpener.get().open(credential)
            try {
                val resolved = session.resolveWorkshop(target.publishedFileId)
                check(resolved.resolved) {
                    "The Steam Content SDK did not resolve Workshop item ${target.publishedFileId} to an app, depot, and manifest"
                }
                check(resolved.appId > 0L && resolved.depotId > 0L) {
                    "The Steam Content SDK resolved Workshop item ${target.publishedFileId} without a usable app or depot"
                }
                val stream =
                    session.openRangeStream(
                        appId = resolved.appId,
                        depotId = resolved.depotId,
                        filePath = filePath,
                        manifestId = resolved.manifestId,
                    )
                return CoordinatedVideoStreamSession(
                    SteamContentSdkVideoStreamSession(
                        session = session,
                        stream = stream,
                        title = target.title,
                        fileName = filePath,
                        contentLength = target.expectedSize,
                    ),
                    playbackCoordinator.registerPlayback(),
                )
            } catch (error: Throwable) {
                runCatching { session.close() }
                throw error
            }
        }
    }

private class CoordinatedVideoStreamSession(
    private val delegate: WorkshopVideoStreamSession,
    private val playbackLease: Closeable,
) : WorkshopVideoStreamSession by delegate {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            delegate.close()
        } finally {
            playbackLease.close()
        }
    }
}

/**
 * The two implementations of [WorkshopVideoStreamRepository]'s playback source this build can choose
 * between.
 */
internal enum class WorkshopVideoStreamPath {
    /** The direct-link fast path and the kSteam + `wallhub-rust` depot chunks: the default path. */
    DIRECT_LINK_AND_WALLHUB_RUST,

    /** The Steam Content SDK resolves the item and serves every range of the file itself. */
    STEAM_CONTENT_SDK,
}

/**
 * The single decision point for one playback stream.
 *
 * `wallhub.useSdkRangeStream` (surfaced as `BuildConfig.USE_SDK_RANGE_STREAM`) stays off by default, so
 * the unchecked value selects the existing direct-link and depot-chunk path byte for byte. The repository
 * routes through this one expression rather than testing the flag itself, so a later comparison and the
 * deletion of the old arm touch this one place.
 */
internal fun selectWorkshopVideoStreamPath(useSdkRangeStream: Boolean): WorkshopVideoStreamPath =
    if (useSdkRangeStream) {
        WorkshopVideoStreamPath.STEAM_CONTENT_SDK
    } else {
        WorkshopVideoStreamPath.DIRECT_LINK_AND_WALLHUB_RUST
    }

private const val VIDEO_STREAM_OPEN_ATTEMPTS = 3
private const val VIDEO_STREAM_RETRY_DELAY_MS = 750L
private const val STREAM_CACHE_DIRECTORY = "steam-video-stream"
