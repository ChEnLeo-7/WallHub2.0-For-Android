package com.wallhub.android.feature.home

import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeDefaultRefreshTest {
    @Test
    fun `WallHub reset clears filters cards projections and pagination before request completes`() {
        val previous = HomeUiState(
            query = "cat",
            creatorId = "42",
            requiredTags = setOf("Anime"),
            sort = WorkshopSort.MOST_RECENT,
            items = listOf(WorkshopSummary(7L, "Old filtered card", "Old author")),
            authorDisplayNames = mapOf(7L to "Old author"),
            personaDegradedItemIds = setOf(7L),
            currentPage = 5,
            nextPage = 6,
            totalPages = 12,
            totalCount = 360,
            hasNextPage = true,
            isInitialLoading = false,
            isLoadingMore = true,
            isPageLoading = true,
        )

        val refreshing = previous.resetForDefaultBrowse()

        assertEquals("", refreshing.query)
        assertNull(refreshing.creatorId)
        assertTrue(refreshing.requiredTags.isEmpty())
        assertEquals(WorkshopSort.TRENDING, refreshing.sort)
        assertTrue(refreshing.items.isEmpty())
        assertTrue(refreshing.authorDisplayNames.isEmpty())
        assertTrue(refreshing.personaDegradedItemIds.isEmpty())
        assertEquals(1, refreshing.currentPage)
        assertEquals(2, refreshing.nextPage)
        assertEquals(1, refreshing.totalPages)
        assertNull(refreshing.totalCount)
        assertFalse(refreshing.hasNextPage)
        assertTrue(refreshing.isInitialLoading)
        assertFalse(refreshing.isLoadingMore)
        assertFalse(refreshing.isPageLoading)
    }

    @Test
    fun `late filtered response cannot replace newer default refresh state`() {
        val refreshing = HomeUiState(isInitialLoading = true)
        val oldFilteredPage = WorkshopPage(
            items = listOf(WorkshopSummary(7L, "Old filtered card", "Old author")),
            page = 1,
            hasNextPage = false,
        )

        val afterLateResponse = refreshing.commitHomePageIfCurrent(
            expectedVersion = 3L,
            currentVersion = 4L,
            page = oldFilteredPage,
        )

        assertEquals(refreshing, afterLateResponse)
        assertTrue(afterLateResponse.items.isEmpty())
    }
}
