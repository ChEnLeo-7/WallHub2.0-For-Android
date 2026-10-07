package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountCollectionFilterTest {
    private val wallpaper =
        WorkshopSummary(
            id = 3423261668,
            title = "Neon City Rain",
            author = "Creator",
            type = WorkshopType.VIDEO,
            tags = listOf("Wallpaper", "Video", "Everyone", "Anime", "1920 x 1080", "Approved"),
        )

    @Test
    fun `advanced collection filters match all selected dimensions`() {
        val query =
            AccountWorkshopQuery(
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                searchText = "Neon City",
                exactPhrase = true,
                types = setOf(WorkshopType.VIDEO),
                ratings = setOf(WorkshopRating.EVERYONE),
                genres = setOf("Anime"),
                officialTags = setOf("Approved"),
                resolutions = setOf("1920 x 1080"),
            )

        assertTrue(query.matchesAccountCollectionItem(wallpaper))
    }

    @Test
    fun `excluded official tag rejects a collection item`() {
        val query =
            AccountWorkshopQuery(
                collection = AccountWorkshopCollection.FAVORITES,
                excludedOfficialTags = setOf("Approved"),
            )

        assertFalse(query.matchesAccountCollectionItem(wallpaper))
    }

    @Test
    fun `type and rating filters reject mismatched items`() {
        assertFalse(
            AccountWorkshopQuery(
                collection = AccountWorkshopCollection.VOTED,
                types = setOf(WorkshopType.SCENE),
            ).matchesAccountCollectionItem(wallpaper),
        )
        assertFalse(
            AccountWorkshopQuery(
                collection = AccountWorkshopCollection.VOTED,
                ratings = setOf(WorkshopRating.MATURE),
            ).matchesAccountCollectionItem(wallpaper),
        )
    }

    @Test
    fun `token search can span fields while exact phrase cannot`() {
        val tokenQuery =
            AccountWorkshopQuery(
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                searchText = "Neon Anime",
            )
        val phraseQuery = tokenQuery.copy(exactPhrase = true)

        assertTrue(tokenQuery.matchesAccountCollectionItem(wallpaper))
        assertFalse(phraseQuery.matchesAccountCollectionItem(wallpaper))
    }

    @Test
    fun `library default rating retains filtered cursor semantics`() {
        val query =
            AccountWorkshopQuery(
                collection = AccountWorkshopCollection.FAVORITES,
                ratings = setOf(WorkshopRating.EVERYONE),
                genres = com.wallhub.android.core.model.WorkshopFilterCatalog.genres.toSet(),
                resolutions = com.wallhub.android.core.model.WorkshopFilterCatalog.resolutions.toSet(),
            )

        assertTrue(query.hasInteractiveFilters())
    }

    @Test
    fun `filtered collection scans source pages until requested page is full`() = runTest {
        val requested = AccountWorkshopQuery(
            collection = AccountWorkshopCollection.FAVORITES,
            page = 1,
            pageSize = 2,
            searchText = "match",
        )
        val calls = mutableListOf<Int>()

        val result = browseFilteredAccountCollection(requested) { sourcePage, sourcePageSize ->
            calls += sourcePage
            val items = when (sourcePage) {
                1 -> listOf(summary(1, "miss"), summary(2, "match one"))
                else -> listOf(summary(3, "match two"), summary(4, "miss"))
            }
            WorkshopPage(items, sourcePage, hasNextPage = sourcePage == 1, totalCount = 4)
        }

        assertEquals(listOf(2L, 3L), result.items.map(WorkshopSummary::id))
        assertEquals(listOf(1, 2), calls)
        assertFalse(result.hasNextPage)
        assertEquals(null, result.totalCount)
    }

    @Test
    fun `filtered collection scan is bounded and remains appendable when limit reached`() = runTest {
        val requested = AccountWorkshopQuery(
            collection = AccountWorkshopCollection.FAVORITES,
            pageSize = 2,
            searchText = "absent",
        )
        var calls = 0

        val result = browseFilteredAccountCollection(requested) { sourcePage, _ ->
            calls++
            WorkshopPage(listOf(summary(sourcePage.toLong(), "miss")), sourcePage, hasNextPage = true)
        }

        assertEquals(FILTER_SOURCE_PAGES_PER_UI_PAGE, calls)
        assertTrue(result.hasNextPage)
    }

    @Test
    fun `filtered collection advances its bounded source prefix and terminates at the absolute cap`() = runTest {
        var calls = 0
        val result =
            browseFilteredAccountCollection(
                AccountWorkshopQuery(
                    collection = AccountWorkshopCollection.FAVORITES,
                    page = 4,
                    pageSize = 2,
                    searchText = "absent",
                ),
            ) { sourcePage, _ ->
                calls++
                WorkshopPage(listOf(summary(sourcePage.toLong(), "miss")), sourcePage, hasNextPage = true)
            }

        assertEquals(MAX_ACCOUNT_COLLECTION_FILTER_SOURCE_PAGES, calls)
        assertFalse(result.hasNextPage)
    }

    @Test
    fun `filtered collection scan propagates cancellation`() {
        assertThrows(CancellationException::class.java) {
            runTest {
                browseFilteredAccountCollection(
                    AccountWorkshopQuery(
                        collection = AccountWorkshopCollection.FAVORITES,
                        searchText = "match",
                    ),
                ) { _, _ -> throw CancellationException("cancelled") }
            }
        }
    }

    private fun summary(id: Long, title: String) = wallpaper.copy(id = id, title = title)
}
