package com.wallhub.android.feature.library

import androidx.lifecycle.SavedStateHandle
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryInitialCollectionTest {
    @Test
    fun `navigation collection is present in the first library state`() {
        val state =
            SavedStateHandle(
                mapOf(
                    "collection" to "FAVORITES",
                    "library.collection" to "SUBSCRIPTIONS",
                ),
            ).libraryState()

        assertEquals(LibraryCollectionTab.FAVORITES, state.collection)
    }

    @Test
    fun `persisted collection remains the fallback for the normal library destination`() {
        val state = SavedStateHandle(mapOf("library.collection" to "FAVORITES")).libraryState()

        assertEquals(LibraryCollectionTab.FAVORITES, state.collection)
    }

    @Test
    fun `unknown navigation input falls back to subscriptions`() {
        val state = SavedStateHandle(mapOf("collection" to "UNKNOWN")).libraryState()

        assertEquals(LibraryCollectionTab.SUBSCRIPTIONS, state.collection)
    }
}
