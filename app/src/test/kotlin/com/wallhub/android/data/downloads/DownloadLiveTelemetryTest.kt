package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DownloadLiveActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadLiveTelemetryTest {
    @Test
    fun `downloading remains receiving when measured speed ages out`() = runTest {
        var now = 1_000L
        val telemetry =
            DownloadLiveTelemetry(
                nowMs = { now },
                scope = backgroundScope,
                waitMs = { delay(it) },
            )

        telemetry.accept(
            "task",
            SteamDownloadProgress(
                phase = SteamDownloadPhase.DOWNLOADING,
                networkBytes = 4_096L,
                networkBytesPerSecond = 2_048L,
                firstContentReceived = true,
            ),
        )
        assertEquals(DownloadLiveActivity.RECEIVING, telemetry.current("task")?.activity)
        assertEquals(2_048L, telemetry.current("task")?.networkBytesPerSecond)

        now += 1_999L
        telemetry.accept(
            "task",
            SteamDownloadProgress(
                phase = SteamDownloadPhase.DOWNLOADING,
                networkBytes = 4_096L,
                networkBytesPerSecond = 2_048L,
                firstContentReceived = true,
            ),
        )
        assertEquals(DownloadLiveActivity.RECEIVING, telemetry.current("task")?.activity)
        assertEquals(2_048L, telemetry.current("task")?.networkBytesPerSecond)

        now += 1L
        telemetry.accept(
            "task",
            SteamDownloadProgress(
                phase = SteamDownloadPhase.DOWNLOADING,
                networkBytes = 4_096L,
                networkBytesPerSecond = 2_048L,
                firstContentReceived = true,
            ),
        )

        assertEquals(DownloadLiveActivity.RECEIVING, telemetry.current("task")?.activity)
        assertNull(telemetry.current("task")?.networkBytesPerSecond)
    }

    @Test
    fun `timer clears measured speed without changing downloading activity`() = runTest {
        val telemetry =
            DownloadLiveTelemetry(
                nowMs = { testScheduler.currentTime },
                scope = backgroundScope,
                waitMs = { delay(it) },
            )

        telemetry.accept(
            "task",
            SteamDownloadProgress(
                phase = SteamDownloadPhase.DOWNLOADING,
                networkBytes = 4_096L,
                networkBytesPerSecond = 2_048L,
                firstContentReceived = true,
            ),
        )
        runCurrent()

        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(DownloadLiveActivity.RECEIVING, telemetry.current("task")?.activity)
        assertEquals(2_048L, telemetry.current("task")?.networkBytesPerSecond)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(DownloadLiveActivity.RECEIVING, telemetry.current("task")?.activity)
        assertNull(telemetry.current("task")?.networkBytesPerSecond)
    }

    @Test
    fun `sequential dependency and task counters establish new activity epochs`() = runTest {
        var now = 1_000L
        val telemetry =
            DownloadLiveTelemetry(
                nowMs = { now },
                scope = backgroundScope,
                waitMs = { delay(it) },
            )

        telemetry.accept(
            "task",
            SteamDownloadProgress(
                phase = SteamDownloadPhase.DOWNLOADING,
                networkBytes = 8_192L,
                networkBytesPerSecond = 4_096L,
                firstContentReceived = true,
            ),
        )
        assertEquals(DownloadLiveActivity.RECEIVING, telemetry.current("task")?.activity)

        now += 100L
        telemetry.accept(
            "task",
            SteamDownloadProgress(
                phase = SteamDownloadPhase.DOWNLOADING,
                networkBytes = 1_024L,
                networkBytesPerSecond = 4_096L,
                firstContentReceived = true,
            ),
        )
        assertEquals(DownloadLiveActivity.RECEIVING, telemetry.current("task")?.activity)
        assertNull(telemetry.current("task")?.networkBytesPerSecond)

        now += 100L
        telemetry.accept(
            "task",
            SteamDownloadProgress(
                phase = SteamDownloadPhase.DOWNLOADING,
                networkBytes = 1_024L,
                networkBytesPerSecond = 4_096L,
                firstContentReceived = true,
            ),
        )
        assertEquals(DownloadLiveActivity.RECEIVING, telemetry.current("task")?.activity)
        assertNull(telemetry.current("task")?.networkBytesPerSecond)

        telemetry.accept(
            "task",
            SteamDownloadProgress(
                phase = SteamDownloadPhase.DOWNLOADING,
                networkBytes = 2_048L,
                networkBytesPerSecond = 1_024L,
                firstContentReceived = true,
            ),
        )
        assertEquals(DownloadLiveActivity.RECEIVING, telemetry.current("task")?.activity)
        assertEquals(1_024L, telemetry.current("task")?.networkBytesPerSecond)

        telemetry.accept(
            "task",
            SteamDownloadProgress(
                phase = SteamDownloadPhase.PREPARING_DOWNLOAD,
                networkBytes = 2_048L,
            ),
        )
        telemetry.accept(
            "task",
            SteamDownloadProgress(
                phase = SteamDownloadPhase.DOWNLOADING,
                networkBytes = 0L,
                networkBytesPerSecond = 1_024L,
                firstContentReceived = false,
            ),
        )
        assertEquals(DownloadLiveActivity.PREPARING, telemetry.current("task")?.activity)
        assertNull(telemetry.current("task")?.networkBytesPerSecond)

        telemetry.accept(
            "task",
            SteamDownloadProgress(
                phase = SteamDownloadPhase.DOWNLOADING,
                networkBytes = 0L,
                networkBytesPerSecond = 1_024L,
                firstContentReceived = true,
            ),
        )
        assertEquals(DownloadLiveActivity.RECEIVING, telemetry.current("task")?.activity)
        assertNull(telemetry.current("task")?.networkBytesPerSecond)

        telemetry.accept(
            "task",
            SteamDownloadProgress(
                phase = SteamDownloadPhase.DOWNLOADING,
                networkBytes = 512L,
                networkBytesPerSecond = 512L,
                firstContentReceived = true,
            ),
        )
        assertEquals(DownloadLiveActivity.RECEIVING, telemetry.current("task")?.activity)
        assertEquals(512L, telemetry.current("task")?.networkBytesPerSecond)
    }

    @Test
    fun `preparing and verifying never expose a historical speed`() = runTest {
        val telemetry =
            DownloadLiveTelemetry(
                nowMs = { 1_000L },
                scope = backgroundScope,
                waitMs = { delay(it) },
            )

        telemetry.accept(
            "task",
            SteamDownloadProgress(
                phase = SteamDownloadPhase.PREPARING_DOWNLOAD,
                networkBytes = 4_096L,
                networkBytesPerSecond = 2_048L,
                firstContentReceived = true,
            ),
        )
        assertEquals(DownloadLiveActivity.PREPARING, telemetry.current("task")?.activity)
        assertNull(telemetry.current("task")?.networkBytesPerSecond)

        telemetry.accept("task", SteamDownloadProgress(phase = SteamDownloadPhase.VERIFYING))
        assertEquals(DownloadLiveActivity.VERIFYING, telemetry.current("task")?.activity)
        assertNull(telemetry.current("task")?.networkBytesPerSecond)
    }
}
