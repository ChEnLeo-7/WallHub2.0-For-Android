package com.wallhub.android.data.steam

import android.os.SystemClock
import android.util.Log
import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.AccountWorkshopSort
import com.wallhub.android.core.model.WALLPAPER_ENGINE_APP_ID
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.defaultSort
import io.steamcontent.sdk.WorkshopPage as SdkWorkshopPage
import io.steamcontent.sdk.WorkshopPersonalList
import io.steamcontent.sdk.WorkshopPersonalSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun accountCollectionCountReadKey(
    accountId: Long,
    sessionGeneration: Long,
    collection: AccountWorkshopCollection,
): String = "account-count:$accountId:$sessionGeneration:$collection"

internal fun accountCollectionReadKey(
    accountId: Long,
    sessionGeneration: Long,
    collectionGeneration: Long,
    collection: AccountWorkshopCollection,
    sourcePage: Int,
    sourcePageSize: Int,
    sort: AccountWorkshopSort = collection.defaultSort(),
): String =
    "account:$accountId:$sessionGeneration:$collectionGeneration:$collection:$sort:$sourcePage:$sourcePageSize"

/** SDK personal ordering is independent of the public Workshop ordering. */
internal fun AccountWorkshopSort.sdkPersonalSort(): Int =
    when (this) {
        AccountWorkshopSort.RELATIONSHIP_DATE_DESC -> WorkshopPersonalSort.RELATIONSHIP_DATE_DESC
        AccountWorkshopSort.TITLE_ASC -> WorkshopPersonalSort.TITLE_ASC
        AccountWorkshopSort.UPDATED_DESC -> WorkshopPersonalSort.UPDATED_DESC
        AccountWorkshopSort.CREATED_DESC -> WorkshopPersonalSort.CREATED_DESC
    }

private fun accountCollectionPriority(
    query: AccountWorkshopQuery,
    sourcePageSize: Int,
): WorkshopReadPriority =
    if (query.resolveTotalCount && sourcePageSize == 1) {
        WorkshopReadPriority.BACKGROUND
    } else {
        WorkshopReadPriority.FOREGROUND
    }

/** Authenticated personal-collection paging; page one always starts a cold generation and no list page is cached. */
internal class SteamWorkshopAccountReader(
    private val engine: SteamContentSdkEngine,
    private val gateway: SteamWorkshopSdkGateway,
    private val cache: SteamWorkshopCacheStore,
    private val mutations: SteamAccountCollectionMutationLedger,
    private val mapPage: (SdkWorkshopPage, Int, AccountWorkshopCollection) -> WorkshopPage,
) {
    suspend fun readCollectionCount(collection: AccountWorkshopCollection): Int? =
        withContext(Dispatchers.IO) {
            awaitWorkshopSession(WorkshopSessionAccess.AUTHENTICATED_ACCOUNT, gateway::awaitSignedInSession)
            val accountId = gateway.signedInAccountId() ?: error("No signed-in Steam account is available")
            val sessionGeneration =
                engine.currentSessionGeneration()
                    ?: error("No signed-in Steam session generation is available")
            gateway.read(
                key = accountCollectionCountReadKey(accountId, sessionGeneration, collection),
                kind = WorkshopReadKind.ACCOUNT_COLLECTION,
                priority = WorkshopReadPriority.BACKGROUND,
            ) {
                engine.workshopPersonalItems(
                    accountId = accountId,
                    list = collection.sdkPersonalList(),
                    pageSize = 1,
                    pageOffset = 0,
                    appId = WALLPAPER_ENGINE_APP_ID.toLong(),
                ).use { page ->
                    mapPage(page, 1, collection).let { mapped ->
                        mapped.totalCount?.coerceAtLeast(0)
                            ?: 0.takeIf { mapped.items.isEmpty() && !mapped.hasNextPage }
                    }
                }
            }
        }

    suspend fun browseCollection(
        query: AccountWorkshopQuery,
        forceRefresh: Boolean = false,
        background: Boolean = false,
    ): WorkshopPage =
        withContext(Dispatchers.IO) {
            val normalized = query.normalized()
            val startedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "account collection begin collection=${normalized.collection} page=${normalized.page}")
            awaitWorkshopSession(WorkshopSessionAccess.AUTHENTICATED_ACCOUNT, gateway::awaitSignedInSession)
            Log.i(TAG, "account collection session-ready ms=${SystemClock.elapsedRealtime() - startedAt}")
            val accountId = gateway.signedInAccountId() ?: error("No signed-in Steam account is available")
            val sessionGeneration =
                engine.currentSessionGeneration()
                    ?: error("No signed-in Steam session generation is available")
            val collectionGeneration =
                if (forceRefresh || normalized.page == 1) {
                    cache.beginAccountCollectionRefresh(accountId, normalized.collection)
                } else {
                    cache.accountCollectionGeneration(accountId, normalized.collection)
                }
            refresh(accountId, sessionGeneration, collectionGeneration, normalized, background)
        }

    private suspend fun refresh(
        accountId: Long,
        sessionGeneration: Long,
        collectionGeneration: Long,
        query: AccountWorkshopQuery,
        background: Boolean,
    ): WorkshopPage {
        val readToken = mutations.beginRead()
        val list = query.collection.sdkPersonalList()
        val loaded =
            if (!query.hasInteractiveFilters()) {
                val source = loadPage(accountId, sessionGeneration, collectionGeneration, list, query, query.page, query.pageSize, background)
                source.copy(items = source.items.filter(query::matchesAccountCollectionItem))
            } else {
                cache.accountPager.page(
                    scope = AccountCollectionScope(accountId, sessionGeneration, collectionGeneration),
                    query = query,
                ) { sourcePage, sourcePageSize ->
                    loadPage(accountId, sessionGeneration, collectionGeneration, list, query, sourcePage, sourcePageSize, background)
                }
            }
        val completed =
            mutations.completeRead(
                token = readToken,
                accountId = accountId,
                collection = query.collection,
                remotePage = loaded,
                completeCollection = query.page == 1 && !query.hasInteractiveFilters() && !loaded.hasNextPage,
                includeSummary = { false },
                allowInsertion = false,
                maxItems = query.pageSize,
            )
        Log.i(TAG, "account collection complete collection=${query.collection} items=${completed.page.items.size}")
        return completed.page
    }

    private suspend fun loadPage(
        accountId: Long,
        sessionGeneration: Long,
        collectionGeneration: Long,
        list: Int,
        query: AccountWorkshopQuery,
        sourcePage: Int,
        sourcePageSize: Int,
        background: Boolean,
    ): WorkshopPage =
        gateway.read(
            key =
                accountCollectionReadKey(
                    accountId,
                    sessionGeneration,
                    collectionGeneration,
                    query.collection,
                    sourcePage,
                    sourcePageSize,
                    query.sort,
                ),
            kind = WorkshopReadKind.ACCOUNT_COLLECTION,
            priority = if (background) WorkshopReadPriority.BACKGROUND else accountCollectionPriority(query, sourcePageSize),
        ) {
            engine.workshopPersonalItems(
                accountId = accountId,
                list = list,
                pageSize = sourcePageSize,
                pageOffset = pageOffsetOf(sourcePage, sourcePageSize),
                appId = WALLPAPER_ENGINE_APP_ID.toLong(),
                sort = query.sort.sdkPersonalSort(),
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
