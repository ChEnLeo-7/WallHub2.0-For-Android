package com.wallhub.android.feature.detail

import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopInteraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopInteractionBehaviorTest {
    @Test
    fun `unknown fields stay disabled independently`() {
        val interaction =
            WorkshopInteraction(
                subscriptionState = SubscriptionState.UNKNOWN,
                favoriteState = FavoriteState.FAVORITED,
            )

        assertFalse(interaction.canToggleSubscription(isLoading = false, isUpdating = false))
        assertTrue(interaction.canToggleFavorite(isLoading = false, isUpdating = false))
        assertFalse(interaction.canToggleFavorite(isLoading = true, isUpdating = false))
        assertFalse(interaction.canToggleFavorite(isLoading = false, isUpdating = true))
    }

    @Test
    fun `subscription mutation response preserves known favorite`() {
        val current =
            WorkshopInteraction(
                subscriptionState = SubscriptionState.NOT_SUBSCRIBED,
                favoriteState = FavoriteState.FAVORITED,
            )
        val subscriptionOnly =
            WorkshopInteraction(subscriptionState = SubscriptionState.SUBSCRIBED)

        assertEquals(
            WorkshopInteraction(
                subscriptionState = SubscriptionState.SUBSCRIBED,
                favoriteState = FavoriteState.FAVORITED,
            ),
            current.mergeKnownFields(subscriptionOnly),
        )
    }

    @Test
    fun `favorite mutation response preserves known subscription`() {
        val current =
            WorkshopInteraction(
                subscriptionState = SubscriptionState.SUBSCRIBED,
                favoriteState = FavoriteState.NOT_FAVORITED,
            )
        val favoriteOnly =
            WorkshopInteraction(favoriteState = FavoriteState.FAVORITED)

        assertEquals(
            WorkshopInteraction(
                subscriptionState = SubscriptionState.SUBSCRIBED,
                favoriteState = FavoriteState.FAVORITED,
            ),
            current.mergeKnownFields(favoriteOnly),
        )
    }
}
