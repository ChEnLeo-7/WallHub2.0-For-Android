package com.wallhub.android

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.wallhub.android.core.model.LauncherIconController
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.data.downloads.WallHubDownloadWorkerFactory
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class WallHubApplication :
    Application(),
    Configuration.Provider,
    ImageLoaderFactory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Inject
    lateinit var workerFactory: WallHubDownloadWorkerFactory

    @Inject
    lateinit var steamHttpClientFactory: SteamHttpClientFactory

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var launcherIconController: LauncherIconController

    @Inject
    lateinit var steamSessionRepository: SteamSessionRepository

    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
        ProcessLifecycleOwner
            .get()
            .lifecycle
            .addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        steamSessionRepository.onAppForegrounded()
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        steamSessionRepository.onAppBackgrounded()
                    }
                },
            )
        applicationScope.launch {
            try {
                val preferences = settingsRepository.preferences.first()
                launcherIconController.setThemedIconEnabled(preferences.useThemedLauncherIcon)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to apply startup preferences; defaults remain active", error)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    override fun newImageLoader(): ImageLoader =
        ImageLoader
            .Builder(this)
            .okHttpClient(steamHttpClientFactory.newBuilder().build())
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }.build()

    private companion object {
        const val TAG = "WallHubApplication"
    }
}
