package com.wallhub.android.feature.setup

import app.cash.paparazzi.Paparazzi
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.testing.PadLandscapeAdaptiveContent
import com.wallhub.android.testing.PadLandscapeDevice
import org.junit.Rule
import org.junit.Test

class SetupWizardTabletScreenshotTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = PadLandscapeDevice.copy(locale = "zh-rCN"),
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test fun welcome() = snapshot(WallHubSetupPage.Welcome, "wallhub-setup-pad-01-welcome")

    @Test fun network() = snapshot(WallHubSetupPage.Network, "wallhub-setup-pad-02-network")

    @Test fun warmup() = snapshot(WallHubSetupPage.Warmup, "wallhub-setup-pad-03-warmup")

    @Test fun connectivity() = snapshot(WallHubSetupPage.Connectivity, "wallhub-setup-pad-04-connectivity")

    @Test fun storage() = snapshot(WallHubSetupPage.Storage, "wallhub-setup-pad-05-storage")

    @Test fun steam() = snapshot(WallHubSetupPage.Steam, "wallhub-setup-pad-06-steam")

    @Test fun finish() = snapshot(WallHubSetupPage.Finish, "wallhub-setup-pad-07-finish")

    private fun snapshot(page: WallHubSetupPage, name: String) {
        paparazzi.snapshot(name = name) {
            WallHubTheme(
                preference = ThemePreference.DARK,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                PadLandscapeAdaptiveContent {
                    WallHubSetupPadPreview(page)
                }
            }
        }
    }
}

