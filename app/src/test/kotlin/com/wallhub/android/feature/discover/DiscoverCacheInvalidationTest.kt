package com.wallhub.android.feature.discover

import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverCacheInvalidationTest {
    @Test
    fun `realtime generation invalidates Steam data caches without deleting curator LKG`() = runTest {
        val invalidated = mutableListOf<String>()

        invalidateDiscoverCaches(
            unifiedWorkshopRepository = Unified(invalidated),
            workshopRepository = Workshop(invalidated),
            collectionResolver = Collection(invalidated),
        )

        assertEquals(listOf("unified", "workshop", "collection"), invalidated)
    }

    private class Unified(private val invalidated: MutableList<String>) : SteamUnifiedWorkshopRepository {
        override suspend fun invalidateDiscoverCache() { invalidated += "unified" }
        override suspend fun browsePublic(query: WorkshopBrowseQuery): WorkshopPage? = null
        override suspend fun getPublicDetail(workshopId: Long): WorkshopDetail? = null
        override suspend fun getAuthenticatedComments(
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String,
        ): WorkshopCommentPage? = null
    }

    private class Workshop(private val invalidated: MutableList<String>) : WorkshopRepository {
        override suspend fun invalidateDiscoverCache() { invalidated += "workshop" }
        override suspend fun browse(query: WorkshopBrowseQuery): WorkshopPage = error("not used")
        override suspend fun getDetail(workshopId: Long): WorkshopDetail = error("not used")
        override suspend fun getComments(
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String?,
        ): WorkshopCommentPage = error("not used")
    }

    private class Collection(private val invalidated: MutableList<String>) : DiscoverCollectionResolver {
        override suspend fun invalidateDiscoverCache() { invalidated += "collection" }
        override suspend fun browse(collectionId: Long, page: Int, pageSize: Int): WorkshopPage = error("not used")
    }
}
