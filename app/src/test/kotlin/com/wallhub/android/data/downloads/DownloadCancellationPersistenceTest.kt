package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two decisions that keep a cancelled download cancelled.
 *
 * The cancel path writes the request and starts a run that observes it, acknowledges it, and clears
 * it. The run that was actually interrupted therefore has to read an already-acknowledged CANCELLED
 * row as the user's cancel, and no run may publish progress over that terminal row. Getting either
 * decision wrong is what let a cancelled item go back to reading as downloading.
 */
class DownloadCancellationPersistenceTest {
    @Test
    fun `a pending cancel request is the user's cancel`() {
        assertTrue(
            isUserCancellation(
                currentRequestedAction = DownloadAction.CANCEL.name,
                currentStatus = DownloadStatus.DOWNLOADING.name,
            ),
        )
    }

    @Test
    fun `a cancel already acknowledged by another run is still the user's cancel`() {
        assertTrue(
            isUserCancellation(
                currentRequestedAction = null,
                currentStatus = DownloadStatus.CANCELLED.name,
            ),
        )
    }

    @Test
    fun `a system stop without a cancel is not the user's cancel`() {
        assertFalse(
            isUserCancellation(
                currentRequestedAction = null,
                currentStatus = DownloadStatus.DOWNLOADING.name,
            ),
        )
        assertFalse(isUserCancellation(currentRequestedAction = null, currentStatus = null))
    }

    @Test
    fun `a pause request is not the user's cancel`() {
        assertFalse(
            isUserCancellation(
                currentRequestedAction = DownloadAction.PAUSE.name,
                currentStatus = DownloadStatus.DOWNLOADING.name,
            ),
        )
    }

    @Test
    fun `progress may not be published over an acknowledged cancellation`() {
        listOf(
            DownloadStatus.DOWNLOADING,
            DownloadStatus.QUEUED,
            DownloadStatus.RESOLVING,
            DownloadStatus.FAILED,
        ).forEach { next ->
            assertFalse(
                "a $next write must not replace an acknowledged cancellation",
                mayPublishDownloadProgress(currentStatus = DownloadStatus.CANCELLED.name, nextStatus = next),
            )
        }
    }

    @Test
    fun `writing the cancellation itself stays allowed`() {
        assertTrue(
            mayPublishDownloadProgress(
                currentStatus = DownloadStatus.CANCELLED.name,
                nextStatus = DownloadStatus.CANCELLED,
            ),
        )
    }

    @Test
    fun `progress is published over a row that has not been cancelled`() {
        assertTrue(
            mayPublishDownloadProgress(
                currentStatus = DownloadStatus.DOWNLOADING.name,
                nextStatus = DownloadStatus.DOWNLOADING,
            ),
        )
        assertTrue(
            mayPublishDownloadProgress(
                currentStatus = DownloadStatus.DOWNLOADING.name,
                nextStatus = DownloadStatus.CANCELLED,
            ),
        )
        assertTrue(mayPublishDownloadProgress(currentStatus = null, nextStatus = DownloadStatus.QUEUED))
    }
}
