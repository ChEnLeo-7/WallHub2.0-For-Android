package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.WorkshopType
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRetryRoutingTest {
    @Test
    fun completeDownloadRetriesConversion() {
        val directory = Files.createTempDirectory("wallhub-complete").toFile()
        try {
            directory.resolve("project.json").writeText("{}")

            assertTrue(task(directory.path, downloadedBytes = 10L, totalBytes = 10L).hasCompleteStagingDownload())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun emptyOrPartialDownloadRetriesDownload() {
        val directory = Files.createTempDirectory("wallhub-partial").toFile()
        try {
            assertFalse(task(directory.path, downloadedBytes = 0L, totalBytes = 10L).hasCompleteStagingDownload())

            directory.resolve("video.mp4.wallhub.part").writeText("partial")
            assertFalse(task(directory.path, downloadedBytes = 10L, totalBytes = 10L).hasCompleteStagingDownload())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun task(
        stagingDirectory: String,
        downloadedBytes: Long,
        totalBytes: Long,
    ) = DownloadTask(
        id = "task",
        workshopId = 1L,
        title = "Workshop",
        type = WorkshopType.UNKNOWN,
        status = DownloadStatus.FAILED,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        stagingDirectory = stagingDirectory,
    )
}
