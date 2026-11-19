@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wallhub.android.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.wallhub.android.TopLevelDestination
import com.wallhub.android.core.designsystem.WallHubAppSurface
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.feature.downloads.BettboxDownloadFab
import com.wallhub.android.feature.downloads.DownloadsClearConfirmationDialog
import com.wallhub.android.feature.downloads.DownloadsScreen
import com.wallhub.android.feature.downloads.DownloadsUiState
import com.wallhub.android.feature.downloads.DownloadsTypeFilterMenuItems
import com.wallhub.android.feature.home.HomeCardHostContent
import com.wallhub.android.feature.home.HomeCardLayoutKey
import com.wallhub.android.feature.home.HomeCardMetrics
import com.wallhub.android.feature.home.HomeCardQuickSheetContent
import com.wallhub.android.feature.home.HomeScreen
import com.wallhub.android.feature.home.HomeUiState
import com.wallhub.android.feature.home.HomeViewMode
import com.wallhub.android.feature.profile.ProfileCountState
import com.wallhub.android.feature.profile.ProfileCounts
import com.wallhub.android.feature.profile.ProfileMd3Content
import org.junit.Rule
import org.junit.Test

/**
 * Device-accurate MD3 review renders for the main WallHub surfaces.
 *
 * Same 1440 x 3168 / 560 dpi device config as Md3SettingsDevicePreviewTest; the
 * status bar and gesture handle are composed in the same Paparazzi frame.
 */
class Md3CoreScreensPreviewTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = WallHubPhoneDevice,
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun homeLight() {
        paparazzi.gif("wallhub-md3-core-home-light-1440x3168", 0L, 2_400L, 30) {
            CoreDeviceFrame(theme = ThemePreference.LIGHT) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        HomeScreen(
                            state = coreHomeState(),
                            onAction = {},
                            onOpenSettings = {},
                        )
                    }
                    CoreBottomNavigation(selected = TopLevelDestination.HOME)
                }
            }
        }
    }

    @Test
    fun homeList() {
        paparazzi.gif("wallhub-md3-core-home-list-1440x3168", 0L, 2_400L, 30) {
            CoreDeviceFrame(theme = ThemePreference.LIGHT) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        HomeScreen(
                            state = coreHomeState().copy(viewMode = HomeViewMode.LIST),
                            onAction = {},
                            onOpenSettings = {},
                        )
                    }
                    CoreBottomNavigation(selected = TopLevelDestination.HOME)
                }
            }
        }
    }

    @Test
    fun homeCardQuickSheet() {
        paparazzi.gif("wallhub-md3-core-card-sheet-1440x3168", 0L, 2_400L, 30) {
            CoreDeviceFrame(theme = ThemePreference.LIGHT) {
                val item = coreHomeState().items.first()
                HomeCardQuickSheetContent(
                    item = item,
                    content =
                        HomeCardHostContent(
                            items = coreHomeState().items,
                            authorDisplayNames = emptyMap(),
                            layoutKey = HomeCardLayoutKey.resolve(HomeViewMode.LIST, 1),
                            metrics = HomeCardMetrics(height = 96.dp, gridStatisticsAvailableWidth = 0.dp, listStatisticsAvailableWidth = 0.dp),
                            coverSizePx = 512,
                            action = HomeCardAction.DOWNLOAD,
                            onOpenDetail = {},
                            onPrimaryAction = {},
                            onDownload = {},
                            onSearchAuthor = {},
                            onCopyText = { _, _ -> },
                            onOpenSteam = {},
                            onAuthorNameRequested = {},
                        ),
                    onDismiss = {},
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }

    @Test
    fun homeBlueSimulation() {
        paparazzi.gif("wallhub-md3-core-home-blue-simulation-1440x3168", 0L, 2_400L, 30) {
            CoreDeviceFrame(theme = ThemePreference.LIGHT, accent = AccentPreference.BLUE) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        HomeScreen(
                            state = coreHomeState(),
                            onAction = {},
                            onOpenSettings = {},
                        )
                    }
                    CoreBottomNavigation(selected = TopLevelDestination.HOME)
                }
            }
        }
    }

    @Test
    fun homeDark() {
        paparazzi.gif("wallhub-md3-core-home-dark-1440x3168", 0L, 2_400L, 30) {
            CoreDeviceFrame(theme = ThemePreference.DARK) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        HomeScreen(
                            state = coreHomeState(),
                            onAction = {},
                            onOpenSettings = {},
                        )
                    }
                    CoreBottomNavigation(selected = TopLevelDestination.HOME)
                }
            }
        }
    }

    @Test
    fun profileLight() {
        paparazzi.gif("wallhub-md3-core-profile-light-1440x3168", 0L, 2_400L, 30) {
            CoreDeviceFrame(theme = ThemePreference.LIGHT) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        WallHubPageScaffold(
                            title = "我的",
                            actions = {
                                IconButton(onClick = {}) {
                                    Icon(Icons.Outlined.Settings, contentDescription = "设置")
                                }
                            },
                        ) { padding ->
                            ProfileMd3Content(
                                session =
                                    SteamSessionState(
                                        phase = SteamSessionPhase.SIGNED_IN,
                                        accountName = "wallhub_review",
                                        personaName = "WallHub Review",
                                        avatarUrl = coverUri(3818033498L),
                                        isConnected = true,
                                    ),
                                runtimeHours = 128,
                                counts =
                                    ProfileCounts(
                                        subscriptions = ProfileCountState.Loaded(12_840),
                                        favorites = ProfileCountState.Loaded(3_210),
                                    ),
                                isSignedIn = true,
                                isRestoring = false,
                                onOpenDownloads = {},
                                onOpenLocal = {},
                                onOpenSubscriptions = {},
                                onOpenFavorites = {},
                                onOpenLogin = {},
                                modifier = Modifier.fillMaxSize().padding(padding),
                            )
                        }
                    }
                    CoreBottomNavigation(selected = TopLevelDestination.PROFILE)
                }
            }
        }
    }

    @Test
    fun downloadsExpressivePageDarkPhone() {
        paparazzi.snapshot(name = "wallhub-downloads-expressive-dark-phone") {
            ExpressiveCoreDeviceFrame(theme = ThemePreference.DARK) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        DownloadsScreen(
                            state = DownloadsUiState(allTasks = previewDownloadTasks),
                            onAction = {},
                        )
                    }
                    CoreBottomNavigation(selected = TopLevelDestination.DOWNLOADS)
                }
            }
        }
    }

    @Test
    fun downloadsExpressiveFabExpandedDarkPhone() {
        paparazzi.snapshot(name = "wallhub-downloads-expressive-fab-expanded-dark-phone") {
            ExpressiveCoreDeviceFrame(theme = ThemePreference.DARK) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        DownloadsScreen(
                            state = DownloadsUiState(allTasks = previewDownloadTasks),
                            onAction = {},
                            initialFabExpanded = true,
                        )
                    }
                    CoreBottomNavigation(selected = TopLevelDestination.DOWNLOADS)
                }
            }
        }
    }

    @Test
    fun downloadsExpressiveFabExpandedLightPhone() {
        paparazzi.snapshot(name = "wallhub-downloads-expressive-fab-expanded-light-phone") {
            ExpressiveCoreDeviceFrame(theme = ThemePreference.LIGHT) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        DownloadsScreen(
                            state = DownloadsUiState(allTasks = previewDownloadTasks),
                            onAction = {},
                            initialFabExpanded = true,
                        )
                    }
                    CoreBottomNavigation(selected = TopLevelDestination.DOWNLOADS)
                }
            }
        }
    }

    @Test
    fun downloadsExpressiveEmptyFabExpandedLightPhone() {
        paparazzi.snapshot(name = "wallhub-downloads-expressive-empty-fab-expanded-light-phone") {
            ExpressiveCoreDeviceFrame(theme = ThemePreference.LIGHT) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        DownloadsScreen(
                            state = DownloadsUiState(),
                            onAction = {},
                            initialFabExpanded = true,
                        )
                    }
                    CoreBottomNavigation(selected = TopLevelDestination.DOWNLOADS)
                }
            }
        }
    }

    @Test
    fun downloadsExpressiveLightPhone() {
        paparazzi.snapshot(name = "wallhub-downloads-expressive-light-phone") {
            ExpressiveCoreDeviceFrame(theme = ThemePreference.LIGHT) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        DownloadsScreen(
                            state = DownloadsUiState(allTasks = previewDownloadTasks),
                            onAction = {},
                        )
                    }
                    CoreBottomNavigation(selected = TopLevelDestination.DOWNLOADS)
                }
            }
        }
    }

    @Test
    fun downloadsExpressiveTypeFilterMenuDarkPhone() {
        paparazzi.snapshot(name = "wallhub-downloads-expressive-type-filter-menu-dark-phone") {
            ExpressiveCoreDeviceFrame(theme = ThemePreference.DARK) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            DownloadsScreen(
                                state = DownloadsUiState(allTasks = previewDownloadTasks),
                                onAction = {},
                            )
                        }
                        CoreBottomNavigation(selected = TopLevelDestination.DOWNLOADS)
                    }
                    androidx.compose.material3.Surface(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 108.dp),
                        shape = MaterialTheme.shapes.small,
                        color = com.wallhub.android.core.designsystem.wallHubStyle().surfaces.menu,
                        tonalElevation = 0.dp,
                        shadowElevation = 6.dp,
                    ) {
                        Column(Modifier.width(200.dp)) {
                            DownloadsTypeFilterMenuItems(
                                selectedTypes = setOf(WorkshopType.VIDEO),
                                onSelect = {},
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun downloadsLight() {
        paparazzi.snapshot(name = "wallhub-md3-core-downloads-light-1440x3168") {
            CoreDeviceFrame(theme = ThemePreference.LIGHT) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        DownloadsScreen(
                            state =
                                DownloadsUiState(
                                    allTasks = coreDownloadTasks,
                                    tasks = coreDownloadTasks,
                                ),
                            onAction = {},
                        )
                    }
                    CoreBottomNavigation(selected = TopLevelDestination.DOWNLOADS)
                }
            }
        }
    }
}

private val previewDownloadTasks =
    listOf(
        DownloadTask(
            id = "preview-video",
            workshopId = 3818033498L,
            title = "雨夜列车",
            type = WorkshopType.VIDEO,
            status = DownloadStatus.DOWNLOADING,
            previewUrl = coverUri(3818033498L),
            downloadedBytes = 420_000_000L,
            totalBytes = 1_000_000_000L,
        ),
        DownloadTask(
            id = "preview-scene",
            workshopId = 3823357259L,
            title = "山谷晨雾",
            type = WorkshopType.SCENE,
            status = DownloadStatus.PAUSED,
            previewUrl = coverUri(3823357259L),
            downloadedBytes = 310_000_000L,
            totalBytes = 780_000_000L,
        ),
        DownloadTask(
            id = "preview-web",
            workshopId = 3811917786L,
            title = "轨道空间站",
            type = WorkshopType.WEB,
            status = DownloadStatus.COMPLETED,
            previewUrl = coverUri(3811917786L),
            downloadedBytes = 96_000_000L,
            totalBytes = 96_000_000L,
        ),
        DownloadTask(
            id = "preview-failed",
            workshopId = 3823357260L,
            title = "像素海岸",
            type = WorkshopType.SCENE,
            status = DownloadStatus.FAILED,
            downloadedBytes = 170_000_000L,
            totalBytes = 520_000_000L,
        ),
    )

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

@Composable
private fun ExpressiveCoreDeviceFrame(
    theme: ThemePreference,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    WallHubTheme(
        preference = theme,
        accent = AccentPreference.DEFAULT,
        useSystemMonet = false,
        visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
    ) {
        WallHubAppSurface {
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(top = with(density) { STATUS_BAR_HEIGHT_PX.toDp() }),
                ) {
                    content()
                }
                DeviceStatusBar(
                    modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
                    dark = theme == ThemePreference.DARK,
                )
                GestureHandle(
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                    dark = theme == ThemePreference.DARK,
                )
            }
        }
    }
}

@Composable
private fun CoreDeviceFrame(
    theme: ThemePreference,
    accent: AccentPreference = AccentPreference.DEFAULT,
    content: @Composable () -> Unit,
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
                    content()
                }
                DeviceStatusBar(
                    modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
                    dark = theme == ThemePreference.DARK,
                )
                GestureHandle(
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                    dark = theme == ThemePreference.DARK,
                )
            }
        }
    }
}

@Composable
private fun CoreBottomNavigation(selected: TopLevelDestination) {
    NavigationBar(
        windowInsets = WindowInsets(bottom = with(LocalDensity.current) { 112.toDp() }),
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val isSelected = destination == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = {},
                icon = {
                    Icon(
                        imageVector =
                            when (destination) {
                                TopLevelDestination.HOME -> if (isSelected) Icons.Filled.Home else Icons.Outlined.Home
                                TopLevelDestination.DISCOVER ->
                                    if (isSelected) Icons.Filled.Explore else Icons.Outlined.Explore
                                TopLevelDestination.DOWNLOADS ->
                                    if (isSelected) Icons.Filled.Download else Icons.Outlined.Download

                                TopLevelDestination.PROFILE ->
                                    if (isSelected) Icons.Filled.Person else Icons.Outlined.PersonOutline
                            },
                        contentDescription = destination.name,
                    )
                },
                label = {
                    Text(
                        text =
                            when (destination) {
                                TopLevelDestination.HOME -> "首页"
                                TopLevelDestination.DISCOVER -> "发现"
                                TopLevelDestination.DOWNLOADS -> "下载"
                                TopLevelDestination.PROFILE -> "我的"
                            },
                    )
                },
            )
        }
    }
}

private fun coreHomeState(): HomeUiState =
    HomeUiState(
        items =
            listOf(
                WorkshopSummary(
                    id = 3811917786L,
                    title = "Carrier Strike Group",
                    author = "wladbelsky",
                    type = WorkshopType.WEB,
                    previewUrl = coverUri(3811917786L),
                    subscriptions = 6_800L,
                    favorites = 451L,
                    fileSizeBytes = 1_400_000L,
                ),
                WorkshopSummary(
                    id = 3818033498L,
                    title = "ICEY艾希：续",
                    author = "Potato_hz",
                    type = WorkshopType.VIDEO,
                    previewUrl = coverUri(3818033498L),
                    subscriptions = 19_000L,
                    favorites = 1_100L,
                    fileSizeBytes = 64_100_000L,
                ),
                WorkshopSummary(
                    id = 3823357259L,
                    title = "Talia Yang / Cyberpunk Edgerunners 2",
                    author = "Steam 创作者",
                    type = WorkshopType.SCENE,
                    previewUrl = coverUri(3823357259L),
                    subscriptions = 19_000L,
                    favorites = 639L,
                    fileSizeBytes = 5_070_379L,
                ),
                WorkshopSummary(
                    id = 3803547930L,
                    title = "海绵宝宝｜章鱼哥的上班日常",
                    author = "SpongeBob",
                    type = WorkshopType.VIDEO,
                    previewUrl = coverUri(3803547930L),
                    subscriptions = 17_900L,
                    favorites = 6_100L,
                    fileSizeBytes = 24_000_000L,
                ),
            ),
        columns = 0,
        isInitialLoading = false,
        totalCount = 4,
        cardAction = HomeCardAction.DOWNLOAD,
        showHomeTopInfo = true,
        homeSearchFab = true,
        viewMode = HomeViewMode.GRID,
    )

private val coreDownloadTasks =
    listOf(
        DownloadTask(
            id = "review-downloading",
            workshopId = 3811917786L,
            title = "Carrier Strike Group",
            type = WorkshopType.WEB,
            status = DownloadStatus.DOWNLOADING,
            previewUrl = coverUri(3811917786L),
            downloadedBytes = 24_000_000L,
            totalBytes = 51_400_000L,
            bytesPerSecond = 2_400_000L,
        ),
        DownloadTask(
            id = "review-downloading-2",
            workshopId = 3818033498L,
            title = "ICEY艾希：续",
            type = WorkshopType.VIDEO,
            status = DownloadStatus.DOWNLOADING,
            previewUrl = coverUri(3818033498L),
            downloadedBytes = 18_000_000L,
            totalBytes = 64_100_000L,
            bytesPerSecond = 1_100_000L,
        ),
        DownloadTask(
            id = "review-completed",
            workshopId = 3823357259L,
            title = "Talia Yang / Cyberpunk Edgerunners 2",
            type = WorkshopType.SCENE,
            status = DownloadStatus.COMPLETED,
            previewUrl = coverUri(3823357259L),
            downloadedBytes = 4_800_000L,
            totalBytes = 4_800_000L,
            outputLabel = "Talia.mpkg",
        ),
    )
