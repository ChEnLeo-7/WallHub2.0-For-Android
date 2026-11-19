package com.wallhub.settings

import app.cash.paparazzi.Paparazzi
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.InstalledAppInfo
import com.wallhub.android.core.model.SteamAccessState
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.testing.PadLandscapeAdaptiveContent
import com.wallhub.android.testing.PadLandscapeDevice
import org.junit.Rule
import org.junit.Test

class SettingsTabletScreenshotTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = PadLandscapeDevice,
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun expandedMasterDetailDark() {
        paparazzi.snapshot(name = "wallhub-settings-tablet") {
            val preferences =
                AppPreferences(
                    theme = ThemePreference.DARK,
                    visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
                    useSystemMonet = false,
                )
            WallHubTheme(
                preference = preferences.theme,
                useSystemMonet = preferences.useSystemMonet,
                visualStyle = preferences.visualStyle,
            ) {
                PadLandscapeAdaptiveContent {
                    SettingsScreen(
                        preferences = preferences,
                        appLanguage = AppLanguage.SIMPLIFIED_CHINESE,
                        steamAccessState = SteamAccessState(),
                        session = SteamSessionState(),
                        diagnosticExportState = DiagnosticExportUiState(),
                        notificationsGranted = true,
                        appUpdateState =
                            AppUpdateUiState(
                                installed =
                                    InstalledAppInfo(
                                        appName = "WallHub",
                                        packageName = "com.wallhub.android",
                                        versionName = "0.9.0",
                                        versionCode = 35L,
                                    ),
                            ),
                        workshopSourceCacheState = WorkshopSourceCacheUiState(),
                        onBack = {},
                        onAction = {},
                    )
                }
            }
        }
    }
}

