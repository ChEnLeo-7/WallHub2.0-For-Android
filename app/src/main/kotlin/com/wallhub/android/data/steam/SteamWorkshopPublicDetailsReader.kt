package com.wallhub.android.data.steam

import android.util.Log
import com.wallhub.android.core.model.CmPublicDetailResult
import com.wallhub.android.core.model.CmPublicDetailsBatch
import com.wallhub.android.core.model.WALLPAPER_ENGINE_APP_ID
import com.wallhub.android.core.model.WorkshopDetail
import io.steamcontent.sdk.WorkshopDetailsStatus
import io.steamcontent.sdk.WorkshopResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Typed public CM detail and collection reads, including partial-batch preservation and handle cleanup. */
internal class SteamWorkshopPublicDetailsReader(
    private val engine: SteamContentSdkEngine,
    private val gateway: SteamWorkshopSdkGateway,
    private val cache: SteamWorkshopCacheStore,
    private val mapDetail: (WorkshopResult) -> WorkshopDetail,
    private val mapChildren: (WorkshopResult) -> List<Long>,
) {
    suspend fun getDetail(workshopId: Long): CmPublicDetailResult =
        withContext(Dispatchers.IO) {
            require(workshopId > 0L) { "Invalid Workshop item ID" }
            cache.publicDetails.getFresh(workshopId)?.let {
                return@withContext CmPublicDetailResult.Available(it)
            }
            gateway.awaitUsableSession()
            getDetails(listOf(workshopId)).outcomes.firstOrNull()?.second
                ?: CmPublicDetailResult.Unavailable("CM detail result unavailable")
        }

    suspend fun getDetails(workshopIds: List<Long>): CmPublicDetailsBatch =
        withContext(Dispatchers.IO) {
            val ids = workshopIds.filter { it > 0L }.distinct()
            if (ids.isEmpty()) return@withContext CmPublicDetailsBatch(emptyList())
            runCatchingUnavailable {
                val readEpoch = cache.publicEpoch.get()
                val entries = ids.chunked(MAX_DETAILS_ITEMS).flatMap { batch ->
                    gateway.read(
                        key = publicWorkshopReadKey(readEpoch, "typed-details:${batch.joinToString(",")}"),
                        kind = WorkshopReadKind.DETAILS,
                        priority = WorkshopReadPriority.FOREGROUND,
                    ) { engine.openWorkshopItems(batch.toLongArray(), WALLPAPER_ENGINE_APP_ID.toLong()) }
                }
                try {
                    val response =
                        CmPublicDetailsBatch(
                            entries.map { entry ->
                                val result = entry.result
                                val detail =
                                    if (entry.status == WorkshopDetailsStatus.OK && result != null) {
                                        mapDetail(result).also {
                                            engine.currentSessionGeneration()?.let { generation ->
                                                cache.detailInteractions.remember(
                                                    entry.itemId,
                                                    generation,
                                                    interactionOf(result),
                                                )
                                            }
                                        }
                                    } else {
                                        null
                                    }
                                entry.itemId to mapCmDetailOutcome(entry.status, entry.serviceResult, detail)
                            },
                        )
                    response.available.forEach { detail -> cache.publicDetails.put(detail.summary.id, detail) }
                    response
                } finally {
                    entries.forEach { it.result?.close() }
                }
            } ?: CmPublicDetailsBatch(ids.map { it to CmPublicDetailResult.Unavailable("CM unavailable") })
        }

    suspend fun getCollectionChildren(collectionId: Long): List<Long>? =
        withContext(Dispatchers.IO) {
            runCatchingUnavailable {
                require(collectionId > 0L) { "Invalid Workshop collection ID" }
                val readEpoch = cache.publicEpoch.get()
                gateway.read(
                    key = publicWorkshopReadKey(readEpoch, "collection:$collectionId"),
                    kind = WorkshopReadKind.DETAILS,
                    priority = WorkshopReadPriority.FOREGROUND,
                ) {
                    engine.openWorkshopItem(collectionId, WALLPAPER_ENGINE_APP_ID.toLong()).use { result ->
                        mapChildren(result).also { children ->
                            Log.i(
                                TAG,
                                "workshop collection children id=$collectionId count=${children.size} " +
                                    "ids=${children.take(MAX_LOGGED_CHILDREN)}",
                            )
                        }
                    }
                }
            }
        }

    private companion object {
        const val TAG = "WallHubSdkWorkshop"
        const val MAX_DETAILS_ITEMS = 16
        const val MAX_LOGGED_CHILDREN = 12
    }
}
