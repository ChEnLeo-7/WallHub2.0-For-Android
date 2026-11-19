package com.wallhub.android.data.downloads

import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.prototype.mpkg.MpkgInsufficientSpaceException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkManagerRecoveryTest {
    @Test
    fun `committed completed conversion is not scheduled again`() {
        val task = task(status = DownloadStatus.COMPLETED.name, exportState = ExportTransactionState.COMMITTED.name)

        assertEquals(ConversionWorkerCandidateDecision.ALREADY_COMMITTED, decideConversionWorkerCandidate(task))
    }

    @Test
    fun `interrupted conversion and export remain recoverable`() {
        assertEquals(
            ConversionWorkerCandidateDecision.RUN,
            decideConversionWorkerCandidate(task(status = DownloadStatus.CONVERTING.name)),
        )
        assertEquals(
            ConversionWorkerCandidateDecision.RUN,
            decideConversionWorkerCandidate(task(status = DownloadStatus.EXPORTING.name)),
        )
    }

    @Test
    fun `incomplete export artifact is cleaned instead of projected completed`() {
        assertEquals(
            ExportRecoveryDecision.CLEAN_AND_RESTART,
            decideExportRecovery(
                transactionState = ExportTransactionState.WRITING.name,
                target = ExportArtifactSnapshot(exists = false, isPending = null, displayName = null),
                expectedOutputName = "wallpaper.mpkg",
            ),
        )
    }

    @Test
    fun `published content target in writing state is projected completed`() {
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
    fun `backup cleanup retries are bounded`() {
        assertTrue(shouldRetryExportBackupCleanup("content://backup", 0))
        assertTrue(shouldRetryExportBackupCleanup("content://backup", 2))
        assertFalse(shouldRetryExportBackupCleanup("content://backup", 3))
        assertFalse(shouldRetryExportBackupCleanup(null, 0))
    }

    @Test
    fun `direct export recovery never deletes the published backup when target uses the same uri`() {
        val published = "file:///storage/emulated/0/Download/WallHub/wallpaper.mpkg"
        val transaction =
            ExportTransaction(
                state = ExportTransactionState.WRITING,
                targetUri = published,
                backupUri = published,
                outputName = "wallpaper.mpkg",
                mimeType = WorkshopExportMimeTypes.MPKG,
            )

        assertFalse(shouldDeleteInterruptedPublicTarget(transaction))
        assertTrue(
            shouldDeleteInterruptedPublicTarget(
                transaction.copy(targetUri = "$published.wallhub.tmp"),
            ),
        )
    }

    @Test
    fun `direct export space check rejects a target volume that cannot hold the temporary output`() {
        val outputBytes = 128L * 1024L * 1024L

        assertFailsWith<MpkgInsufficientSpaceException> {
            requireDirectExportSpace(outputBytes, outputBytes)
        }
        requireDirectExportSpace(outputBytes, outputBytes + 64L * 1024L * 1024L)
    }

    private fun task(
        status: String,
        exportState: String = ExportTransactionState.NONE.name,
    ): FormalTaskRecordEntity =
        FormalTaskRecordEntity(
            taskId = "focused-recovery-task",
            workshopId = 1L,
            title = "Focused recovery",
            type = "SCENE",
            status = status,
            conversionAttempt = 2L,
            lastSuccessfulAttempt = 1L,
            exportTransactionState = exportState,
            createdAt = 1L,
            updatedAt = 1L,
        )
}
