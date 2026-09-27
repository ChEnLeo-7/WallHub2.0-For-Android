package com.wallhub.android.feature.downloads

import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.WorkshopType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins what the download card may show while the SDK is transferring.
 *
 * A session-backed download reports no durably committed byte until its run ends, so for the whole
 * transfer the card has no percentage and no measured rate. A determinate 0% bar and a 正在测速
 * (measuring) line are both claims the app cannot support: the card must move without inventing a
 * number, and must fall back to the plain status until it has a real reading.
 */
class DownloadProgressCardTest {
    private fun task(
        status: DownloadStatus,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        bytesPerSecond: Long = 0L,
    ): DownloadTask =
        DownloadTask(
            id = "task-1",
            workshopId = 3799142774L,
            title = "Rhine Lab",
            type = WorkshopType.WEB,
            status = status,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            bytesPerSecond = bytesPerSecond,
        )

    @Test
    fun `a running download with no committed bytes asks for an indeterminate bar`() {
        assertNull(
            downloadProgressFraction(
                task(status = DownloadStatus.DOWNLOADING, totalBytes = 43_388_068L),
            ),
        )
    }

    @Test
    fun `a committed reading drives the bar against the item's total`() {
        assertEquals(
            0.5f,
            downloadProgressFraction(
                task(status = DownloadStatus.DOWNLOADING, downloadedBytes = 5_000L, totalBytes = 10_000L),
            )!!,
            1e-6f,
        )
    }

    @Test
    fun `a task that is not running a transfer keeps its determinate bar`() {
        assertEquals(
            0f,
            downloadProgressFraction(
                task(status = DownloadStatus.QUEUED, totalBytes = 10_000L),
            )!!,
            1e-6f,
        )
        assertEquals(
            0f,
            downloadProgressFraction(task(status = DownloadStatus.PAUSED))!!,
            1e-6f,
        )
    }

    @Test
    fun `an unmeasured rate has no rate line`() {
        assertNull(
            measuredDownloadSpeed(
                task(status = DownloadStatus.DOWNLOADING, totalBytes = 43_388_068L),
            ),
        )
    }

    @Test
    fun `a measured rate is shown while the transfer runs`() {
        assertEquals(
            2_048L,
            measuredDownloadSpeed(
                task(status = DownloadStatus.DOWNLOADING, downloadedBytes = 1_024L, bytesPerSecond = 2_048L),
            ),
        )
    }

    @Test
    fun `a rate left behind by a finished transfer is not shown`() {
        assertNull(
            measuredDownloadSpeed(
                task(
                    status = DownloadStatus.COMPLETED,
                    downloadedBytes = 10_000L,
                    totalBytes = 10_000L,
                    bytesPerSecond = 2_048L,
                ),
            ),
        )
    }
}
