package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.WorkshopVideoStreamSession
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VideoStreamCloseSchedulerTest {
    @Test
    fun `close returns after scheduling and cleans one session exactly once`() {
        val scheduler = RecordingVideoStreamCloseScheduler()
        val delegate = RecordingVideoStreamSession()
        val playbackLeaseCloses = AtomicInteger(0)
        val session =
            CoordinatedVideoStreamSession(
                delegate = delegate,
                playbackLease = Closeable { playbackLeaseCloses.incrementAndGet() },
                closeReservation = scheduler.reservation,
            )

        session.close()
        session.close()

        assertEquals(1, scheduler.actions.size)
        assertEquals(1, delegate.cancelCalls.get())
        assertEquals(0, delegate.closeCalls.get())
        assertEquals(1, playbackLeaseCloses.get())

        scheduler.actions.single().invoke()

        assertEquals(1, delegate.closeCalls.get())
    }

    @Test
    fun `playback lease is released before failing native cleanup runs`() {
        val scheduler = RecordingVideoStreamCloseScheduler()
        val delegate = RecordingVideoStreamSession(throwOnClose = true)
        val playbackLeaseCloses = AtomicInteger(0)
        val session =
            CoordinatedVideoStreamSession(
                delegate = delegate,
                playbackLease = Closeable { playbackLeaseCloses.incrementAndGet() },
                closeReservation = scheduler.reservation,
            )

        session.close()
        assertEquals(1, playbackLeaseCloses.get())

        runCatching { scheduler.actions.single().invoke() }

        assertEquals(1, delegate.closeCalls.get())
    }

    @Test
    fun `cleanup ownership is reserved before opening and remains bounded`() {
        val cleanupScheduler = TestCoroutineScheduler()
        val cleanupScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(cleanupScheduler))
        val scheduler =
            CoroutineVideoStreamCloseScheduler(
                scope = cleanupScope,
                clockMs = { 0L },
                report = { _, _, _ -> },
            )

        val reservations = List(10) { scheduler.tryReserve() }

        assertEquals(10, reservations.count { it != null })
        assertNull(scheduler.tryReserve())
        reservations.filterNotNull().forEach(VideoStreamCloseReservation::close)
        val reopened = scheduler.tryReserve()
        assertNotNull(reopened)
        reopened?.close()
        cleanupScope.cancel()
    }

    @Test
    fun `cleanup outlives the caller coroutine`() {
        val callerScheduler = TestCoroutineScheduler()
        val callerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(callerScheduler))
        val cleanupScheduler = TestCoroutineScheduler()
        val cleanupScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(cleanupScheduler))
        val scheduler =
            CoroutineVideoStreamCloseScheduler(
                scope = cleanupScope,
                clockMs = { 0L },
                report = { _, _, _ -> },
            )
        val delegate = RecordingVideoStreamSession()
        val playbackLeaseCloses = AtomicInteger(0)
        val session =
            CoordinatedVideoStreamSession(
                delegate = delegate,
                playbackLease = Closeable { playbackLeaseCloses.incrementAndGet() },
                closeReservation = checkNotNull(scheduler.tryReserve()),
            )

        val caller = callerScope.launch { session.close() }
        callerScheduler.runCurrent()
        caller.cancel()
        callerScope.cancel()
        assertEquals(1, delegate.cancelCalls.get())
        assertEquals(0, delegate.closeCalls.get())
        assertEquals(1, playbackLeaseCloses.get())

        cleanupScheduler.runCurrent()

        assertEquals(1, delegate.closeCalls.get())
        assertEquals(1, playbackLeaseCloses.get())
        cleanupScope.cancel()
    }

    private class RecordingVideoStreamCloseScheduler {
        val actions = mutableListOf<() -> Unit>()
        val reservation =
            object : VideoStreamCloseReservation {
                override fun schedule(action: () -> Unit) {
                    actions += action
                }

                override fun close() = Unit
            }
    }

    private class RecordingVideoStreamSession(
        private val throwOnClose: Boolean = false,
    ) : WorkshopVideoStreamSession {
        override val title: String = "Video"
        override val fileName: String = "video.mp4"
        override val contentLength: Long = 1L
        val cancelCalls = AtomicInteger(0)
        val closeCalls = AtomicInteger(0)

        override fun cancelPendingReads() {
            cancelCalls.incrementAndGet()
        }

        override suspend fun readAt(
            position: Long,
            destination: ByteArray,
            destinationOffset: Int,
            length: Int,
        ): Int = 0

        override fun close() {
            closeCalls.incrementAndGet()
            if (throwOnClose) error("close failed")
        }
    }
}
