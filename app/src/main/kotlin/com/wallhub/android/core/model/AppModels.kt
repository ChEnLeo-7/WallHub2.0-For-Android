package com.wallhub.android.core.model

import java.net.URI

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class AccentPreference {
    DEFAULT,
    MONET,
    BLUE,
    GREEN,
    ROSE,
    VIOLET,
    CUSTOM,
}

enum class HomeCardAction {
    DOWNLOAD,
    PLAY_VIDEO,
    OPEN_STEAM,
}

enum class HomePaginationMode {
    INFINITE_SCROLL,
    PAGED,
}

enum class SteamAccessMode {
    SMART_DOH,
    HOSTS,
}

enum class SteamWorkshopDataSource {
    COMMUNITY_HTML,
    WEB_API,
    CM_WEBSOCKET,
}

enum class SteamAccessPhase {
    DISABLED,
    READY,
    RESOLVING,
    DEGRADED,
    FAILED,
}

data class InstalledAppInfo(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val lastUpdateTimeMillis: Long = 0L,
)

data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseName: String,
    val notes: String,
    val publishedAt: String,
    val htmlUrl: String,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long,
    val sha256: String,
    val isNewer: Boolean,
)

data class SteamAccessState(
    val phase: SteamAccessPhase = SteamAccessPhase.DISABLED,
    val networkType: String = "unknown",
    val activeHost: String? = null,
    val selectedAddress: String? = null,
    val candidateCount: Int = 0,
    val fallbackCount: Long = 0L,
    val message: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class SteamSessionPhase {
    SIGNED_OUT,
    RESTORABLE,
    SIGNING_IN,
    WAITING_FOR_DEVICE_CONFIRMATION,
    WAITING_FOR_CODE,
    SIGNED_IN,
    EXPIRED,
    FAILED,
}

data class SteamSessionState(
    val phase: SteamSessionPhase = SteamSessionPhase.SIGNED_OUT,
    val accountName: String? = null,
    val personaName: String? = null,
    val avatarUrl: String? = null,
    val message: String? = null,
    val requiresCode: Boolean = false,
    val awaitingDeviceConfirmation: Boolean = false,
    val hasStoredSession: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class SteamContentCredential(
    val accountName: String,
    val refreshToken: String,
)

const val DEFAULT_HOME_PAGE_SIZE = 30
val HOME_PAGE_SIZE_OPTIONS = listOf(10, 15, 30, 50)

fun Int.normalizedHomePageSize(): Int =
    HOME_PAGE_SIZE_OPTIONS.minBy { option -> kotlin.math.abs(toLong() - option.toLong()) }

data class AppPreferences(
    val setupWizardCompleted: Boolean = false,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val accent: AccentPreference = AccentPreference.MONET,
    val customAccentColor: String = "#5B7AA0",
    val useSystemMonet: Boolean = true,
    val useThemedLauncherIcon: Boolean = true,
    val outputTreeUri: String? = null,
    val outputDirectoryLabel: String? = null,
    val localManagementTreeUri: String? = null,
    val localManagementDirectoryLabel: String? = null,
    val localWallpaperViewMode: LocalWallpaperViewMode = LocalWallpaperViewMode.LIST,
    val homePageSize: Int = DEFAULT_HOME_PAGE_SIZE,
    val homeColumns: Int = 2,
    val homeFilterMultiSelect: Boolean = true,
    val homeSearchFab: Boolean = true,
    val homeCardAction: HomeCardAction = HomeCardAction.DOWNLOAD,
    val homePaginationMode: HomePaginationMode = HomePaginationMode.INFINITE_SCROLL,
    val matureContentEnabled: Boolean = false,
    val maxConcurrentDownloads: Int = 1,
    val chunkDownloadConcurrency: Int = 24,
    val downloadProxyUrl: String = "",
    val downloadProxyEnabled: Boolean = false,
    val downloadProxyRequiresConfirmation: Boolean = false,
    val steamAccessEnabled: Boolean = true,
    val steamAccessMode: SteamAccessMode = SteamAccessMode.SMART_DOH,
    val steamAccessDohEndpoints: List<String> = DEFAULT_STEAM_ACCESS_DOH_ENDPOINTS,
    val steamAccessDisabledDohEndpoints: Set<String> = emptySet(),
    val steamAccessHosts: String = "",
    val mediaCacheLimitMb: Int = 512,
    val steamApiKey: String = "",
    val steamWorkshopDataSource: SteamWorkshopDataSource = SteamWorkshopDataSource.COMMUNITY_HTML,
    /**
     * Streams a video directly from Steam Depot chunks. This remains opt-in because a
     * completed local download is more predictable on mobile networks and after app restarts.
     */
    val onlineChunkPlaybackEnabled: Boolean = false,
) {
    override fun toString(): String =
        "AppPreferences(" +
            "setupWizardCompleted=$setupWizardCompleted, theme=$theme, " +
            "accent=$accent, customAccentColor=$customAccentColor, " +
            "useSystemMonet=$useSystemMonet, useThemedLauncherIcon=$useThemedLauncherIcon, " +
            "outputTreeUri=$outputTreeUri, outputDirectoryLabel=$outputDirectoryLabel, " +
            "localManagementTreeUri=$localManagementTreeUri, " +
            "localManagementDirectoryLabel=$localManagementDirectoryLabel, " +
            "localWallpaperViewMode=$localWallpaperViewMode, homePageSize=$homePageSize, " +
            "homeColumns=$homeColumns, homeFilterMultiSelect=$homeFilterMultiSelect, homeSearchFab=$homeSearchFab, " +
            "homeCardAction=$homeCardAction, homePaginationMode=$homePaginationMode, " +
            "matureContentEnabled=$matureContentEnabled, maxConcurrentDownloads=$maxConcurrentDownloads, " +
            "chunkDownloadConcurrency=$chunkDownloadConcurrency, downloadProxyUrl=$downloadProxyUrl, " +
            "downloadProxyEnabled=$downloadProxyEnabled, " +
            "downloadProxyRequiresConfirmation=$downloadProxyRequiresConfirmation, " +
            "steamAccessEnabled=$steamAccessEnabled, steamAccessMode=$steamAccessMode, " +
            "steamAccessDohEndpoints=$steamAccessDohEndpoints, " +
            "steamAccessDisabledDohEndpoints=$steamAccessDisabledDohEndpoints, " +
            "steamAccessHosts=$steamAccessHosts, mediaCacheLimitMb=$mediaCacheLimitMb, " +
            "steamApiKey=<redacted>, steamWorkshopDataSource=$steamWorkshopDataSource, " +
            "onlineChunkPlaybackEnabled=$onlineChunkPlaybackEnabled)"
}

const val STEAM_ACCESS_DOH_ENDPOINT_LIMIT = 8

val DEFAULT_STEAM_ACCESS_DOH_ENDPOINTS =
    listOf(
        "https://1.12.12.12/resolve",
        "https://doh.pub/resolve",
        "https://dns.alidns.com/resolve",
    )

fun normalizeSteamAccessDohEndpoint(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty() || value.length > 2_048) return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    val validPort = uri.port == -1 || uri.port in 1..65_535
    return value.takeIf {
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null &&
            validPort
    }
}

fun normalizeSteamAccessDohEndpoints(endpoints: List<String>): List<String> =
    endpoints
        .asSequence()
        .mapNotNull(::normalizeSteamAccessDohEndpoint)
        .distinct()
        .take(STEAM_ACCESS_DOH_ENDPOINT_LIMIT)
        .toList()

fun AppPreferences.enabledSteamAccessDohEndpoints(): List<String> =
    steamAccessDohEndpoints.filterNot(
        steamAccessDisabledDohEndpoints::contains,
    )

fun isSupportedDownloadProxyUrl(raw: String): Boolean {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
    val supportedScheme = uri.scheme?.lowercase() in setOf("http", "https", "socks", "socks5")
    val supportedPath = uri.path.isNullOrEmpty() || uri.path == "/"
    return supportedScheme &&
        !uri.host.isNullOrBlank() &&
        (uri.port == -1 || uri.port in 1..65_535) &&
        uri.userInfo == null &&
        uri.query == null &&
        uri.fragment == null &&
        supportedPath
}

data class AppError(
    val title: String,
    val message: String,
    val canRetry: Boolean = false,
)

enum class DiagnosticLevel {
    INFO,
    WARNING,
    ERROR,
}

data class DiagnosticEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,
    val level: DiagnosticLevel,
    val message: String,
    val attributes: Map<String, String> = emptyMap(),
)
