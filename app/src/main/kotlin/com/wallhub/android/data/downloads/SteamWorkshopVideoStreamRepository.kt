package com.wallhub.android.data.downloads

import android.content.Context
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.WorkshopVideoStreamRepository
import com.wallhub.android.core.model.WorkshopVideoStreamSession
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SteamWorkshopVideoStreamRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val credentialProvider: SteamContentCredentialProvider,
        private val settingsRepository: SettingsRepository,
        private val steamHttpClientFactory: SteamHttpClientFactory,
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
                    var lastError: Throwable? = null
                    repeat(VIDEO_STREAM_OPEN_ATTEMPTS) { attempt ->
                        try {
                            return@withContext SteamContentDownloader().openVideoStream(
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
                            ).also { openedStream = it }
                        } catch (error: SteamDepotAccessException) {
                            lastError = error
                            if (error.result != `in`.dragonbra.javasteam.enums.EResult.AccessDenied ||
                                attempt + 1 >= VIDEO_STREAM_OPEN_ATTEMPTS
                            ) {
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
    }

private const val VIDEO_STREAM_OPEN_ATTEMPTS = 3
private const val VIDEO_STREAM_RETRY_DELAY_MS = 750L
private const val STREAM_CACHE_DIRECTORY = "steam-video-stream"
