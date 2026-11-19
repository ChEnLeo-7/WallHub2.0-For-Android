package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.WorkshopType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFailureDiagnosticTest {
    @Test
    fun `failure diagnostic preserves stage type and sanitized cause chain`() {
        val error =
            IllegalStateException("authorization=secret-token", IllegalArgumentException("disk full"))

        val diagnostic = downloadFailureDiagnostic(DownloadStatus.RESOLVING.name, error)

        assertEquals("RESOLUTION", diagnostic.stage)
        assertEquals("IllegalStateException", diagnostic.type)
        assertTrue(diagnostic.detail.contains("IllegalStateException"))
        assertTrue(diagnostic.detail.contains("IllegalArgumentException"))
        assertFalse(diagnostic.detail.contains("secret-token"))
        assertTrue(diagnostic.detail.contains("[REDACTED]"))
    }

    @Test
    fun `conversion classification preserves actual exception type and sanitized causes`() {
        val failure = classifyConversionFailure(
            IllegalStateException("token=secret", java.io.IOException("disk full")),
        )

        assertEquals("project_invalid", failure.reasonCode)
        assertEquals("IllegalStateException", failure.exceptionType)
        assertTrue(failure.fallbackMessage.contains("IOException: disk full"))
        assertFalse(failure.fallbackMessage.contains("token=secret"))
    }

    @Test
    fun `diagnostic bounds cyclic cause chains and long messages`() {
        val outer = IllegalStateException("outer")
        val inner = IllegalArgumentException("inner", outer)
        outer.initCause(inner)
        val cyclic = downloadFailureDiagnostic("UNKNOWN", outer)
        assertEquals(8, cyclic.detail.split(" <- ").size)
        assertEquals("DOWNLOAD", cyclic.stage)

        val long = downloadFailureDiagnostic("QUEUED", IllegalStateException("x".repeat(5_000)))
        assertEquals(4_000, long.detail.length)
        assertEquals("QUEUE", long.stage)
    }

    @Test
    fun `saved diagnostics survive entity model round trip`() {
        val task = DownloadTask(
            id = "failed-task",
            workshopId = 1L,
            title = "Sample",
            type = WorkshopType.VIDEO,
            status = DownloadStatus.FAILED,
            failureStage = "EXPORT",
            failureType = "IOException",
            failureDetail = "IOException: disk full",
        )

        val restored = task.toEntity().toModel()
        assertEquals(task.failureStage, restored.failureStage)
        assertEquals(task.failureType, restored.failureType)
        assertEquals(task.failureDetail, restored.failureDetail)
    }

    @Test
    fun `failure stage maps transfer and conversion statuses`() {
        assertEquals("TRANSFER_OR_VERIFY", DownloadStatus.DOWNLOADING.name.toFailureStage())
        assertEquals("CONVERSION", DownloadStatus.CONVERTING.name.toFailureStage())
        assertEquals("EXPORT", DownloadStatus.EXPORTING.name.toFailureStage())
    }
}
