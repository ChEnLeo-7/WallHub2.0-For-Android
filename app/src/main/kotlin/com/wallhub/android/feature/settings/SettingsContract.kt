@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.wallhub.android.R
import com.wallhub.android.core.model.AccentPreference
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

    data object RestartSetupWizard : SettingsAction

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
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector,
) {
    BASIC(
        labelRes = R.string.settings_category_basic,
        descriptionRes = R.string.settings_category_basic_description,
        icon = Icons.Outlined.Tune,
    ),
    DOWNLOAD(
        labelRes = R.string.settings_category_downloads,
        descriptionRes = R.string.settings_category_downloads_description,
        icon = Icons.Outlined.Download,
    ),
    STEAM(
        labelRes = R.string.settings_category_steam,
        descriptionRes = R.string.settings_category_steam_description,
        icon = Icons.Outlined.PersonOutline,
    ),
    APPEARANCE(
        labelRes = R.string.settings_category_appearance,
        descriptionRes = R.string.settings_category_appearance_description,
        icon = Icons.Outlined.Palette,
    ),
    EXPERIMENTAL(
        labelRes = R.string.settings_category_experimental,
        descriptionRes = R.string.settings_category_experimental_description,
        icon = Icons.Outlined.Notifications,
    ),
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
