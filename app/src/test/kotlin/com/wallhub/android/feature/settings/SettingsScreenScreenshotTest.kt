package com.wallhub.android.feature.settings

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.InstalledAppInfo
import com.wallhub.android.core.model.SteamAccessState
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.ThemePreference
import org.junit.Rule
import org.junit.Test

class SettingsScreenScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, showSystemUi = false)

    @Test
    fun overviewLightEnglish() {
        paparazzi.snapshot { settingsScreenshotContent() }
    }
}

class SettingsScreenMediumScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_7, showSystemUi = false)

    @Test
    fun overviewLightEnglish() {
        paparazzi.snapshot { settingsScreenshotContent() }
    }
}

class SettingsScreenExpandedScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_10, showSystemUi = false)

    @Test
    fun overviewLightEnglish() {
        paparazzi.snapshot { settingsScreenshotContent() }
    }
}

@Composable
private fun settingsScreenshotContent() {
    val preferences =
        AppPreferences(
            theme = ThemePreference.LIGHT,
            language = AppLanguage.EN,
            useSystemMonet = false,
        )
    WallHubTheme(
        preference = preferences.theme,
        language = preferences.language,
        useSystemMonet = preferences.useSystemMonet,
    ) {
        SettingsScreen(
            preferences = preferences,
            steamAccessState = SteamAccessState(),
            session = SteamSessionState(),
            diagnosticExportState = DiagnosticExportUiState(),
            appUpdateState =
                AppUpdateUiState(
                    installed =
                        InstalledAppInfo(
                            appName = "WallHub",
                            packageName = "com.wallhub.android",
                            versionName = "0.8.25",
                            versionCode = 35L,
                        ),
                ),
            onAction = {},
        )
    }
}
