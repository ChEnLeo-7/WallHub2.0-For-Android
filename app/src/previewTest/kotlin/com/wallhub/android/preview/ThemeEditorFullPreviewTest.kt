@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.preview

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.wallhub.android.core.designsystem.WallHubAppSurface
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.settings.ThemeEditorScreen
import org.junit.Rule
import org.junit.Test

/** A long phone page render so the full theme-editor surface is visible in one image. */
class ThemeEditorFullPreviewTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = 1440,
                    screenHeight = 5_200,
                    xdpi = 560,
                    ydpi = 560,
                    density = Density.create(560),
                    fontScale = 1.0f,
                    locale = "zh-rCN",
                ),
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun themeEditorFullLight() {
        paparazzi.snapshot(name = "wallhub-settings-theme-editor-full-light-1440x5200") {
            EditorPreview(ThemePreference.LIGHT)
        }
    }

    @Test
    fun themeEditorFullDark() {
        paparazzi.snapshot(name = "wallhub-settings-theme-editor-full-dark-1440x5200") {
            EditorPreview(ThemePreference.DARK)
        }
    }

    @Test
    fun themeEditorFullReset() {
        paparazzi.snapshot(name = "wallhub-settings-theme-editor-full-reset-1440x5200") {
            EditorPreview(ThemePreference.LIGHT, resetDefaults = true)
        }
    }

    @Test
    fun themeEditorFullContrastOpen() {
        paparazzi.snapshot(name = "wallhub-settings-theme-editor-full-contrast-open-1440x5200") {
            EditorPreview(ThemePreference.LIGHT, contrastOpen = true)
        }
    }

    @Test
    fun pureBlackThemeEditorMd3Off() {
        paparazzi.snapshot(name = "pure-black-theme-editor-md3-off-phone-dark") {
            EditorPreview(ThemePreference.DARK, visualStyle = VisualStyle.MATERIAL3, pureBlack = false)
        }
    }

    @Test
    fun pureBlackThemeEditorMd3On() {
        paparazzi.snapshot(name = "pure-black-theme-editor-md3-on-phone-dark") {
            EditorPreview(ThemePreference.DARK, visualStyle = VisualStyle.MATERIAL3, pureBlack = true)
        }
    }

    @Test
    fun themeEditorMd3ExpressiveLight() {
        paparazzi.snapshot(name = "theme-editor-md3e-light-1440x5200") {
            EditorPreview(ThemePreference.LIGHT, visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE, pureBlack = false)
        }
    }

    @Test
    fun pureBlackThemeEditorMd3ExpressiveOff() {
        paparazzi.snapshot(name = "pure-black-theme-editor-md3e-off-phone-dark") {
            EditorPreview(ThemePreference.DARK, visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE, pureBlack = false)
        }
    }

    @Test
    fun pureBlackThemeEditorMd3ExpressiveOn() {
        paparazzi.snapshot(name = "pure-black-theme-editor-md3e-on-phone-dark") {
            EditorPreview(ThemePreference.DARK, visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE, pureBlack = true)
        }
    }
}

@Composable
private fun EditorPreview(
    theme: ThemePreference,
    resetDefaults: Boolean = false,
    contrastOpen: Boolean = false,
    visualStyle: VisualStyle = VisualStyle.MATERIAL3,
    pureBlack: Boolean = false,
) {
    WallHubTheme(
        preference = theme,
        accent = AccentPreference.DEFAULT,
        useSystemMonet = false,
        visualStyle = visualStyle,
        pureBlackInDark = pureBlack,
    ) {
        WallHubAppSurface {
            ThemeEditorScreen(
                preferences =
                    if (resetDefaults) {
                        AppPreferences(
                            theme = ThemePreference.SYSTEM,
                            accent = AccentPreference.MONET,
                            useSystemMonet = true,
                            useThemedLauncherIcon = true,
                        )
                    } else {
                        AppPreferences(
                            theme = theme,
                            useSystemMonet = false,
                            visualStyle = visualStyle,
                            pureBlackInDark = pureBlack,
                        )
                    },
                customAccentColor = "#2B6CB0",
                onBack = {},
                onThemePreferenceChange = {},
                onVisualStyleChange = {},
                onCustomAccentColorChanged = {},
                onAccentChange = { _, _ -> },
                onSystemMonetEnabledChange = {},
                onThemedLauncherIconEnabledChange = {},
                previewContrastMenuOpen = contrastOpen,
            )
        }
    }
}
