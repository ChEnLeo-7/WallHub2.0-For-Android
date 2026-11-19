@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.wallhub.android.TopLevelDestination
import com.wallhub.android.WallHubFloatingNavigationBar
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.topLevelDestinations
import org.junit.Rule
import org.junit.Test

/** Review render of the floating navigation capsule on a busy backdrop, light and dark. */
class FloatingNavBarVariantsPreviewTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = 1080,
                    screenHeight = 1500,
                    xdpi = 440,
                    ydpi = 440,
                    density = Density.create(440),
                    fontScale = 1.0f,
                    locale = "zh-rCN",
                ),
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun floatingNavBarVariants() {
        paparazzi.snapshot {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF3A4A5A), Color(0xFF8A5A3A), Color(0xFF2A3A4A)),
                            ),
                        ),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                BarCase("light · home selected", ThemePreference.LIGHT, TopLevelDestination.HOME)
                BarCase("light · downloads selected", ThemePreference.LIGHT, TopLevelDestination.DOWNLOADS)
                BarCase("light · profile selected", ThemePreference.LIGHT, TopLevelDestination.PROFILE)
                BarCase("dark · home selected", ThemePreference.DARK, TopLevelDestination.HOME)
                BarCase("dark · discover selected", ThemePreference.DARK, TopLevelDestination.DISCOVER)
            }
        }
    }
}

@Composable
private fun BarCase(
    caption: String,
    preference: ThemePreference,
    selected: TopLevelDestination,
) {
    WallHubTheme(
        preference = preference,
        accent = AccentPreference.BLUE,
        useSystemMonet = false,
        visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.padding(start = 20.dp),
            )
            WallHubFloatingNavigationBar(
                destinations = topLevelDestinations(includeDownloads = true),
                currentTopLevelDestination = selected,
                onNavigateTo = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
