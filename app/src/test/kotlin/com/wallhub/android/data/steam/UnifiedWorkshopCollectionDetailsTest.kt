package com.wallhub.android.data.steam

import steam.webui.publishedfile.CPublishedFile_GetDetails_Response
import steam.webui.publishedfile.PublishedFileDetails
import steam.webui.publishedfile.PublishedFileDetails_Child
import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedWorkshopCollectionDetailsTest {
    @Test
    fun collectionChildrenPreserveSteamSortOrderAndRemoveDuplicates() {
        val collectionId = 1_884_277_090L
        val detail =
            PublishedFileDetails(
                publishedfileid = collectionId,
                result = ERESULT_OK,
                children =
                    listOf(
                        child(id = 30L, order = 2),
                        child(id = 10L, order = 0),
                        child(id = 20L, order = 1),
                        child(id = 10L, order = 3),
                    ),
            )
        val response = CPublishedFile_GetDetails_Response(publishedfiledetails = listOf(detail))

        assertEquals(listOf(10L, 20L, 30L), mapUnifiedCollectionChildIds(collectionId, response))
    }

    private fun child(
        id: Long,
        order: Int,
    ) = PublishedFileDetails_Child(
        publishedfileid = id,
        sortorder = order,
    )
}
