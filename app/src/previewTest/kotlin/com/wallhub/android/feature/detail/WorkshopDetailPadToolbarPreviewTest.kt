package com.wallhub.android.feature.detail

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.testing.PadLandscapeAdaptiveContent
import com.wallhub.android.testing.PadTopLevelPreview
import org.junit.Rule
import org.junit.Test

class WorkshopDetailPadToolbarPreviewTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_TABLET.copy(
            screenWidth = 2800,
            screenHeight = 1968,
            xdpi = 283,
            ydpi = 283,
            density = Density.create(283),
            orientation = ScreenOrientation.LANDSCAPE,
            locale = "zh-rCN",
        ),
        showSystemUi = false,
        useDeviceResolution = true,
    )

    @Test
    fun expressivePadDetailToolbarUsesSpacedActions() {
        paparazzi.snapshot(name = "wallhub-detail-pad-toolbar-md3-2800x1968-283dpi") {
            com.wallhub.android.core.designsystem.WallHubTheme(
                preference = com.wallhub.android.core.model.ThemePreference.LIGHT,
                accent = AccentPreference.BLUE,
                useSystemMonet = false,
                visualStyle = com.wallhub.android.core.model.VisualStyle.MATERIAL3,
            ) {
                PadTopLevelPreview(selectedLabel = "首页") {
                    PadLandscapeAdaptiveContent { TabletDetailScreenPreview() }
                }
            }
        }
    }
}
