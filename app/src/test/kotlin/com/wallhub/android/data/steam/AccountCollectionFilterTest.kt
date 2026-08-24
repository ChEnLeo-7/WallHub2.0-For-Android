package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountCollectionFilterTest {
    private val wallpaper =
        WorkshopSummary(
            id = 3423261668,
            title = "Neon City Rain",
            author = "Creator",
            type = WorkshopType.VIDEO,
            tags = listOf("Wallpaper", "Video", "Everyone", "Anime", "1920 x 1080", "Approved"),
        )

    @Test
    fun `advanced collection filters match all selected dimensions`() {
        val query =
            AccountWorkshopQuery(
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                searchText = "Neon City",
                exactPhrase = true,
                types = setOf(WorkshopType.VIDEO),
                ratings = setOf(WorkshopRating.EVERYONE),
                genres = setOf("Anime"),
                officialTags = setOf("Approved"),
                resolutions = setOf("1920 x 1080"),
            )

        assertTrue(query.matchesAccountCollectionItem(wallpaper))
    }

    @Test
    fun `excluded official tag rejects a collection item`() {
        val query =
            AccountWorkshopQuery(
                collection = AccountWorkshopCollection.FAVORITES,
                excludedOfficialTags = setOf("Approved"),
            )

        assertFalse(query.matchesAccountCollectionItem(wallpaper))
    }

    @Test
    fun `type and rating filters reject mismatched items`() {
        assertFalse(
            AccountWorkshopQuery(
                collection = AccountWorkshopCollection.VOTED,
                types = setOf(WorkshopType.SCENE),
            ).matchesAccountCollectionItem(wallpaper),
        )
        assertFalse(
            AccountWorkshopQuery(
                collection = AccountWorkshopCollection.VOTED,
                ratings = setOf(WorkshopRating.MATURE),
            ).matchesAccountCollectionItem(wallpaper),
        )
    }

    @Test
    fun `token search can span fields while exact phrase cannot`() {
        val tokenQuery =
            AccountWorkshopQuery(
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                searchText = "Neon Anime",
            )
        val phraseQuery = tokenQuery.copy(exactPhrase = true)

        assertTrue(tokenQuery.matchesAccountCollectionItem(wallpaper))
        assertFalse(phraseQuery.matchesAccountCollectionItem(wallpaper))
    }
}
