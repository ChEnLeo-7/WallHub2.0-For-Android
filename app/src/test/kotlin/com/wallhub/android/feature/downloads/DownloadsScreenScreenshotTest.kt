package com.wallhub.android.feature.downloads

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.WorkshopType
import org.junit.Rule
import org.junit.Test

class DownloadsCompactScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, showSystemUi = false)

    @Test
    fun managementDynamicChinese() {
        paparazzi.snapshot { downloadsScreenshotContent() }
    }
}

class DownloadsMediumScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_7, showSystemUi = false)

    @Test
    fun managementDynamicChinese() {
        paparazzi.snapshot { downloadsScreenshotContent() }
    }
}

class DownloadsScreenScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_10, showSystemUi = false)

    @Test
    fun managementExpandedDynamicChinese() {
        paparazzi.snapshot { downloadsScreenshotContent() }
    }
}

@Composable
private fun downloadsScreenshotContent() {
    WallHubTheme(
        preference = ThemePreference.LIGHT,
        language = AppLanguage.ZH,
        useSystemMonet = true,
    ) {
        DownloadsScreen(
            state = DownloadsUiState(tasks = screenshotDownloads),
            onAction = {},
        )
    }
}

private val screenshotDownloads =
    listOf(
        DownloadTask(
            id = "download-1",
            workshopId = 1L,
            title = "雨夜列车",
            type = WorkshopType.VIDEO,
            status = DownloadStatus.DOWNLOADING,
            downloadedBytes = 420_000_000L,
            totalBytes = 1_000_000_000L,
            bytesPerSecond = 12_000_000L,
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
            status = DownloadStatus.COMPLETED,
            downloadedBytes = 96_000_000L,
            totalBytes = 96_000_000L,
            outputLabel = "Download/WallHub/轨道空间站.zip",
        ),
    )
