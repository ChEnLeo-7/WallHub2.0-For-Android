package com.wallhub.android.feature.library

import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSummary
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryMaturePolicyTest {
    @Test
    fun `tightening mature policy invalidates visible rows and mature rating`() {
        val state =
            LibraryUiState(
                matureContentEnabled = true,
                selectedRatings = setOf(WorkshopRating.EVERYONE, WorkshopRating.MATURE),
                items = listOf(WorkshopSummary(id = 42L, title = "mature", author = "author")),
                currentPage = 4,
            )

        val tightened = state.withMatureContentPolicy(enabled = false, invalidateItems = true)

        assertTrue(tightened.items.isEmpty())
        assertTrue(WorkshopRating.MATURE !in tightened.selectedRatings)
        assertEquals(1, tightened.currentPage)
    }
}
