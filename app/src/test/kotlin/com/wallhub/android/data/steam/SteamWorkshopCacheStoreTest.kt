package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopPage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamWorkshopCacheStoreTest {
    @Test
    fun `public CM page expires after two minutes with no stale window`() = runTest {
        var now = 10_000L
        val store = SteamWorkshopCacheStore { now }
        val query = WorkshopBrowseQuery()
        val page = WorkshopPage(emptyList(), page = 1, hasNextPage = false)

        store.publicPages.put(query, page)
        now += 119_999L
        assertEquals(page, store.publicPages.getFresh(query))

        now += 2L
        assertNull(store.publicPages.getFresh(query))
        assertNull(store.publicPages.getStale(query))
    }

    @Test
    fun `public CM detail uses the same two-minute fresh-only window`() = runTest {
        var now = 20_000L
        val store = SteamWorkshopCacheStore { now }
        val detail = WorkshopDetail(WorkshopSummary(id = 42L, title = "detail", author = "author"))

        store.publicDetails.put(42L, detail)
        assertEquals(detail, store.publicDetails.getFresh(42L))
        now += 120_001L
        assertNull(store.publicDetails.getFresh(42L))
        assertNull(store.publicDetails.getStale(42L))
    }

    @Test
    fun `confirmed additions patch only eligible first page projections`() = runTest {
        val store = SteamWorkshopCacheStore { 0L }
        val firstKey =
            AccountPageCacheKey(
                accountId = 10L,
                sessionGeneration = 1L,
                query = AccountWorkshopQuery(AccountWorkshopCollection.FAVORITES, page = 1, pageSize = 2),
            )
        val secondKey = firstKey.copy(query = firstKey.query.copy(page = 2))
        val old = WorkshopSummary(id = 1L, title = "old", author = "author")
        val added =
            WorkshopSummary(
                id = 2L,
                title = "added",
                author = "author",
                favoriteState = FavoriteState.FAVORITED,
            )
        store.accountPages.put(firstKey, WorkshopPage(listOf(old), page = 1, hasNextPage = true))
        store.accountPages.put(secondKey, WorkshopPage(listOf(old), page = 2, hasNextPage = false))

        store.patchAccountCollection(10L, AccountWorkshopCollection.FAVORITES, 2L, true, added)

        assertEquals(listOf(2L, 1L), store.accountPages.getFresh(firstKey)?.items?.map(WorkshopSummary::id))
        assertEquals(listOf(1L), store.accountPages.getFresh(secondKey)?.items?.map(WorkshopSummary::id))
    }

    @Test
    fun `mutation invalidation clears public and account generations together`() = runTest {
        val store = SteamWorkshopCacheStore { 0L }
        store.invalidateAfterMutation()
        assertEquals(1L, store.publicEpoch.get())
        assertEquals(1L, store.accountEpoch.get())
    }
}
