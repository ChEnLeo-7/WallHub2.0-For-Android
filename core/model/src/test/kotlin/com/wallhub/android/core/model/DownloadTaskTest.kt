package com.wallhub.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTaskTest {
    @Test
    fun `paused task exposes resume and cancel actions`() {
        val task = DownloadTask(
            id = "task",
            workshopId = 1L,
            title = "Wallpaper",
            type = WorkshopType.VIDEO,
            status = DownloadStatus.PAUSED,
            downloadedBytes = 50L,
            totalBytes = 100L,
        )

        assertEquals(0.5f, task.progress)
        assertTrue(DownloadAction.RESUME in task.availableActions)
        assertTrue(DownloadAction.CANCEL in task.availableActions)
    }

    @Test
    fun `completed private staging task exposes export action`() {
        val task = DownloadTask(
            id = "task",
            workshopId = 1L,
            title = "Wallpaper",
            type = WorkshopType.SCENE,
            status = DownloadStatus.COMPLETED,
            stagingDirectory = "/data/user/0/com.wallhub.android/files/wallhub-workshop/task",
        )

        assertTrue(DownloadAction.EXPORT in task.availableActions)
    }

    @Test
    fun `conversion task only exposes cancellation`() {
        val task = DownloadTask(
            id = "task",
            workshopId = 1L,
            title = "Wallpaper",
            type = WorkshopType.SCENE,
            status = DownloadStatus.CONVERTING,
        )

        assertEquals(setOf(DownloadAction.CANCEL), task.availableActions)
    }
}
