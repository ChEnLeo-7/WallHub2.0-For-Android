package com.wallhub.android.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormalWorkshopExportRecoveryTest {
    @Test
    fun `mpkg exports use the canonical package mime type`() {
        assertEquals("application/vnd.mpkg", WorkshopExportMimeTypes.MPKG)
    }

    @Test
    fun `a committed destination is projected instead of exported a second time`() {
        assertEquals(
            ExportRecoveryDecision.PROJECT_COMPLETED,
            decideExportRecovery(
                transactionState = ExportTransactionState.COMMITTED.name,
                target = ExportArtifactSnapshot(exists = true, isPending = false, displayName = "wallpaper.mpkg"),
                expectedOutputName = "wallpaper.mpkg",
            ),
        )
    }

    @Test
    fun `a media store row published before process death is recognized as committed`() {
        assertEquals(
            ExportRecoveryDecision.PROJECT_COMPLETED,
            decideExportRecovery(
                transactionState = ExportTransactionState.WRITING.name,
                target = ExportArtifactSnapshot(exists = true, isPending = false, displayName = "wallpaper.mpkg"),
                expectedOutputName = "wallpaper.mpkg",
            ),
        )
    }

    @Test
    fun `a saf target renamed before process death is recognized as committed`() {
        assertEquals(
            ExportRecoveryDecision.PROJECT_COMPLETED,
            decideExportRecovery(
                transactionState = ExportTransactionState.WRITING.name,
                target = ExportArtifactSnapshot(exists = true, isPending = null, displayName = "wallpaper.mpkg"),
                expectedOutputName = "wallpaper.mpkg",
            ),
        )
    }

    @Test
    fun `an incomplete pending destination is cleaned before export resumes`() {
        assertEquals(
            ExportRecoveryDecision.CLEAN_AND_RESTART,
            decideExportRecovery(
                transactionState = ExportTransactionState.WRITING.name,
                target = ExportArtifactSnapshot(exists = true, isPending = true, displayName = "wallpaper.mpkg"),
                expectedOutputName = "wallpaper.mpkg",
            ),
        )
    }

    @Test
    fun `a missing committed destination is cleaned and regenerated from staging`() {
        assertEquals(
            ExportRecoveryDecision.CLEAN_AND_RESTART,
            decideExportRecovery(
                transactionState = ExportTransactionState.COMMITTED.name,
                target = ExportArtifactSnapshot(exists = false, isPending = null, displayName = null),
                expectedOutputName = "wallpaper.mpkg",
            ),
        )
    }

    @Test
    fun `failed saf restoration keeps the backup reference recoverable`() {
        val transaction =
            ExportTransaction(
                state = ExportTransactionState.WRITING,
                targetUri = "content://tree/pending",
                backupUri = "content://tree/backup",
                outputName = "wallpaper.mpkg",
                mimeType = WorkshopExportMimeTypes.MPKG,
            )

        val failed = transaction.afterRollback(restoredBackup = false)
        assertEquals("content://tree/backup", failed.backupUri)
        assertEquals(ExportTransactionState.WRITING, failed.state)

        val restored = transaction.afterRollback(restoredBackup = true)
        assertFalse(restored.backupUri != null)
        assertEquals(ExportTransactionState.NONE, restored.state)
        assertTrue(restored.targetUri == null)
    }
}
