package com.wallhub.prototype.mpkg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MpkgTimingSessionTest {
    @Test
    fun `snapshot aggregates stable phase durations and counts without resource identity`() {
        var now = 100L
        val session = MpkgTimingSession { now }

        session.measure(MpkgPhase.RGBA_LZ4) {
            now += 25L
        }
        session.measure(MpkgPhase.RGBA_LZ4) {
            now += 35L
        }
        session.measure(MpkgPhase.PACKAGE) {
            now += 10L
        }

        val snapshot = session.snapshot()

        assertEquals(60L, snapshot.phase(MpkgPhase.RGBA_LZ4).elapsedNanos)
        assertEquals(2, snapshot.phase(MpkgPhase.RGBA_LZ4).invocations)
        assertEquals(10L, snapshot.phase(MpkgPhase.PACKAGE).elapsedNanos)
        assertEquals(1, snapshot.phase(MpkgPhase.PACKAGE).invocations)
        val encoded = snapshot.toPrivacySafeLogFields()
        assertFalse(encoded.contains("/"))
        assertFalse(encoded.contains(".tex"))
    }
}
