package com.wallhub.android.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.feature.downloads.DownloadsScreen
import com.wallhub.android.feature.downloads.DownloadsUiState
import com.wallhub.android.feature.profile.ProfileCountState
import com.wallhub.android.feature.profile.ProfileCounts
import com.wallhub.android.feature.profile.ProfileMd3Content
import com.wallhub.settings.AppearanceSettingsScreen
import com.wallhub.settings.SettingsCategoryIndex
import org.junit.Rule
import org.junit.Test

/**
 * MD3 five-fix review renders: light, DEFAULT accent, production screens.
 * Async cover/avatar frames use GIFs so the last frame contains loaded images.
 */
class Md3FiveFixesPreviewTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "zh-rCN"),
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun home() {
        paparazzi.gif("md3-five-home-gif", 0L, 2_400L, 30) {
            RealisticPhoneHome(accent = AccentPreference.DEFAULT)
        }
    }

    @Test
    fun settingsCategory() {
        paparazzi.snapshot(name = "md3-five-settings-category") {
            Md3PreviewTheme {
                SettingsCategoryIndex(
                    title = stringResource(R.string.settings_title),
                    onBack = {},
                    onOpenCategory = {},
                    onOpenAbout = {},
                )
            }
        }
    }

    @Test
    fun settingsAppearance() {
        paparazzi.snapshot(name = "md3-five-settings-appearance") {
            Md3PreviewTheme {
                AppearanceSettingsScreen(
                    preferences = previewPreferences,
                    availableAccents = AccentPreference.entries.filter { it != AccentPreference.MONET },
                    customAccentColor = "#5B7AA0",
                    onBack = {},
                    onCustomAccentColorChanged = {},
                    onThemePreferenceChange = {},
                    onVisualStyleChange = {},
                    onAccentChange = { _, _ -> },
                    onSystemMonetEnabledChange = {},
                    onThemedLauncherIconEnabledChange = {},
                    onHomePreferencesChange = { _, _, _, _, _ -> },
                    onHomePaginationModeChange = {},
                    onHomeSearchFabChange = {},
                    onHomeTopInfoChange = {},
                    onHomeBottomBarAutoHideChange = {},
                )
            }
        }
    }

    @Test
    fun profile() {
        paparazzi.gif("md3-five-profile-gif", 0L, 2_400L, 30) {
            Md3PreviewTheme {
                WallHubPageScaffold(
                    title = stringResource(R.string.navigation_profile),
                    actions = {
                        IconButton(onClick = {}) {
                            Icon(Icons.Outlined.Settings, contentDescription = null)
                        }
                    },
                ) { padding ->
                    ProfileMd3Content(
                        session =
                            SteamSessionState(
                                phase = SteamSessionPhase.SIGNED_IN,
                                accountName = "wallhub_review",
                                personaName = "WallHub Review",
                                avatarUrl = coverUri(3818033498L),
                                isConnected = true,
                            ),
                        runtimeHours = 128,
                        counts =
                            ProfileCounts(
                                subscriptions = ProfileCountState.Loaded(12_840),
                                favorites = ProfileCountState.Loaded(3_210),
                            ),
                        isSignedIn = true,
                        isRestoring = false,
                        onOpenDownloads = {},
                        onOpenLocal = {},
                        onOpenSubscriptions = {},
                        onOpenFavorites = {},
                        onOpenLogin = {},
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }
            }
        }
    }

    @Test
    fun downloads() {
        paparazzi.gif("md3-five-downloads-gif", 0L, 2_400L, 30) {
            Md3PreviewTheme {
                DownloadsScreen(
                    state =
                        DownloadsUiState(
                            allTasks = previewDownloadTasks,
                            tasks = previewDownloadTasks,
                        ),
                    onAction = {},
                )
            }
        }
    }
}

private val previewPreferences =
    AppPreferences(
        theme = ThemePreference.LIGHT,
        accent = AccentPreference.DEFAULT,
        visualStyle = VisualStyle.MATERIAL3,
        useSystemMonet = false,
    )

private val previewDownloadTasks =
    listOf(
        DownloadTask(
            id = "review-downloading",
            workshopId = 3811917786L,
            title = "Carrier Strike Group",
            type = WorkshopType.WEB,
            status = DownloadStatus.DOWNLOADING,
            previewUrl = coverUri(3811917786L),
            downloadedBytes = 24_000_000L,
            totalBytes = 51_400_000L,
            bytesPerSecond = 2_400_000L,
        ),
        DownloadTask(
            id = "review-downloading-2",
            workshopId = 3818033498L,
            title = "ICEY艾希：续",
            type = WorkshopType.VIDEO,
            status = DownloadStatus.DOWNLOADING,
            previewUrl = coverUri(3818033498L),
            downloadedBytes = 18_000_000L,
            totalBytes = 64_100_000L,
            bytesPerSecond = 1_100_000L,
        ),
        DownloadTask(
            id = "review-completed",
            workshopId = 3823357259L,
            title = "Talia Yang / Cyberpunk Edgerunners 2",
            type = WorkshopType.SCENE,
            status = DownloadStatus.COMPLETED,
            previewUrl = coverUri(3823357259L),
            downloadedBytes = 4_800_000L,
            totalBytes = 4_800_000L,
            outputLabel = "Talia.mpkg",
        ),
    )

@Composable
private fun Md3PreviewTheme(content: @Composable () -> Unit) {
    WallHubTheme(
        preference = ThemePreference.LIGHT,
        accent = AccentPreference.DEFAULT,
        useSystemMonet = false,
        visualStyle = VisualStyle.MATERIAL3,
        content = content,
    )
}
