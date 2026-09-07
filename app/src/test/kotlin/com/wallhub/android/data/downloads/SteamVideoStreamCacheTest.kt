package com.wallhub.android.data.downloads

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SteamVideoStreamCacheTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun chunkBytes(size: Int): ByteArray = ByteArray(size) { index -> (index % 251).toByte() }

    private fun chunkTotal(root: File): Long =
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".chunk") }.sumOf(File::length)

    @Test
    fun reservationBudgetSubtractsTemporaryAndIncomingBytes() {
        assertEquals(700L, SteamVideoStreamCache.reservationAdjustedLimit(1000L, 200L, 100L))
        assertEquals(0L, SteamVideoStreamCache.reservationAdjustedLimit(1000L, 900L, 200L))
        assertEquals(500L, SteamVideoStreamCache.reservationAdjustedLimit(1000L, 0L, 500L))
    }

    @Test
    fun commitsStayWithinConfiguredLimitWithoutProtection(): Unit =
        runBlocking {
            val limit = 2L * 1024L * 1024L
            val root = tempFolder.newFolder("trim-root")
            val cache = SteamVideoStreamCache(root, "ns", limit)
            try {
                repeat(3) { index ->
                    val data = chunkBytes(1024 * 1024)
                    cache.commitVerified(index * 1024L * 1024L, steamAdler32(data), data)
                }
                assertTrue(chunkTotal(root) <= limit)
            } finally {
                cache.close()
            }
        }

    @Test
    fun protectedWorkingSetMayExceedLimitUntilBackgroundSweepTrims(): Unit =
        runBlocking {
            val limit = 4L * 1024L * 1024L
            val highWatermark = (limit * SteamVideoStreamCache.SWEEP_HIGH_WATERMARK_RATIO).toLong()
            val target = (limit * SteamVideoStreamCache.SWEEP_TARGET_WATERMARK_RATIO).toLong()
            val root = tempFolder.newFolder("sweep-root")
            val cache = SteamVideoStreamCache(root, "ns", limit, sweepDebounceMs = 0L)
            try {
                val data = chunkBytes(1280 * 1024)
                val base = 10L * 1024L * 1024L
                cache.protectChunkOffsets(listOf(base, base + 10_000_000L))
                cache.commitVerified(base, steamAdler32(data), data)
                cache.commitVerified(base + 10_000_000L, steamAdler32(data), data)
                // The second protected commit cannot evict anything, so the cache
                // crosses the high watermark and schedules the background sweep.
                assertTrue(chunkTotal(root) >= highWatermark)
                cache.protectChunkOffsets(emptySet())
                withTimeout(10_000L) {
                    while (chunkTotal(root) > target) delay(50L)
                }
                assertTrue(chunkTotal(root) in 1..target)
            } finally {
                cache.close()
            }
        }
}
