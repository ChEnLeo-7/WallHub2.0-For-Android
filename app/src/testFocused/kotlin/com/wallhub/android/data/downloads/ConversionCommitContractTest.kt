package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.prototype.mpkg.ConversionReportJsonCodec
import com.wallhub.prototype.mpkg.WorkshopConversionReport
import com.wallhub.prototype.mpkg.WorkshopKind
import java.io.File
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversionCommitContractTest {
    @Test
    fun `video and web successful reports are persistable before publication`() {
        listOf(WorkshopKind.VIDEO, WorkshopKind.WEB).forEach { kind ->
            val encoded = ConversionReportJsonCodec.encode(WorkshopConversionReport(kind, File("output.${kind.outputExtension}")))

            assertTrue(encoded.isNotBlank(), "$kind must produce durable successful report metadata")
        }
    }

    @Test
    fun `completed export recovery does not repeat exporting publication CAS`() {
        assertFalse(requiresRecoveredPublicationCommit(DownloadStatus.COMPLETED.name))
        assertTrue(requiresRecoveredPublicationCommit(DownloadStatus.EXPORTING.name))
    }
}
