package com.wallhub.android.feature.home

import com.wallhub.android.core.model.WorkshopFilterCatalog
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthorSearchInitialStateTest {
    @Test
    fun `author route starts with the complete author query before loading`() {
        val state = initialHomeUiState("76561198113367551")

        assertEquals("author:76561198113367551", state.query)
        assertEquals("76561198113367551", state.creatorId)
        assertEquals(WorkshopSort.MOST_RECENT, state.sort)
        assertEquals(0, state.days)
        assertEquals(setOf(WorkshopRating.EVERYONE), state.selectedRatings)
        assertEquals(WorkshopFilterCatalog.genres.toSet(), state.selectedGenres)
        assertEquals(WorkshopFilterCatalog.resolutions.toSet(), state.selectedResolutions)
    }

    @Test
    fun `missing or invalid author route keeps the discover defaults`() {
        assertNull(initialHomeUiState(null).creatorId)
        assertNull(initialHomeUiState("not-an-author").creatorId)
        assertEquals(HomeUiState(), initialHomeUiState(""))
    }
}
