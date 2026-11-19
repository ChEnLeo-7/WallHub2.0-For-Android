package com.wallhub.android.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.wallhub.android.core.designsystem.WallHubAppSurface
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
import com.wallhub.android.testing.PadLandscapeAdaptiveContent
import com.wallhub.android.testing.PadLandscapeDevice
import com.wallhub.android.testing.PadTopLevelPreview
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Phone Paparazzi render for design review.
 *
 * Uses real Workshop ids/titles plus the matching real cover files from testFocused resources.
 * The GIF frames give Coil time to complete its asynchronous image request before the final frame.
 */
class RealisticPhoneHomePreviewTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "zh-rCN"),
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun realisticPhoneHome() {
        paparazzi.gif("wallhub-realistic-phone-home", 0L, 2_400L, 30) {
            RealisticPhoneHome()
        }
    }

    @Test
    fun colorFixPhone() {
        paparazzi.gif("color-fix-phone-gif", 0L, 2_400L, 30) {
            RealisticPhoneHome(accent = AccentPreference.DEFAULT)
        }
    }
}

class RealisticPadHomePreviewTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = PadLandscapeDevice,
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun colorFixPad() {
        paparazzi.gif("color-fix-pad-gif", 0L, 2_400L, 30) {
            RealisticPadHome()
        }
    }
}

private data class RealWorkshop(
    val id: Long,
    val title: String,
    val author: String,
    val type: WorkshopType,
    val subscriptions: Long,
    val favorites: Long,
    val fileSize: Long,
)

private val realisticWorkshops =
    listOf(
        RealWorkshop(
            id = 3811917786L,
            title = "Carrier Strike Group",
            author = "wladbelsky",
            type = WorkshopType.WEB,
            subscriptions = 6_800L,
            favorites = 451L,
            fileSize = 1_400_000L,
        ),
        RealWorkshop(
            id = 3829902899L,
            title = "乙骨忧太-音频动态切换-高度自定义-Yuta Okkotsu – Audio-Reactive Dynamic Switch – Highly Customizable",
            author = "Steam 创作者",
            type = WorkshopType.SCENE,
            subscriptions = 7_900L,
            favorites = 341L,
            fileSize = 31_400_000L,
        ),
        RealWorkshop(
            id = 3818033498L,
            title = "ICEY艾希：续",
            author = "Potato_hz",
            type = WorkshopType.VIDEO,
            subscriptions = 19_000L,
            favorites = 1_100L,
            fileSize = 64_100_000L,
        ),
        RealWorkshop(
            id = 3823357259L,
            title = "Talia Yang / Cyberpunk Edgerunners 2 (X-ray Interactive)",
            author = "Steam 创作者",
            type = WorkshopType.SCENE,
            subscriptions = 19_000L,
            favorites = 639L,
            fileSize = 5_070_379L,
        ),
        RealWorkshop(
            id = 3829467967L,
            title = "麻匪 Rainlink 郁",
            author = "Hope麻匪",
            type = WorkshopType.SCENE,
            subscriptions = 16_000L,
            favorites = 741L,
            fileSize = 43_600_000L,
        ),
        RealWorkshop(
            id = 3803547930L,
            title = "海绵宝宝｜章鱼哥的上班日常",
            author = "SpongeBob",
            type = WorkshopType.VIDEO,
            subscriptions = 17_900L,
            favorites = 6_100L,
            fileSize = 24_000_000L,
        ),
    )

internal fun coverUri(id: Long): String {
    val direct = File("src/testFocused/res/drawable-nodpi/workshop_$id.jpg")
    if (direct.isFile) return direct.absoluteFile.toURI().toString()
    val fromRoot =
        File("app/src/testFocused/res/drawable-nodpi/workshop_$id.jpg")
    return fromRoot.absoluteFile.toURI().toString()
}

private fun realisticItems(): List<WorkshopSummary> =
    realisticWorkshops.map { workshop ->
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

private fun realisticHomeState(items: List<WorkshopSummary>): HomeUiState =
    HomeUiState(
        items = items,
        columns = 0,
        isInitialLoading = false,
        totalCount = items.size,
        cardAction = HomeCardAction.DOWNLOAD,
        showHomeTopInfo = true,
        homeSearchFab = true,
        viewMode = HomeViewMode.GRID,
    )

@Composable
internal fun RealisticPhoneHome(accent: AccentPreference = AccentPreference.BLUE) {
    val items = realisticItems()
    WallHubTheme(
        preference = ThemePreference.LIGHT,
        accent = accent,
        useSystemMonet = false,
        visualStyle = VisualStyle.MATERIAL3,
    ) {
        WallHubAppSurface {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    HomeScreen(
                        state = realisticHomeState(items),
                        onAction = {},
                        onOpenSettings = {},
                    )
                }
                PreviewNavigationBar()
            }
        }
    }
}

@Composable
private fun RealisticPadHome() {
    val baseItems = realisticItems()
    val items =
        List(4) { page ->
            baseItems.map { item ->
                item.copy(id = item.id + page * 100_000L)
            }
        }.flatten()
    WallHubTheme(
        preference = ThemePreference.LIGHT,
        accent = AccentPreference.DEFAULT,
        useSystemMonet = false,
        visualStyle = VisualStyle.MATERIAL3,
    ) {
        val configuration = LocalConfiguration.current
        WallHubAppSurface(
            modifier =
                Modifier.requiredSize(
                    configuration.screenWidthDp.dp,
                    configuration.screenHeightDp.dp,
                ),
        ) {
            PadLandscapeAdaptiveContent {
                PadTopLevelPreview(selectedLabel = "首页") {
                    HomeScreen(
                        state = realisticHomeState(items),
                        onAction = {},
                        onOpenSettings = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewNavigationBar(modifier: Modifier = Modifier) {
    NavigationBar(modifier = modifier) {
        NavigationBarItem(
            selected = true,
            onClick = {},
            icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
            label = { Text("首页") },
        )
        NavigationBarItem(
            selected = false,
            onClick = {},
            icon = { Icon(Icons.Outlined.Explore, contentDescription = null) },
            label = { Text("发现") },
        )
        NavigationBarItem(
            selected = false,
            onClick = {},
            icon = { Icon(Icons.Outlined.Download, contentDescription = null) },
            label = { Text("下载") },
        )
        NavigationBarItem(
            selected = false,
            onClick = {},
            icon = { Icon(Icons.Outlined.PersonOutline, contentDescription = null) },
            label = { Text("我的") },
        )
    }
}


