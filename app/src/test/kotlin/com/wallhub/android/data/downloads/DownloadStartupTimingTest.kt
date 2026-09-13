package com.wallhub.android.data.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadStartupTimingTest {
    @Test
    fun `first committed chunk produces an initial speed`() {
        assertEquals(
            1_000_000L,
            downloadSpeedBytesPerSecond(
                phase = SteamDownloadPhase.DOWNLOADING,
                completedBytes = 500_000L,
                previousBytes = 0L,
                elapsedMs = 500L,
                previousSpeed = 0L,
            ),
        )
    }

    @Test
    fun `non downloading phases retain previous speed`() {
        assertEquals(
            123L,
            downloadSpeedBytesPerSecond(
                phase = SteamDownloadPhase.RESOLVING,
                completedBytes = 0L,
                previousBytes = 0L,
                elapsedMs = 500L,
                previousSpeed = 123L,
            ),
        )
    }

    @Test
    fun `formal chunk attempts fail over before the shared read timeout`() {
        assertEquals(5_000L, FORMAL_CHUNK_ATTEMPT_TIMEOUT_MS)
        check(FORMAL_CHUNK_ATTEMPT_TIMEOUT_MS < CDN_READ_TIMEOUT_MS)
    }
}
