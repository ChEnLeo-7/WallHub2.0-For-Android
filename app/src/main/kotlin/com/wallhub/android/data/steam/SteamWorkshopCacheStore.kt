package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopPage
import java.util.concurrent.atomic.AtomicLong

/** Owns CM cache data and its epochs so invalidation cannot diverge from freshness checks. */
internal class SteamWorkshopCacheStore(
    clockMillis: () -> Long = System::currentTimeMillis,
) {
    val publicEpoch = AtomicLong()
    val accountEpoch = AtomicLong()
    val detailInteractions = WorkshopDetailInteractionCache()
    val accountPager = AccountCollectionPager()
    val publicDetails =
        SteamReadCache<Long, WorkshopDetail>(
            maxEntries = 64,
            freshMillis = FRESH_MILLIS,
            staleMillis = FRESH_MILLIS,
            clockMillis = clockMillis,
        )
    val publicPages =
        SteamReadCache<WorkshopBrowseQuery, WorkshopPage>(
            freshMillis = FRESH_MILLIS,
            staleMillis = FRESH_MILLIS,
            clockMillis = clockMillis,
        )
    val accountPages =
        SteamReadCache<AccountPageCacheKey, WorkshopPage>(
            freshMillis = FRESH_MILLIS,
            staleMillis = FRESH_MILLIS,
            clockMillis = clockMillis,
        )

    suspend fun invalidatePublic() {
        publicEpoch.incrementAndGet()
        publicDetails.invalidateAll()
        publicPages.invalidateAll()
    }

    suspend fun invalidateAfterMutation() {
        publicEpoch.incrementAndGet()
        accountEpoch.incrementAndGet()
        detailInteractions.invalidateAll()
        publicDetails.invalidateAll()
        publicPages.invalidateAll()
        accountPager.invalidateAll()
    }

    suspend fun patchAccountCollection(
        accountId: Long,
        collection: AccountWorkshopCollection,
        itemId: Long,
        present: Boolean,
        summary: WorkshopSummary?,
    ) {
        accountEpoch.incrementAndGet()
        accountPages.removeWhere { key ->
            key.accountId == accountId &&
                key.query.collection == collection &&
                key.query.resolveTotalCount &&
                key.query.pageSize == 1
        }
        accountPages.updateWhere(
            predicate = { key -> key.accountId == accountId && key.query.collection == collection },
            transform = { key, page ->
                val existing = page.items.indexOfFirst { it.id == itemId }
                val patchedItems =
                    when {
                        !present && existing >= 0 -> page.items.filterNot { it.id == itemId }
                        present && existing >= 0 && summary != null ->
                            page.items.toMutableList().also { it[existing] = summary }
                        present && existing < 0 && summary != null && key.query.page == 1 &&
                            key.query.matchesAccountCollectionItem(summary) ->
                            (listOf(summary) + page.items).take(key.query.pageSize)
                        else -> page.items
                    }
                page.copy(items = patchedItems, totalCount = null, totalPages = null)
            },
        )
        accountPager.invalidateAll()
    }

    private companion object {
        const val FRESH_MILLIS = 120_000L
    }
}

internal data class AccountPageCacheKey(
    val accountId: Long,
    val sessionGeneration: Long,
    val query: AccountWorkshopQuery,
)
