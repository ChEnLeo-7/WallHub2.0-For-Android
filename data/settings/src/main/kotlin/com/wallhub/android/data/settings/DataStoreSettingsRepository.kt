package com.wallhub.android.data.settings

import com.wallhub.android.core.database.AppPreferencesStore
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.model.LocalWallpaperViewMode
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.SteamAccessMode
import com.wallhub.android.core.model.SteamWorkshopDataSource
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class DataStoreSettingsRepository @Inject constructor(
    private val store: AppPreferencesStore,
) : SettingsRepository {
    override val preferences: Flow<AppPreferences> = store.preferences

    override suspend fun setTheme(theme: ThemePreference) {
        store.setTheme(theme)
    }

    override suspend fun setLanguage(language: AppLanguage) {
        store.setLanguage(language)
    }

    override suspend fun setAccent(accent: AccentPreference, customColor: String?) {
        store.setAccent(accent, customColor)
    }

    override suspend fun setSystemMonetEnabled(enabled: Boolean) {
        store.setSystemMonetEnabled(enabled)
    }

    override suspend fun setThemedLauncherIconEnabled(enabled: Boolean) {
        store.setThemedLauncherIconEnabled(enabled)
    }

    override suspend fun setHomePreferences(
        pageSize: Int,
        columns: Int,
        multiSelect: Boolean,
        cardAction: HomeCardAction,
        matureContentEnabled: Boolean,
    ) {
        store.setHomePreferences(pageSize, columns, multiSelect, cardAction, matureContentEnabled)
    }

    override suspend fun setHomePaginationMode(mode: HomePaginationMode) {
        store.setHomePaginationMode(mode)
    }

    override suspend fun setDownloadPreferences(
        maxConcurrentDownloads: Int,
        chunkDownloadConcurrency: Int,
        proxyUrl: String,
        mediaCacheLimitMb: Int,
    ) {
        store.setDownloadPreferences(maxConcurrentDownloads, chunkDownloadConcurrency, proxyUrl, mediaCacheLimitMb)
    }

    override suspend fun setOnlineStreamCacheLimitMb(limitMb: Int) {
        store.setOnlineStreamCacheLimitMb(limitMb)
    }

    override suspend fun setDownloadProxyEnabled(enabled: Boolean) {
        store.setDownloadProxyEnabled(enabled)
    }

    override suspend fun setSteamAccessEnabled(enabled: Boolean) {
        store.setSteamAccessEnabled(enabled)
    }

    override suspend fun setSteamAccessMode(mode: SteamAccessMode) {
        store.setSteamAccessMode(mode)
    }

    override suspend fun setSteamAccessDohEndpoints(
        endpoints: List<String>,
        disabledEndpoints: Set<String>,
    ) {
        store.setSteamAccessDohEndpoints(endpoints, disabledEndpoints)
    }

    override suspend fun setSteamAccessHosts(hosts: String) {
        store.setSteamAccessHosts(hosts)
    }

    override suspend fun setSteamApiKey(apiKey: String) {
        store.setSteamApiKey(apiKey)
    }

    override suspend fun setSteamWorkshopDataSource(source: SteamWorkshopDataSource) {
        store.setSteamWorkshopDataSource(source)
    }

    override suspend fun setOnlineChunkPlaybackEnabled(enabled: Boolean) {
        store.setOnlineChunkPlaybackEnabled(enabled)
    }

    override suspend fun setOutputDirectory(treeUri: String, label: String) {
        store.setOutputDirectory(treeUri, label)
    }

    override suspend fun clearOutputDirectory() {
        store.clearOutputDirectory()
    }

    override suspend fun setLocalManagementDirectory(treeUri: String, label: String) {
        store.setLocalManagementDirectory(treeUri, label)
    }

    override suspend fun clearLocalManagementDirectory() {
        store.clearLocalManagementDirectory()
    }

    override suspend fun setLocalWallpaperViewMode(mode: LocalWallpaperViewMode) {
        store.setLocalWallpaperViewMode(mode)
    }
}
