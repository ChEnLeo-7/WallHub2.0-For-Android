package com.wallhub.android.data.steam

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient
import com.wallhub.android.data.steam.protobuf.CommunityMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedWorkshopDetailsTest {
    @Test
    fun `detail request includes public metadata fields`() {
        val request = buildUnifiedWorkshopDetailRequest(123L)

        assertEquals(listOf(123L), request.publishedfileidsList)
        assertEquals(WALLPAPER_ENGINE_APP_ID, request.appid)
        assertTrue(request.includetags)
        assertTrue(request.includeadditionalpreviews)
        assertTrue(request.includemetadata)
        assertTrue(request.stripDescriptionBbcode)
    }

    @Test
    fun `detail response maps creator profile and metadata`() {
        val detail = SteammessagesPublishedfileSteamclient.PublishedFileDetails.newBuilder()
            .setResult(EResult.OK.code())
            .setPublishedfileid(123L)
            .setCreator(76561198000000001L)
            .setTitle("Example")
            .setFileDescription("Description")
            .setPreviewUrl("https://images.example/preview.jpg")
            .setFileSize(4096L)
            .setTimeCreated(100)
            .setTimeUpdated(200)
            .setSubscriptions(300)
            .addTags(
                SteammessagesPublishedfileSteamclient.PublishedFileDetails.Tag.newBuilder()
                    .setTag("Scene")
                    .build(),
            )
            .build()

        val mapped = mapUnifiedWorkshopDetail(
            detail = detail,
            creatorProfile = SteamProfile("Creator", "https://images.example/avatar.jpg"),
        )

        assertEquals(123L, mapped.summary.id)
        assertEquals("Creator", mapped.summary.author)
        assertEquals("76561198000000001", mapped.creatorId)
        assertEquals("Description", mapped.description)
        assertEquals(4096L, mapped.fileSizeBytes)
        assertEquals(300L, mapped.subscriptions)
    }

    @Test
    fun `comment request uses public published file thread`() {
        val request = buildCommunityCommentRequest(
            workshopId = 123L,
            ownerId = "76561198000000001",
            start = -4,
            count = 100,
        )

        assertEquals(76561198000000001L, request.steamid)
        assertEquals(5, request.commentThreadType)
        assertEquals(123L, request.gidfeature)
        assertEquals(-1L, request.gidfeature2)
        assertEquals(0, request.start)
        assertEquals(50, request.count)
    }

    @Test
    fun `comment response maps profiles creator and pagination`() {
        val response = CommunityMessages.GetCommentThreadResponse.newBuilder()
            .setSteamid(76561198000000001L)
            .setStart(20)
            .setCount(2)
            .setTotalCount(25)
            .addComments(
                CommunityMessages.Comment.newBuilder()
                    .setGidcomment(1L)
                    .setSteamid(76561198000000001L)
                    .setTimestamp(1_700_000_000)
                    .setText("Creator comment"),
            )
            .addComments(
                CommunityMessages.Comment.newBuilder()
                    .setGidcomment(2L)
                    .setSteamid(76561198000000002L)
                    .setTimestamp(1_700_000_001)
                    .setText("User comment"),
            )
            .build()

        val page = mapCommunityComments(
            response = response,
            requestedStart = 0,
            requestedCount = 20,
            creatorId = "76561198000000001",
            profiles = mapOf(
                76561198000000001L to SteamProfile("Creator"),
                76561198000000002L to SteamProfile("Reader", "https://images.example/avatar.jpg"),
            ),
        )

        assertEquals(listOf("Creator", "Reader"), page.comments.map { it.author })
        assertEquals("76561198000000002", page.comments[1].authorId)
        assertTrue(page.comments[0].isCreator)
        assertFalse(page.comments[1].isCreator)
        assertEquals(22, page.nextStart)
        assertEquals(25, page.total)
        assertTrue(page.hasMore)
    }

    @Test
    fun `post request preserves validated text`() {
        val request = buildCommunityPostRequest(
            normalizeWorkshopCommentRequest(
                workshopId = 123L,
                ownerId = "76561198000000001",
                text = "  hello  ",
            ),
        )

        assertEquals(76561198000000001L, request.steamid)
        assertEquals(5, request.commentThreadType)
        assertEquals(123L, request.gidfeature)
        assertEquals("hello", request.text)
    }
}
