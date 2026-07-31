package com.wallhub.android.feature.home

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import org.junit.Rule
import org.junit.Test

class HomeScreenCompactScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, showSystemUi = false)

    @Test
    fun populatedLightChinese() {
        paparazzi.snapshot { homeScreenshotContent(columns = 2) }
    }
}

class HomeScreenMediumScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_7, showSystemUi = false)

    @Test
    fun populatedLightChinese() {
        paparazzi.snapshot { homeScreenshotContent(columns = 3) }
    }
}

class HomeScreenExpandedScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_10, showSystemUi = false)

    @Test
    fun populatedLightChinese() {
        paparazzi.snapshot { homeScreenshotContent(columns = 4) }
    }
}

@Composable
private fun homeScreenshotContent(columns: Int) {
    WallHubTheme(
        preference = ThemePreference.LIGHT,
        language = AppLanguage.ZH,
        useSystemMonet = false,
    ) {
        HomeScreen(
            state =
                HomeUiState(
                    items = screenshotWorkshops,
                    columns = columns,
                    totalCount = screenshotWorkshops.size,
                    isInitialLoading = false,
                ),
            onAction = {},
        )
    }
}

private val screenshotWorkshops =
    listOf(
        WorkshopSummary(
            id = 1L,
            title = "雨夜列车",
            author = "WallHub Studio",
            type = WorkshopType.VIDEO,
            tags = listOf("Cyberpunk", "Relaxing"),
            subscriptions = 12_840,
            favorites = 3_210,
        ),
        WorkshopSummary(
            id = 2L,
            title = "山谷晨雾",
            author = "Landscape Lab",
            type = WorkshopType.SCENE,
            tags = listOf("Nature", "Landscape"),
            subscriptions = 8_420,
            favorites = 1_920,
        ),
        WorkshopSummary(
            id = 3L,
            title = "轨道空间站",
            author = "Deep Orbit",
            type = WorkshopType.WEB,
            tags = listOf("Sci-Fi", "Technology"),
            subscriptions = 6_300,
            favorites = 980,
        ),
        WorkshopSummary(
            id = 4L,
            title = "像素海岸",
            author = "Retro Works",
            type = WorkshopType.SCENE,
            tags = listOf("Pixel art", "Retro"),
            subscriptions = 5_120,
            favorites = 860,
        ),
        WorkshopSummary(
            id = 5L,
            title = "深海微光",
            author = "Blue Current",
            type = WorkshopType.VIDEO,
            tags = listOf("Abstract", "Relaxing"),
            subscriptions = 4_780,
            favorites = 720,
        ),
        WorkshopSummary(
            id = 6L,
            title = "纸上王国",
            author = "Tiny Worlds",
            type = WorkshopType.SCENE,
            tags = listOf("Fantasy", "Cartoon"),
            subscriptions = 3_950,
            favorites = 610,
        ),
    )
