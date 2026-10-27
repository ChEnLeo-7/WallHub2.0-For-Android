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
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.LauncherIconController
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.data.downloads.DownloadWorkScheduler
import com.wallhub.android.data.downloads.WallHubDownloadWorkerFactory
import com.wallhub.android.data.downloads.WorkshopSourceCacheManager
import com.wallhub.android.data.steam.SteamWorkshopPublicSessionRouter
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class WallHubApplication :
    Application(),
    Configuration.Provider,
    ImageLoaderFactory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Inject
    internal lateinit var workerFactory: WallHubDownloadWorkerFactory

    @Inject
    lateinit var steamHttpClientFactory: SteamHttpClientFactory

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var launcherIconController: LauncherIconController

    @Inject
    lateinit var steamSessionRepository: SteamSessionRepository

    @Inject
    lateinit var accountWorkshopRepository: AccountWorkshopRepository

    @Inject
    internal lateinit var downloadWorkScheduler: DownloadWorkScheduler

    @Inject
    internal lateinit var workshopSourceCacheManager: WorkshopSourceCacheManager

    @Inject
    internal lateinit var publicSessionRouter: SteamWorkshopPublicSessionRouter

    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
        steamSessionRepository.restorePersistedSession()
        applicationScope.launch {
            steamSessionRepository.session
                .map(::accountCollectionPrefetchIdentity)
                .distinctUntilChanged()
                .filterNotNull()
                .collect {
                    publicSessionRouter.onAccountAuthenticated()
                    try {
                        accountWorkshopRepository.prefetchCollections()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Log.w(TAG, "Unable to prefetch account collections", error)
                    }
                }
        }
        applicationScope.launch {
            try {
                workshopSourceCacheManager.reconcileAndEnforceBudget()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to reconcile the Workshop source cache during startup", error)
            }
        }
        applicationScope.launch {
            try {
                downloadWorkScheduler.wake()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // Commands are durable; a later process start or user action will submit another wake.
                Log.e(TAG, "Unable to wake the durable download host during startup", error)
            }
        }
        ProcessLifecycleOwner
            .get()
            .lifecycle
            .addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        steamSessionRepository.onAppForegrounded()
                        if (steamSessionRepository.session.value.phase == SteamSessionPhase.SIGNED_IN) {
                            applicationScope.launch {
                                runCatching { accountWorkshopRepository.prefetchCollections() }
                                    .onFailure { error ->
                                        if (error is CancellationException) throw error
                                        Log.w(TAG, "Unable to refresh prefetched account collections", error)
                                    }
                            }
                        }
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        steamSessionRepository.onAppBackgrounded()
                        applicationScope.launch { publicSessionRouter.onAppBackgrounded() }
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
            .okHttpClient(steamHttpClientFactory.newAnonymousReadBuilder().build())
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

internal fun accountCollectionPrefetchIdentity(state: com.wallhub.android.core.model.SteamSessionState): String? =
    state.accountName?.takeIf { state.phase == SteamSessionPhase.SIGNED_IN }?.lowercase(Locale.ROOT)
