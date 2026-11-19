package com.wallhub.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Switch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.designsystem.wallHubStyle
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.testhost.WallHubTestActivity
import com.wallhub.settings.SettingsPreferenceIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.uwuaosp.compose.settingslib.PreferenceGroupSpacer
import org.uwuaosp.compose.settingslib.PreferencePosition
import org.uwuaosp.compose.settingslib.PreferenceRow

@RunWith(AndroidJUnit4::class)
class BettboxSettingsLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WallHubTestActivity>()

    @Test
    fun monetBrightSettingsUseClassicRowSpacing() {
        var pageColor = 0
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                accent = AccentPreference.MONET,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                val style = wallHubStyle()
                pageColor = style.surfaces.page.toArgb()
                Box(modifier = Modifier.fillMaxSize().background(style.surfaces.page)) {
                    Column(modifier = Modifier.testTag("settings-column")) {
                        SettingsRowFixtures()
                    }
                }
            }
        }

        val first = composeRule.onNodeWithTag("settings-row-0").getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithTag("settings-row-1").getUnclippedBoundsInRoot()
        val gapPx = with(composeRule.density) { (second.top - first.bottom).toPx() }
        assertEquals(2.dp.value * composeRule.density.density, gapPx, 0.75f)

        val image = composeRule.onNodeWithTag("settings-column").captureToImage().toPixelMap()
        val gapY = with(composeRule.density) { ((first.bottom + second.top) / 2f).toPx() }.toInt()
        assertEquals(pageColor, image[image.width / 2, gapY].toArgb())

        composeRule.onNodeWithTag("settings-row-0").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("settings-row-1").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Monet settings row 1").assertIsDisplayed()
        composeRule.onNodeWithText("Monet settings row 2").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-row-switch").assertIsDisplayed()
    }

    @Test
    fun classicSettingsRetainTwoDpGroupSpacing() {
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                accent = AccentPreference.MONET,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3,
            ) {
                val style = wallHubStyle()
                Box(modifier = Modifier.fillMaxSize().background(style.surfaces.page)) {
                    Column(modifier = Modifier.testTag("settings-column")) {
                        SettingsRowFixtures()
                    }
                }
            }
        }

        val first = composeRule.onNodeWithTag("settings-row-0").getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithTag("settings-row-1").getUnclippedBoundsInRoot()
        val gapPx = with(composeRule.density) { (second.top - first.bottom).toPx() }
        assertEquals(2.dp.value * composeRule.density.density, gapPx, 0.75f)
    }

    @Test
    fun largeFontScaleKeepsSettingsTextVisibleAndRowsSeparated() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = composeRule.density.density,
                    fontScale = 1.3f,
                ),
            ) {
                WallHubTheme(
                    preference = ThemePreference.LIGHT,
                    accent = AccentPreference.MONET,
                    useSystemMonet = false,
                    visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
                ) {
                    val style = wallHubStyle()
                    Box(modifier = Modifier.fillMaxSize().background(style.surfaces.page)) {
                        Column(modifier = Modifier.testTag("settings-column")) {
                            SettingsRowFixtures(largeText = true)
                        }
                    }
                }
            }
        }

        val first = composeRule.onNodeWithTag("settings-row-0").getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithTag("settings-row-1").getUnclippedBoundsInRoot()
        val third = composeRule.onNodeWithTag("settings-row-2").getUnclippedBoundsInRoot()
        assertTrue(first.bottom <= second.top)
        assertTrue(second.bottom <= third.top)
        composeRule.onNodeWithText("Large font settings title with a second line").assertIsDisplayed()
        composeRule.onNodeWithText("Large font summary that wraps without being clipped by the card").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-row-switch").assertIsDisplayed()
    }
}

@androidx.compose.runtime.Composable
private fun SettingsRowFixtures(largeText: Boolean = false) {
    val title =
        if (largeText) {
            "Large font settings title with a second line"
        } else {
            "Monet settings row 1"
        }
    val summary =
        if (largeText) {
            "Large font summary that wraps without being clipped by the card"
        } else {
            "Secondary text with a stable trailing control"
        }
    PreferenceRow(
        title = title,
        summary = summary,
        modifier = Modifier.testTag("settings-row-0"),
        position = PreferencePosition.Top,
        iconContent = { SettingsPreferenceIcon(Icons.Outlined.FolderOpen) },
        trailingContent = {
            Switch(
                checked = true,
                onCheckedChange = {},
                modifier = Modifier.testTag("settings-row-switch"),
            )
        },
    )
    PreferenceGroupSpacer()
    PreferenceRow(
        title = if (largeText) "Second large font row" else "Monet settings row 2",
        summary = "Another summary",
        modifier = Modifier.testTag("settings-row-1"),
        position = PreferencePosition.Middle,
        iconContent = { SettingsPreferenceIcon(Icons.Outlined.FolderOpen) },
    )
    PreferenceGroupSpacer()
    PreferenceRow(
        title = if (largeText) "Third large font row" else "Monet settings row 3",
        summary = "Third summary",
        modifier = Modifier.testTag("settings-row-2"),
        position = PreferencePosition.Bottom,
        iconContent = { SettingsPreferenceIcon(Icons.Outlined.FolderOpen) },
    )
}

