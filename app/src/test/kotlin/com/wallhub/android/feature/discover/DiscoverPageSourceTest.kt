package com.wallhub.android.feature.discover

import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiscoverPageSourceTest {
    @Test
    fun `query prefers unified Steam results and preserves their order`() = runTest {
        val unified = FakeUnified(page(3L, 1L, 2L))
        val fallback = FakeWorkshop(page(9L))
        val source = DefaultDiscoverPageSource(unified, fallback, UnusedCollection, DiscoverNetworkBudget())

        val result = source.page(DiscoverPageRecipe(WorkshopBrowseQuery()), page = 1, pageSize = 20)

        assertEquals(listOf(3L, 1L, 2L), result.items.map(WorkshopSummary::id))
        assertEquals(1, unified.browseCalls)
        assertEquals(0, fallback.browseCalls)
    }

    @Test
    fun `query falls back only when unified Steam is unavailable`() = runTest {
        val unified = FakeUnified(null)
        val fallback = FakeWorkshop(page(8L, 7L))
        val source = DefaultDiscoverPageSource(unified, fallback, UnusedCollection, DiscoverNetworkBudget())

        val result = source.page(DiscoverPageRecipe(WorkshopBrowseQuery()), page = 1, pageSize = 20)

        assertEquals(listOf(8L, 7L), result.items.map(WorkshopSummary::id))
        assertEquals(1, unified.browseCalls)
        assertEquals(1, fallback.browseCalls)
    }

    @Test
    fun `collection preserves child order and does not query ordinary browse`() = runTest {
        val unified = FakeUnified(page(99L))
        val fallback = FakeWorkshop(page(98L))
        val collection = FakeCollection(page(4L, 2L, 6L))
        val source = DefaultDiscoverPageSource(unified, fallback, collection, DiscoverNetworkBudget())

        val result = source.page(
            DiscoverPageRecipe(query = WorkshopBrowseQuery(), collectionId = 123L),
            page = 1,
            pageSize = 20,
        )

        assertEquals(listOf(4L, 2L, 6L), result.items.map(WorkshopSummary::id))
        assertEquals(0, unified.browseCalls)
        assertEquals(0, fallback.browseCalls)
        assertEquals(1, collection.browseCalls)
    }

    private class FakeUnified(private val result: WorkshopPage?) : SteamUnifiedWorkshopRepository {
        var browseCalls = 0
        override suspend fun browsePublic(query: WorkshopBrowseQuery): WorkshopPage? {
            browseCalls += 1
            return result
        }
        override suspend fun getPublicDetail(workshopId: Long): WorkshopDetail? = null
        override suspend fun getAuthenticatedComments(
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String,
        ): WorkshopCommentPage? = null
    }

    private class FakeWorkshop(private val result: WorkshopPage) : WorkshopRepository {
        var browseCalls = 0
        override suspend fun browse(query: WorkshopBrowseQuery): WorkshopPage {
            browseCalls += 1
            return result
        }
        override suspend fun getDetail(workshopId: Long): WorkshopDetail = error("not used")
        override suspend fun getComments(
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String?,
        ): WorkshopCommentPage = error("not used")
    }

    private class FakeCollection(private val result: WorkshopPage) : DiscoverCollectionResolver {
        var browseCalls = 0
        override suspend fun browse(collectionId: Long, page: Int, pageSize: Int): WorkshopPage {
            browseCalls += 1
            return result
        }
    }

    private object UnusedCollection : DiscoverCollectionResolver {
        override suspend fun browse(collectionId: Long, page: Int, pageSize: Int): WorkshopPage = error("not used")
    }

    private fun page(vararg ids: Long) =
        WorkshopPage(
            items = ids.map { WorkshopSummary(id = it, title = "item-$it", author = "author", type = WorkshopType.SCENE) },
            page = 1,
            hasNextPage = false,
        )
}
