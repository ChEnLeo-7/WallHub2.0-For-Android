package com.wallhub.android.data.steam

import android.os.SystemClock
import android.util.Log
import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.WALLPAPER_ENGINE_APP_ID
import com.wallhub.android.core.model.WorkshopPage
import io.steamcontent.sdk.WorkshopPage as SdkWorkshopPage
import io.steamcontent.sdk.WorkshopPersonalList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun accountCollectionPriority(
    query: AccountWorkshopQuery,
    sourcePageSize: Int,
): WorkshopReadPriority =
    if (query.resolveTotalCount && sourcePageSize == 1) {
        WorkshopReadPriority.BACKGROUND
    } else {
        WorkshopReadPriority.FOREGROUND
    }

/** Authenticated account collection paging, isolated from public Workshop reads. */
internal class SteamWorkshopAccountReader(
    private val engine: SteamContentSdkEngine,
    private val gateway: SteamWorkshopSdkGateway,
    private val cache: SteamWorkshopCacheStore,
    private val mapPage: (SdkWorkshopPage, Int, AccountWorkshopCollection) -> WorkshopPage,
) {
    suspend fun browseCollection(query: AccountWorkshopQuery): WorkshopPage =
        withContext(Dispatchers.IO) {
            val normalized = query.normalized()
            val startedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "account collection begin collection=${normalized.collection} page=${normalized.page}")
            try {
                awaitWorkshopSession(WorkshopSessionAccess.AUTHENTICATED_ACCOUNT, gateway::awaitSignedInSession)
                Log.i(TAG, "account collection session-ready ms=${SystemClock.elapsedRealtime() - startedAt}")
                val accountId = gateway.signedInAccountId() ?: error("No signed-in Steam account is available")
                val generation =
                    engine.currentSessionGeneration()
                        ?: error("No signed-in Steam session generation is available")
                val cacheKey = AccountPageCacheKey(accountId, generation, normalized)
                cache.accountPages.getFresh(cacheKey)?.let {
                    Log.i(TAG, "account collection cache-hit ms=${SystemClock.elapsedRealtime() - startedAt}")
                    return@withContext it
                }
                val readEpoch = cache.accountEpoch.get()
                val list = normalized.collection.sdkPersonalList()
                Log.i(TAG, "account collection request-start ms=${SystemClock.elapsedRealtime() - startedAt}")
                val loaded =
                    if (!normalized.hasInteractiveFilters()) {
                        val source = loadPage(accountId, generation, list, normalized, normalized.page, normalized.pageSize)
                        source.copy(items = source.items.filter(normalized::matchesAccountCollectionItem))
                    } else {
                        cache.accountPager.page(
                            scope = AccountCollectionScope(accountId, generation),
                            query = normalized,
                        ) { sourcePage, sourcePageSize ->
                            loadPage(accountId, generation, list, normalized, sourcePage, sourcePageSize)
                        }
                    }
                if (cache.accountEpoch.get() == readEpoch) cache.accountPages.put(cacheKey, loaded)
                Log.i(
                    TAG,
                    "account collection complete collection=${normalized.collection} items=${loaded.items.size} " +
                        "ms=${SystemClock.elapsedRealtime() - startedAt}",
                )
                loaded
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }

    private suspend fun loadPage(
        accountId: Long,
        sessionGeneration: Long,
        list: Int,
        query: AccountWorkshopQuery,
        sourcePage: Int,
        sourcePageSize: Int,
    ): WorkshopPage =
        gateway.read(
            key = "account:$accountId:$sessionGeneration:${query.collection}:$sourcePage:$sourcePageSize",
            kind = WorkshopReadKind.ACCOUNT_COLLECTION,
            priority = accountCollectionPriority(query, sourcePageSize),
        ) {
            engine.workshopPersonalItems(
                accountId = accountId,
                list = list,
                pageSize = sourcePageSize,
                pageOffset = pageOffsetOf(sourcePage, sourcePageSize),
                appId = WALLPAPER_ENGINE_APP_ID.toLong(),
            ).use { mapPage(it, sourcePage, query.collection) }
        }

    private fun AccountWorkshopCollection.sdkPersonalList(): Int =
        when (this) {
            AccountWorkshopCollection.SUBSCRIPTIONS -> WorkshopPersonalList.SUBSCRIBED
            AccountWorkshopCollection.FAVORITES -> WorkshopPersonalList.FAVORITED
        }

    private fun pageOffsetOf(page: Int, pageSize: Int): Int {
        val offset = (page.coerceAtLeast(1).toLong() - 1L) * pageSize.coerceAtLeast(1).toLong()
        return offset.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private companion object {
        const val TAG = "WallHubSdkWorkshop"
    }
}
