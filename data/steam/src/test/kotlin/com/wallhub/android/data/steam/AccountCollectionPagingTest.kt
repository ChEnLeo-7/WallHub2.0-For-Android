package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountCollectionPagingTest {
    @Test
    fun `returns the requested page and detects the next client-filtered result`() {
        val selection =
            selectAccountCollectionPage(
                matches = (1..33).toList(),
                page = 2,
                pageSize = 16,
                sourceExhausted = false,
            )

        assertEquals((17..32).toList(), selection.items)
        assertTrue(selection.hasNextPage)
    }

    @Test
    fun `ends after a partial final client-filtered page`() {
        val selection =
            selectAccountCollectionPage(
                matches = (1..19).toList(),
                page = 2,
                pageSize = 16,
                sourceExhausted = true,
            )

        assertEquals(listOf(17, 18, 19), selection.items)
        assertFalse(selection.hasNextPage)
    }

    @Test
    fun `large page offsets return an empty selection without overflowing`() {
        val selection =
            selectAccountCollectionPage(
                matches = (1..33).toList(),
                page = Int.MAX_VALUE,
                pageSize = 30,
                sourceExhausted = true,
            )

        assertEquals(emptyList<Int>(), selection.items)
        assertFalse(selection.hasNextPage)
    }

    @Test
    fun `collection search matches title author ids and tags without breaking type filter`() {
        val video =
            WorkshopSummary(
                id = 3673655753,
                title = "Blue city rain",
                author = "Studio North",
                creatorId = "76561198000000000",
                type = WorkshopType.VIDEO,
                tags = listOf("Cyberpunk", "Audio responsive"),
            )

        fun query(
            text: String,
            type: WorkshopType? = WorkshopType.VIDEO,
        ) = AccountWorkshopQuery(
            collection = AccountWorkshopCollection.FAVORITES,
            searchText = text,
            type = type,
        )

        assertTrue(query("city rain").matchesAccountCollectionItem(video))
        assertTrue(query("studio north").matchesAccountCollectionItem(video))
        assertTrue(query("3673655753").matchesAccountCollectionItem(video))
        assertTrue(query("76561198000000000").matchesAccountCollectionItem(video))
        assertTrue(query("cyberpunk").matchesAccountCollectionItem(video))
        assertFalse(query("city", WorkshopType.SCENE).matchesAccountCollectionItem(video))
        assertFalse(query("mountain").matchesAccountCollectionItem(video))
    }
}
