package com.wallhub.android.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.feature.home.HomeCardHostContent
import com.wallhub.android.feature.home.HomeCardLayoutKey
import com.wallhub.android.feature.home.HomeCardMetrics
import com.wallhub.android.feature.home.HomeCardQuickSheetContent
import com.wallhub.android.feature.home.HomeScreen
import com.wallhub.android.feature.home.HomeUiState
import com.wallhub.android.feature.home.HomeViewMode
import com.wallhub.android.feature.setup.WallHubSetupPage
import com.wallhub.android.feature.setup.WallHubSetupPadPreview
import com.wallhub.android.testing.PadLandscapeAdaptiveContent
import com.wallhub.android.testing.PadLandscapeDevice
import com.wallhub.settings.AppearanceSettingsScreen
import org.junit.Rule
import org.junit.Test

private val previewWorkshops =
    listOf(
        WorkshopSummary(
            id = 1L,
            title = "Carrier Strike Group",
            author = "wladbelsky",
            previewUrl = "android.resource://com.wallhub.android/drawable/wallhub_logo",
            type = WorkshopType.SCENE,
            subscriptions = 6_400,
            favorites = 421,
            fileSizeBytes = 1_400_000,
        ),
        WorkshopSummary(
            id = 2L,
            title = "【明日方舟/Arknights】檐花穗影-黍shu",
            author = "agro",
            previewUrl = "android.resource://com.wallhub.android/drawable/wallhub_logo",
            type = WorkshopType.VIDEO,
            subscriptions = 52_000,
            favorites = 2_600,
            fileSizeBytes = 34_000_000,
        ),
        WorkshopSummary(
            id = 3L,
            title = "轨道空间站",
            author = "Deep Orbit",
            previewUrl = "android.resource://com.wallhub.android/drawable/wallhub_logo",
            type = WorkshopType.WEB,
            subscriptions = 6_300,
            favorites = 980,
            fileSizeBytes = 53_900_000,
        ),
        WorkshopSummary(
            id = 4L,
            title = "像素海岸",
            author = "Retro Works",
            previewUrl = "android.resource://com.wallhub.android/drawable/wallhub_logo",
            type = WorkshopType.SCENE,
            subscriptions = 5_120,
            favorites = 860,
            fileSizeBytes = 9_900_000,
        ),
    )

private val previewAppPreferences =
    AppPreferences(
        theme = ThemePreference.DARK,
        accent = AccentPreference.BLUE,
        visualStyle = VisualStyle.MATERIAL3,
        useSystemMonet = false,
    )

private fun previewHomeState(style: VisualStyle): HomeUiState =
    HomeUiState(
        items = previewWorkshops,
        columns = 0,
        isInitialLoading = false,
        totalCount = previewWorkshops.size,
        cardAction = HomeCardAction.DOWNLOAD,
        showHomeTopInfo = true,
    )

private fun previewHostContent(): HomeCardHostContent =
    HomeCardHostContent(
        items = previewWorkshops,
        authorDisplayNames = emptyMap(),
        layoutKey = HomeCardLayoutKey.resolve(HomeViewMode.GRID, 2),
        metrics = HomeCardMetrics(0.dp, 0.dp, 0.dp),
        coverSizePx = 720,
        action = HomeCardAction.PLAY_VIDEO,
        onOpenDetail = {},
        onPrimaryAction = {},
        onDownload = {},
        onSearchAuthor = {},
        onCopyText = { _, _ -> },
        onOpenSteam = {},
        onAuthorNameRequested = {},
    )

@Composable
private fun PreviewTheme(
    style: VisualStyle,
    content: @Composable () -> Unit,
) {
    WallHubTheme(
        preference = ThemePreference.DARK,
        accent = AccentPreference.BLUE,
        useSystemMonet = false,
        visualStyle = style,
        content = content,
    )
}

class UiFixPhonePreviewTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "zh-rCN"),
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun homeMd3() {
        paparazzi.snapshot(name = "ui-fix-home-md3") {
            PreviewTheme(VisualStyle.MATERIAL3) {
                HomeScreen(state = previewHomeState(VisualStyle.MATERIAL3), onAction = {}, onOpenSettings = {})
            }
        }
    }

    @Test
    fun homeM3e() {
        paparazzi.snapshot(name = "ui-fix-home-m3e") {
            PreviewTheme(VisualStyle.MATERIAL3_EXPRESSIVE) {
                HomeScreen(state = previewHomeState(VisualStyle.MATERIAL3_EXPRESSIVE), onAction = {}, onOpenSettings = {})
            }
        }
    }

    @Test
    fun quickSheet() {
        paparazzi.snapshot(name = "ui-fix-quick-sheet") {
            PreviewTheme(VisualStyle.MATERIAL3) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(16.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        HomeCardQuickSheetContent(
                            item = previewWorkshops.first(),
                            content = previewHostContent(),
                            onDismiss = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun settingsAppearanceMd3() {
        paparazzi.snapshot(name = "ui-fix-settings-appearance-md3") {
            PreviewTheme(VisualStyle.MATERIAL3) {
                AppearanceSettingsScreen(
                    preferences = previewAppPreferences,
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
    fun settingsAppearanceM3e() {
        paparazzi.snapshot(name = "ui-fix-settings-appearance-m3e") {
            PreviewTheme(VisualStyle.MATERIAL3_EXPRESSIVE) {
                AppearanceSettingsScreen(
                    preferences = previewAppPreferences.copy(visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE),
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
}

class UiFixPadPreviewTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = PadLandscapeDevice,
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun wizardNetworkMd3() = wizardSnapshot(WallHubSetupPage.Network, VisualStyle.MATERIAL3, "ui-fix-wizard-network-md3")

    @Test
    fun wizardNetworkM3e() = wizardSnapshot(WallHubSetupPage.Network, VisualStyle.MATERIAL3_EXPRESSIVE, "ui-fix-wizard-network-m3e")

    @Test
    fun wizardFinishMd3() = wizardSnapshot(WallHubSetupPage.Finish, VisualStyle.MATERIAL3, "ui-fix-wizard-finish-md3")

    @Test
    fun wizardFinishM3e() = wizardSnapshot(WallHubSetupPage.Finish, VisualStyle.MATERIAL3_EXPRESSIVE, "ui-fix-wizard-finish-m3e")

    private fun wizardSnapshot(
        page: WallHubSetupPage,
        style: VisualStyle,
        name: String,
    ) {
        paparazzi.snapshot(name = name) {
            PreviewTheme(style) {
                PadLandscapeAdaptiveContent {
                    WallHubSetupPadPreview(page)
                }
            }
        }
    }
}
