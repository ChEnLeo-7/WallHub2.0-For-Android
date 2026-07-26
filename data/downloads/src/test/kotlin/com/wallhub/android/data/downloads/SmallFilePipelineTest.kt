package com.wallhub.android.data.downloads

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class SmallFilePipelineTest {
    @Test
    fun `only small short chunk files enter the file pipeline`() {
        assertTrue(isSmallFilePipelineCandidate(SMALL_FILE_PIPELINE_MAX_BYTES, 2))
        assertTrue(isSmallFilePipelineCandidate(0L, 0))
        assertTrue(!isSmallFilePipelineCandidate(SMALL_FILE_PIPELINE_MAX_BYTES + 1L, 1))
        assertTrue(!isSmallFilePipelineCandidate(32L, SMALL_FILE_PIPELINE_MAX_CHUNKS + 1))
    }

    @Test
    fun `small file batch size uses the existing chunk concurrency budget`() {
        assertEquals(1, smallFilePipelineBatchSize(0))
        assertEquals(8, smallFilePipelineBatchSize(8))
        assertEquals(32, smallFilePipelineBatchSize(32))
    }

    @Test
    fun `parallel file progress remains monotonic and complete`() = runTest {
        val events = mutableListOf<SteamDownloadProgress>()
        val reporter = DownloadProgressReporter(
            totalBytes = 50L,
            totalFiles = 2,
            onProgress = events::add,
        )

        listOf(
            async { reporter.addDownloadedBytes("audio/one.wav", 20L) },
            async { reporter.addDownloadedBytes("audio/two.wav", 30L) },
        ).awaitAll()
        listOf(
            async { reporter.markFileCompleted("audio/one.wav") },
            async { reporter.markFileCompleted("audio/two.wav") },
        ).awaitAll()

        val snapshot = reporter.snapshot()
        assertEquals(50L, snapshot.downloadedBytes)
        assertEquals(2, snapshot.completedFiles)
        assertTrue(events.zipWithNext().all { (before, after) ->
            before.completedBytes <= after.completedBytes &&
                before.completedFiles <= after.completedFiles
        })
    }
}
