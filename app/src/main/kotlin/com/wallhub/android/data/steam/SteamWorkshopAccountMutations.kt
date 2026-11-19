package com.wallhub.android.data.steam

import android.util.Log
import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WALLPAPER_ENGINE_APP_ID
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopSummary
import io.steamcontent.sdk.WorkshopItemField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private fun expectedSubscriptionState(requested: Boolean): SubscriptionState =
    if (requested) SubscriptionState.SUBSCRIBED else SubscriptionState.NOT_SUBSCRIBED

internal fun confirmRemoteSubscriptionMutation(
    requested: Boolean,
    acknowledged: Boolean,
    observed: SubscriptionState,
): SubscriptionState {
    check(acknowledged == requested) { "Steam acknowledged a different subscription state" }
    check(observed == expectedSubscriptionState(requested)) {
        "Steam did not confirm the requested subscription state"
    }
    return observed
}

/** Authenticated interaction reads and mutations; public catalog reads have no dependency on this module. */
internal class SteamWorkshopAccountMutations(
    private val engine: SteamContentSdkEngine,
    private val gateway: SteamWorkshopSdkGateway,
    private val cache: SteamWorkshopCacheStore,
    private val mutations: SteamAccountCollectionMutationLedger,
) {
    private val subscriptionMutationMutex = Mutex()

    suspend fun getInteraction(workshopId: Long): WorkshopInteraction =
        withContext(Dispatchers.IO) {
            awaitWorkshopSession(WorkshopSessionAccess.AUTHENTICATED_ACCOUNT, gateway::awaitSignedInSession)
            val generation = engine.currentSessionGeneration()
            if (generation != null) {
                cache.detailInteractions.take(workshopId, generation)?.let { return@withContext it }
            }
            runCatchingUnavailable {
                gateway.read(
                    key = "interaction:$workshopId",
                    kind = WorkshopReadKind.INTERACTION,
                    priority = WorkshopReadPriority.FOREGROUND,
                ) { readInteraction(workshopId) }
            } ?: WorkshopInteraction()
        }

    suspend fun setSubscribed(
        workshopId: Long,
        subscribed: Boolean,
        summary: WorkshopSummary?,
    ): WorkshopInteraction =
        setSubscribed(
            expectedAccountId = null,
            expectedAccountName = null,
            workshopId = workshopId,
            subscribed = subscribed,
            summary = summary,
        )

    suspend fun setSubscribedForCurrentAccount(
        expectedAccountName: String,
        workshopId: Long,
        subscribed: Boolean,
        summary: WorkshopSummary?,
    ): WorkshopInteraction =
        setSubscribed(
            expectedAccountId = null,
            workshopId = workshopId,
            subscribed = subscribed,
            summary = summary,
            expectedAccountName = expectedAccountName,
        )

    suspend fun setSubscribed(
        expectedAccountId: Long?,
        workshopId: Long,
        subscribed: Boolean,
        summary: WorkshopSummary?,
        expectedAccountName: String? = null,
    ): WorkshopInteraction =
        withContext(Dispatchers.IO) {
            subscriptionMutationMutex.withLock {
                awaitWorkshopSession(WorkshopSessionAccess.AUTHENTICATED_ACCOUNT, gateway::awaitSignedInSession)
                val accountId = gateway.signedInAccountId() ?: error("No signed-in Steam account is available")
                val sessionGeneration = gateway.currentSessionGeneration() ?: error("No signed-in Steam session is available")
                check(expectedAccountId == null || expectedAccountId == accountId) {
                    "The signed-in Steam account changed before the mutation"
                }
                check(expectedAccountName == null || gateway.signedInAccountName() == expectedAccountName) {
                    "The signed-in Steam account changed before the mutation"
                }
                val acknowledgement =
                    gateway.write(WorkshopReadKind.SET_SUBSCRIBED) {
                        val actingAccountId = gateway.signedInAccountId() ?: error("No signed-in Steam account is available")
                        check(actingAccountId == accountId) { "The signed-in Steam account changed during the mutation" }
                        check(expectedAccountName == null || gateway.signedInAccountName() == expectedAccountName) {
                            "The signed-in Steam account changed during the mutation"
                        }
                        check(gateway.currentSessionGeneration() == sessionGeneration) {
                            "The signed-in Steam session changed during the mutation"
                        }
                        engine.setSubscribed(workshopId, actingAccountId, subscribed)
                    }
                val confirmedState =
                    confirmSubscriptionReadBack(
                        accountId = accountId,
                        sessionGeneration = sessionGeneration,
                        workshopId = workshopId,
                        requested = subscribed,
                        acknowledged = acknowledgement.enabled,
                    )
                Log.i(TAG, "workshop subscribe write id=$workshopId ask=$subscribed confirmed=$confirmedState")
                cache.invalidateAfterMutation()
                val mutation =
                    mutations.confirmedMutation(
                        accountId = accountId,
                        collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                        itemId = workshopId,
                        present = confirmedState == SubscriptionState.SUBSCRIBED,
                        summary = summary,
                        cachedPage = null,
                        publishChange = false,
                    )
                mutations.publish(mutation.change)
                WorkshopInteraction(subscriptionState = confirmedState)
            }
        }

    private suspend fun confirmSubscriptionReadBack(
        accountId: Long,
        sessionGeneration: Long,
        workshopId: Long,
        requested: Boolean,
        acknowledged: Boolean,
    ): SubscriptionState {
        check(acknowledged == requested) { "Steam acknowledged a different subscription state" }
        var lastFailure: Throwable? = null
        repeat(SUBSCRIPTION_CONFIRMATION_ATTEMPTS) { attempt ->
            try {
                check(gateway.signedInAccountId() == accountId) {
                    "The signed-in Steam account changed while confirming the mutation"
                }
                check(gateway.currentSessionGeneration() == sessionGeneration) {
                    "The signed-in Steam session changed while confirming the mutation"
                }
                val observed =
                    gateway.readOnce(
                        key = "confirm-subscription:$workshopId:$attempt",
                        kind = WorkshopReadKind.INTERACTION,
                        priority = WorkshopReadPriority.FOREGROUND,
                    ) {
                        check(gateway.signedInAccountId() == accountId)
                        check(gateway.currentSessionGeneration() == sessionGeneration)
                        readInteraction(workshopId).subscriptionState
                    }
                check(gateway.signedInAccountId() == accountId) {
                    "The signed-in Steam account changed while confirming the mutation"
                }
                check(gateway.currentSessionGeneration() == sessionGeneration) {
                    "The signed-in Steam session changed while confirming the mutation"
                }
                if (observed == expectedSubscriptionState(requested)) return observed
                lastFailure = IllegalStateException("Steam did not confirm the requested subscription state")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
            }
            if (attempt + 1 < SUBSCRIPTION_CONFIRMATION_ATTEMPTS) {
                delay(SUBSCRIPTION_CONFIRMATION_DELAY_MS * (attempt + 1L))
            }
        }
        throw lastFailure ?: IllegalStateException("Steam did not confirm the requested subscription state")
    }

    suspend fun setFavorited(
        workshopId: Long,
        favorited: Boolean,
        summary: WorkshopSummary?,
    ): WorkshopInteraction = setFavorited(null, workshopId, favorited, summary)

    suspend fun setFavoritedForCurrentAccount(
        expectedAccountName: String,
        workshopId: Long,
        favorited: Boolean,
        summary: WorkshopSummary?,
    ): WorkshopInteraction =
        setFavorited(
            expectedAccountId = null,
            workshopId = workshopId,
            favorited = favorited,
            summary = summary,
            expectedAccountName = expectedAccountName,
        )

    suspend fun setFavorited(
        expectedAccountId: Long?,
        workshopId: Long,
        favorited: Boolean,
        summary: WorkshopSummary?,
        expectedAccountName: String? = null,
    ): WorkshopInteraction =
        withContext(Dispatchers.IO) {
            awaitWorkshopSession(WorkshopSessionAccess.AUTHENTICATED_ACCOUNT, gateway::awaitSignedInSession)
            val accountId = gateway.signedInAccountId() ?: error("No signed-in Steam account is available")
            check(expectedAccountId == null || expectedAccountId == accountId) {
                "The signed-in Steam account changed before the mutation"
            }
            check(expectedAccountName == null || gateway.signedInAccountName() == expectedAccountName) {
                "The signed-in Steam account changed before the mutation"
            }
            val confirmed = gateway.write(WorkshopReadKind.SET_FAVORITED) {
                val actingAccountId = gateway.signedInAccountId() ?: error("No signed-in Steam account is available")
                check(actingAccountId == accountId) { "The signed-in Steam account changed during the mutation" }
                check(expectedAccountName == null || gateway.signedInAccountName() == expectedAccountName) {
                    "The signed-in Steam account changed during the mutation"
                }
                engine.setFavorited(workshopId, actingAccountId, favorited)
            }
            Log.i(TAG, "workshop favorite write id=$workshopId ask=$favorited confirmed=${confirmed.enabled}")
            cache.invalidateAfterMutation()
            val mutation =
                mutations.confirmedMutation(
                    accountId = accountId,
                    collection = AccountWorkshopCollection.FAVORITES,
                    itemId = workshopId,
                    present = confirmed.enabled,
                    summary = summary,
                    cachedPage = null,
                    publishChange = false,
                )
            mutations.publish(mutation.change)
            WorkshopInteraction(
                favoriteState = if (confirmed.enabled) FavoriteState.FAVORITED else FavoriteState.NOT_FAVORITED,
            )
        }

    suspend fun postComment(workshopId: Long, ownerId: String, text: String) =
        withContext(Dispatchers.IO) {
            awaitWorkshopSession(WorkshopSessionAccess.AUTHENTICATED_ACCOUNT, gateway::awaitSignedInSession)
            val request = normalizeWorkshopCommentRequest(workshopId, ownerId, text)
            gateway.write(WorkshopReadKind.POST_COMMENT) {
                engine.postComment(request.workshopId, request.ownerId.toLong(), request.text)
            }
            cache.invalidateAfterMutation()
        }

    private fun readInteraction(workshopId: Long): WorkshopInteraction {
        require(workshopId > 0L) { "Invalid Workshop item ID" }
        return engine.openWorkshopItem(workshopId, WALLPAPER_ENGINE_APP_ID.toLong()).use { result ->
            interactionOf(result).also { interaction ->
                Log.i(
                    TAG,
                    "workshop interaction id=$workshopId subscribed=${interaction.subscriptionState} " +
                        "favorited=${interaction.favoriteState} " +
                        "subscribed-field=${result.field(WorkshopItemField.SUBSCRIBED).state} " +
                        "favorited-field=${result.field(WorkshopItemField.FAVOURITED).state}",
                )
            }
        }
    }

    private companion object {
        const val TAG = "WallHubSdkWorkshop"
        const val SUBSCRIPTION_CONFIRMATION_ATTEMPTS = 3
        const val SUBSCRIPTION_CONFIRMATION_DELAY_MS = 250L
    }
}
