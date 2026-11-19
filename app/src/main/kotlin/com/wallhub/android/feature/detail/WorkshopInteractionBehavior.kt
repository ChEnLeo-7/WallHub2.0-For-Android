package com.wallhub.android.feature.detail

import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopInteraction

internal fun WorkshopInteraction.mergeKnownFields(update: WorkshopInteraction): WorkshopInteraction =
    WorkshopInteraction(
        subscriptionState =
            update.subscriptionState.takeUnless { it == SubscriptionState.UNKNOWN }
                ?: subscriptionState,
        favoriteState =
            update.favoriteState.takeUnless { it == FavoriteState.UNKNOWN }
                ?: favoriteState,
    )

internal fun WorkshopInteraction.canToggleSubscription(
    isUpdating: Boolean,
    canSubmitUnknownState: Boolean = false,
): Boolean =
    !isUpdating && (subscriptionState != SubscriptionState.UNKNOWN || canSubmitUnknownState)

internal fun WorkshopInteraction.canToggleFavorite(
    isUpdating: Boolean,
    canSubmitUnknownState: Boolean = false,
): Boolean =
    !isUpdating && (favoriteState != FavoriteState.UNKNOWN || canSubmitUnknownState)

internal fun WorkshopInteraction.requestedSubscriptionState(): Boolean =
    subscriptionState != SubscriptionState.SUBSCRIBED

internal fun WorkshopInteraction.requestedFavoriteState(): Boolean =
    favoriteState != FavoriteState.FAVORITED
