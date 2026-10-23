package com.wallhub.android.feature.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopDetailCommentsReducerTest {
    @Test
    fun `request moves comments synchronously from not requested to loading`() {
        val result = beginCommentsRequest(WorkshopDetailUiState(), refresh = true)

        assertTrue(result.commentsRequested)
        assertTrue(result.isLoadingComments)
        assertFalse(result.isLoadingMoreComments)
        assertNull(result.commentsError)
    }
}
