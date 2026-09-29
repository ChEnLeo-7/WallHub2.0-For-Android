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
 * Until the first durable byte reading arrives the card has no percentage and no measured rate. It shows an
 * empty, still bar and the phase line - not an invented number, and not a sweeping animation either, because
 * that is motion the transfer has not produced.
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
    fun `a running download with no committed bytes has no measured fraction`() {
        // Null is "nothing measured yet": the card draws its bar empty and still until this answers a number.
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
