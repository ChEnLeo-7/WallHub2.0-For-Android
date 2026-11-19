package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.AccountWorkshopSort
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.core.model.defaultSort
import com.wallhub.android.core.model.effectiveSort
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AccountCollectionSortTest {
    @Test
    fun `personal queries default to relationship date and sorting alone uses direct paging`() {
        val query = AccountWorkshopQuery(collection = AccountWorkshopCollection.SUBSCRIPTIONS)

        assertEquals(AccountWorkshopSort.RELATIONSHIP_DATE_DESC, query.sort)
        for (sort in AccountWorkshopSort.entries) {
            val sorted = query.copy(sort = sort)
            assertEquals(sort, sorted.normalized().sort)
            assertFalse(sorted.hasInteractiveFilters())
        }
        assertEquals(4, AccountWorkshopSort.entries.map { query.copy(sort = it) }.toSet().size)
    }

    @Test
    fun `collection defaults use only supported personal sorts`() {
        assertEquals(AccountWorkshopSort.RELATIONSHIP_DATE_DESC, AccountWorkshopCollection.SUBSCRIPTIONS.defaultSort())
        assertEquals(AccountWorkshopSort.UPDATED_DESC, AccountWorkshopCollection.FAVORITES.defaultSort())
        assertEquals(
            AccountWorkshopSort.UPDATED_DESC,
            AccountWorkshopQuery(collection = AccountWorkshopCollection.FAVORITES).sort,
        )
    }

    @Test
    fun `favorites normalize legacy relationship date to updated order while preserving supported sorts`() {
        for (sort in AccountWorkshopSort.entries) {
            val query = AccountWorkshopQuery(collection = AccountWorkshopCollection.FAVORITES, sort = sort)
            val expected =
                if (sort == AccountWorkshopSort.RELATIONSHIP_DATE_DESC) AccountWorkshopSort.UPDATED_DESC else sort

            assertEquals(expected, query.effectiveSort())
            assertEquals(expected, query.normalized().sort)
            assertFalse(query.normalized().hasInteractiveFilters())
        }
        val subscriptions = AccountWorkshopQuery(collection = AccountWorkshopCollection.SUBSCRIPTIONS)
        assertEquals(
            AccountWorkshopSort.UPDATED_DESC,
            subscriptions.copy(collection = AccountWorkshopCollection.FAVORITES).normalized().sort,
        )
    }

    @Test
    fun `personal sorts map to the SDK contract independently of public sorts`() {
        assertEquals(1, AccountWorkshopSort.RELATIONSHIP_DATE_DESC.sdkPersonalSort())
        assertEquals(2, AccountWorkshopSort.TITLE_ASC.sdkPersonalSort())
        assertEquals(3, AccountWorkshopSort.UPDATED_DESC.sdkPersonalSort())
        assertEquals(4, AccountWorkshopSort.CREATED_DESC.sdkPersonalSort())
    }

    @Test
    fun `source page flights cannot coalesce different personal sorts`() {
        for (collection in AccountWorkshopCollection.entries) {
            val keys =
                AccountWorkshopSort.entries.map { sort ->
                    accountCollectionReadKey(
                        accountId = 42L,
                        sessionGeneration = 7L,
                        collectionGeneration = 1L,
                        collection = collection,
                        sourcePage = 2,
                        sourcePageSize = 24,
                        sort = sort,
                    )
                }

            assertEquals(AccountWorkshopSort.entries.size, keys.toSet().size)
        }
    }

    @Test
    fun `legacy favorites relation order shares the normalized updated cursor`() =
        runTest {
            val pager = AccountCollectionPager()
            val scope = AccountCollectionScope(accountId = 42L, sessionGeneration = 7L, collectionGeneration = 1L)
            val favorite =
                WorkshopSummary(
                    id = 1L,
                    title = "match",
                    author = "Creator",
                    type = WorkshopType.SCENE,
                    tags = listOf("Wallpaper", "Scene", "Everyone"),
                )
            var loads = 0
            for (sort in listOf(AccountWorkshopSort.RELATIONSHIP_DATE_DESC, AccountWorkshopSort.UPDATED_DESC)) {
                val result =
                    pager.page(
                        scope = scope,
                        query =
                            AccountWorkshopQuery(
                                collection = AccountWorkshopCollection.FAVORITES,
                                searchText = "match",
                                sort = sort,
                            ),
                    ) { page, _ ->
                        loads += 1
                        WorkshopPage(items = listOf(favorite), page = page, hasNextPage = false)
                    }
                assertEquals(listOf(favorite), result.items)
            }
            assertEquals(1, loads)
        }

    @Test
    fun `filtered cursors preserve each sort while alternating pages in one collection generation`() =
        runTest {
            val pager = AccountCollectionPager(AccountCollectionPagerLimits(sourcePageSize = 2))
            val scope = AccountCollectionScope(accountId = 42L, sessionGeneration = 7L, collectionGeneration = 1L)
            val orders =
                mapOf(
                    AccountWorkshopSort.RELATIONSHIP_DATE_DESC to listOf(4L, 3L, 2L, 1L),
                    AccountWorkshopSort.TITLE_ASC to listOf(1L, 2L, 3L, 4L),
                    AccountWorkshopSort.UPDATED_DESC to listOf(3L, 1L, 4L, 2L),
                    AccountWorkshopSort.CREATED_DESC to listOf(2L, 4L, 1L, 3L),
                )
            val loads = orders.keys.associateWith { mutableListOf<Int>() }

            for (page in 1..4) {
                for ((sort, ids) in orders) {
                    val result =
                        pager.page(
                            scope = scope,
                            query =
                                AccountWorkshopQuery(
                                    collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                                    page = page,
                                    pageSize = 1,
                                    searchText = "match",
                                    sort = sort,
                                ),
                        ) { sourcePage, sourcePageSize ->
                            loads.getValue(sort) += sourcePage
                            WorkshopPage(
                                items =
                                    ids.drop((sourcePage - 1) * sourcePageSize).take(sourcePageSize).map { id ->
                                        WorkshopSummary(
                                            id = id,
                                            title = "match $id",
                                            author = "Creator",
                                            type = WorkshopType.SCENE,
                                            tags = listOf("Wallpaper", "Scene", "Everyone"),
                                        )
                                    },
                                page = sourcePage,
                                hasNextPage = sourcePage * sourcePageSize < ids.size,
                            )
                        }

                    assertEquals(listOf(ids[page - 1]), result.items.map { it.id }, "$sort page $page")
                    assertEquals(page < ids.size, result.hasNextPage, "$sort page $page")
                }
            }
            for ((sort, pages) in loads) {
                assertEquals(listOf(1, 2), pages, "$sort should resume only its own source cursor")
            }
        }
}
