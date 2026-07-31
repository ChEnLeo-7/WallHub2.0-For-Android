package com.wallhub.android.feature.downloads

import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.DownloadTaskRepository
import com.wallhub.android.core.model.WorkshopType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class DownloadsStateSourceTest {
    @Test
    fun `state source filters fake repository tasks without room worker or steam`() =
        runTest {
            val source =
                DownloadsStateSource(
                    FakeDownloadTaskRepository(
                        listOf(
                            task("running", DownloadStatus.DOWNLOADING),
                            task("finished", DownloadStatus.COMPLETED),
                        ),
                    ),
                )

            source.setFilter(DownloadFilter.COMPLETED)
            source.setTypeFilter(DownloadTypeFilter.VIDEO)

            assertEquals(
                listOf("finished"),
                source.states
                    .first()
                    .tasks
                    .map(DownloadTask::id),
            )
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

    private class FakeDownloadTaskRepository(
        tasks: List<DownloadTask>,
    ) : DownloadTaskRepository {
        private val state = MutableStateFlow(tasks)

        override val tasks: Flow<List<DownloadTask>> = state

        override suspend fun find(taskId: String): DownloadTask? = state.value.firstOrNull { it.id == taskId }

        override suspend fun upsert(task: DownloadTask) {
            state.value = state.value.filterNot { it.id == task.id } + task
        }

        override suspend fun enqueue(request: DownloadRequest): DownloadTask =
            DownloadTask(
                id = request.workshopId.toString(),
                workshopId = request.workshopId,
                title = request.title,
                type = request.type,
                status = DownloadStatus.QUEUED,
            )

        override suspend fun requestAction(
            taskId: String,
            action: DownloadAction,
        ) = Unit

        override suspend fun reorder(taskIds: List<String>) = Unit

        override suspend fun clearFinishedHistory(): Int = 0
    }
}
