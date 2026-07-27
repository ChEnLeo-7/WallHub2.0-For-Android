package com.wallhub.android

import android.app.Application
import android.os.Build
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.wallhub.android.core.model.LauncherIconController
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.data.downloads.WallHubDownloadWorkerFactory
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class WallHubApplication : Application(), Configuration.Provider, ImageLoaderFactory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Inject
    lateinit var workerFactory: WallHubDownloadWorkerFactory

    @Inject
    lateinit var steamHttpClientFactory: SteamHttpClientFactory

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var launcherIconController: LauncherIconController

    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
        applicationScope.launch {
            val preferences = settingsRepository.preferences.first()
            runCatching {
                launcherIconController.setThemedIconEnabled(preferences.useThemedLauncherIcon)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(steamHttpClientFactory.newBuilder().build())
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()
}
