package com.wallhub.android.data.downloads

import android.content.ContextWrapper
import com.wallhub.android.core.database.FormalTaskRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FormalDownloadProgressProjectorTest {
    @Test
    fun `dependency projections share one monotonic persistence cadence`() = runTest {
        var now = 800L
        val writes = mutableListOf<ProgressProjectionWrite>()
        val task = task()
        val projector =
            FormalDownloadProgressProjector(
                context = ContextWrapper(null),
                initialTask = task,
                durableCheckpointBytes = 0,
                authenticated = false,
                timingContext = DownloadTimingContext(taskId = task.taskId, workshopId = task.workshopId),
                nowMs = { now },
            ) { previous, write ->
                writes += write
                previous.copy(
                    downloadedBytes = write.downloadedBytes,
                    totalBytes = write.totalBytes,
                    updatedAt = now,
                )
            }

        projector.acceptDependency(100, 200, 10, 100, "dependency")
        now = 1_000L
        projector.acceptDependency(100, 200, 20, 100, "dependency")
        now = 1_100L
        projector.acceptDependency(100, 200, 100, 100, "dependency")

        assertEquals(2, writes.size)
        assertEquals(110L, writes.first().downloadedBytes)
        assertEquals(200L, writes.last().downloadedBytes)
        assertEquals(300L, writes.last().totalBytes)
    }

    @Test
    fun `automatic attempt phases never regress already persisted durable bytes`() = runTest {
        var now = 1_000L
        val writes = mutableListOf<ProgressProjectionWrite>()
        val task = task().copy(downloadedBytes = 80L, totalBytes = 100L)
        val projector =
            FormalDownloadProgressProjector(
                context = ContextWrapper(null),
                initialTask = task,
                durableCheckpointBytes = 0L,
                authenticated = true,
                timingContext = DownloadTimingContext(taskId = task.taskId, workshopId = task.workshopId),
                nowMs = { now },
            ) { previous, write ->
                writes += write
                previous.copy(downloadedBytes = write.downloadedBytes, totalBytes = write.totalBytes)
            }

        projector.accept(SteamDownloadProgress(SteamDownloadPhase.DOWNLOADING, completedBytes = 20L, totalBytes = 100L))
        now += 1_000L
        projector.accept(SteamDownloadProgress(SteamDownloadPhase.VERIFYING, completedBytes = 70L, totalBytes = 100L))

        assertEquals(listOf(80L, 80L), writes.map { it.downloadedBytes })
        assertEquals(80L, projector.task.downloadedBytes)
    }

    private fun task() =
        FormalTaskRecordEntity(
            taskId = "task",
            workshopId = 1,
            title = "task",
            type = "scene",
            status = "DOWNLOADING",
            createdAt = 1,
            updatedAt = 1,
        )
}
