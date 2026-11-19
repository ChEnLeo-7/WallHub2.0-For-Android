package com.wallhub.android.feature.downloads

import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.WorkshopType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadFailureDetailsTest {
    @Test
    fun `new diagnostic fields take precedence`() {
        val task =
            task(
                status = DownloadStatus.FAILED,
                message = "generic",
                failureStage = "RESOLUTION",
                failureType = "NetworkError",
                failureDetail = "saved reason",
                conversionReportJson = "{\"result\":\"failed\",\"detail\":\"old\"}",
            )

        assertEquals(
            DownloadFailureDetails("RESOLUTION", "NetworkError", "saved reason"),
            task.failureDetails(),
        )
    }

    @Test
    fun `legacy conversion report is used when diagnostic fields are absent`() {
        val task =
            task(
                status = DownloadStatus.FAILED,
                conversionReportJson = "{\"result\":\"failed\",\"reasonCode\":\"format_limit\",\"detail\":\"bad package\"}",
            )

        assertEquals(
            DownloadFailureDetails("CONVERSION", "format_limit", "bad package"),
            task.failureDetails(),
        )
    }

    @Test
    fun `malformed legacy report falls back to saved message`() {
        assertEquals(
            DownloadFailureDetails("DOWNLOAD", "Unknown", "connection lost"),
            task(
                status = DownloadStatus.FAILED,
                message = "connection lost",
                conversionReportJson = "invalid json",
            ).failureDetails(),
        )
    }

    @Test
    fun `non-failed task has no failure details`() {
        assertNull(task(status = DownloadStatus.COMPLETED).failureDetails())
    }

    private fun task(
        status: DownloadStatus,
        message: String? = null,
        failureStage: String? = null,
        failureType: String? = null,
        failureDetail: String? = null,
        conversionReportJson: String? = null,
    ) =
        DownloadTask(
            id = "task",
            workshopId = 1L,
            title = "Wallpaper",
            type = WorkshopType.VIDEO,
            status = status,
            message = message,
            failureStage = failureStage,
            failureType = failureType,
            failureDetail = failureDetail,
            conversionReportJson = conversionReportJson,
        )
}
