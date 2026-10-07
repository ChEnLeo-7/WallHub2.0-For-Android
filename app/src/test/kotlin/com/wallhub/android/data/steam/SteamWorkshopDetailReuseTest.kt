package com.wallhub.android.data.steam

import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopInteraction
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SteamWorkshopDetailReuseTest {
    @Test
    fun `interaction from detail is consumed without another detail read`() {
        val cache = WorkshopDetailInteractionCache()
        val interaction =
            WorkshopInteraction(
                subscriptionState = SubscriptionState.SUBSCRIBED,
                favoriteState = FavoriteState.NOT_FAVORITED,
            )

        cache.remember(workshopId = 42L, sessionGeneration = 7L, interaction = interaction)

        assertEquals(interaction, cache.take(workshopId = 42L, sessionGeneration = 7L))
        assertEquals(null, cache.take(workshopId = 42L, sessionGeneration = 7L))
    }

    @Test
    fun `interaction from a previous session is not reused`() {
        val cache = WorkshopDetailInteractionCache()
        cache.remember(
            workshopId = 42L,
            sessionGeneration = 7L,
            interaction = WorkshopInteraction(subscriptionState = SubscriptionState.SUBSCRIBED),
        )

        assertEquals(null, cache.take(workshopId = 42L, sessionGeneration = 8L))
    }

    @Test
    fun `old detail interaction is not reused as a later refresh`() {
        var now = 0L
        val cache = WorkshopDetailInteractionCache(freshMillis = 10L, clockMillis = { now })
        cache.remember(
            workshopId = 42L,
            sessionGeneration = 7L,
            interaction = WorkshopInteraction(subscriptionState = SubscriptionState.SUBSCRIBED),
        )

        now = 10L

        assertEquals(null, cache.take(workshopId = 42L, sessionGeneration = 7L))
    }

    @Test
    fun `comment page survives persona enrichment failure with placeholders`() = runBlocking {
        val page =
            WorkshopCommentPage(
                comments =
                    listOf(
                        WorkshopComment(
                            author = "",
                            text = "The comment body",
                            authorId = "76561198000000000",
                            isAuthorPlaceholder = true,
                        ),
                    ),
                start = 0,
                count = 20,
                nextStart = 0,
                total = 1,
                hasMore = false,
                ownerId = "76561198000000001",
            )

        val result = page.withOptionalPersonaEnrichment {
            throw IOException("player service unavailable")
        }

        assertEquals(page, result)
        assertEquals("The comment body", result.comments.single().text)
        assertEquals(true, result.comments.single().isAuthorPlaceholder)
    }

    @Test
    fun `persona enrichment does not swallow cancellation`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                WorkshopCommentPage(
                    comments = emptyList(),
                    start = 0,
                    count = 20,
                    nextStart = 0,
                    total = 0,
                    hasMore = false,
                    ownerId = null,
                ).withOptionalPersonaEnrichment {
                    throw CancellationException("cancelled")
                }
            }
        }
    }
}
