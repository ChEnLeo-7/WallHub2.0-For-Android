package com.wallhub.android

import android.app.Application
import android.os.Build
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.wallhub.android.data.downloads.WallHubDownloadWorkerFactory
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WallHubApplication : Application(), Configuration.Provider, ImageLoaderFactory {
    @Inject
    lateinit var workerFactory: WallHubDownloadWorkerFactory

    @Inject
    lateinit var steamHttpClientFactory: SteamHttpClientFactory

    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
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
