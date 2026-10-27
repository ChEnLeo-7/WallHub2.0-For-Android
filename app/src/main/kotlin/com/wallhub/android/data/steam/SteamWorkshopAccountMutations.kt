package com.wallhub.android.data.steam

import android.util.Log
import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WALLPAPER_ENGINE_APP_ID
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopSummary
import io.steamcontent.sdk.WorkshopItemField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun persistAccountCollectionMutation(
    snapshots: AccountWorkshopPageSnapshotStore,
    accountId: Long,
    collection: AccountWorkshopCollection,
    itemId: Long,
    present: Boolean,
    summary: WorkshopSummary?,
): Boolean =
    snapshots.updateCollection(accountId, collection, itemId, present, summary) ||
        snapshots.removeAccount(accountId)

/** Authenticated interaction reads and mutations; public catalog reads have no dependency on this module. */
internal class SteamWorkshopAccountMutations(
    private val engine: SteamContentSdkEngine,
    private val gateway: SteamWorkshopSdkGateway,
    private val cache: SteamWorkshopCacheStore,
    private val mutations: SteamAccountCollectionMutationLedger,
    private val snapshots: AccountWorkshopPageSnapshotStore,
) {
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
        withContext(Dispatchers.IO) {
            awaitWorkshopSession(WorkshopSessionAccess.AUTHENTICATED_ACCOUNT, gateway::awaitSignedInSession)
            val accountId = gateway.signedInAccountId() ?: error("No signed-in Steam account is available")
            val confirmed = gateway.write(WorkshopReadKind.SET_SUBSCRIBED) {
                val actingAccountId = gateway.signedInAccountId() ?: error("No signed-in Steam account is available")
                check(actingAccountId == accountId) { "The signed-in Steam account changed during the mutation" }
                engine.setSubscribed(workshopId, actingAccountId, subscribed)
            }
            Log.i(TAG, "workshop subscribe write id=$workshopId ask=$subscribed confirmed=${confirmed.enabled}")
            cache.invalidateAfterMutation()
            val mutation =
                mutations.confirmedMutation(
                    accountId = accountId,
                    collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                    itemId = workshopId,
                    present = confirmed.enabled,
                    summary = summary,
                    cachedPage = null,
                    publishChange = false,
                )
            val patchedSummary = summary?.copy(
                subscriptionState =
                    if (confirmed.enabled) SubscriptionState.SUBSCRIBED else SubscriptionState.NOT_SUBSCRIBED,
            )
            cache.patchAccountCollection(
                accountId,
                AccountWorkshopCollection.SUBSCRIPTIONS,
                workshopId,
                confirmed.enabled,
                patchedSummary,
            )
            persistAccountCollectionMutation(
                snapshots,
                accountId,
                AccountWorkshopCollection.SUBSCRIPTIONS,
                workshopId,
                confirmed.enabled,
                patchedSummary,
            )
            mutations.publish(mutation.change)
            WorkshopInteraction(
                subscriptionState =
                    if (confirmed.enabled) SubscriptionState.SUBSCRIBED else SubscriptionState.NOT_SUBSCRIBED,
            )
        }

    suspend fun setFavorited(
        workshopId: Long,
        favorited: Boolean,
        summary: WorkshopSummary?,
    ): WorkshopInteraction =
        withContext(Dispatchers.IO) {
            awaitWorkshopSession(WorkshopSessionAccess.AUTHENTICATED_ACCOUNT, gateway::awaitSignedInSession)
            val accountId = gateway.signedInAccountId() ?: error("No signed-in Steam account is available")
            val confirmed = gateway.write(WorkshopReadKind.SET_FAVORITED) {
                val actingAccountId = gateway.signedInAccountId() ?: error("No signed-in Steam account is available")
                check(actingAccountId == accountId) { "The signed-in Steam account changed during the mutation" }
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
            val patchedSummary = summary?.copy(
                favoriteState = if (confirmed.enabled) FavoriteState.FAVORITED else FavoriteState.NOT_FAVORITED,
            )
            cache.patchAccountCollection(
                accountId,
                AccountWorkshopCollection.FAVORITES,
                workshopId,
                confirmed.enabled,
                patchedSummary,
            )
            persistAccountCollectionMutation(
                snapshots,
                accountId,
                AccountWorkshopCollection.FAVORITES,
                workshopId,
                confirmed.enabled,
                patchedSummary,
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
    }
}
