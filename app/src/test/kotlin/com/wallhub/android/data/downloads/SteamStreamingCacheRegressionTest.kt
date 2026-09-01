package com.wallhub.android.data.downloads

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import `in`.dragonbra.javasteam.util.Adler32 as SteamAdler32

class SteamStreamingCacheRegressionTest {
    @Test
    fun `foreground demand can identify and replace an active prefetch permit`() =
        runTest {
            val permits = ForegroundFirstPermitPool(maxPermits = 1)
            val keepPrefetchActive = CompletableDeferred<Unit>()
            val prefetchStarted = CompletableDeferred<Unit>()
            val foregroundStarted = CompletableDeferred<Unit>()
            val prefetch =
                async(start = CoroutineStart.UNDISPATCHED) {
                    permits.withPermit(SteamStreamChunkPriority.PREFETCH, requestId = 41L) {
                        prefetchStarted.complete(Unit)
                        keepPrefetchActive.await()
                    }
                }
            prefetchStarted.await()

            assertEquals(41L, permits.activePrefetchRequestId())
            val foreground =
                async {
                    permits.withPermit(SteamStreamChunkPriority.FOREGROUND, requestId = 42L) {
                        foregroundStarted.complete(Unit)
                    }
                }
            assertFalse(foregroundStarted.isCompleted)

            prefetch.cancelAndJoin()
            foreground.await()
            assertTrue(foregroundStarted.isCompleted)
            assertEquals(null, permits.activePrefetchRequestId())
        }

    @Test
    fun `cache eviction preserves the protected playback window`() =
        runTest {
            val root = Files.createTempDirectory("steam-stream-protected-lru-test").toFile()
            val cache = SteamVideoStreamCache(root, "video-a", 1_000L)
            try {
                val first = ByteArray(400) { 1 }
                val ahead = ByteArray(400) { 2 }
                val refill = ByteArray(400) { 3 }
                cache.commit(0L, SteamAdler32.calculate(first), first)
                Thread.sleep(5L)
                cache.commit(400L, SteamAdler32.calculate(ahead), ahead)
                cache.protectChunkOffsets(listOf(400L))
                Thread.sleep(5L)
                cache.readSlice(0L, first.size, SteamAdler32.calculate(first), 0, 16)
                Thread.sleep(5L)

                cache.commit(800L, SteamAdler32.calculate(refill), refill)

                assertTrue(root.resolve("video-a/400.chunk").isFile)
                assertContentEquals(
                    ahead.copyOfRange(0, 16),
                    cache.readSlice(400L, ahead.size, SteamAdler32.calculate(ahead), 0, 16),
                )
                assertTrue(
                    root.walkTopDown().filter { it.isFile && it.name.endsWith(".chunk") }.sumOf(File::length) <= 1_000L,
                )
            } finally {
                cache.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun `manual clear keeps active playback chunks until the session closes`() =
        runTest {
            val root = Files.createTempDirectory("steam-stream-protected-clear-test").toFile()
            val cache = SteamVideoStreamCache(root, "video-a", 4_096L)
            try {
                val data = ByteArray(1_024) { 4 }
                cache.commit(0L, SteamAdler32.calculate(data), data)
                cache.protectChunkOffsets(listOf(0L))

                assertEquals(0L, SteamVideoStreamCache.clearRoot(root))
                assertTrue(root.resolve("video-a/0.chunk").isFile)

                cache.close()
                assertEquals(data.size.toLong(), SteamVideoStreamCache.clearRoot(root))
                assertFalse(root.resolve("video-a/0.chunk").exists())
            } finally {
                cache.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun `encrypted staging reservations apply byte backpressure`() =
        runTest {
            val root = Files.createTempDirectory("steam-stream-staging-gate-test").toFile()
            val cache = SteamVideoStreamCache(root, "video-a", 1_000L)
            try {
                val first = cache.reserveEncrypted(150)
                val waiting =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        cache.reserveEncrypted(100)
                    }

                assertFalse(waiting.isCompleted)
                first.release()
                waiting.await().release()
            } finally {
                cache.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun `memory pipeline leaves the full cache limit available for decoded playback chunks`() {
        val root = Files.createTempDirectory("steam-stream-memory-pipeline-test").toFile()
        val cache =
            SteamVideoStreamCache(
                rootDirectory = root,
                namespace = "video-a",
                limitBytes = 1_000L,
                maximumEncryptedChunkBytes = 800L,
                minimumStagingCapacityBytes = 1_000L,
                stagingEnabled = false,
            )
        try {
            assertEquals(1_000L, cache.prefetchCapacityBytes)
        } finally {
            cache.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `adaptive buffer uses conservative throughput to choose the Webview target`() {
        val mib = 1024L * 1024L
        val policy = SteamStreamAdaptiveBufferPolicy(600L * mib, 512L * mib)
        repeat(3) { index ->
            policy.recordTransfer(20 * mib.toInt(), 1_000_000_000L, (index + 1L) * 1_000_000_000L)
        }

        val decision =
            policy.evaluate(
                readPosition = 20L * mib,
                playbackPositionMs = 1_000L,
                bufferedPositionMs = 3_000L,
                durationMs = 60_000L,
                playbackSpeed = 1f,
            )

        assertEquals(12_000L, decision.targetDurationMs)
        assertEquals(120L * mib, decision.targetBytes)
        assertFalse(decision.bandwidthLimited)
    }

    @Test
    fun `sustained throughput below original consumption reports bandwidth pressure`() {
        val mib = 1024L * 1024L
        val policy = SteamStreamAdaptiveBufferPolicy(600L * mib, 512L * mib)
        repeat(3) { index ->
            policy.recordTransfer(5 * mib.toInt(), 1_000_000_000L, (index + 1L) * 1_000_000_000L)
        }

        repeat(2) {
            assertFalse(
                policy.evaluate(10L * mib, 1_000L, 2_000L, 60_000L, 1f).bandwidthLimited,
            )
        }
        assertTrue(
            policy.evaluate(10L * mib, 1_000L, 2_000L, 60_000L, 1f).bandwidthLimited,
        )
    }

    @Test
    fun `adaptive buffer retains interleaved byte and media time samples`() {
        val mib = 1024L * 1024L
        val policy = SteamStreamAdaptiveBufferPolicy(100L * mib, 100L * mib)

        policy.evaluate(0L, 0L, 0L, 10_000L, 1f)
        policy.evaluate(20L * mib, 0L, 0L, 10_000L, 1f)
        val decision = policy.evaluate(20L * mib, 0L, 1_000L, 10_000L, 1f)

        assertTrue(decision.requiredBytesPerSecond > 13.5 * mib.toDouble())
    }

    @Test
    fun `staging capacity covers one full user concurrency window`() {
        assertEquals(70L, steamStreamStagingWindowBytes(listOf(10, 20, 30, 40), concurrency = 2))
        assertEquals(100L, steamStreamStagingWindowBytes(listOf(10, 20, 30, 40), concurrency = 48))
        assertEquals(140L, steamStreamStagingPipelineBytes(listOf(10, 20, 30, 40), concurrency = 2))
    }

    @Test
    fun `stream chunk pipeline budget matches Webview uncompressed byte accounting`() {
        assertEquals(
            1L * 1024L * 1024L,
            steamStreamChunkPipelineBytes(1 * 1024 * 1024, 1 * 1024 * 1024),
        )
        assertEquals(
            8L * 1024L * 1024L,
            steamStreamChunkPipelineBytes(4 * 1024 * 1024, 8 * 1024 * 1024),
        )
    }

    @Test
    fun `decode workers follow configured concurrency and processor capacity`() {
        assertEquals(2, steamStreamDecodeThreads(configuredConcurrency = 2, availableProcessors = 8))
        assertEquals(8, steamStreamDecodeThreads(configuredConcurrency = 12, availableProcessors = 8))
        assertEquals(8, steamStreamDecodeThreads(configuredConcurrency = 32, availableProcessors = 16))
        assertEquals(2, steamStreamDecodeThreads(configuredConcurrency = 32, availableProcessors = 2))
    }

    @Test
    fun `initial prefetch prioritizes head tail metadata and contiguous startup bytes`() {
        val mib = 1024L * 1024L
        assertEquals(
            SteamInitialPrefetchPlan(
                first = SteamStreamByteRange(0L, 2L * mib - 1L),
                tail = SteamStreamByteRange(92L * mib, 100L * mib - 1L),
                initial = SteamStreamByteRange(2L * mib, 16L * mib - 1L),
            ),
            steamInitialPrefetchPlan(100L * mib),
        )
    }

    @Test
    fun `missing range extends real coverage to the adaptive target`() {
        val mib = 1024L * 1024L
        assertEquals(
            SteamStreamByteRange(
                start = 17L * mib,
                endInclusive = 40L * mib - 1L,
            ),
            steamStreamMissingRange(
                contentLength = 100L * mib,
                consumedPosition = 10L * mib,
                bufferedEndInclusive = 17L * mib - 1L,
                targetAheadBytes = 30L * mib,
            ),
        )
        assertEquals(
            null,
            steamStreamMissingRange(
                contentLength = 100L * mib,
                consumedPosition = 10L * mib,
                bufferedEndInclusive = 40L * mib - 1L,
                targetAheadBytes = 30L * mib,
            ),
        )
    }
}
