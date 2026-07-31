package com.wallhub.android.data.downloads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundFirstPermitPoolTest {
    @Test
    fun `foreground stream reads retain a reserved permit and pass queued prefetch`() =
        runTest {
            val pool = ForegroundFirstPermitPool(maxPermits = 3)
            val firstPrefetchRelease = CompletableDeferred<Unit>()
            val secondPrefetchRelease = CompletableDeferred<Unit>()
            val foregroundRelease = CompletableDeferred<Unit>()
            val queuedForegroundRelease = CompletableDeferred<Unit>()
            val queuedPrefetchRelease = CompletableDeferred<Unit>()
            val queuedForegroundStarted = CompletableDeferred<Unit>()
            val queuedPrefetchStarted = CompletableDeferred<Unit>()

            val firstPrefetch =
                async(start = CoroutineStart.UNDISPATCHED) {
                    pool.withPermit(SteamStreamChunkPriority.PREFETCH) { firstPrefetchRelease.await() }
                }
            val secondPrefetch =
                async(start = CoroutineStart.UNDISPATCHED) {
                    pool.withPermit(SteamStreamChunkPriority.PREFETCH) { secondPrefetchRelease.await() }
                }
            val queuedPrefetch =
                async(start = CoroutineStart.UNDISPATCHED) {
                    pool.withPermit(SteamStreamChunkPriority.PREFETCH) {
                        queuedPrefetchStarted.complete(Unit)
                        queuedPrefetchRelease.await()
                    }
                }
            val foreground =
                async(start = CoroutineStart.UNDISPATCHED) {
                    pool.withPermit(SteamStreamChunkPriority.FOREGROUND) { foregroundRelease.await() }
                }
            val queuedForeground =
                async(start = CoroutineStart.UNDISPATCHED) {
                    pool.withPermit(SteamStreamChunkPriority.FOREGROUND) {
                        queuedForegroundStarted.complete(Unit)
                        queuedForegroundRelease.await()
                    }
                }

            advanceUntilIdle()
            assertFalse(queuedPrefetchStarted.isCompleted)
            assertFalse(queuedForegroundStarted.isCompleted)

            foregroundRelease.complete(Unit)
            advanceUntilIdle()
            assertTrue(queuedForegroundStarted.isCompleted)
            assertFalse(queuedPrefetchStarted.isCompleted)

            queuedForegroundRelease.complete(Unit)
            advanceUntilIdle()
            assertTrue(queuedPrefetchStarted.isCompleted)

            firstPrefetchRelease.complete(Unit)
            secondPrefetchRelease.complete(Unit)
            queuedPrefetchRelease.complete(Unit)
            firstPrefetch.await()
            secondPrefetch.await()
            foreground.await()
            queuedForeground.await()
            queuedPrefetch.await()
        }
}
