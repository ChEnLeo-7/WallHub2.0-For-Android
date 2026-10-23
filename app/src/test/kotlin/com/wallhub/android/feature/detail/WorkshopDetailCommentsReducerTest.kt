package com.wallhub.android.feature.detail

import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopSummary
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
    fun `request moves comments synchronously from not requested to loading`() {
        val result = beginCommentsRequest(WorkshopDetailUiState(), refresh = true)

        assertTrue(result.commentsRequested)
        assertTrue(result.isLoadingComments)
        assertFalse(result.isLoadingMoreComments)
        assertNull(result.commentsError)
    }
}
