package com.wallhub.android.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.wallhub.android.core.designsystem.WallHubAppSurface
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.InstalledAppInfo
import com.wallhub.android.core.model.SteamAccessState
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.settings.AppUpdateUiState
import com.wallhub.settings.AppearanceSettingsScreen
import com.wallhub.settings.DiagnosticExportUiState
import com.wallhub.settings.SettingsScreen
import com.wallhub.settings.ThemeEditorScreen
import com.wallhub.settings.WorkshopSourceCacheUiState
import org.junit.Rule
import org.junit.Test

/**
 * Device-accurate MD3 review render of the settings entry page.
 *
 * The adb device is a 1440 x 3168 panel running at 560 dpi (411 x 905 dp), so this uses
 * exactly that many pixels and dpi instead of a generic Pixel preset. The status bar and
 * gesture handle are composed in the same Paparazzi frame so the result reads like a real
 * screenshot while every pixel still comes from the JVM renderer.
 */
class Md3SettingsDevicePreviewTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = WallHubPhoneDevice,
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun settingsIndexLight() {
        paparazzi.snapshot(name = "wallhub-settings-md3-index-light-1440x3168") {
            RealisticSettingsIndex(theme = ThemePreference.LIGHT)
        }
    }

    @Test
    fun settingsIndexDark() {
        paparazzi.snapshot(name = "wallhub-settings-md3-index-dark-1440x3168") {
            RealisticSettingsIndex(theme = ThemePreference.DARK)
        }
    }

    @Test
    fun settingsIndexBlueSimulation() {
        paparazzi.snapshot(name = "wallhub-settings-md3-index-blue-simulation-1440x3168") {
            RealisticSettingsIndex(theme = ThemePreference.LIGHT, accent = AccentPreference.BLUE)
        }
    }

    @Test
    fun settingsAppearanceLight() {
        paparazzi.snapshot(name = "wallhub-settings-md3-appearance-light-1440x3168") {
            RealisticSettingsAppearance()
        }
    }

    @Test
    fun themeEditorLight() {
        paparazzi.snapshot(name = "wallhub-settings-md3-theme-editor-light-1440x3168") {
            RealisticThemeEditor(theme = ThemePreference.LIGHT)
        }
    }

    @Test
    fun themeEditorDark() {
        paparazzi.snapshot(name = "wallhub-settings-md3-theme-editor-dark-1440x3168") {
            RealisticThemeEditor(theme = ThemePreference.DARK)
        }
    }

    @Test
    fun themeEditorReset() {
        paparazzi.snapshot(name = "wallhub-settings-md3-theme-editor-reset-1440x3168") {
            RealisticThemeEditor(theme = ThemePreference.LIGHT, resetDefaults = true)
        }
    }

    @Test
    fun themeEditorContrastOpen() {
        paparazzi.snapshot(name = "wallhub-settings-md3-theme-editor-contrast-open-1440x3168") {
            RealisticThemeEditor(theme = ThemePreference.LIGHT, contrastOpen = true)
        }
    }
}

private val WallHubPhoneDevice =
    DeviceConfig.PIXEL_5.copy(
        screenWidth = 1440,
        screenHeight = 3168,
        xdpi = 560,
        ydpi = 560,
        density = Density.create(560),
        fontScale = 1.0f,
        locale = "zh-rCN",
    )

private val settingsPreferences =
    AppPreferences(
        setupWizardCompleted = true,
        theme = ThemePreference.LIGHT,
        accent = AccentPreference.DEFAULT,
        visualStyle = VisualStyle.MATERIAL3,
        useSystemMonet = false,
    )

private val settingsInstalledApp =
    InstalledAppInfo(
        appName = "WallHub",
        packageName = "com.wallhub.android",
        versionName = "0.9.0",
        versionCode = 35L,
    )

@Composable
private fun RealisticSettingsIndex(
    theme: ThemePreference,
    accent: AccentPreference = AccentPreference.DEFAULT,
) {
    val density = LocalDensity.current
    WallHubTheme(
        preference = theme,
        accent = accent,
        useSystemMonet = false,
        visualStyle = VisualStyle.MATERIAL3,
    ) {
        WallHubAppSurface {
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(top = with(density) { STATUS_BAR_HEIGHT_PX.toDp() }),
                ) {
                    SettingsScreen(
                        preferences = settingsPreferences.copy(theme = theme, accent = accent),
                        appLanguage = AppLanguage.SIMPLIFIED_CHINESE,
                        steamAccessState = SteamAccessState(),
                        session =
                            SteamSessionState(
                                phase = SteamSessionPhase.SIGNED_IN,
                                accountName = "wallhub_review",
                                personaName = "WallHub Review",
                                isConnected = true,
                            ),
                        diagnosticExportState = DiagnosticExportUiState(),
                        notificationsGranted = true,
                        appUpdateState = AppUpdateUiState(installed = settingsInstalledApp),
                        workshopSourceCacheState = WorkshopSourceCacheUiState(),
                        onBack = {},
                        onAction = {},
                    )
                }
                DeviceStatusBar(modifier = Modifier.align(Alignment.TopCenter), dark = theme == ThemePreference.DARK)
                GestureHandle(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    dark = theme == ThemePreference.DARK,
                )
            }
        }
    }
}

@Composable
private fun RealisticThemeEditor(
    theme: ThemePreference,
    resetDefaults: Boolean = false,
    contrastOpen: Boolean = false,
) {
    val density = LocalDensity.current
    WallHubTheme(
        preference = theme,
        accent = AccentPreference.DEFAULT,
        useSystemMonet = false,
        visualStyle = VisualStyle.MATERIAL3,
    ) {
        WallHubAppSurface {
            Box(Modifier.fillMaxSize()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(top = with(density) { STATUS_BAR_HEIGHT_PX.toDp() }),
                ) {
                    ThemeEditorScreen(
                        preferences =
                            if (resetDefaults) {
                                AppPreferences(
                                    theme = ThemePreference.SYSTEM,
                                    accent = AccentPreference.MONET,
                                    visualStyle = VisualStyle.MATERIAL3,
                                    useSystemMonet = true,
                                    useThemedLauncherIcon = true,
                                )
                            } else {
                                settingsPreferences.copy(theme = theme)
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
                DeviceStatusBar(modifier = Modifier.align(Alignment.TopCenter), dark = theme == ThemePreference.DARK)
                GestureHandle(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    dark = theme == ThemePreference.DARK,
                )
            }
        }
    }
}

@Composable
private fun RealisticSettingsAppearance() {
    val density = LocalDensity.current
    WallHubTheme(
        preference = ThemePreference.LIGHT,
        accent = AccentPreference.DEFAULT,
        useSystemMonet = false,
        visualStyle = VisualStyle.MATERIAL3,
    ) {
        WallHubAppSurface {
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(top = with(density) { STATUS_BAR_HEIGHT_PX.toDp() }),
                ) {
                    AppearanceSettingsScreen(
                        preferences = settingsPreferences,
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
                DeviceStatusBar(modifier = Modifier.align(Alignment.TopCenter), dark = false)
                GestureHandle(modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

private val statusBarInk = Color(0xFF0B0B0B)
private val statusBarInkDark = Color(0xFFF2F2F2)
internal const val STATUS_BAR_HEIGHT_PX = 160

@Composable
internal fun DeviceStatusBar(
    modifier: Modifier = Modifier,
    dark: Boolean,
) {
    val density = LocalDensity.current
    fun px(value: Int): Dp = with(density) { value.toDp() }
    val ink = if (dark) statusBarInkDark else statusBarInk
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(px(STATUS_BAR_HEIGHT_PX)),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(start = px(72), end = px(76)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "17:24",
                color = ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 0.2.sp,
            )
            Spacer(Modifier.weight(1f))
            NetworkSpeed(ink = ink)
            Spacer(Modifier.width(px(12)))
            WifiGlyph(ink = ink)
            Spacer(Modifier.width(px(8)))
            SignalGlyph(ink = ink)
            Spacer(Modifier.width(px(20)))
            BatteryGlyph(ink = ink)
        }
    }
}

@Composable
private fun NetworkSpeed(ink: Color) {
    val density = LocalDensity.current
    Column(
        modifier = Modifier.width(with(density) { 92.toDp() }),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = "413.7 KB",
            color = ink,
            fontSize = 5.2.sp,
            lineHeight = 6.2.sp,
            maxLines = 1,
            softWrap = false,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
        )
        Text(
            text = "36.4 KB",
            color = ink,
            fontSize = 5.2.sp,
            lineHeight = 6.2.sp,
            maxLines = 1,
            softWrap = false,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
        )
    }
}

@Composable
private fun WifiGlyph(ink: Color) {
    val density = LocalDensity.current
    Box(
        modifier =
            Modifier
                .width(with(density) { 58.toDp() })
                .height(with(density) { 48.toDp() }),
    ) {
        Canvas(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .width(with(density) { 58.toDp() })
                    .height(with(density) { 38.toDp() }),
        ) {
            val cx = size.width / 2f
            val baseY = size.height - size.height * 0.09f
            val strokeWidth = size.height * 0.13f
            listOf(0.59f, 0.40f, 0.22f).forEach { fraction ->
                val radius = size.height * fraction
                drawArc(
                    color = ink,
                    startAngle = 225f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(cx - radius, baseY - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
            drawCircle(
                color = ink,
                radius = size.height * 0.10f,
                center = Offset(cx, baseY),
            )
        }
        Text(
            text = "6",
            color = ink,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = with(density) { 1.toDp() }),
        )
        Canvas(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .width(with(density) { 12.toDp() })
                    .height(with(density) { 14.toDp() }),
        ) {
            val arrowWidth = size.width * 0.42f
            val arrowHeight = size.height * 0.28f
            val cx = size.width / 2f
            val path =
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx - arrowWidth / 2f, arrowHeight)
                    lineTo(cx + arrowWidth / 2f, arrowHeight)
                    lineTo(cx, 0f)
                    close()
                }
            drawPath(path, color = ink)
            val downPath =
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx - arrowWidth / 2f, size.height - arrowHeight)
                    lineTo(cx + arrowWidth / 2f, size.height - arrowHeight)
                    lineTo(cx, size.height)
                    close()
                }
            drawPath(downPath, color = ink)
        }
    }
}

@Composable
private fun SignalGlyph(ink: Color) {
    val density = LocalDensity.current
    Row(
        modifier =
            Modifier
                .width(with(density) { 89.toDp() })
                .height(with(density) { 44.toDp() }),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "5G",
            color = ink,
            style =
                TextStyle(
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
        )
        Spacer(Modifier.width(with(density) { 6.toDp() }))
        Canvas(
            modifier =
                Modifier
                    .width(with(density) { 44.toDp() })
                    .height(with(density) { 40.toDp() }),
        ) {
            val barWidth = size.width * 0.135f
            val gap = size.width * 0.115f
            val heights = listOf(0.34f, 0.52f, 0.72f, 1.0f)
            heights.forEachIndexed { index, fraction ->
                val barHeight = size.height * fraction
                drawRoundRect(
                    color = ink,
                    topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth * 0.3f),
                )
            }
        }
    }
}

@Composable
private fun BatteryGlyph(ink: Color) {
    val density = LocalDensity.current
    Box(
        modifier =
            Modifier
                .width(with(density) { 78.toDp() })
                .height(with(density) { 40.toDp() }),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val nubWidth = size.width * 0.075f
            val bodyWidth = size.width - nubWidth - size.width * 0.015f
            drawRoundRect(
                color = ink,
                topLeft = Offset.Zero,
                size = Size(bodyWidth, size.height),
                cornerRadius = CornerRadius(size.height * 0.24f),
            )
            drawRoundRect(
                color = ink,
                topLeft = Offset(bodyWidth + size.width * 0.015f, size.height * 0.31f),
                size = Size(nubWidth, size.height * 0.38f),
                cornerRadius = CornerRadius(nubWidth * 0.45f),
            )
        }
        Text(
            text = "100",
            color = if (ink == statusBarInk) Color.White else Color(0xFF141414),
            style =
                TextStyle(
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = with(density) { 7.toDp() }),
        )
    }
}

@Composable
internal fun GestureHandle(
    modifier: Modifier = Modifier,
    dark: Boolean = false,
) {
    val density = LocalDensity.current
    Box(
        modifier =
            modifier
                .padding(bottom = with(density) { 100.toDp() })
                .width(with(density) { 424.toDp() })
                .height(with(density) { 12.toDp() })
                .background(
                    color = if (dark) Color(0xFFC9C9C9) else Color(0xFF6F6F6F),
                    shape = RoundedCornerShape(percent = 50),
                ),
    )
}
