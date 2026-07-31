package com.wallhub.android.feature.downloads

import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.WorkshopType
import org.junit.Test
import kotlin.test.assertEquals

class DownloadFilterTest {
    @Test
    fun `downloading filter keeps all active tasks`() {
        val active =
            listOf(
                task("queued", DownloadStatus.QUEUED),
                task("paused", DownloadStatus.PAUSED),
                task("converting", DownloadStatus.CONVERTING),
                task("exporting", DownloadStatus.EXPORTING),
            )
        val all =
            active +
                listOf(
                    task("completed", DownloadStatus.COMPLETED),
                    task("failed", DownloadStatus.FAILED),
                )

        assertEquals(active.map(DownloadTask::id), filterTasks(all, DownloadFilter.DOWNLOADING).map(DownloadTask::id))
    }

    private fun task(
        id: String,
        status: DownloadStatus,
    ) = DownloadTask(
        id = id,
        workshopId = 1L,
        title = id,
        type = WorkshopType.VIDEO,
        status = status,
    )
}
