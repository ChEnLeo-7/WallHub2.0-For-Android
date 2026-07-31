@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.AppReleaseInfo
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.model.InstalledAppInfo
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

data class DiagnosticExportUiState(
    val isExporting: Boolean = false,
    val message: String? = null,
    val isFailure: Boolean = false,
)

enum class AppUpdatePhase {
    IDLE,
    CHECKING,
    AVAILABLE,
    UP_TO_DATE,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
}

data class AppUpdateUiState(
    val installed: InstalledAppInfo,
    val phase: AppUpdatePhase = AppUpdatePhase.IDLE,
    val release: AppReleaseInfo? = null,
    val downloadedApkPath: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String? = null,
) {
    val canDownloadRelease: Boolean
        get() =
            release?.isNewer == true &&
                downloadedApkPath == null &&
                (phase == AppUpdatePhase.AVAILABLE || phase == AppUpdatePhase.FAILED)

    val progress: Float
        get() =
            if (totalBytes > 0L) {
                (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
            } else {
                0f
            }
}

sealed interface SettingsAction {
    data class ThemeChanged(
        val theme: ThemePreference,
    ) : SettingsAction

    data class LanguageChanged(
        val language: AppLanguage,
    ) : SettingsAction

    data class AccentChanged(
        val accent: AccentPreference,
        val customColor: String? = null,
    ) : SettingsAction

    data class SystemMonetEnabledChanged(
        val enabled: Boolean,
    ) : SettingsAction

    data class ThemedLauncherIconEnabledChanged(
        val enabled: Boolean,
    ) : SettingsAction

    data class HomePreferencesChanged(
        val pageSize: Int,
        val columns: Int,
        val multiSelect: Boolean,
        val cardAction: HomeCardAction,
        val matureContentEnabled: Boolean,
    ) : SettingsAction

    data class HomePaginationModeChanged(
        val mode: HomePaginationMode,
    ) : SettingsAction

    data class DownloadPreferencesChanged(
        val maxConcurrentDownloads: Int,
        val chunkDownloadConcurrency: Int,
        val proxyUrl: String,
        val mediaCacheLimitMb: Int,
    ) : SettingsAction

    data class DownloadProxyEnabledChanged(
        val enabled: Boolean,
    ) : SettingsAction

    data class OnlineStreamCacheLimitChanged(
        val limitMb: Int,
    ) : SettingsAction

    data class SteamApiKeyChanged(
        val apiKey: String,
    ) : SettingsAction

    data class SteamWorkshopDataSourceChanged(
        val source: SteamWorkshopDataSource,
    ) : SettingsAction

    data class OnlineChunkPlaybackEnabledChanged(
        val enabled: Boolean,
    ) : SettingsAction

    data class SteamAccessEnabledChanged(
        val enabled: Boolean,
    ) : SettingsAction

    data class SteamAccessDohEndpointsChanged(
        val endpoints: List<String>,
        val disabledEndpoints: Set<String>,
    ) : SettingsAction

    data object RefreshSteamAccess : SettingsAction

    data object LogoutSteam : SettingsAction

    data object SelectOutputDirectory : SettingsAction

    data class OutputDirectorySelected(
        val treeUri: String,
        val label: String,
    ) : SettingsAction

    data object ClearOutputDirectory : SettingsAction

    data object CheckForAppUpdate : SettingsAction

    data object DownloadLatestRelease : SettingsAction

    data object CancelAppUpdateDownload : SettingsAction

    data object ExportDiagnostics : SettingsAction

    data class DiagnosticDocumentSelected(
        val destinationUri: String,
    ) : SettingsAction

    data object RequestNotifications : SettingsAction

    data object OpenSteamLogin : SettingsAction

    data class InstallDownloadedRelease(
        val path: String,
    ) : SettingsAction

    data class OpenExternalUri(
        val uri: String,
        val failureMessage: String,
    ) : SettingsAction

    data class SystemActionFailed(
        val message: String,
    ) : SettingsAction

    data class InstallerFailed(
        val message: String,
    ) : SettingsAction
}

sealed interface SettingsEffect {
    data object SelectOutputDirectory : SettingsEffect

    data object ExportDiagnostics : SettingsEffect

    data object RequestNotifications : SettingsEffect

    data object OpenSteamLogin : SettingsEffect

    data class InstallDownloadedRelease(
        val path: String,
    ) : SettingsEffect

    data class OpenExternalUri(
        val uri: String,
        val failureMessage: String,
    ) : SettingsEffect

    data class ShowMessage(
        val message: String,
    ) : SettingsEffect
}

internal fun SettingsAction.toEffect(): SettingsEffect? =
    when (this) {
        SettingsAction.SelectOutputDirectory -> SettingsEffect.SelectOutputDirectory
        SettingsAction.ExportDiagnostics -> SettingsEffect.ExportDiagnostics
        SettingsAction.RequestNotifications -> SettingsEffect.RequestNotifications
        SettingsAction.OpenSteamLogin -> SettingsEffect.OpenSteamLogin
        is SettingsAction.InstallDownloadedRelease -> SettingsEffect.InstallDownloadedRelease(path)
        is SettingsAction.OpenExternalUri -> SettingsEffect.OpenExternalUri(uri, failureMessage)
        else -> null
    }

internal enum class SettingsCategory(
    val labelZh: String,
    val labelEn: String,
    val descriptionZh: String,
    val descriptionEn: String,
    val icon: ImageVector,
) {
    BASIC(
        labelZh = "基本设置",
        labelEn = "Basic settings",
        descriptionZh = "内容访问、应用信息、更新与诊断",
        descriptionEn = "Content access, app information, updates, and diagnostics",
        icon = Icons.Outlined.Tune,
    ),
    DOWNLOAD(
        labelZh = "下载",
        labelEn = "Downloads",
        descriptionZh = "导出目录、下载并发与代理",
        descriptionEn = "Export directory, concurrency, and proxy",
        icon = Icons.Outlined.Download,
    ),
    STEAM(
        labelZh = "Steam",
        labelEn = "Steam",
        descriptionZh = "账户、服务访问与创意工坊数据源",
        descriptionEn = "Account, service access, and Workshop data source",
        icon = Icons.Outlined.PersonOutline,
    ),
    APPEARANCE(
        labelZh = "外观",
        labelEn = "Appearance",
        descriptionZh = "语言、主题、强调色与发现页偏好",
        descriptionEn = "Language, theme, accent color, and Discover preferences",
        icon = Icons.Outlined.Palette,
    ),
    EXPERIMENTAL(
        labelZh = "实验功能",
        labelEn = "Experimental",
        descriptionZh = "在线分块播放与系统权限",
        descriptionEn = "Online chunk streaming and system permissions",
        icon = Icons.Outlined.Notifications,
    ),
    ;

    fun label(language: AppLanguage): String = if (language == AppLanguage.EN) labelEn else labelZh

    fun description(language: AppLanguage): String = if (language == AppLanguage.EN) descriptionEn else descriptionZh
}

internal enum class SteamStreamCachePreset(
    val limitMb: Int?,
) {
    MB_512(512),
    GB_1(1024),
    GB_2(2048),
    GB_5(5120),
    GB_8(8192),
    CUSTOM(null),
}
