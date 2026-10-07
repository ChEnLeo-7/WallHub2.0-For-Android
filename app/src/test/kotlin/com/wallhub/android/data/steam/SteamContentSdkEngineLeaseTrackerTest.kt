package com.wallhub.android.data.steam

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamContentSdkEngineLeaseTrackerTest {
    @Test
    fun retiredEngineClosesOnlyAfterLastContentLeaseDrains() {
        val tracker = SteamContentSdkEngineLeaseTracker()
        val first = tracker.acquire()
        val second = tracker.acquire()
        var closes = 0

        tracker.whenIdle { closes += 1 }
        assertEquals(0, closes)
        first.close()
        assertEquals(0, closes)
        second.close()
        assertEquals(1, closes)
    }

    @Test
    fun idleEngineClosesImmediatelyAndLeaseCloseIsIdempotent() {
        val tracker = SteamContentSdkEngineLeaseTracker()
        var closes = 0
        tracker.whenIdle { closes += 1 }
        assertEquals(1, closes)

        val lease = tracker.acquire()
        lease.close()
        lease.close()
        tracker.whenIdle { closes += 1 }
        assertEquals(2, closes)
    }
}
