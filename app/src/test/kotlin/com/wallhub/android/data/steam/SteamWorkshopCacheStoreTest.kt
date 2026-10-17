package com.wallhub.android.data.steam

import com.wallhub.android.core.model.WorkshopBrowseQuery
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
    fun `mutation invalidation clears public and account generations together`() = runTest {
        val store = SteamWorkshopCacheStore { 0L }
        store.invalidateAfterMutation()
        assertEquals(1L, store.publicEpoch.get())
        assertEquals(1L, store.accountEpoch.get())
    }
}
