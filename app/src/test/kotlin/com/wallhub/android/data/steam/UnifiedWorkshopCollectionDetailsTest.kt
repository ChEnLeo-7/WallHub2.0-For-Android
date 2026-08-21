package com.wallhub.android.data.steam

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient
import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedWorkshopCollectionDetailsTest {
    @Test
    fun collectionChildrenPreserveSteamSortOrderAndRemoveDuplicates() {
        val collectionId = 1_884_277_090L
        val detail =
            SteammessagesPublishedfileSteamclient.PublishedFileDetails
                .newBuilder()
                .setPublishedfileid(collectionId)
                .setResult(EResult.OK.code())
                .addChildren(child(id = 30L, order = 2))
                .addChildren(child(id = 10L, order = 0))
                .addChildren(child(id = 20L, order = 1))
                .addChildren(child(id = 10L, order = 3))
                .build()
        val response =
            SteammessagesPublishedfileSteamclient.CPublishedFile_GetDetails_Response
                .newBuilder()
                .addPublishedfiledetails(detail)
                .build()

        assertEquals(listOf(10L, 20L, 30L), mapUnifiedCollectionChildIds(collectionId, response))
    }

    private fun child(
        id: Long,
        order: Int,
    ) = SteammessagesPublishedfileSteamclient.PublishedFileDetails.Child
        .newBuilder()
        .setPublishedfileid(id)
        .setSortorder(order)
        .build()
}
