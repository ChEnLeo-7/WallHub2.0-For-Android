package com.wallhub.android.feature.downloads

import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BulkDownloadActionTest {
    @Test
    fun `active task makes bulk action pause even when another task is paused`() {
        assertEquals(
            DownloadAction.PAUSE,
            selectBulkDownloadAction(
                listOf(
                    task("active", DownloadStatus.DOWNLOADING),
                    task("paused", DownloadStatus.PAUSED),
                ),
            ),
        )
    }

    @Test
    fun `only paused actionable tasks makes bulk action start`() {
        assertEquals(
            DownloadAction.RESUME,
            selectBulkDownloadAction(
                listOf(
                    task("paused-1", DownloadStatus.PAUSED),
                    task("paused-2", DownloadStatus.PAUSED),
                ),
            ),
        )
    }

    @Test
    fun `pending transport control suppresses bulk action until queue state settles`() {
        assertNull(
            selectBulkDownloadAction(
                listOf(
                    task("pausing", DownloadStatus.DOWNLOADING, pendingControl = DownloadAction.PAUSE),
                    task("paused", DownloadStatus.PAUSED),
                ),
            ),
        )
    }

    @Test
    fun `finished tasks do not expose a bulk transport action`() {
        assertNull(selectBulkDownloadAction(listOf(task("done", DownloadStatus.COMPLETED))))
    }

    private fun task(
        id: String,
        status: DownloadStatus,
        pendingControl: DownloadAction? = null,
    ) =
        DownloadTask(
            id = id,
            workshopId = id.hashCode().toLong(),
            title = id,
            type = com.wallhub.android.core.model.WorkshopType.VIDEO,
            status = status,
            pendingControl = pendingControl,
        )
}
