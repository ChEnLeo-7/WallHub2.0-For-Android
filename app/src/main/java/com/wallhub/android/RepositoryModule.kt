package com.wallhub.android

import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.AppUpdateRepository
import com.wallhub.android.core.model.DownloadTaskRepository
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamContentCredentialProvider
import com.wallhub.android.core.model.SteamAccessRepository
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.WorkshopVideoStreamRepository
import com.wallhub.android.core.model.LocalWallpaperRepository
import com.wallhub.android.core.model.LauncherIconController
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.data.diagnostics.FileDiagnosticRepository
import com.wallhub.android.data.downloads.LocalWallpaperFileRepository
import com.wallhub.android.data.downloads.RoomDownloadTaskRepository
import com.wallhub.android.data.downloads.SteamWorkshopVideoStreamRepository
import com.wallhub.android.data.settings.DataStoreSettingsRepository
import com.wallhub.android.data.steam.SecureSteamSessionRepository
import com.wallhub.android.data.steamaccess.SteamAccessManager
import com.wallhub.android.data.update.GitHubAppUpdateRepository
import com.wallhub.android.data.workshop.CommunityWorkshopRepository
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
    abstract fun bindSettingsRepository(
        repository: DataStoreSettingsRepository,
    ): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindLauncherIconController(
        controller: AndroidLauncherIconController,
    ): LauncherIconController

    @Binds
    @Singleton
    abstract fun bindSteamSessionRepository(
        repository: SecureSteamSessionRepository,
    ): SteamSessionRepository

    @Binds
    @Singleton
    abstract fun bindSteamContentCredentialProvider(
        repository: SecureSteamSessionRepository,
    ): SteamContentCredentialProvider

    @Binds
    @Singleton
    abstract fun bindSteamAccessRepository(
        repository: SteamAccessManager,
    ): SteamAccessRepository

    @Binds
    @Singleton
    abstract fun bindAccountWorkshopRepository(
        repository: SecureSteamSessionRepository,
    ): AccountWorkshopRepository

    @Binds
    @Singleton
    abstract fun bindSteamUnifiedWorkshopRepository(
        repository: SecureSteamSessionRepository,
    ): SteamUnifiedWorkshopRepository

    @Binds
    @Singleton
    abstract fun bindWorkshopRepository(
        repository: CommunityWorkshopRepository,
    ): WorkshopRepository

    @Binds
    @Singleton
    abstract fun bindDownloadTaskRepository(
        repository: RoomDownloadTaskRepository,
    ): DownloadTaskRepository

    @Binds
    @Singleton
    abstract fun bindLocalWallpaperRepository(
        repository: LocalWallpaperFileRepository,
    ): LocalWallpaperRepository

    @Binds
    @Singleton
    abstract fun bindWorkshopVideoStreamRepository(
        repository: SteamWorkshopVideoStreamRepository,
    ): WorkshopVideoStreamRepository

    @Binds
    @Singleton
    abstract fun bindDiagnosticRepository(
        repository: FileDiagnosticRepository,
    ): DiagnosticRepository

    @Binds
    @Singleton
    abstract fun bindAppUpdateRepository(
        repository: GitHubAppUpdateRepository,
    ): AppUpdateRepository
}
