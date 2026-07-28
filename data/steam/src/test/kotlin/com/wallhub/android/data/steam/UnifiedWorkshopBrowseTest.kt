package com.wallhub.android.data.steam

import `in`.dragonbra.javasteam.enums.EPublishedFileQueryType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedWorkshopBrowseTest {
    @Test
    fun `query request maps discovery filters to published file rpc`() {
        val request = buildUnifiedWorkshopBrowseRequest(
            WorkshopBrowseQuery(
                page = 2,
                pageSize = 24,
                types = setOf(WorkshopType.VIDEO),
                genres = setOf("Nature"),
                officialTags = setOf("3D"),
                resolutions = setOf("1920 x 1080"),
                ratings = setOf(WorkshopRating.EVERYONE),
                days = 30,
                sort = WorkshopSort.TRENDING,
            ),
        )

        assertEquals(EPublishedFileQueryType.RankedByTrend.code(), request.queryType)
        assertEquals(2, request.page)
        assertEquals(24, request.numperpage)
        assertEquals(431960, request.creatorAppid)
        assertEquals(431960, request.appid)
        assertEquals(30, request.days)
        assertTrue(request.includeRecentVotesOnly)
        assertEquals(
            setOf("Video", "Everyone", "Nature", "3D", "1920 x 1080"),
            request.requiredtagsList.toSet(),
        )
        assertTrue(request.returnTags)
        assertTrue(request.returnPreviews)
        assertTrue(request.returnDetails)
    }

    @Test
    fun `text search uses the Steam text ranking mode`() {
        val request = buildUnifiedWorkshopBrowseRequest(
            WorkshopBrowseQuery(
                searchText = "  neon city  ",
                sort = WorkshopSort.MOST_RECENT,
            ),
        )

        assertEquals(EPublishedFileQueryType.RankedByTextSearch.code(), request.queryType)
        assertEquals("neon city", request.searchText)
        assertFalse(request.includeRecentVotesOnly)
    }

    @Test
    fun `multiple category selections exclude only unselected Steam tags`() {
        val request = buildUnifiedWorkshopBrowseRequest(
            WorkshopBrowseQuery(
                types = setOf(WorkshopType.VIDEO, WorkshopType.SCENE),
                ratings = setOf(WorkshopRating.EVERYONE, WorkshopRating.QUESTIONABLE),
                officialTags = setOf("3D"),
            ),
        )

        assertEquals(setOf("3D"), request.requiredtagsList.toSet())
        assertEquals(setOf("Web", "Mature"), request.excludedtagsList.toSet())
    }

    @Test
    fun `query response maps details and pagination without html parsing`() {
        val detail = SteammessagesPublishedfileSteamclient.PublishedFileDetails
            .newBuilder()
            .setResult(EResult.OK.code())
            .setPublishedfileid(3418132227L)
            .setCreator(76561198000000000L)
            .setTitle("Neon City")
            .setPreviewUrl("https://images.steamusercontent.com/preview.jpg")
            .setSubscriptions(120)
            .setFavorited(25)
            .setViews(300)
            .setFileSize(4096L)
            .addTags(workshopTag("Video"))
            .addTags(workshopTag("Everyone"))
            .build()
        val response = SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Response
            .newBuilder()
            .setTotal(61)
            .addPublishedfiledetails(detail)
            .build()

        val page = mapUnifiedWorkshopBrowseResponse(
            query = WorkshopBrowseQuery(
                page = 2,
                pageSize = 24,
                types = setOf(WorkshopType.VIDEO),
                ratings = setOf(WorkshopRating.EVERYONE),
            ),
            response = response,
        )

        assertEquals(2, page.page)
        assertEquals(61, page.totalCount)
        assertEquals(3, page.totalPages)
        assertTrue(page.hasNextPage)
        assertEquals(1, page.items.size)
        assertEquals(3418132227L, page.items.single().id)
        assertEquals("Neon City", page.items.single().title)
        assertEquals(WorkshopType.VIDEO, page.items.single().type)
        assertEquals(120L, page.items.single().subscriptions)
        assertEquals(4096L, page.items.single().fileSizeBytes)
    }

    @Test
    fun `reported maximum page stays within the established browse limit`() {
        val response = SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Response
            .newBuilder()
            .setTotal(Int.MAX_VALUE)
            .build()

        val page = mapUnifiedWorkshopBrowseResponse(
            query = WorkshopBrowseQuery(pageSize = 1),
            response = response,
        )

        assertEquals(1_000, page.totalPages)
    }

    private fun workshopTag(
        value: String,
    ): SteammessagesPublishedfileSteamclient.PublishedFileDetails.Tag =
        SteammessagesPublishedfileSteamclient.PublishedFileDetails.Tag
            .newBuilder()
            .setTag(value)
            .build()
}
