package com.wallhub.android.feature.detail

import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopInteraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkshopInteractionBehaviorTest {
    @Test
    fun `connected account can submit add while interaction is unknown`() {
        val interaction = WorkshopInteraction()

        assertTrue(interaction.canToggleSubscription(isUpdating = false, canSubmitUnknownState = true))
        assertTrue(interaction.canToggleFavorite(isUpdating = false, canSubmitUnknownState = true))
        assertEquals(true, interaction.requestedSubscriptionState())
        assertEquals(true, interaction.requestedFavoriteState())
    }

    @Test
    fun `unknown interaction stays disabled without a connected account`() {
        val interaction = WorkshopInteraction()

        assertFalse(interaction.canToggleSubscription(isUpdating = false))
        assertFalse(interaction.canToggleFavorite(isUpdating = false))
        assertFalse(interaction.canToggleSubscription(isUpdating = true, canSubmitUnknownState = true))
        assertFalse(interaction.canToggleFavorite(isUpdating = true, canSubmitUnknownState = true))
    }

    @Test
    fun `known interaction toggles to opposite explicit target`() {
        assertEquals(
            false,
            WorkshopInteraction(subscriptionState = SubscriptionState.SUBSCRIBED).requestedSubscriptionState(),
        )
        assertEquals(
            true,
            WorkshopInteraction(subscriptionState = SubscriptionState.NOT_SUBSCRIBED).requestedSubscriptionState(),
        )
        assertEquals(
            false,
            WorkshopInteraction(favoriteState = FavoriteState.FAVORITED).requestedFavoriteState(),
        )
        assertEquals(
            true,
            WorkshopInteraction(favoriteState = FavoriteState.NOT_FAVORITED).requestedFavoriteState(),
        )
    }
}
