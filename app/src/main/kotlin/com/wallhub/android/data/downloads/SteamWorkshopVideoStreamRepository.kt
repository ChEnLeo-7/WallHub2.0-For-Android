package com.wallhub.android.data.downloads

import android.content.Context
import android.util.Log
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamContentCredentialProvider
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
        private val playbackCoordinator: PlaybackDownloadCoordinator,
        private val sdkSessionBroker: SteamContentSdkSessionBroker,
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
                    // A direct-file item is a file Steam publishes at a URL: there is no depot to resolve,
                    // so the product's own HTTP range reader serves it, exactly as the direct-file download
                    // path does. Every depot-backed item is resolved and served by the SDK range arm, which
                    // reads no Depot Key, CDN token, or URL of ours.
                    val stream =
                        when (val delivery = workshopDelivery(target)) {
                            is WorkshopDelivery.DirectFile ->
                                SteamRemoteVideoStream.open(
                                    title = target.title,
                                    fileName =
                                        target.rawFileName.ifBlank {
                                            delivery.remoteUrl.substringAfterLast('/').substringBefore('?')
                                        },
                                    remoteUrl = delivery.remoteUrl,
                                    expectedSize = target.expectedSize,
                                    clientBuilder =
                                        steamHttpClientFactory.newBuilder().applyDownloadProxy(activeProxyUrl),
                                )
                            is WorkshopDelivery.DepotContent -> openSdkVideoStream(target)
                        }
                    val coordinated = CoordinatedVideoStreamSession(stream, playbackCoordinator.registerPlayback())
                    openedStream = coordinated
                    return@withContext coordinated
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
        /** The online-playback cache ceiling the product's settings name, in bytes. */
        private suspend fun mediaCacheLimitBytes(): Long =
            settingsRepository.preferences.first().mediaCacheLimitMb
                .coerceAtLeast(MIN_MEDIA_CACHE_MB)
                .toLong() * BYTES_PER_MB

        private suspend fun openSdkVideoStream(target: WorkshopContentTarget): WorkshopVideoStreamSession {
            val credential =
                credentialProvider.loadContentCredential()
                    ?: credentialProvider.restoreContentCredential()
            check(credential != null && credential.refreshToken.isNotBlank()) {
                "The Steam Content SDK range path needs the stored Steam refresh token; none was read"
            }
            val sessionLease = sdkSessionBroker.acquire(credential)
            val session = sessionLease.session
            try {
                val resolved = session.resolveWorkshop(target.publishedFileId)
                check(resolved.resolved) {
                    "The Steam Content SDK did not resolve Workshop item ${target.publishedFileId} to an app, depot, and manifest"
                }
                check(resolved.appId > 0L && resolved.depotId > 0L) {
                    "The Steam Content SDK resolved Workshop item ${target.publishedFileId} without a usable app or depot"
                }
                // The manifest names the file, because the item's public details need not: the product rule
                // that picks the video lives here, not in the SDK, and the size it reports is the decoded one.
                val videoFile =
                    selectStreamableVideoFile(
                        session.manifestFiles(resolved.appId, resolved.depotId, resolved.manifestId),
                    ) ?: error(
                        "The Steam Content SDK manifest for Workshop item ${target.publishedFileId} " +
                            "has no streamable video file",
                    )
                // One line per open so a parity reading can name the path that served the ranges. The ids
                // are public catalog numbers; no title, path, key, or token is logged.
                Log.i(
                    "SteamVideoStream",
                    "playback served by=steam-content-sdk app=${resolved.appId} depot=${resolved.depotId}",
                )
                val stream =
                    session.openRangeStream(
                        appId = resolved.appId,
                        depotId = resolved.depotId,
                        filePath = videoFile.path,
                        manifestId = resolved.manifestId,
                        // The product's own online-playback cache setting is this stream's ceiling now: the
                        // SDK builds one bounded cache per stream, so the size the user chose is the size it
                        // holds. Zero would leave the SDK's own default in place.
                        cacheCapacityBytes = mediaCacheLimitBytes(),
                    )
                return CoordinatedVideoStreamSession(
                    SteamContentSdkVideoStreamSession(
                        sessionLease = sessionLease,
                        stream = stream,
                        title = target.title,
                        fileName = videoFile.path,
                        contentLength = videoFile.size,
                    ),
                    playbackCoordinator.registerPlayback(),
                )
            } catch (error: Throwable) {
                runCatching { sessionLease.close() }
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

private const val STREAM_CACHE_DIRECTORY = "steam-video-stream"

/** The settings screen's own floor for the online-playback cache, mirrored here so the stream never asks for
 * a cache smaller than the screen can express. */
private const val MIN_MEDIA_CACHE_MB = 128
private const val BYTES_PER_MB = 1024L * 1024L
