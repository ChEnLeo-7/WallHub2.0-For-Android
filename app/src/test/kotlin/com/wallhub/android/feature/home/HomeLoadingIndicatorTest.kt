package com.wallhub.android.feature.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLoadingIndicatorTest {
    @Test
    fun `only page refresh uses the pull to refresh indicator`() {
        assertFalse(HomeUiState().showPullToRefresh())
        assertTrue(HomeUiState(isInitialLoading = false, isPageLoading = true).showPullToRefresh())
    }

    @Test
    fun `idle and append loading do not show a page refresh spinner`() {
        assertFalse(HomeUiState(isInitialLoading = false).showPullToRefresh())
        assertFalse(HomeUiState(isInitialLoading = false, isLoadingMore = true).showPullToRefresh())
    }

    @Test
    fun `browse failure stops the loading indicator`() {
        assertFalse(HomeUiState(isInitialLoading = false, error = "Browse failed").showPullToRefresh())
    }
}
