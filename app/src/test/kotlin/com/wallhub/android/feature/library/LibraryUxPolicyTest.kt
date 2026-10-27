package com.wallhub.android.feature.library

import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.model.WorkshopType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryUxPolicyTest {
    @Test
    fun `collection switch preserves query filters and pagination mode but starts the peer at page one`() {
        val previous =
            LibraryUiState(
                collection = LibraryCollectionTab.SUBSCRIPTIONS,
                selectedTypes = setOf(WorkshopType.VIDEO),
                searchQuery = "rain",
                exactPhrase = true,
                paginationMode = HomePaginationMode.PAGED,
                currentPage = 4,
            )

        val switched = previous.selectCollectionState(LibraryCollectionTab.FAVORITES)

        assertEquals(LibraryCollectionTab.FAVORITES, switched.collection)
        assertEquals(setOf(WorkshopType.VIDEO), switched.selectedTypes)
        assertEquals("rain", switched.searchQuery)
        assertTrue(switched.exactPhrase)
        assertEquals(HomePaginationMode.PAGED, switched.paginationMode)
        assertEquals(1, switched.currentPage)
    }

    @Test
    fun `reset filters never changes the active collection`() {
        val previous =
            LibraryUiState(
                collection = LibraryCollectionTab.FAVORITES,
                selectedTypes = setOf(WorkshopType.VIDEO),
                exactPhrase = true,
                paginationMode = HomePaginationMode.PAGED,
            )

        val reset = previous.resetLibraryFiltersState()

        assertEquals(LibraryCollectionTab.FAVORITES, reset.collection)
        assertTrue(reset.selectedTypes.isEmpty())
        assertFalse(reset.exactPhrase)
        assertEquals(HomePaginationMode.INFINITE_SCROLL, reset.paginationMode)
    }

    @Test
    fun `append failure blocks automatic pagination until explicit retry`() {
        val failed =
            LibraryUiState(
                hasNextPage = true,
                nextPage = 3,
                appliedSearchQuery = "",
                appendErrorRes = 1,
                failedAppendPage = 3,
            )

        assertFalse(shouldAutoLoadNextLibraryPage(failed))
        assertTrue(canRetryNextLibraryPage(failed))
    }

    @Test
    fun `empty result distinguishes collection search and filter causes`() {
        assertEquals(LibraryEmptyKind.TRUE_EMPTY, LibraryUiState().emptyKind())
        assertEquals(
            LibraryEmptyKind.SEARCH_NO_RESULTS,
            LibraryUiState(searchQuery = "rain", appliedSearchQuery = "rain").emptyKind(),
        )
        assertEquals(
            LibraryEmptyKind.FILTERED_NO_RESULTS,
            LibraryUiState(selectedTypes = setOf(WorkshopType.VIDEO)).emptyKind(),
        )
    }
}
