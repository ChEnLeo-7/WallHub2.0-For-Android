package com.wallhub.android.data.steamaccess

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SteamRoutePrewarmTrackerTest {
    @Test
    fun `completion for one port does not satisfy another port`() =
        runTest {
            val tracker = SteamRoutePrewarmTracker()
            val httpsKey = SteamRoutePrewarmKey("wifi", 0L, "cmp1-sgp1.steamserver.net", 443)
            val cmKey = SteamRoutePrewarmKey("wifi", 0L, "cmp1-sgp1.steamserver.net", 27020)
            val cmWaiter = async {
                tracker.awaitCompletionOrInvalidation(cmKey) {}
            }

            tracker.complete(httpsKey, true)
            testScheduler.runCurrent()

            assertFalse(cmWaiter.isCompleted)
            assertEquals(true, tracker.result(httpsKey))
            assertNull(tracker.result(cmKey))

            tracker.complete(cmKey, true)
            assertEquals(true, cmWaiter.await())
        }
}
