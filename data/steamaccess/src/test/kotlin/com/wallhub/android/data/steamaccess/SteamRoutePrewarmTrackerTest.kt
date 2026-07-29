package com.wallhub.android.data.steamaccess

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteamRoutePrewarmTrackerTest {
    private val key = SteamRoutePrewarmKey(
        networkType = "wifi",
        generation = 0L,
        hostname = "steamcommunity.com",
    )

    @Test
    fun `synchronous completion after refresh request is not missed`() = runBlocking {
        val tracker = SteamRoutePrewarmTracker()

        val result = tracker.awaitCompletionOrInvalidation(key) {
            tracker.complete(key, available = true)
        }

        assertTrue(result == true)
    }

    @Test
    fun `invalidation wakes waiter without completing stale generation`() = runBlocking {
        val tracker = SteamRoutePrewarmTracker()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            tracker.awaitCompletionOrInvalidation(key) {}
        }

        tracker.invalidate(key.generation + 1L)

        assertNull(waiting.await())
    }

    @Test
    fun `failed completion is consumed so retry can request again`() {
        val tracker = SteamRoutePrewarmTracker()
        tracker.complete(key, available = false)

        assertFalse(tracker.result(key) ?: true)
        assertNull(tracker.result(key))
    }

    @Test
    fun `completion from invalidated generation is rejected`() {
        val tracker = SteamRoutePrewarmTracker(initialGeneration = key.generation)
        val nextGeneration = key.generation + 1L

        tracker.invalidate(nextGeneration)
        tracker.complete(key, available = true)

        assertNull(tracker.result(key))
        assertNull(tracker.result(key.copy(generation = nextGeneration)))
    }

    @Test
    fun `completion is isolated by route generation`() {
        val tracker = SteamRoutePrewarmTracker(initialGeneration = key.generation)
        val nextGeneration = key.copy(generation = key.generation + 1L)
        tracker.complete(key, available = true)

        assertEquals(true, tracker.result(key))
        assertNull(tracker.result(nextGeneration))
    }
}
