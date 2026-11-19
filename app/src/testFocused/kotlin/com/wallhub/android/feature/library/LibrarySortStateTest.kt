package com.wallhub.android.feature.library

import androidx.lifecycle.SavedStateHandle
import com.wallhub.android.core.model.AccountWorkshopSort
import org.junit.Test
import kotlin.test.assertEquals

class LibrarySortStateTest {
    @Test
    fun `collection switch keeps independent sort choices`() {
        val state = LibraryUiState(
            collectionSorts = mapOf(
                LibraryCollectionTab.SUBSCRIPTIONS to AccountWorkshopSort.TITLE_ASC,
                LibraryCollectionTab.FAVORITES to AccountWorkshopSort.CREATED_DESC,
            ),
        )
        assertEquals(AccountWorkshopSort.TITLE_ASC, state.sortFor())
        assertEquals(
            AccountWorkshopSort.CREATED_DESC,
            state.selectCollectionState(LibraryCollectionTab.FAVORITES).sortFor(),
        )
    }

    @Test
    fun `reset affects current sort and retains other collection choice`() {
        val reset = LibraryUiState(
            collectionSorts = mapOf(
                LibraryCollectionTab.SUBSCRIPTIONS to AccountWorkshopSort.TITLE_ASC,
                LibraryCollectionTab.FAVORITES to AccountWorkshopSort.CREATED_DESC,
            ),
        ).resetLibraryFiltersState()
        assertEquals(AccountWorkshopSort.RELATIONSHIP_DATE_DESC, reset.sortFor())
        assertEquals(AccountWorkshopSort.CREATED_DESC, reset.sortFor(LibraryCollectionTab.FAVORITES))
    }

    @Test
    fun `restoration defaults unsupported favorite date to update order`() {
        val restored = SavedStateHandle(
            mapOf(
                "library.sort.SUBSCRIPTIONS" to AccountWorkshopSort.TITLE_ASC.name,
                "library.sort.FAVORITES" to AccountWorkshopSort.RELATIONSHIP_DATE_DESC.name,
            ),
        ).libraryState()
        assertEquals(AccountWorkshopSort.TITLE_ASC, restored.sortFor())
        assertEquals(AccountWorkshopSort.UPDATED_DESC, restored.sortFor(LibraryCollectionTab.FAVORITES))
    }

    @Test
    fun `missing or unknown saved choices use collection defaults`() {
        val restored = SavedStateHandle(mapOf("library.sort.SUBSCRIPTIONS" to "unknown")).libraryState()
        assertEquals(AccountWorkshopSort.RELATIONSHIP_DATE_DESC, restored.sortFor())
        assertEquals(AccountWorkshopSort.UPDATED_DESC, restored.sortFor(LibraryCollectionTab.FAVORITES))
    }
}
