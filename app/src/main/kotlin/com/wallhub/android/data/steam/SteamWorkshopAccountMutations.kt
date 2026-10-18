package com.wallhub.android.data.steam

import android.util.Log
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WALLPAPER_ENGINE_APP_ID
import com.wallhub.android.core.model.WorkshopInteraction
import io.steamcontent.sdk.WorkshopItemField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Authenticated interaction reads and mutations; public catalog reads have no dependency on this module. */
internal class SteamWorkshopAccountMutations(
    private val engine: SteamContentSdkEngine,
    private val gateway: SteamWorkshopSdkGateway,
    private val cache: SteamWorkshopCacheStore,
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

    suspend fun setSubscribed(workshopId: Long, subscribed: Boolean): WorkshopInteraction =
        withContext(Dispatchers.IO) {
            awaitWorkshopSession(WorkshopSessionAccess.AUTHENTICATED_ACCOUNT, gateway::awaitSignedInSession)
            val confirmed =
                gateway.write(WorkshopReadKind.SET_SUBSCRIBED) {
                    val accountId = gateway.signedInAccountId() ?: error("No signed-in Steam account is available")
                    engine.setSubscribed(workshopId, accountId, subscribed)
                }
            Log.i(TAG, "workshop subscribe write id=$workshopId ask=$subscribed confirmed=${confirmed.enabled}")
            cache.invalidateAfterMutation()
            WorkshopInteraction(
                subscriptionState =
                    if (confirmed.enabled) SubscriptionState.SUBSCRIBED else SubscriptionState.NOT_SUBSCRIBED,
            )
        }

    suspend fun setFavorited(workshopId: Long, favorited: Boolean): WorkshopInteraction =
        withContext(Dispatchers.IO) {
            awaitWorkshopSession(WorkshopSessionAccess.AUTHENTICATED_ACCOUNT, gateway::awaitSignedInSession)
            val confirmed =
                gateway.write(WorkshopReadKind.SET_FAVORITED) {
                    val accountId = gateway.signedInAccountId() ?: error("No signed-in Steam account is available")
                    engine.setFavorited(workshopId, accountId, favorited)
                }
            Log.i(TAG, "workshop favorite write id=$workshopId ask=$favorited confirmed=${confirmed.enabled}")
            cache.invalidateAfterMutation()
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
