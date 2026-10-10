package com.wallhub.android.feature.detail

import androidx.media3.common.PlaybackException
import com.wallhub.android.core.model.WorkshopVideoStreamFailure
import com.wallhub.android.core.model.WorkshopVideoStreamFailureException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRecoveryBudgetTest {
    @Test
    fun `full screen receives at most two automatic retries for one workshop page`() {
        val budget = StreamRecoveryBudget()
        val error = playbackFailure(WorkshopVideoStreamFailure.SESSION_INVALIDATED)

        assertTrue(budget.tryAcquire(StreamPlaybackPath.FULL_SCREEN, error))
        assertTrue(budget.tryAcquire(StreamPlaybackPath.FULL_SCREEN, error))
        assertFalse(budget.tryAcquire(StreamPlaybackPath.FULL_SCREEN, error))
        assertEquals(2, budget.automaticRetriesUsed)
    }

    @Test
    fun `inline receives the same two retry budget and first frame does not refill it`() {
        val budget = StreamRecoveryBudget()
        val error = playbackFailure(WorkshopVideoStreamFailure.CDN_ROUTE_EXHAUSTED)

        assertTrue(budget.tryAcquire(StreamPlaybackPath.INLINE, error))
        budget.onFirstFrameRendered()
        assertTrue(budget.tryAcquire(StreamPlaybackPath.INLINE, error))
        budget.onFirstFrameRendered()
        assertFalse(budget.tryAcquire(StreamPlaybackPath.INLINE, error))
    }

    @Test
    fun `manual retry explicitly resets the automatic retry budget`() {
        val budget = StreamRecoveryBudget()
        val error = playbackFailure(WorkshopVideoStreamFailure.SESSION_INVALIDATED)
        repeat(2) { assertTrue(budget.tryAcquire(StreamPlaybackPath.FULL_SCREEN, error)) }
        assertFalse(budget.tryAcquire(StreamPlaybackPath.FULL_SCREEN, error))

        budget.resetForManualRetry()

        assertTrue(budget.tryAcquire(StreamPlaybackPath.FULL_SCREEN, error))
        assertEquals(1, budget.automaticRetriesUsed)
    }

    @Test
    fun `new item gets a fresh budget without sharing attempts`() {
        val firstItem = StreamRecoveryBudget()
        val secondItem = StreamRecoveryBudget()
        val error = playbackFailure(WorkshopVideoStreamFailure.SESSION_INVALIDATED)
        repeat(2) { assertTrue(firstItem.tryAcquire(StreamPlaybackPath.INLINE, error)) }

        assertFalse(firstItem.tryAcquire(StreamPlaybackPath.INLINE, error))
        assertTrue(secondItem.tryAcquire(StreamPlaybackPath.INLINE, error))
    }

    @Test
    fun `both paths reject untyped failures even when text resembles an old SDK diagnostic`() {
        val budget = StreamRecoveryBudget()
        val untyped =
            PlaybackException(
                "source",
                IOException("invalid stream handle; CDN candidate set exhausted: transport"),
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            )

        assertFalse(budget.tryAcquire(StreamPlaybackPath.FULL_SCREEN, untyped))
        assertFalse(budget.tryAcquire(StreamPlaybackPath.INLINE, untyped))
        assertEquals(0, budget.automaticRetriesUsed)
    }

    @Test
    fun `typed mapping preserves the original SDK failure as the cause`() {
        val sdkFailure = IllegalStateException("raw SDK diagnostic")
        val mapped =
            WorkshopVideoStreamFailureException(
                failure = WorkshopVideoStreamFailure.SESSION_INVALIDATED,
                cause = sdkFailure,
            )
        val playback =
            PlaybackException(
                "source",
                IOException("Media3 data source", mapped),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )

        val typed = playback.workshopVideoStreamFailureOrNull()

        assertEquals(WorkshopVideoStreamFailure.SESSION_INVALIDATED, typed?.failure)
        assertSame(sdkFailure, typed?.cause)
    }

    private fun playbackFailure(failure: WorkshopVideoStreamFailure): PlaybackException =
        PlaybackException(
            "source",
            IOException(
                "Media3 data source",
                WorkshopVideoStreamFailureException(failure, IllegalStateException("raw SDK diagnostic")),
            ),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
}
