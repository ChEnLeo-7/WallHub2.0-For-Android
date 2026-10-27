package com.wallhub.android.feature.detail

import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopDetailCommentsReducerTest {
    @Test
    fun `detail seed is visible while background refresh starts`() {
        val seed = WorkshopDetail(WorkshopSummary(id = 42, title = "Seed", author = "Author"))

        val seeded = applyDetailSeed(WorkshopDetailUiState(), seed)
        val refreshing = beginDetailRefresh(seeded)

        assertEquals(seed, refreshing.detail)
        assertFalse(seeded.isLoading)
        assertFalse(refreshing.isLoading)
    }

    @Test
    fun `opening detail automatically requests first comments page once creator is known`() {
        val detail = WorkshopDetail(WorkshopSummary(id = 42, title = "Seed", author = "Author", creatorId = "76561198000000000"))

        assertTrue(shouldAutoLoadComments(WorkshopDetailUiState(detail = detail)))
        assertFalse(
            shouldAutoLoadComments(
                WorkshopDetailUiState(detail = detail, commentsRequested = true),
            ),
        )
    }

    @Test
    fun `request moves comments synchronously from not requested to loading`() {
        val result = beginCommentsRequest(WorkshopDetailUiState(), refresh = true)

        assertTrue(result.commentsRequested)
        assertTrue(result.isLoadingComments)
        assertFalse(result.isLoadingMoreComments)
        assertNull(result.commentsError)
    }

    @Test
    fun `reload invalidates old comments request and clears loading flags`() {
        val loading =
            WorkshopDetailUiState(
                commentsRequestGeneration = 7L,
                commentsRequested = true,
                isLoadingComments = true,
                isLoadingMoreComments = true,
            )

        val reloaded = resetCommentsForDetailReload(loading)

        assertEquals(8L, reloaded.commentsRequestGeneration)
        assertFalse(reloaded.commentsRequested)
        assertFalse(reloaded.isLoadingComments)
        assertFalse(reloaded.isLoadingMoreComments)
    }

    @Test
    fun `stale comments failure cannot commit after reload`() {
        val requestGeneration = 3L
        val reloaded =
            resetCommentsForDetailReload(
                beginCommentsRequest(
                    WorkshopDetailUiState(commentsRequestGeneration = requestGeneration),
                    refresh = true,
                ),
            )

        val result =
            completeCommentsRequest(reloaded, requestGeneration) {
                copy(commentsError = DetailUiText.Dynamic("stale failure"))
            }

        assertNull(result.commentsError)
        assertFalse(result.isLoadingComments)
        assertFalse(result.isLoadingMoreComments)
    }

    @Test
    fun `cancelled request that completes non cooperatively cannot commit stale success`() = runTest {
        var state =
            beginCommentsRequest(
                WorkshopDetailUiState(commentsRequestGeneration = 1L),
                refresh = true,
            )
        val requestGeneration = state.commentsRequestGeneration
        val release = CompletableDeferred<Unit>()
        val request =
            launch {
                withContext(NonCancellable) {
                    release.await()
                    state = completeCommentsRequest(state, requestGeneration) { copy(commentsTotal = 99) }
                }
            }
        runCurrent()

        state = resetCommentsForDetailReload(state)
        request.cancel()
        release.complete(Unit)
        request.join()

        assertEquals(2L, state.commentsRequestGeneration)
        assertNull(state.commentsTotal)
        assertFalse(state.isLoadingComments)
        assertFalse(state.isLoadingMoreComments)
    }
}

class HoldSpeedFrameCapturePolicyTest {
    @Test
    fun `one frame is captured per long press`() {
        val policy = HoldSpeedFrameCapturePolicy()

        policy.onHoldStarted()
        assertTrue(policy.takeCaptureRequest())
        assertFalse(policy.takeCaptureRequest())

        policy.onHoldEnded()
        policy.onHoldStarted()
        assertTrue(policy.takeCaptureRequest())
        assertFalse(policy.takeCaptureRequest())
    }

    @Test
    fun `ending hold cancels an unconsumed capture request`() {
        val policy = HoldSpeedFrameCapturePolicy()

        policy.onHoldStarted()
        policy.onHoldEnded()

        assertFalse(policy.takeCaptureRequest())
    }
}
