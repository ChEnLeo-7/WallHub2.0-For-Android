package com.wallhub.android.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.Paparazzi
import com.wallhub.android.core.designsystem.WallHubAppSurface
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.testing.PadLandscapeAdaptiveContent
import com.wallhub.android.testing.PadLandscapeDevice
import com.wallhub.settings.ThemeEditorScreen
import org.junit.Rule
import org.junit.Test

class ThemeEditorPadLandscapePreviewTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = PadLandscapeDevice,
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun themeEditorPadLandscapeLight() {
        paparazzi.snapshot(name = "wallhub-settings-theme-editor-pad-landscape-light-2800x1968") {
            PadLandscapeEditorPreview()
        }
    }
}

@Composable
private fun PadLandscapeEditorPreview() {
    PadLandscapeAdaptiveContent {
        WallHubTheme(
            preference = ThemePreference.LIGHT,
            accent = AccentPreference.DEFAULT,
            useSystemMonet = false,
            visualStyle = VisualStyle.MATERIAL3,
        ) {
            WallHubAppSurface {
                Box(Modifier.fillMaxSize()) {
                    ThemeEditorScreen(
                        preferences = AppPreferences(theme = ThemePreference.LIGHT, useSystemMonet = false),
                        customAccentColor = "#2B6CB0",
                        onBack = {},
                        onThemePreferenceChange = {},
                        onVisualStyleChange = {},
                        onCustomAccentColorChanged = {},
                        onAccentChange = { _, _ -> },
                        onSystemMonetEnabledChange = {},
                        onThemedLauncherIconEnabledChange = {},
                    )
                }
            }
        }
    }
}
