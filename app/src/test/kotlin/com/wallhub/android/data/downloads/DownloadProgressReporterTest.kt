package com.wallhub.android.data.downloads

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadProgressReporterTest {
    @Test
    fun firstChunkCommitCallbackRunsOnceWhenChunksCompleteConcurrently() = runBlocking {
        val callbackCount = AtomicInteger()
        val reporter =
            DownloadProgressReporter(
                totalBytes = 100L,
                totalFiles = 1,
                onProgress = {},
                onFirstChunkCommitted = { callbackCount.incrementAndGet() },
            )

        List(8) { async { reporter.markChunkCommitted() } }.awaitAll()

        assertEquals(1, callbackCount.get())
    }
}
