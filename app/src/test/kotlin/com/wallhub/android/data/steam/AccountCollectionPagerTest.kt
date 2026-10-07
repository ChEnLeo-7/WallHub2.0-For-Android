package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountCollectionPagerTest {
    private val scope = AccountCollectionScope(accountId = 76561198000000001L, sessionGeneration = 7L)

    @Test
    fun `next ui page continues from the materialized cursor instead of rescanning source page one`() = runTest {
        val pager = AccountCollectionPager()
        val calls = mutableListOf<Int>()
        val loader = source(calls)

        val first = pager.page(scope, query(page = 1), loader)
        val second = pager.page(scope, query(page = 2), loader)

        assertEquals(listOf(2L, 4L), first.items.map(WorkshopSummary::id))
        assertEquals(listOf(6L, 8L), second.items.map(WorkshopSummary::id))
        assertEquals(listOf(1, 2, 3, 4), calls)
    }

    @Test
    fun `account and session generation isolate cursors`() = runTest {
        val pager = AccountCollectionPager()
        val calls = mutableListOf<Int>()
        val loader = source(calls)

        pager.page(scope, query(), loader)
        pager.page(scope.copy(sessionGeneration = 8L), query(), loader)
        pager.page(scope.copy(accountId = scope.accountId + 1), query(), loader)

        assertEquals(listOf(1, 2, 1, 2, 1, 2), calls)
    }

    @Test
    fun `collection and normalized filter fingerprint isolate cursors while page shape does not`() = runTest {
        val pager = AccountCollectionPager()
        val calls = mutableListOf<Int>()
        val loader = source(calls)

        pager.page(scope, query(searchText = " match "), loader)
        pager.page(scope, query(page = 2, pageSize = 1, searchText = "match"), loader)
        pager.page(scope, query(collection = AccountWorkshopCollection.SUBSCRIPTIONS), loader)
        pager.page(scope, query(exactPhrase = true), loader)

        assertEquals(listOf(1, 2, 1, 2, 1, 2), calls)
    }

    @Test
    fun `least recently used cursor is evicted when state bound is reached`() = runTest {
        val pager = AccountCollectionPager(AccountCollectionPagerLimits(maxCursors = 2))
        val calls = mutableListOf<Int>()
        val loader = source(calls)

        pager.page(scope, query(collection = AccountWorkshopCollection.FAVORITES), loader)
        pager.page(scope, query(collection = AccountWorkshopCollection.SUBSCRIPTIONS), loader)
        pager.page(scope, query(collection = AccountWorkshopCollection.VOTED), loader)
        pager.page(scope, query(collection = AccountWorkshopCollection.FAVORITES), loader)

        assertEquals(listOf(1, 2, 1, 2, 1, 2, 1, 2), calls)
    }

    @Test
    fun `source scan and materialized matches are bounded`() = runTest {
        val pager =
            AccountCollectionPager(
                AccountCollectionPagerLimits(
                    maxCursors = 1,
                    maxSourcePagesPerCursor = 2,
                    sourcePageSize = 3,
                ),
            )
        val calls = mutableListOf<Int>()

        val result =
            pager.page(scope, query(pageSize = 10, searchText = "match")) { sourcePage, _ ->
                calls += sourcePage
                WorkshopPage(
                    items = (1L..100L).map { id -> summary(sourcePage * 1_000L + id, "match") },
                    page = sourcePage,
                    hasNextPage = true,
                )
            }

        assertEquals(listOf(1, 2), calls)
        assertEquals(6, result.items.size)
        assertFalse(result.hasNextPage)
    }

    @Test
    fun `cancelled source load does not advance the cursor`() = runTest {
        val pager = AccountCollectionPager()
        val calls = mutableListOf<Int>()

        assertFailsWith<CancellationException> {
            pager.page(scope, query()) { sourcePage, _ ->
                calls += sourcePage
                throw CancellationException("cancelled")
            }
        }
        val result = pager.page(scope, query(), source(calls))

        assertEquals(listOf(1, 1, 2), calls)
        assertEquals(listOf(2L, 4L), result.items.map(WorkshopSummary::id))
    }

    @Test
    fun `unfiltered query remains the caller direct path`() = runTest {
        val pager = AccountCollectionPager()
        var loaded = false

        assertFailsWith<IllegalArgumentException> {
            pager.page(
                scope,
                AccountWorkshopQuery(collection = AccountWorkshopCollection.FAVORITES),
            ) { _, _ ->
                loaded = true
                WorkshopPage(emptyList(), 1, false)
            }
        }

        assertFalse(loaded)
    }

    @Test
    fun `exhausted source produces a stable terminal page without another load`() = runTest {
        val pager = AccountCollectionPager()
        val calls = mutableListOf<Int>()
        val loader: suspend (Int, Int) -> WorkshopPage = { sourcePage, _ ->
            calls += sourcePage
            WorkshopPage(listOf(summary(1, "match")), sourcePage, hasNextPage = false)
        }

        val first = pager.page(scope, query(pageSize = 2), loader)
        val second = pager.page(scope, query(page = 2, pageSize = 2), loader)

        assertEquals(listOf(1L), first.items.map(WorkshopSummary::id))
        assertFalse(first.hasNextPage)
        assertTrue(second.items.isEmpty())
        assertFalse(second.hasNextPage)
        assertEquals(listOf(1), calls)
    }

    private fun query(
        collection: AccountWorkshopCollection = AccountWorkshopCollection.FAVORITES,
        page: Int = 1,
        pageSize: Int = 2,
        searchText: String = "match",
        exactPhrase: Boolean = false,
    ) =
        AccountWorkshopQuery(
            collection = collection,
            page = page,
            pageSize = pageSize,
            searchText = searchText,
            exactPhrase = exactPhrase,
        )

    private fun source(calls: MutableList<Int>): suspend (Int, Int) -> WorkshopPage = { sourcePage, _ ->
        calls += sourcePage
        val firstId = (sourcePage - 1L) * 2L + 1L
        WorkshopPage(
            items =
                listOf(
                    summary(firstId, "miss"),
                    summary(firstId + 1L, "match"),
                ),
            page = sourcePage,
            hasNextPage = sourcePage < 10,
        )
    }

    private fun summary(id: Long, title: String) =
        WorkshopSummary(
            id = id,
            title = title,
            author = "Creator",
        )
}
