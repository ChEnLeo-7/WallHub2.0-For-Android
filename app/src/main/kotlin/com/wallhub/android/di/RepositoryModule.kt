package com.wallhub.android

import android.os.SystemClock
import android.util.Log
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.AppUpdateRepository
import com.wallhub.android.core.model.DepotDownloader
import com.wallhub.android.core.model.DiagnosticExportRepository
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.DownloadTaskRepository
import com.wallhub.android.core.model.LauncherIconController
import com.wallhub.android.core.model.LocalWallpaperRepository
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamAccessRepository
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.SteamPlaytimeRepository
import com.wallhub.android.core.model.SteamProtocolClient
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopVideoStreamRepository
import com.wallhub.android.data.diagnostics.FileDiagnosticRepository
import com.wallhub.android.data.discover.HttpOfficialDiscoverMetadataRepository
import com.wallhub.android.data.downloads.HybridDepotDownloader
import com.wallhub.android.data.downloads.LocalWallpaperFileRepository
import com.wallhub.android.data.downloads.RoomDownloadTaskRepository
import com.wallhub.android.data.downloads.RustDepotDownloader
import com.wallhub.android.data.downloads.SteamContentSdkDepotDownloader
import com.wallhub.android.data.downloads.SteamContentSdkEngineSessionOpener
import com.wallhub.android.data.downloads.SteamContentSdkSessionOpener
import com.wallhub.android.data.downloads.SteamWorkshopVideoStreamRepository
import com.wallhub.android.data.settings.DataStoreSettingsRepository
import com.wallhub.android.data.steam.KSteamSessionRepository
import com.wallhub.android.data.steam.SteamContentSdkCredentialProvider
import com.wallhub.android.data.steamaccess.SteamAccessManager
import com.wallhub.android.data.update.GitHubAppUpdateRepository
import com.wallhub.android.data.workshop.CommunityWorkshopRepository
import com.wallhub.android.feature.discover.model.OfficialDiscoverMetadataRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindOfficialDiscoverMetadataRepository(
        repository: HttpOfficialDiscoverMetadataRepository,
    ): OfficialDiscoverMetadataRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(repository: DataStoreSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindLauncherIconController(controller: AndroidLauncherIconController): LauncherIconController

    /**
     * Exposes the kSteam session as the engine-neutral protocol seam. The singleton instance is shared
     * with the other kSteam contract bindings; only the session lifecycle is routed by
     * `SteamSessionBackendModule`.
     */
    @Binds
    @Singleton
    abstract fun bindSteamProtocolClient(repository: KSteamSessionRepository): SteamProtocolClient

    /**
     * The SDK download path's session opener. It reads the same platform hook and route factory the
     * engine already exposes; binding it here keeps the transfer itself free of SDK/native types.
     */
    @Binds
    @Singleton
    abstract fun bindSteamContentSdkSessionOpener(
        opener: SteamContentSdkEngineSessionOpener,
    ): SteamContentSdkSessionOpener

    companion object {
        /**
         * Depot engine routing after the JavaSteam removal. The hybrid wrapper keeps its failure
         * accounting and re-probe behaviour around whichever engine the opt-in flag selects, so
         * switching backends is one build property rather than a code change.
         */
        /**
         * Content-credential routing after the session migration.
         *
         * With `wallhub.useSdkSession` off (the default) this is kSteam's live account token, exactly as
         * before. With it on, the SDK owns the session and its retained token is the one the download and
         * range arms must present, so the credential comes from the store the SDK writes.
         */
        @Provides
        @Singleton
        internal fun provideSteamContentCredentialProvider(
            kSteam: KSteamSessionRepository,
            sdk: SteamContentSdkCredentialProvider,
        ): SteamContentCredentialProvider {
            val useSdkSession = BuildConfig.USE_SDK_SESSION
            Log.i(
                "WallHubSteamSession",
                "content credential selected=" +
                    if (useSdkSession) "steam-content-sdk-store" else "ksteam",
            )
            return if (useSdkSession) sdk else kSteam
        }

        @Provides
        @Singleton
        internal fun provideDepotDownloader(
            sdkEngine: SteamContentSdkDepotDownloader,
            rustEngine: RustDepotDownloader,
            diagnostics: DiagnosticRepository,
        ): DepotDownloader {
            val useSdkEngine = BuildConfig.USE_SDK_DEPOT_CORE
            Log.i(
                "WallHubSdkDepot",
                "depot engine selected=${if (useSdkEngine) "steam-content-sdk" else "wallhub-rust"}",
            )
            return HybridDepotDownloader(
                if (useSdkEngine) sdkEngine else rustEngine,
                diagnostics,
                SystemClock::elapsedRealtime,
            )
        }
    }

    @Binds
    @Singleton
    abstract fun bindSteamPlaytimeRepository(repository: KSteamSessionRepository): SteamPlaytimeRepository

    @Binds
    @Singleton
    abstract fun bindSteamAccessRepository(repository: SteamAccessManager): SteamAccessRepository

    @Binds
    @Singleton
    abstract fun bindAccountWorkshopRepository(repository: KSteamSessionRepository): AccountWorkshopRepository

    @Binds
    @Singleton
    abstract fun bindSteamUnifiedWorkshopRepository(repository: KSteamSessionRepository): SteamUnifiedWorkshopRepository

    @Binds
    @Singleton
    abstract fun bindWorkshopRepository(repository: CommunityWorkshopRepository): WorkshopRepository

    @Binds
    @Singleton
    abstract fun bindDownloadTaskRepository(repository: RoomDownloadTaskRepository): DownloadTaskRepository

    @Binds
    @Singleton
    abstract fun bindLocalWallpaperRepository(repository: LocalWallpaperFileRepository): LocalWallpaperRepository

    @Binds
    @Singleton
    abstract fun bindWorkshopVideoStreamRepository(repository: SteamWorkshopVideoStreamRepository): WorkshopVideoStreamRepository

    @Binds
    @Singleton
    abstract fun bindDiagnosticRepository(repository: FileDiagnosticRepository): DiagnosticRepository

    @Binds
    @Singleton
    abstract fun bindDiagnosticExportRepository(repository: FileDiagnosticRepository): DiagnosticExportRepository

    @Binds
    @Singleton
    abstract fun bindAppUpdateRepository(repository: GitHubAppUpdateRepository): AppUpdateRepository
}
