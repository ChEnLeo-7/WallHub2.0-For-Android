package com.wallhub.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRequestFactoryTest {
    @Test
    fun `quick download request carries every shared MPKG preference`() {
        val item =
            WorkshopSummary(
                id = 42L,
                title = "Remote title",
                author = "Author",
                type = WorkshopType.SCENE,
                previewUrl = "https://example.invalid/preview.jpg",
                fileSizeBytes = 1234L,
            )
        val preferences =
            AppPreferences(
                outputTreeUri = "content://downloads/wallhub",
                mpkgConversionPreset = MpkgConversionPreset.EXTREME_QUALITY,
                preserveAudio = true,
            )

        val request =
            item.toQuickDownloadRequest(
                preferences = preferences,
                localizedTitle = "Localized title",
                taskId = "stable-task",
            )

        assertEquals(42L, request.workshopId)
        assertEquals("Localized title", request.title)
        assertEquals("stable-task", request.taskId)
        assertEquals(ExportFormat.AUTO, request.exportFormat)
        assertEquals(MpkgConversionPreset.EXTREME_QUALITY, request.mpkgConversionPreset)
        assertTrue(request.preserveAudio)
        assertEquals("content://downloads/wallhub", request.outputTreeUri)
    }

    @Test
    fun `quick download request accepts the caller output directory snapshot`() {
        val item = WorkshopSummary(id = 7L, title = "Wallpaper", author = "Author", type = WorkshopType.SCENE)

        val request =
            item.toQuickDownloadRequest(
                preferences = AppPreferences(outputTreeUri = "content://stale"),
                localizedTitle = "Wallpaper",
                outputTreeUri = "content://current",
            )

        assertEquals("content://current", request.outputTreeUri)
    }
}
