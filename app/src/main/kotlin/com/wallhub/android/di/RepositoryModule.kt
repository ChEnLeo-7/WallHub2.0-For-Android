package com.wallhub.android

import android.util.Log
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.AppUpdateRepository
import com.wallhub.android.core.model.DiagnosticExportRepository
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.LauncherIconController
import com.wallhub.android.core.model.LocalWallpaperRepository
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamAccessRepository
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.SteamPlaytimeRepository
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.WorkshopDownloads
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopVideoStreamRepository
import com.wallhub.android.data.diagnostics.FileDiagnosticRepository
import com.wallhub.android.data.discover.HttpOfficialDiscoverMetadataRepository
import com.wallhub.android.data.downloads.LocalWallpaperFileRepository
import com.wallhub.android.data.downloads.RoomDownloadTaskRepository
import com.wallhub.android.data.downloads.SteamWorkshopVideoStreamRepository
import com.wallhub.android.data.settings.DataStoreSettingsRepository
import com.wallhub.android.data.steam.SteamContentSdkCredentialProvider
import com.wallhub.android.data.steam.SteamContentSdkWorkshopRepository
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

    companion object {
        /**
         * The content credential the download and range arms present.
         *
         * The SDK owns the session, so its retained token is the one those arms must use, and it comes from
         * the store the SDK writes. The line is kept because a device reading names the store that served a
         * run without exposing any part of the token.
         */
        @Provides
        @Singleton
        internal fun provideSteamContentCredentialProvider(
            sdk: SteamContentSdkCredentialProvider,
        ): SteamContentCredentialProvider {
            Log.i(
                "WallHubSteamSession",
                "content credential selected=steam-content-sdk-store",
            )
            return sdk
        }

    }

    @Binds
    @Singleton
    abstract fun bindSteamPlaytimeRepository(repository: SteamContentSdkWorkshopRepository): SteamPlaytimeRepository

    @Binds
    @Singleton
    abstract fun bindSteamAccessRepository(repository: SteamAccessManager): SteamAccessRepository

    @Binds
    @Singleton
    abstract fun bindAccountWorkshopRepository(repository: SteamContentSdkWorkshopRepository): AccountWorkshopRepository

    @Binds
    @Singleton
    abstract fun bindSteamUnifiedWorkshopRepository(
        repository: SteamContentSdkWorkshopRepository,
    ): SteamUnifiedWorkshopRepository

    @Binds
    @Singleton
    abstract fun bindWorkshopRepository(repository: CommunityWorkshopRepository): WorkshopRepository

    @Binds
    @Singleton
    abstract fun bindWorkshopDownloads(repository: RoomDownloadTaskRepository): WorkshopDownloads

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
