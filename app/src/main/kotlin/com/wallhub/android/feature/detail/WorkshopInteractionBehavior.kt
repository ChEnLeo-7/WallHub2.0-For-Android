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
    isLoading: Boolean,
    isUpdating: Boolean,
): Boolean =
    !isLoading &&
        !isUpdating &&
        subscriptionState != SubscriptionState.UNKNOWN

internal fun WorkshopInteraction.canToggleFavorite(
    isLoading: Boolean,
    isUpdating: Boolean,
): Boolean =
    !isLoading &&
        !isUpdating &&
        favoriteState != FavoriteState.UNKNOWN
