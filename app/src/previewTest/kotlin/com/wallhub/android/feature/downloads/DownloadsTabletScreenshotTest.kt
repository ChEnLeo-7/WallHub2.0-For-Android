package com.wallhub.android.feature.downloads

import androidx.compose.runtime.Composable
import app.cash.paparazzi.Paparazzi
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.testing.PadLandscapeAdaptiveContent
import com.wallhub.android.testing.PadLandscapeDevice
import com.wallhub.android.testing.PadTopLevelPreview
import org.junit.Rule
import org.junit.Test

class DownloadsTabletSquareScreenshotTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = PadLandscapeDevice,
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun expandedOneToOneViewDark() {
        paparazzi.snapshot(name = "wallhub-downloads-tablet-1x1-view") {
            DownloadsTabletPreview(initialWideView = false)
        }
    }
}

class DownloadsTabletWideScreenshotTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = PadLandscapeDevice,
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun expandedSixteenToNineViewDark() {
        paparazzi.snapshot(name = "wallhub-downloads-tablet-16x9-view") {
            DownloadsTabletPreview(initialWideView = true)
        }
    }
}

@Composable
private fun DownloadsTabletPreview(initialWideView: Boolean) {
    WallHubTheme(
        preference = ThemePreference.DARK,
        useSystemMonet = false,
        visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
    ) {
        PadLandscapeAdaptiveContent {
            PadTopLevelPreview(selectedLabel = "下载") {
                DownloadsScreen(
                    state =
                        DownloadsUiState(
                            filter = DownloadFilter.DOWNLOADING,
                            allTasks = tabletDownloads,
                        ),
                    onAction = {},
                    initialWideView = initialWideView,
                )
            }
        }
    }
}

private val tabletDownloads =
    listOf(
        DownloadTask(
            id = "download-1",
            workshopId = 1L,
            title = "雨夜列车",
            type = WorkshopType.VIDEO,
            status = DownloadStatus.DOWNLOADING,
            downloadedBytes = 420_000_000L,
            totalBytes = 1_000_000_000L,
            liveNetworkBytesPerSecond = 12_000_000L,
        ),
        DownloadTask(
            id = "download-2",
            workshopId = 2L,
            title = "山谷晨雾",
            type = WorkshopType.SCENE,
            status = DownloadStatus.PAUSED,
            downloadedBytes = 310_000_000L,
            totalBytes = 780_000_000L,
        ),
        DownloadTask(
            id = "download-3",
            workshopId = 3L,
            title = "轨道空间站",
            type = WorkshopType.WEB,
            status = DownloadStatus.RESOLVING,
            downloadedBytes = 96_000_000L,
            totalBytes = 640_000_000L,
        ),
        DownloadTask(
            id = "download-4",
            workshopId = 4L,
            title = "像素海岸",
            type = WorkshopType.SCENE,
            status = DownloadStatus.FAILED,
            downloadedBytes = 170_000_000L,
            totalBytes = 520_000_000L,
            message = "网络连接中断",
        ),
    )

