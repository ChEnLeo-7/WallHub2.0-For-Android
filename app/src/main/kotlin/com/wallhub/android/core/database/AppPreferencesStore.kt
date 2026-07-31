package com.wallhub.android.core.database

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.DEFAULT_STEAM_ACCESS_DOH_ENDPOINTS
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.model.LocalWallpaperViewMode
import com.wallhub.android.core.model.SteamAccessMode
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.isSupportedDownloadProxyUrl
import com.wallhub.android.core.model.normalizeSteamAccessDohEndpoints
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val PREFERENCES_FILE_NAME = "wallhub_formal_preferences"
private val Context.dataStore by preferencesDataStore(
    name = PREFERENCES_FILE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class AppPreferencesStore(
    context: Context,
) {
    private val applicationContext = context.applicationContext

    val preferences: Flow<AppPreferences> =
        applicationContext.dataStore.data
            .catch { error ->
                if (error is IOException) {
                    Log.w(TAG, "Preferences could not be read; using defaults", error)
                    emit(preferencesFallbackFor(error))
                } else {
                    throw error
                }
            }.map(::toAppPreferences)

    suspend fun setTheme(theme: ThemePreference) {
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.theme] = theme.name
        }
    }

    suspend fun setLanguage(language: AppLanguage) {
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.language] = language.name
        }
    }

    suspend fun setAccent(
        accent: AccentPreference,
        customColor: String?,
    ) {
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.accent] = accent.name
            customColor?.trim()?.takeIf(String::isNotBlank)?.let { color ->
                preferences[Keys.customAccentColor] = color
            }
        }
    }

    suspend fun setSystemMonetEnabled(enabled: Boolean) {
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.useSystemMonet] = enabled
        }
    }

    suspend fun setThemedLauncherIconEnabled(enabled: Boolean) {
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.useThemedLauncherIcon] = enabled
        }
    }

    suspend fun setHomePreferences(
        pageSize: Int,
        columns: Int,
        multiSelect: Boolean,
        cardAction: HomeCardAction,
        matureContentEnabled: Boolean,
    ) {
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.homePageSize] = pageSize.coerceIn(10, 50)
            preferences[Keys.homeColumns] = columns.coerceIn(1, 4)
            preferences[Keys.homeFilterMultiSelect] = multiSelect
            preferences[Keys.homeCardAction] = cardAction.name
            preferences[Keys.matureContentEnabled] = matureContentEnabled
        }
    }

    suspend fun setHomePaginationMode(mode: HomePaginationMode) {
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.homePaginationMode] = mode.name
        }
    }

    suspend fun setDownloadPreferences(
        maxConcurrentDownloads: Int,
        chunkDownloadConcurrency: Int,
        proxyUrl: String,
        mediaCacheLimitMb: Int,
    ) {
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.maxConcurrentDownloads] = maxConcurrentDownloads.coerceIn(1, 4)
            preferences[Keys.chunkDownloadConcurrency] = chunkDownloadConcurrency.coerceIn(12, 48)
            preferences[Keys.downloadProxyUrl] = proxyUrl.trim()
            if (!isSupportedDownloadProxyUrl(proxyUrl)) preferences[Keys.downloadProxyEnabled] = false
            preferences[Keys.mediaCacheLimitMb] = mediaCacheLimitMb.coerceAtLeast(128)
        }
    }

    suspend fun setOnlineStreamCacheLimitMb(limitMb: Int) {
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.mediaCacheLimitMb] = limitMb.coerceAtLeast(128)
        }
    }

    suspend fun setDownloadProxyEnabled(enabled: Boolean) {
        applicationContext.dataStore.edit { preferences ->
            val canEnable = enabled && isSupportedDownloadProxyUrl(preferences[Keys.downloadProxyUrl].orEmpty())
            preferences[Keys.downloadProxyEnabled] = canEnable
        }
    }

    suspend fun setSteamAccessEnabled(enabled: Boolean) {
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.steamAccessEnabled] = enabled
        }
    }

    suspend fun setSteamAccessMode(mode: SteamAccessMode) {
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.steamAccessMode] = mode.name
        }
    }

    suspend fun setSteamAccessDohEndpoints(
        endpoints: List<String>,
        disabledEndpoints: Set<String>,
    ) {
        applicationContext.dataStore.edit { preferences ->
            val normalized =
                normalizeSteamAccessDohEndpoints(endpoints)
                    .ifEmpty { DEFAULT_STEAM_ACCESS_DOH_ENDPOINTS }
            val disabled =
                normalizeSteamAccessDohEndpoints(disabledEndpoints.toList())
                    .filterTo(linkedSetOf()) { endpoint -> endpoint in normalized }
            preferences[Keys.steamAccessMode] = SteamAccessMode.SMART_DOH.name
            preferences[Keys.steamAccessDohEndpoints] = normalized.joinToString("\n")
            if (disabled.isEmpty()) {
                preferences.remove(Keys.steamAccessDisabledDohEndpoints)
            } else {
                preferences[Keys.steamAccessDisabledDohEndpoints] = disabled.joinToString("\n")
            }
        }
    }

    suspend fun setSteamAccessHosts(hosts: String) {
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.steamAccessHosts] = hosts.trim().take(1_048_576)
        }
    }

    @Deprecated("Steam API credentials are written through SteamApiCredentialRepository")
    suspend fun setSteamApiKey(apiKey: String) {
        applicationContext.dataStore.edit { preferences ->
            val normalized = apiKey.trim()
            if (normalized.isEmpty()) {
                preferences.remove(Keys.steamApiKey)
            } else {
                preferences[Keys.steamApiKey] = normalized
            }
        }
    }

    internal suspend fun readLegacySteamApiKey(): String =
        applicationContext.dataStore.data
            .first()[Keys.steamApiKey]
            .orEmpty()

    internal suspend fun clearLegacySteamApiKey() {
        applicationContext.dataStore.edit { preferences -> preferences.remove(Keys.steamApiKey) }
    }

    suspend fun setSteamWorkshopDataSource(source: SteamWorkshopDataSource) {
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.steamWorkshopDataSource] = source.name
        }
    }

    suspend fun setOnlineChunkPlaybackEnabled(enabled: Boolean) {
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.onlineChunkPlaybackEnabled] = enabled
        }
    }

    suspend fun setOutputDirectory(
        treeUri: String,
        label: String,
    ) {
        require(treeUri.isNotBlank()) { "导出目录 URI 不能为空" }
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.outputTreeUri] = treeUri
            preferences[Keys.outputDirectoryLabel] = label
        }
    }

    suspend fun clearOutputDirectory() {
        applicationContext.dataStore.edit { preferences ->
            preferences.remove(Keys.outputTreeUri)
            preferences.remove(Keys.outputDirectoryLabel)
        }
    }

    suspend fun setLocalManagementDirectory(
        treeUri: String,
        label: String,
    ) {
        require(treeUri.isNotBlank()) { "本地管理目录 URI 不能为空" }
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.localManagementTreeUri] = treeUri
            preferences[Keys.localManagementDirectoryLabel] = label
        }
    }

    suspend fun clearLocalManagementDirectory() {
        applicationContext.dataStore.edit { preferences ->
            preferences.remove(Keys.localManagementTreeUri)
            preferences.remove(Keys.localManagementDirectoryLabel)
        }
    }

    suspend fun setLocalWallpaperViewMode(mode: LocalWallpaperViewMode) {
        applicationContext.dataStore.edit { preferences ->
            preferences[Keys.localWallpaperViewMode] = mode.name
        }
    }

    private fun toAppPreferences(preferences: Preferences): AppPreferences {
        val theme =
            preferences[Keys.theme]
                ?.let { value -> runCatching { ThemePreference.valueOf(value) }.getOrNull() }
                ?: ThemePreference.SYSTEM
        val steamAccessDohEndpoints =
            preferences[Keys.steamAccessDohEndpoints]
                ?.lineSequence()
                ?.toList()
                ?.let(::normalizeSteamAccessDohEndpoints)
                ?.takeIf { endpoints -> endpoints.isNotEmpty() }
                ?: DEFAULT_STEAM_ACCESS_DOH_ENDPOINTS
        val disabledSteamAccessDohEndpoints =
            preferences[Keys.steamAccessDisabledDohEndpoints]
                ?.lineSequence()
                ?.toList()
                ?.let(::normalizeSteamAccessDohEndpoints)
                ?.filterTo(linkedSetOf()) { endpoint -> endpoint in steamAccessDohEndpoints }
                .orEmpty()
        return AppPreferences(
            theme = theme,
            language = preferences.enumValue(Keys.language, AppLanguage.ZH),
            accent = preferences.enumValue(Keys.accent, AccentPreference.MONET),
            customAccentColor = preferences[Keys.customAccentColor].orEmpty().ifBlank { "#5B7AA0" },
            useSystemMonet = preferences[Keys.useSystemMonet] ?: true,
            useThemedLauncherIcon = preferences[Keys.useThemedLauncherIcon] ?: true,
            outputTreeUri = preferences[Keys.outputTreeUri],
            outputDirectoryLabel = preferences[Keys.outputDirectoryLabel],
            localManagementTreeUri = preferences[Keys.localManagementTreeUri],
            localManagementDirectoryLabel = preferences[Keys.localManagementDirectoryLabel],
            localWallpaperViewMode =
                preferences.enumValue(
                    Keys.localWallpaperViewMode,
                    LocalWallpaperViewMode.LIST,
                ),
            homePageSize = (preferences[Keys.homePageSize] ?: 24).coerceIn(10, 50),
            homeColumns = (preferences[Keys.homeColumns] ?: 2).coerceIn(1, 4),
            homeFilterMultiSelect = preferences[Keys.homeFilterMultiSelect] ?: true,
            homeCardAction = preferences.enumValue(Keys.homeCardAction, HomeCardAction.DOWNLOAD),
            homePaginationMode =
                preferences.enumValue(
                    Keys.homePaginationMode,
                    HomePaginationMode.INFINITE_SCROLL,
                ),
            matureContentEnabled = preferences[Keys.matureContentEnabled] ?: false,
            maxConcurrentDownloads = (preferences[Keys.maxConcurrentDownloads] ?: 1).coerceIn(1, 4),
            chunkDownloadConcurrency =
                (preferences[Keys.chunkDownloadConcurrency] ?: 24)
                    .let { saved -> if (saved <= 12) 24 else saved.coerceIn(12, 48) },
            downloadProxyUrl = preferences[Keys.downloadProxyUrl].orEmpty(),
            downloadProxyEnabled = preferences[Keys.downloadProxyEnabled] ?: false,
            downloadProxyRequiresConfirmation =
                preferences[Keys.downloadProxyEnabled] == null &&
                    !preferences[Keys.downloadProxyUrl].isNullOrBlank(),
            steamAccessEnabled = preferences[Keys.steamAccessEnabled] ?: true,
            steamAccessMode = preferences.enumValue(Keys.steamAccessMode, SteamAccessMode.SMART_DOH),
            steamAccessDohEndpoints = steamAccessDohEndpoints,
            steamAccessDisabledDohEndpoints = disabledSteamAccessDohEndpoints,
            steamAccessHosts = preferences[Keys.steamAccessHosts].orEmpty(),
            mediaCacheLimitMb = (preferences[Keys.mediaCacheLimitMb] ?: 512).coerceAtLeast(128),
            steamWorkshopDataSource =
                preferences.enumValue(
                    Keys.steamWorkshopDataSource,
                    SteamWorkshopDataSource.COMMUNITY_HTML,
                ),
            onlineChunkPlaybackEnabled = preferences[Keys.onlineChunkPlaybackEnabled] ?: false,
        )
    }

    private inline fun <reified T : Enum<T>> Preferences.enumValue(
        key: Preferences.Key<String>,
        fallback: T,
    ): T =
        this[key]
            ?.let { value -> enumValues<T>().firstOrNull { it.name == value } }
            ?: fallback

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
        val accent = stringPreferencesKey("accent")
        val customAccentColor = stringPreferencesKey("custom_accent_color")
        val useSystemMonet = booleanPreferencesKey("use_system_monet")
        val useThemedLauncherIcon = booleanPreferencesKey("use_themed_launcher_icon")
        val outputTreeUri = stringPreferencesKey("output_tree_uri")
        val outputDirectoryLabel = stringPreferencesKey("output_directory_label")
        val localManagementTreeUri = stringPreferencesKey("local_management_tree_uri")
        val localManagementDirectoryLabel = stringPreferencesKey("local_management_directory_label")
        val localWallpaperViewMode = stringPreferencesKey("local_wallpaper_view_mode")
        val homePageSize = intPreferencesKey("home_page_size")
        val homeColumns = intPreferencesKey("home_columns")
        val homeFilterMultiSelect = booleanPreferencesKey("home_filter_multi_select")
        val homeCardAction = stringPreferencesKey("home_card_action")
        val homePaginationMode = stringPreferencesKey("home_pagination_mode")
        val matureContentEnabled = booleanPreferencesKey("mature_content_enabled")
        val maxConcurrentDownloads = intPreferencesKey("max_concurrent_downloads")
        val chunkDownloadConcurrency = intPreferencesKey("chunk_download_concurrency")
        val downloadProxyUrl = stringPreferencesKey("download_proxy_url")
        val downloadProxyEnabled = booleanPreferencesKey("download_proxy_enabled")
        val steamAccessEnabled = booleanPreferencesKey("steam_access_enabled")
        val steamAccessMode = stringPreferencesKey("steam_access_mode")
        val steamAccessDohEndpoints = stringPreferencesKey("steam_access_doh_endpoints")
        val steamAccessDisabledDohEndpoints = stringPreferencesKey("steam_access_disabled_doh_endpoints")
        val steamAccessHosts = stringPreferencesKey("steam_access_hosts")
        val mediaCacheLimitMb = intPreferencesKey("media_cache_limit_mb")
        val steamApiKey = stringPreferencesKey("steam_api_key")
        val steamWorkshopDataSource = stringPreferencesKey("steam_workshop_data_source")
        val onlineChunkPlaybackEnabled = booleanPreferencesKey("online_chunk_playback_enabled")
    }

    private companion object {
        const val TAG = "AppPreferencesStore"
    }
}

internal fun preferencesFallbackFor(error: Throwable): Preferences = if (error is IOException) emptyPreferences() else throw error
