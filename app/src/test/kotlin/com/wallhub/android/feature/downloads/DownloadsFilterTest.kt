package com.wallhub.android.feature.downloads

import com.wallhub.android.core.model.DownloadCredentialMode
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.prototype.mpkg.ConversionReportJsonCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadsFilterTest {
    private fun task(
        id: String,
        status: DownloadStatus,
    ): DownloadTask =
        DownloadTask(
            id = id,
            workshopId = 1L,
            title = id,
            type = WorkshopType.SCENE,
            status = status,
            credentialMode = DownloadCredentialMode.ANONYMOUS,
        )

    private val allTasks =
        listOf(
            task("queued", DownloadStatus.QUEUED),
            task("resolving", DownloadStatus.RESOLVING),
            task("downloading", DownloadStatus.DOWNLOADING),
            task("paused", DownloadStatus.PAUSED),
            task("converting", DownloadStatus.CONVERTING),
            task("exporting", DownloadStatus.EXPORTING),
            task("failed", DownloadStatus.FAILED),
            task("cancelled", DownloadStatus.CANCELLED),
            task("completed", DownloadStatus.COMPLETED),
        )

    @Test
    fun activeFilterShowsEveryNonCompletedStatusIncludingFailures() {
        val visible = filterTasks(allTasks, DownloadFilter.DOWNLOADING).map { it.id }
        assertEquals(
            listOf("queued", "resolving", "downloading", "paused", "converting", "exporting", "failed", "cancelled"),
            visible,
        )
    }

    @Test
    fun completedFilterShowsOnlyCompletedTasks() {
        assertEquals(listOf("completed"), filterTasks(allTasks, DownloadFilter.COMPLETED).map { it.id })
    }

    @Test
    fun failedFilterShowsFailedAndCancelledTasks() {
        assertEquals(listOf("failed", "cancelled"), filterTasks(allTasks, DownloadFilter.FAILED).map { it.id })
    }

    @Test
    fun unclassifiedTaskStaysVisibleUnderAnyTypeSelection() {
        val unclassified =
            task("unclassified", DownloadStatus.DOWNLOADING).copy(type = WorkshopType.UNKNOWN)

        assertEquals(
            listOf("unclassified"),
            filterTasks(
                listOf(unclassified),
                DownloadFilter.DOWNLOADING,
                setOf(WorkshopType.VIDEO),
            ).map { it.id },
        )
    }

    @Test
    fun typeSelectionStillHidesKnownTypes() {
        assertEquals(
            emptyList<String>(),
            filterTasks(allTasks, DownloadFilter.DOWNLOADING, setOf(WorkshopType.VIDEO)).map { it.id },
        )
    }

    @Test
    fun conversionPresetLabelIsSceneOnly() {
        val scene = task("scene", DownloadStatus.COMPLETED)
        val video = scene.copy(type = WorkshopType.VIDEO)
        val web = scene.copy(type = WorkshopType.WEB)

        assertEquals(true, scene.shouldShowConversionPresetLabel())
        assertEquals(false, video.shouldShowConversionPresetLabel())
        assertEquals(false, web.shouldShowConversionPresetLabel())
        assertEquals(false, scene.copy(exportFormat = ExportFormat.ZIP).shouldShowConversionPresetLabel())
    }

    @Test
    fun warningReportDecodesStableSafeDetails() {
        val report =
            ConversionReportJsonCodec.decodeSummary(
                """{"version":2,"convertedTextures":1,"copiedTextures":2,"removedAudio":0,"rewrittenShaders":3,"warningTotal":2,"warningCounts":{"shader_rewrite_failed":1,"texture_conversion_failed":1},"warningDetails":[{"path":"z/file.frag","code":"shader_rewrite_failed","strategy":"copied"},{"path":"a/file.tex","code":"texture_conversion_failed"}]}""",
            )

        assertEquals(2, report?.warningTotal)
        assertEquals(listOf("a/file.tex", "z/file.frag"), report?.warningDetails?.map { it.path })
        assertEquals("copied", report?.warningDetails?.last()?.strategy)
    }

    @Test
    fun warningReportRejectsAbsoluteAndParentPaths() {
        val absolute = """{"version":2,"warningTotal":1,"warningDetails":[{"path":"/private/file","code":"copy_failed"}]}"""
        val parent = """{"version":2,"warningTotal":1,"warningDetails":[{"path":"../file","code":"copy_failed"}]}"""

        assertEquals(null, ConversionReportJsonCodec.decodeSummary(absolute))
        assertEquals(null, ConversionReportJsonCodec.decodeSummary(parent))
    }

    @Test
    fun completedWarningTaskRemainsInCompletedFilter() {
        val warningTask =
            task("warning", DownloadStatus.COMPLETED).copy(
                conversionReportJson = """{"version":2,"warningTotal":1}""",
            )

        assertEquals(listOf("warning"), filterTasks(listOf(warningTask), DownloadFilter.COMPLETED).map { it.id })
    }

    @Test
    fun pendingControlRequestDisablesAllTaskActions() {
        val task =
            task("pending", DownloadStatus.DOWNLOADING).copy(
                pendingControl = DownloadAction.PAUSE,
            )

        assertEquals(emptySet<DownloadAction>(), task.availableActions)
    }
}
