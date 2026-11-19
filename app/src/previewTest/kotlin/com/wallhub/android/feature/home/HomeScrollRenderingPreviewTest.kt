package com.wallhub.android.feature.home

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.cash.paparazzi.Paparazzi
import com.wallhub.android.core.designsystem.WallHubAppSurface
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.testing.PadLandscapeDevice
import com.wallhub.android.testing.PadLandscapeAdaptiveContent
import com.wallhub.android.testing.PadTopLevelPreview
import kotlinx.coroutines.delay
import java.io.File
import org.junit.Rule
import org.junit.Test

class HomeScrollRenderingPreviewTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = PadLandscapeDevice, showSystemUi = false, useDeviceResolution = true)

    @Test
    fun rendersHomeGridWhileScrolling() {
        val previewUrl = testCoverFile().toURI().toString()
        paparazzi.gif("wallhub-home-scroll-rendering", 0L, 8_000L, 15) {
            ScrollingHomePreview(previewUrl)
        }
    }

    @Test
    fun rendersHomeGridTopEdgeGif() {
        val previewUrl = testCoverFile().toURI().toString()
        paparazzi.gif("wallhub-home-top-edge-scroll", 0L, 6_000L, 15) {
            TopEdgeScrollPreview(previewUrl)
        }
    }
}

@Composable
private fun TopEdgeScrollPreview(previewUrl: String) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState) {
        delay(600)
        scrollGrid(
            gridState = gridState,
            fromPx = 0f,
            toPx = 400f,
            durationMillis = 1_800L,
        )
        delay(500)
        scrollGrid(
            gridState = gridState,
            fromPx = 400f,
            toPx = 0f,
            durationMillis = 1_800L,
        )
    }
    WallHubTheme(
        preference = ThemePreference.DARK,
        accent = AccentPreference.CUSTOM,
        customAccentColor = "#FF9800",
        useSystemMonet = false,
        visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
    ) {
        WallHubAppSurface {
            Box(Modifier.fillMaxSize()) {
                PadLandscapeAdaptiveContent {
                    PadTopLevelPreview(selectedLabel = "首页", railExpanded = true) {
                        HomeScreen(
                            state = HomeUiState(
                                items = scrollPreviewItems(previewUrl).take(24),
                                columns = 6,
                                cardAction = HomeCardAction.DOWNLOAD,
                                totalCount = 24,
                                isInitialLoading = false,
                                homeSearchFab = false,
                                homeBottomBarAutoHide = false,
                            ),
                            onAction = {},
                            gridState = gridState,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrollingHomePreview(previewUrl: String) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState) {
        delay(500)
        gridState.animateScrollToItem(18)
        delay(500)
        gridState.animateScrollToItem(36)
        delay(500)
        gridState.animateScrollToItem(54)
        delay(500)
        gridState.animateScrollToItem(0)
    }
    WallHubTheme(
        preference = ThemePreference.DARK,
        accent = AccentPreference.CUSTOM,
        customAccentColor = "#FF9800",
        useSystemMonet = false,
        visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
    ) {
        WallHubAppSurface {
            Box(Modifier.fillMaxSize()) {
                PadLandscapeAdaptiveContent {
                    PadTopLevelPreview(selectedLabel = "首页", railExpanded = true) {
                        HomeScreen(
                            state = HomeUiState(
                                items = scrollPreviewItems(previewUrl),
                                columns = 6,
                                cardAction = HomeCardAction.DOWNLOAD,
                                totalCount = 72,
                                isInitialLoading = false,
                                homeSearchFab = false,
                                homeBottomBarAutoHide = false,
                            ),
                            onAction = {},
                            gridState = gridState,
                        )
                    }
                }
            }
        }
    }
}

private suspend fun scrollGrid(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    fromPx: Float,
    toPx: Float,
    @Suppress("UNUSED_PARAMETER") durationMillis: Long,
) {
    val steps = 30
    val delta = (toPx - fromPx) / steps
    repeat(steps) {
        gridState.scrollBy(delta)
        delay(80)
    }
}

private fun testCoverFile(): File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { it.resolve("artifacts/wallpaper-covers/cover01.jpg") }
        .first(File::isFile)

private fun scrollPreviewItems(previewUrl: String) = (0 until 72).map { index ->
    WorkshopSummary(
        id = index + 1L,
        title = "Scroll rendering card ${index + 1}",
        author = "WallHub preview",
        type = WorkshopType.entries[index % WorkshopType.entries.size],
        tags = emptyList(),
        previewUrl = previewUrl,
        subscriptions = 20_000L - index * 37L,
        favorites = 4_000L - index * 11L,
        fileSizeBytes = 64_000_000L - index * 120_000L,
    )
}

