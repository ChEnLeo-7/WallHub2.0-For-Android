package com.wallhub.android.data.downloads

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class SteamStreamPrefetchTest {
    @Test
    fun `startup prefetch follows web lead tail and initial buffer order`() {
        val mib = 1024L * 1024L

        assertEquals(
            listOf(
                SteamStreamByteRange(0L, 2L * mib - 1L),
                SteamStreamByteRange(92L * mib, 100L * mib - 1L),
                SteamStreamByteRange(2L * mib, 34L * mib - 1L),
            ),
            steamStreamStartupPrefetchRanges(100L * mib),
        )
    }

    @Test
    fun `short video skips duplicate tail prefetch and clamps the initial buffer`() {
        val mib = 1024L * 1024L

        assertEquals(
            listOf(
                SteamStreamByteRange(0L, 2L * mib - 1L),
                SteamStreamByteRange(2L * mib, 4L * mib - 1L),
            ),
            steamStreamStartupPrefetchRanges(4L * mib),
        )
    }

    @Test
    fun `seek warmup starts at the new position without downloading the file tail`() {
        val mib = 1024L * 1024L

        assertEquals(
            listOf(
                SteamStreamByteRange(20L * mib, 22L * mib - 1L),
                SteamStreamByteRange(22L * mib, 54L * mib - 1L),
            ),
            steamStreamStartupPrefetchRanges(
                contentLength = 100L * mib,
                startPosition = 20L * mib,
            ),
        )
    }

    @Test
    fun `ahead buffer is capped at the video boundary`() {
        val mib = 1024L * 1024L

        assertEquals(
            SteamStreamByteRange(50L * mib, 100L * mib - 1L),
            steamStreamAheadPrefetchRange(100L * mib, 50L * mib),
        )
        assertNull(steamStreamAheadPrefetchRange(100L * mib, 100L * mib))
    }

    @Test
    fun `tail prefetch does not hide a gap in the continuous playback buffer`() {
        val mib = 1024L * 1024L
        val plan = steamStreamStartupPrefetchRanges(100L * mib)

        assertEquals(34L * mib - 1L, steamStreamContiguousPrefetchEnd(0L, plan))
    }

    @Test
    fun `stream prefetch uses at most thirty-two parallel chunk requests`() {
        assertEquals(1, steamStreamPrefetchConcurrency(0))
        assertEquals(6, steamStreamPrefetchConcurrency(6))
        assertEquals(32, steamStreamPrefetchConcurrency(48))
    }
}
