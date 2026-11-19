package com.wallhub.android.feature.home

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import app.cash.paparazzi.Paparazzi
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.testing.PadLandscapeAdaptiveContent
import com.wallhub.android.testing.PadLandscapeDevice
import com.wallhub.android.testing.PadPortraitAdaptiveContent
import com.wallhub.android.testing.PadPortraitDevice
import com.wallhub.android.testing.PadPortraitTopLevelPreview
import com.wallhub.android.testing.PadTopLevelPreview
import org.junit.Rule
import org.junit.Test

class HomeTabletLandscapeScreenshotTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = PadLandscapeDevice,
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun expandedPadSidebar() {
        paparazzi.snapshot(name = "wallhub-home-pad-landscape-sidebar-expanded") {
            homeTabletTheme {
                PadLandscapeAdaptiveContent {
                    PadTopLevelPreview(selectedLabel = "首页", railExpanded = true) { HomeTabletContent() }
                }
            }
        }
    }

    @Test
    fun topInformationCanShowAboveGrid() {
        paparazzi.snapshot(name = "wallhub-home-tablet-landscape-top-info-above-grid") {
            homeTabletTheme {
                PadLandscapeAdaptiveContent {
                    PadTopLevelPreview(selectedLabel = "首页") {
                        HomeTabletContent(showHomeTopInfo = true)
                    }
                }
            }
        }
    }

    @Test
    fun scrolledExpandedUsesFullHeight() {
        paparazzi.snapshot(name = "wallhub-home-tablet-landscape-scrolled-full-height") {
            homeTabletTheme {
                PadLandscapeAdaptiveContent {
                    PadTopLevelPreview(selectedLabel = "首页") {
                        HomeTabletContent(initialFirstVisibleItemIndex = 1)
                    }
                }
            }
        }
    }

    @Test
    fun collapsedPadSidebar() {
        paparazzi.snapshot(name = "wallhub-home-pad-landscape-sidebar-collapsed") {
            homeTabletTheme {
                PadLandscapeAdaptiveContent {
                    PadTopLevelPreview(selectedLabel = "首页") { HomeTabletContent() }
                }
            }
        }
    }
}

class HomeTabletPortraitScreenshotTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = PadPortraitDevice,
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun collapsedPadSidebar() {
        paparazzi.snapshot(name = "wallhub-home-pad-portrait-sidebar-collapsed") {
            homeTabletTheme {
                PadPortraitAdaptiveContent {
                    PadPortraitTopLevelPreview(selectedLabel = "首页") { HomeTabletContent() }
                }
            }
        }
    }

    @Test
    fun expandedPadSidebar() {
        paparazzi.snapshot(name = "wallhub-home-pad-portrait-sidebar-expanded") {
            homeTabletTheme {
                PadPortraitAdaptiveContent {
                    PadPortraitTopLevelPreview(
                        selectedLabel = "首页",
                        railExpanded = true,
                    ) {
                        HomeTabletContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun homeTabletTheme(content: @Composable () -> Unit) {
    WallHubTheme(
        preference = ThemePreference.DARK,
        accent = AccentPreference.BLUE,
        useSystemMonet = false,
        visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
        content = content,
    )
}

@Composable
private fun HomeTabletContent(
    initialFirstVisibleItemIndex: Int = 0,
    showHomeTopInfo: Boolean = false,
) {
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initialFirstVisibleItemIndex)
    HomeScreen(
        state =
            HomeUiState(
                items = tabletScreenshotWorkshops,
                columns = 0,
                showHomeTopInfo = showHomeTopInfo,
                totalCount = tabletScreenshotWorkshops.size,
                isInitialLoading = false,
            ),
        onAction = {},
        onOpenSettings = {},
        gridState = gridState,
    )
}

private val tabletScreenshotWorkshops =
    listOf(
        WorkshopSummary(
            id = 1L,
            title = "雨夜列车",
            author = "WallHub Studio",
            type = WorkshopType.VIDEO,
            tags = listOf("Cyberpunk", "Relaxing"),
            subscriptions = 12_840,
            favorites = 3_210,
            fileSizeBytes = 64_100_000,
        ),
        WorkshopSummary(
            id = 2L,
            title = "【明日方舟/Arknights】山谷晨雾-季shu",
            author = "Landscape Lab",
            type = WorkshopType.SCENE,
            tags = listOf("Nature", "Landscape"),
            subscriptions = 8_420,
            favorites = 1_920,
            fileSizeBytes = 34_000_000,
        ),
        WorkshopSummary(
            id = 3L,
            title = "轨道空间站",
            author = "Deep Orbit",
            type = WorkshopType.WEB,
            tags = listOf("Sci-Fi", "Technology"),
            subscriptions = 6_300,
            favorites = 980,
            fileSizeBytes = 53_900_000,
        ),
        WorkshopSummary(
            id = 4L,
            title = "像素海岸",
            author = "Retro Works",
            type = WorkshopType.SCENE,
            tags = listOf("Pixel art", "Retro"),
            subscriptions = 5_120,
            favorites = 860,
            fileSizeBytes = 9_900_000,
        ),
        WorkshopSummary(
            id = 5L,
            title = "深海微光",
            author = "Blue Current",
            type = WorkshopType.VIDEO,
            tags = listOf("Abstract", "Relaxing"),
            subscriptions = 4_780,
            favorites = 720,
            fileSizeBytes = 18_400_000,
        ),
        WorkshopSummary(
            id = 6L,
            title = "纸上王国",
            author = "Tiny Worlds",
            type = WorkshopType.SCENE,
            tags = listOf("Fantasy", "Cartoon"),
            subscriptions = 3_950,
            favorites = 610,
            fileSizeBytes = 27_600_000,
        ),
        WorkshopSummary(
            id = 7L,
            title = "极光观测站",
            author = "Northern Sky",
            type = WorkshopType.WEB,
            tags = listOf("Aurora", "Night"),
            subscriptions = 3_420,
            favorites = 540,
            fileSizeBytes = 22_300_000,
        ),
        WorkshopSummary(
            id = 8L,
            title = "月面花园",
            author = "Lunar Atelier",
            type = WorkshopType.VIDEO,
            tags = listOf("Space", "Ambient"),
            subscriptions = 2_880,
            favorites = 470,
            fileSizeBytes = 31_800_000,
        ),
    )

