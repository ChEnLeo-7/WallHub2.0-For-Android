package com.wallhub.android.data.steam

import android.os.SystemClock
import android.util.Log
import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.WALLPAPER_ENGINE_APP_ID
import com.wallhub.android.core.model.WorkshopFilterCatalog
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRating
import io.steamcontent.sdk.WorkshopPage as SdkWorkshopPage
import io.steamcontent.sdk.WorkshopPersonalList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun accountCollectionPrefetchQueries(pageSize: Int = 16): List<AccountWorkshopQuery> {
    require(pageSize in 1..MAX_ACCOUNT_WORKSHOP_PAGE_SIZE)
    return AccountWorkshopCollection.entries.flatMap { collection ->
        listOf(
            AccountWorkshopQuery(
                collection = collection,
                page = 1,
                pageSize = pageSize,
                ratings = setOf(WorkshopRating.EVERYONE),
                genres = WorkshopFilterCatalog.genres.toSet(),
                resolutions = WorkshopFilterCatalog.resolutions.toSet(),
            ),
            AccountWorkshopQuery(
                collection = collection,
                page = 1,
                pageSize = 1,
                resolveTotalCount = true,
            ),
        )
    }
}

internal fun accountCollectionPriority(
    query: AccountWorkshopQuery,
    sourcePageSize: Int,
): WorkshopReadPriority =
    if (query.resolveTotalCount && sourcePageSize == 1) {
        WorkshopReadPriority.BACKGROUND
    } else {
        WorkshopReadPriority.FOREGROUND
    }

/** Authenticated account collection paging with account-isolated persistent stale-while-revalidate snapshots. */
internal class SteamWorkshopAccountReader(
    private val engine: SteamContentSdkEngine,
    private val gateway: SteamWorkshopSdkGateway,
    private val cache: SteamWorkshopCacheStore,
    private val snapshots: AccountWorkshopPageSnapshotStore,
    private val mutations: SteamAccountCollectionMutationLedger,
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val mapPage: (SdkWorkshopPage, Int, AccountWorkshopCollection) -> WorkshopPage,
) {
    private val mutableRefreshes = MutableSharedFlow<AccountWorkshopCollection>(extraBufferCapacity = 8)
    val refreshes: Flow<AccountWorkshopCollection> = mutableRefreshes.asSharedFlow()

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
            val generation =
                engine.currentSessionGeneration()
                    ?: error("No signed-in Steam session generation is available")
            val cacheKey = AccountPageCacheKey(accountId, generation, normalized)

            if (!forceRefresh) {
                cache.accountPages.getFresh(cacheKey)?.let { fresh ->
                    Log.i(TAG, "account collection cache-hit ms=${SystemClock.elapsedRealtime() - startedAt}")
                    return@withContext mutations.overlay(
                        accountId = accountId,
                        collection = normalized.collection,
                        page = fresh,
                        includeSummary = normalized::matchesAccountCollectionItem,
                        allowInsertion = normalized.page == 1,
                        maxItems = normalized.pageSize,
                    )
                }
                val snapshotKey = AccountWorkshopPageSnapshotKey(accountId, normalized)
                val snapshot = snapshots.read(snapshotKey)
                    ?.takeIf { clockMillis() - it.storedAtMillis in 0..PERSISTED_STALE_MILLIS }
                if (snapshot != null) {
                    val visible =
                        mutations.overlay(
                            accountId = accountId,
                            collection = normalized.collection,
                            page = snapshot.page,
                            includeSummary = normalized::matchesAccountCollectionItem,
                            allowInsertion = normalized.page == 1,
                            maxItems = normalized.pageSize,
                        )
                    cache.accountPages.put(cacheKey, visible)
                    if (!snapshot.isFreshAt(clockMillis())) {
                        scope.launch {
                            try {
                                refresh(accountId, generation, normalized, background = true)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                Log.w(TAG, "account collection background refresh failed collection=${normalized.collection}", error)
                            }
                        }
                    }
                    return@withContext visible
                }
            }

            refresh(accountId, generation, normalized, background)
        }

    private suspend fun refresh(
        accountId: Long,
        generation: Long,
        query: AccountWorkshopQuery,
        background: Boolean,
    ): WorkshopPage {
        val readEpoch = cache.accountEpoch.get()
        val readToken = mutations.beginRead()
        val list = query.collection.sdkPersonalList()
        val loaded =
            if (!query.hasInteractiveFilters()) {
                val source = loadPage(accountId, generation, list, query, query.page, query.pageSize, background)
                source.copy(items = source.items.filter(query::matchesAccountCollectionItem))
            } else {
                cache.accountPager.page(
                    scope = AccountCollectionScope(accountId, generation),
                    query = query,
                ) { sourcePage, sourcePageSize ->
                    loadPage(accountId, generation, list, query, sourcePage, sourcePageSize, background)
                }
            }
        val completed =
            mutations.completeRead(
                token = readToken,
                accountId = accountId,
                collection = query.collection,
                remotePage = loaded,
                completeCollection = query.page == 1 && !query.hasInteractiveFilters() && !loaded.hasNextPage,
                includeSummary = query::matchesAccountCollectionItem,
                allowInsertion = query.page == 1,
                maxItems = query.pageSize,
            )
        if (cache.accountEpoch.get() == readEpoch && completed.cacheWriteAllowed) {
            val cacheKey = AccountPageCacheKey(accountId, generation, query)
            cache.accountPages.put(cacheKey, completed.page)
            val now = clockMillis()
            snapshots.write(
                AccountWorkshopPageSnapshotKey(accountId, query),
                AccountWorkshopPageSnapshot(
                    page = completed.page,
                    storedAtMillis = now,
                    freshUntilMillis = now + SNAPSHOT_FRESH_MILLIS,
                ),
            )
            if (background) mutableRefreshes.tryEmit(query.collection)
        }
        Log.i(TAG, "account collection complete collection=${query.collection} items=${completed.page.items.size}")
        return completed.page
    }

    private suspend fun loadPage(
        accountId: Long,
        sessionGeneration: Long,
        list: Int,
        query: AccountWorkshopQuery,
        sourcePage: Int,
        sourcePageSize: Int,
        background: Boolean,
    ): WorkshopPage =
        gateway.read(
            key = "account:$accountId:$sessionGeneration:${query.collection}:$sourcePage:$sourcePageSize",
            kind = WorkshopReadKind.ACCOUNT_COLLECTION,
            priority = if (background) WorkshopReadPriority.BACKGROUND else accountCollectionPriority(query, sourcePageSize),
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
        const val SNAPSHOT_FRESH_MILLIS = 5 * 60 * 1_000L
        const val PERSISTED_STALE_MILLIS = 7 * 24 * 60 * 60 * 1_000L
    }
}
