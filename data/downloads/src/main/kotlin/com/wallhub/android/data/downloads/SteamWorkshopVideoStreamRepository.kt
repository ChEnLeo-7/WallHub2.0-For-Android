package com.wallhub.android.data.downloads

import android.content.Context
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.WorkshopVideoStreamRepository
import com.wallhub.android.core.model.WorkshopVideoStreamSession
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory

@Singleton
class SteamWorkshopVideoStreamRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialProvider: SteamContentCredentialProvider,
    private val settingsRepository: SettingsRepository,
    private val steamHttpClientFactory: SteamHttpClientFactory,
) : WorkshopVideoStreamRepository {
    override suspend fun open(workshopId: Long): WorkshopVideoStreamSession = withContext(Dispatchers.IO) {
        require(workshopId > 0L) { "创意工坊项目 ID 无效" }
        val preferences = settingsRepository.preferences.first()
        val activeProxyUrl = preferences.downloadProxyUrl.takeIf { preferences.downloadProxyEnabled }.orEmpty()
        val target = SteamWorkshopContentApi(
            steamHttpClientFactory.newBuilder().applyDownloadProxy(activeProxyUrl),
        ).fetchContentTarget(workshopId)
        check(target.contentTypeHint == "video") { "该项目不是可在线播放的视频壁纸" }
        val cacheDirectory = File(
            context.cacheDir,
            "steam-video-stream/${target.publishedFileId}-${target.contentManifestId}",
        )
        SteamContentDownloader().openVideoStream(
            target = target,
            credential = credentialProvider.loadContentCredential(),
            options = SteamContentDownloadOptions(
                chunkConcurrency = preferences.chunkDownloadConcurrency,
                proxyUrl = activeProxyUrl,
            ),
            cacheDirectory = cacheDirectory,
            cacheLimitBytes = preferences.mediaCacheLimitMb.toLong() * 1024L * 1024L,
        )
    }
}
