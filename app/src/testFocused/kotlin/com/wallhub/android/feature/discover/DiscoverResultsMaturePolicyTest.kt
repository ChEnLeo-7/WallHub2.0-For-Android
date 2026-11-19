package com.wallhub.android.feature.discover

import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.feature.home.HomeFilterSelection
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoverResultsMaturePolicyTest {
    @Test
    fun `tightening mature policy invalidates current result generation state`() {
        val state =
            DiscoverResultsState(
                items = listOf(WorkshopSummary(id = 7L, title = "mature", author = "author")),
                filterSelection = HomeFilterSelection.defaults().copy(ratings = setOf(WorkshopRating.EVERYONE, WorkshopRating.MATURE)),
                matureContentEnabled = true,
                page = 3,
                hasMore = false,
            )

        val tightened = state.withMatureContentPolicy(false)

        assertTrue(tightened.items.isEmpty())
        assertTrue(WorkshopRating.MATURE !in tightened.filterSelection.ratings)
        assertEquals(0, tightened.page)
        assertTrue(tightened.hasMore)
    }
}
