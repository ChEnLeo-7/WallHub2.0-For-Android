package com.wallhub.android.feature.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * "Check again" must not become a probe storm.
 *
 * Each tap used to start another six-request pass while the previous one was still timing out, and the
 * page could publish a snapshot from the pass it had already abandoned. One coordinator owns the check, so
 * a newer tap supersedes the older pass instead of racing it, and leaving the page cancels the pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupNetworkCheckCoordinatorTest {
    @Test
    fun `a newer tap cancels the pass that is still running`() =
        runTest {
            val coordinator = SetupNetworkCheckCoordinator(TestScope(testScheduler))
            val firstStarted = CompletableDeferred<Unit>()
            val firstFinished = CompletableDeferred<Unit>()
            val finished = mutableListOf<String>()

            coordinator.launch {
                firstStarted.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                    finished += "first"
                } finally {
                    firstFinished.complete(Unit)
                }
            }
            advanceUntilIdle()
            assertTrue(firstStarted.isCompleted)

            coordinator.launch { finished += "second" }
            advanceUntilIdle()

            assertTrue(firstFinished.isCompleted, "the superseded pass was never cancelled")
            assertEquals(listOf("second"), finished)
        }

    @Test
    fun `leaving the page cancels the check and nothing publishes afterwards`() =
        runTest {
            val coordinator = SetupNetworkCheckCoordinator(TestScope(testScheduler))
            val published = mutableListOf<String>()

            coordinator.launch {
                CompletableDeferred<Unit>().await()
                published += "late"
            }
            advanceUntilIdle()
            coordinator.cancel()
            advanceUntilIdle()

            assertTrue(published.isEmpty())
            assertFalse(coordinator.isRunning)
        }

    @Test
    fun `one tap runs one check and leaves nothing running`() =
        runTest {
            val coordinator = SetupNetworkCheckCoordinator(TestScope(testScheduler))
            var runs = 0

            coordinator.launch { runs += 1 }
            advanceUntilIdle()

            assertEquals(1, runs)
            assertFalse(coordinator.isRunning)
        }
}
