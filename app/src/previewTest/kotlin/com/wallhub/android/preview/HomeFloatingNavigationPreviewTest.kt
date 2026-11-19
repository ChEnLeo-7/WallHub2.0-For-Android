@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.preview

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.android.resources.Density
import com.wallhub.android.TopLevelDestination
import com.wallhub.android.core.designsystem.LocalWallHubOverlayBottomClearance
import com.wallhub.android.core.designsystem.WallHubAppSurface
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.feature.home.HomeScreen
import com.wallhub.android.feature.home.HomeUiState
import com.wallhub.android.feature.home.HomeViewMode
import com.wallhub.android.WallHubFloatingNavigationBar
import com.wallhub.android.topLevelDestinations
import org.junit.Rule
import org.junit.Test

/** 1440 x 3168 / 560 dpi review render of the Expressive home shell with the floating navigation. */
class HomeFloatingNavigationPreviewTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig =
                DeviceConfig.PIXEL_5.copy(
                    screenWidth = 1440,
                    screenHeight = 3168,
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
    fun homeExpressiveFloatingNavigation() {
        paparazzi.snapshot(name = "wallhub-home-expressive-floating-navigation-1440x3168") {
            HomeExpressiveFloatingNavigationPreview()
        }
    }
}

@Composable
private fun HomeExpressiveFloatingNavigationPreview() {
    val density = LocalDensity.current
    // Paparazzi reports no window insets, so the gesture bar height is emulated explicitly:
    // the pill floats above it and the content reserves the same inset as on device.
    val gestureInset = with(density) { 100.toDp() }
    val previewImageLoader =
        ImageLoader.Builder(androidx.compose.ui.platform.LocalContext.current)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }.build()
    WallHubTheme(
        preference = ThemePreference.LIGHT,
        accent = AccentPreference.BLUE,
        useSystemMonet = false,
        visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
    ) {
        WallHubAppSurface {
            CompositionLocalProvider(LocalImageLoader provides previewImageLoader) {
                Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalWallHubOverlayBottomClearance provides
                        gestureInset + WallHubSizeTokens.floatingNavigationClearance,
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(top = with(density) { STATUS_BAR_HEIGHT_PX.toDp() }),
                    ) {
                        Box(Modifier.weight(1f)) {
                            HomeScreen(
                                state = floatingNavigationHomeState(),
                                onAction = {},
                                onOpenSettings = {},
                            )
                        }
                    }
                }
                DeviceStatusBar(
                    modifier = Modifier.align(Alignment.TopCenter),
                    dark = false,
                )
                GestureHandle(modifier = Modifier.align(Alignment.BottomCenter))
                WallHubFloatingNavigationBar(
                    destinations = topLevelDestinations(includeDownloads = true),
                    currentTopLevelDestination = TopLevelDestination.HOME,
                    onNavigateTo = {},
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = gestureInset),
                )
                }
            }
        }
    }
}

private fun floatingNavigationHomeState(): HomeUiState =
    HomeUiState(
        items = floatingNavigationItems(),
        columns = 0,
        isInitialLoading = false,
        totalCount = floatingNavigationItems().size,
        cardAction = HomeCardAction.DOWNLOAD,
        showHomeTopInfo = true,
        homeSearchFab = true,
        viewMode = HomeViewMode.GRID,
    )

private fun floatingNavigationItems(): List<WorkshopSummary> =
    listOf(
        PreviewWorkshop(3811917786L, "Carrier Strike Group", "wladbelsky", WorkshopType.WEB, 10_000L, 672L, 1_400_000L),
        PreviewWorkshop(3818033498L, "ICEY艾希：续", "Potato_hz", WorkshopType.VIDEO, 19_000L, 1_100L, 64_100_000L),
        PreviewWorkshop(3823357259L, "Talia Yang / Cyberpunk Edgerunners 2", "Steam 创作者", WorkshopType.SCENE, 19_000L, 639L, 5_070_379L),
        PreviewWorkshop(3803547930L, "海绵宝宝｜章鱼哥的上班日常", "SpongeBob", WorkshopType.VIDEO, 17_900L, 6_100L, 24_000_000L),
        PreviewWorkshop(3829902899L, "乙骨忧太-音频动态切换-高度自定义", "Steam 创作者", WorkshopType.SCENE, 7_900L, 341L, 31_400_000L),
        PreviewWorkshop(3829467967L, "Deepseek 图鲸鲨 · 大肥鱼数字生命控制台", "wdsjdyx", WorkshopType.SCENE, 37_000L, 1_500L, 36_300_000L),
    ).map { workshop ->
        WorkshopSummary(
            id = workshop.id,
            title = workshop.title,
            author = workshop.author,
            type = workshop.type,
            previewUrl = coverUri(workshop.id),
            subscriptions = workshop.subscriptions,
            favorites = workshop.favorites,
            fileSizeBytes = workshop.fileSize,
        )
    }

private data class PreviewWorkshop(
    val id: Long,
    val title: String,
    val author: String,
    val type: WorkshopType,
    val subscriptions: Long,
    val favorites: Long,
    val fileSize: Long,
)
