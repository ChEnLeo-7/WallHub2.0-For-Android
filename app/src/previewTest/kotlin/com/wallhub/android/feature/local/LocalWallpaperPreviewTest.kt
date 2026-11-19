package com.wallhub.android.feature.local

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.wallhub.android.core.designsystem.WallHubAppSurface
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.LocalWallpaperFormat
import com.wallhub.android.core.model.LocalWallpaperResource
import com.wallhub.android.core.model.LocalWallpaperScanSnapshot
import com.wallhub.android.core.model.LocalWallpaperSource
import com.wallhub.android.core.model.LocalWallpaperViewMode
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.testing.PadLandscapeAdaptiveContent
import com.wallhub.android.testing.PadLandscapeDevice
import org.junit.Rule
import org.junit.Test

class LocalWallpaperPreviewTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = PadLandscapeDevice,
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun rendersLocalPageListOnPad() {
        paparazzi.snapshot(name = "local-wallpaper-page-list-pad") {
            LocalWallpaperPreview(viewMode = LocalWallpaperViewMode.LIST, pad = true)
        }
    }

    @Test
    fun rendersLocalPageGridOnPad() {
        paparazzi.snapshot(name = "local-wallpaper-page-grid-pad") {
            LocalWallpaperPreview(viewMode = LocalWallpaperViewMode.GRID, pad = true)
        }
    }

    @Test
    fun rendersLocalFilterDrawerOnPad() {
        paparazzi.snapshot(name = "local-wallpaper-filter-pad") {
            LocalWallpaperPreview(viewMode = LocalWallpaperViewMode.LIST, pad = true, openFilters = true)
        }
    }

    @Test
    fun rendersLocalDetailOnPad() {
        paparazzi.snapshot(name = "local-wallpaper-detail-pad") {
            LocalWallpaperPreview(viewMode = LocalWallpaperViewMode.DETAIL, pad = true)
        }
    }
}

class LocalWallpaperPhonePreviewTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "zh-rCN", softButtons = false),
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun rendersLocalPageListOnPhone() {
        paparazzi.snapshot(name = "local-wallpaper-page-list-phone") {
            LocalWallpaperPreview(viewMode = LocalWallpaperViewMode.LIST, pad = false)
        }
    }

    @Test
    fun rendersLocalPageGridOnPhone() {
        paparazzi.snapshot(name = "local-wallpaper-page-grid-phone") {
            LocalWallpaperPreview(viewMode = LocalWallpaperViewMode.GRID, pad = false)
        }
    }

    @Test
    fun rendersLocalDetailOnPhone() {
        paparazzi.snapshot(name = "local-wallpaper-detail-phone") {
            LocalWallpaperPreview(viewMode = LocalWallpaperViewMode.DETAIL, pad = false)
        }
    }
}

@Composable
private fun LocalWallpaperPreview(
    viewMode: LocalWallpaperViewMode,
    pad: Boolean,
    openFilters: Boolean = false,
) {
    val resources = previewLocalResources()
    WallHubTheme(
        preference = ThemePreference.DARK,
        accent = AccentPreference.CUSTOM,
        customAccentColor = "#9CCB7A",
        useSystemMonet = false,
        visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
    ) {
        WallHubAppSurface {
            if (pad) {
                PadLandscapeAdaptiveContent {
                    LocalWallpaperPreviewContent(resources, viewMode, openFilters)
                }
            } else {
                LocalWallpaperPreviewContent(resources, viewMode, openFilters)
            }
        }
    }
}

@Composable
private fun LocalWallpaperPreviewContent(
    resources: List<LocalWallpaperResource>,
    viewMode: LocalWallpaperViewMode,
    openFilters: Boolean,
) {
    LocalWallpaperScreen(
        state =
            LocalWallpaperUiState(
                scan =
                    LocalWallpaperScanSnapshot(
                        resources = resources,
                        sources =
                            listOf(
                                LocalWallpaperSource(
                                    id = "downloads",
                                    label = "下载目录",
                                    rootUri = "content://preview/downloads",
                                    isDownloadDirectory = true,
                                ),
                                LocalWallpaperSource(
                                    id = "wallpaper-engine",
                                    label = "Wallpaper Engine",
                                    rootUri = "content://preview/wallpaper-engine",
                                    isDownloadDirectory = false,
                                ),
                            ),
                        discoveredCount = resources.size,
                        currentSourceLabel = "下载目录",
                    ),
                viewMode = viewMode,
                selectedResourceId = if (viewMode == LocalWallpaperViewMode.DETAIL) resources.first().id else null,
                sourceId = null,
                resources = resources,
                allTags = listOf("4K", "赛博朋克", "极简", "动态"),
            ),
        isPageActive = true,
        onAction = {},
        onScrollChromeCollapsedChanged = {},
        onOpenSettings = {},
        onBack = {},
        initialFiltersOpen = openFilters,
    )
}

private fun previewLocalResources(): List<LocalWallpaperResource> =
    listOf(
        LocalWallpaperResource(
            id = "local-1",
            contentUri = "content://preview/local-1",
            displayName = "Cyberpunk City Loop",
            title = "Cyberpunk City Loop",
            format = LocalWallpaperFormat.MPKG,
            workshopType = WorkshopType.SCENE,
            sourceId = "downloads",
            sourceLabel = "下载目录",
            relativePath = "Download/Cyberpunk City Loop",
            sizeBytes = 48_500_000L,
            modifiedAt = 1_761_000_000_000L,
            mimeType = "application/octet-stream",
            detectionReason = "MPKG package",
            workshopId = 3822357259L,
            isFavorite = true,
            tags = setOf("赛博朋克", "4K"),
        ),
        LocalWallpaperResource(
            id = "local-2",
            contentUri = "content://preview/local-2",
            displayName = "Aurora Field",
            title = "Aurora Field",
            format = LocalWallpaperFormat.VIDEO,
            workshopType = WorkshopType.SCENE,
            sourceId = "downloads",
            sourceLabel = "下载目录",
            relativePath = "Download/Aurora Field.mp4",
            sizeBytes = 92_300_000L,
            modifiedAt = 1_760_900_000_000L,
            mimeType = "video/mp4",
            detectionReason = "Video file",
            tags = setOf("极简"),
        ),
        LocalWallpaperResource(
            id = "local-3",
            contentUri = "content://preview/local-3",
            displayName = "Glass Garden",
            title = "Glass Garden",
            format = LocalWallpaperFormat.HTML,
            workshopType = WorkshopType.WEB,
            sourceId = "wallpaper-engine",
            sourceLabel = "Wallpaper Engine",
            relativePath = "projects/glass_garden/index.html",
            sizeBytes = 14_800_000L,
            modifiedAt = 1_760_800_000_000L,
            mimeType = "text/html",
            detectionReason = "HTML project",
            tags = setOf("动态", "极简"),
        ),
        LocalWallpaperResource(
            id = "local-4",
            contentUri = "content://preview/local-4",
            displayName = "Neon Alley",
            title = "Neon Alley at Night",
            format = LocalWallpaperFormat.PKG,
            workshopType = WorkshopType.SCENE,
            sourceId = "downloads",
            sourceLabel = "下载目录",
            relativePath = "Download/Neon Alley.pkg",
            sizeBytes = 76_400_000L,
            modifiedAt = 1_760_700_000_000L,
            mimeType = "application/octet-stream",
            detectionReason = "PKG package",
            tags = setOf("赛博朋克"),
            importRequestedAt = 1_760_750_000_000L,
        ),
        LocalWallpaperResource(
            id = "local-5",
            contentUri = "content://preview/local-5",
            displayName = "Winter Cabin",
            title = "Winter Cabin",
            format = LocalWallpaperFormat.VIDEO,
            workshopType = WorkshopType.SCENE,
            sourceId = "downloads",
            sourceLabel = "下载目录",
            relativePath = "Download/Winter Cabin.mp4",
            sizeBytes = 118_000_000L,
            modifiedAt = 1_760_600_000_000L,
            mimeType = "video/mp4",
            detectionReason = "Video file",
            tags = setOf("4K"),
        ),
        LocalWallpaperResource(
            id = "local-6",
            contentUri = "content://preview/local-6",
            displayName = "minimal-clock",
            title = "Minimal Clock",
            format = LocalWallpaperFormat.HTML,
            workshopType = WorkshopType.WEB,
            sourceId = "wallpaper-engine",
            sourceLabel = "Wallpaper Engine",
            relativePath = "projects/minimal_clock/index.html",
            sizeBytes = 6_200_000L,
            modifiedAt = 1_760_500_000_000L,
            mimeType = "text/html",
            detectionReason = "HTML project",
            tags = setOf("极简"),
        ),
    )

