package com.wallhub.android.feature.downloads

import com.wallhub.android.core.model.DownloadCredentialMode
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.WorkshopType
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
    fun pendingControlRequestDisablesAllTaskActions() {
        val task =
            task("pending", DownloadStatus.DOWNLOADING).copy(
                pendingControl = DownloadAction.PAUSE,
            )

        assertEquals(emptySet<DownloadAction>(), task.availableActions)
    }
}
