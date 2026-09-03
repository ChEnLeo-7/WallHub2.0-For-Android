package com.wallhub.android

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
import com.wallhub.android.core.model.SteamSessionRepository
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
import com.wallhub.android.data.downloads.SteamWorkshopVideoStreamRepository
import com.wallhub.android.data.settings.DataStoreSettingsRepository
import com.wallhub.android.data.steam.KSteamSessionRepository
import com.wallhub.android.data.steamaccess.SteamAccessManager
import com.wallhub.android.data.update.GitHubAppUpdateRepository
import com.wallhub.android.data.workshop.CommunityWorkshopRepository
import com.wallhub.android.feature.discover.model.OfficialDiscoverMetadataRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
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

    @Binds
    @Singleton
    abstract fun bindSteamSessionRepository(repository: KSteamSessionRepository): SteamSessionRepository

    /**
     * Exposes the kSteam session as the engine-neutral protocol seam. The singleton instance
     * is shared with every individual contract binding above.
     */
    @Binds
    @Singleton
    abstract fun bindSteamProtocolClient(repository: KSteamSessionRepository): SteamProtocolClient

    /** Hybrid depot routing engine (Rust core) as the default depot seam. */
    @Binds
    @Singleton
    abstract fun bindDepotDownloader(downloader: HybridDepotDownloader): DepotDownloader

    @Binds
    @Singleton
    abstract fun bindSteamPlaytimeRepository(repository: KSteamSessionRepository): SteamPlaytimeRepository

    @Binds
    @Singleton
    abstract fun bindSteamContentCredentialProvider(repository: KSteamSessionRepository): SteamContentCredentialProvider

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
