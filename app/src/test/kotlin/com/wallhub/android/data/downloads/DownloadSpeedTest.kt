package com.wallhub.android.data.downloads

import io.steamcontent.sdk.TaskEventKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the rate the download card is allowed to show.
 *
 * The SDK advances durably committed bytes one chunk at a time, so most persist windows carry no new
 * reading at all. Treating such a window as a measurement of zero is what made the card read 正在测速
 * (measuring) for a transfer that was moving, so the unknown rate has to stay distinguishable from a
 * measured one, and a measured one has to survive the windows in between.
 */
class DownloadSpeedTest {
    @Test
    fun `a window with no newly committed bytes keeps the last measured rate`() {
        assertEquals(
            2_048L,
            downloadSpeedBytesPerSecond(
                phase = SteamDownloadPhase.DOWNLOADING,
                completedBytes = 8_192L,
                previousBytes = 8_192L,
                elapsedMs = 750L,
                previousSpeed = 2_048L,
            ),
        )
    }

    @Test
    fun `a rate nobody measured stays unknown`() {
        assertEquals(
            0L,
            downloadSpeedBytesPerSecond(
                phase = SteamDownloadPhase.DOWNLOADING,
                completedBytes = 0L,
                previousBytes = 0L,
                elapsedMs = 750L,
                previousSpeed = 0L,
            ),
        )
    }

    @Test
    fun `a new committed reading is measured over the interval since the last one`() {
        // The window is the time between two commits, not one persist tick: a coarse commit cadence must
        // still publish the average rate the bytes actually arrived at.
        assertEquals(
            4_096L,
            downloadSpeedBytesPerSecond(
                phase = SteamDownloadPhase.DOWNLOADING,
                completedBytes = 24_576L,
                previousBytes = 16_384L,
                elapsedMs = 2_000L,
                previousSpeed = 2_048L,
            ),
        )
    }

    @Test
    fun `a transfer that stopped committing reports an unknown rate again`() {
        assertEquals(
            0L,
            downloadSpeedBytesPerSecond(
                phase = SteamDownloadPhase.DOWNLOADING,
                completedBytes = 8_192L,
                previousBytes = 8_192L,
                elapsedMs = DOWNLOAD_SPEED_STALE_AFTER_MS,
                previousSpeed = 2_048L,
            ),
        )
    }

    @Test
    fun `a phase that is not downloading keeps the rate it had`() {
        assertEquals(
            2_048L,
            downloadSpeedBytesPerSecond(
                phase = SteamDownloadPhase.PREPARING_DOWNLOAD,
                completedBytes = 0L,
                previousBytes = 0L,
                elapsedMs = 750L,
                previousSpeed = 2_048L,
            ),
        )
    }

    @Test
    fun `the byte-carrying event a device reading shows is the durable checkpoint`() {
        // A device reading of `task event kind=11 value=43388068` is this event, and it is the only
        // byte-carrying kind a session-backed run publishes: the per-chunk kind is published by the
        // engine internally and never reaches this bus.
        assertEquals("CHECKPOINT_COMMITTED", sdkTaskEventName(TaskEventKind.CHECKPOINT_COMMITTED))
        assertEquals("CHUNK_PROGRESS", sdkTaskEventName(TaskEventKind.CHUNK_PROGRESS))
        assertEquals("THROUGHPUT_UPDATED", sdkTaskEventName(TaskEventKind.THROUGHPUT_UPDATED))
        assertEquals("PHASE_CHANGED", sdkTaskEventName(TaskEventKind.PHASE_CHANGED))
    }

    @Test
    fun `a kind the app does not know stays an ordinal`() {
        assertEquals("kind 99", sdkTaskEventName(99))
    }
}
