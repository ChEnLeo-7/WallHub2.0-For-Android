package com.wallhub.android.feature.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDemandThrottleTest {
    @Test
    fun `sequential reads publish by byte or time threshold while seek forces immediate demand`() {
        val throttle = StreamDemandThrottle(byteInterval = 256, timeIntervalMs = 250)

        assertTrue(throttle.shouldPublish(position = 0, nowMs = 0))
        assertFalse(throttle.shouldPublish(position = 5, nowMs = 1))
        assertFalse(throttle.shouldPublish(position = 255, nowMs = 249))
        assertTrue(throttle.shouldPublish(position = 256, nowMs = 249))
        assertTrue(throttle.shouldPublish(position = 257, nowMs = 500))
        assertTrue(throttle.shouldPublish(position = 10, nowMs = 501, force = true))
        assertFalse(throttle.shouldPublish(position = 11, nowMs = 502))
    }
}
