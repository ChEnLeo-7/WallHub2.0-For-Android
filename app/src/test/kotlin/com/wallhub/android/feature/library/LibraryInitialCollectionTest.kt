package com.wallhub.android.feature.library

import androidx.lifecycle.SavedStateHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun `library cache evicts the least recently accessed query`() {
        val cache = BoundedLruCache<String, Int>(maxEntries = 2)
        cache["first"] = 1
        cache["second"] = 2

        assertEquals(1, cache["first"])
        cache["third"] = 3

        assertNull(cache["second"])
        assertEquals(listOf("first", "third"), cache.keysInLruOrder())
    }

    @Test
    fun `library cache preserves selective invalidation and patching`() {
        val cache = BoundedLruCache<String, Int>(maxEntries = 3)
        cache["subscriptions-1"] = 1
        cache["subscriptions-2"] = 2
        cache["favorites-1"] = 3

        cache.updateWhere({ it.startsWith("subscriptions") }) { it + 10 }
        cache.removeWhere { it.startsWith("favorites") }

        assertEquals(11, cache["subscriptions-1"])
        assertEquals(12, cache["subscriptions-2"])
        assertNull(cache["favorites-1"])
    }
}
