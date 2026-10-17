package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopPage
import java.util.concurrent.atomic.AtomicLong

/** Owns CM cache data and its epochs so invalidation cannot diverge from freshness checks. */
internal class SteamWorkshopCacheStore {
    val publicEpoch = AtomicLong()
    val accountEpoch = AtomicLong()
    val detailInteractions = WorkshopDetailInteractionCache()
    val accountPager = AccountCollectionPager()
    val publicPages = SteamReadCache<WorkshopBrowseQuery, WorkshopPage>(freshMillis = FRESH_MILLIS, staleMillis = FRESH_MILLIS)
    val accountPages = SteamReadCache<AccountPageCacheKey, WorkshopPage>(freshMillis = FRESH_MILLIS, staleMillis = FRESH_MILLIS)

    suspend fun invalidatePublic() {
        publicEpoch.incrementAndGet()
        publicPages.invalidateAll()
    }

    suspend fun invalidateAfterMutation() {
        publicEpoch.incrementAndGet()
        accountEpoch.incrementAndGet()
        detailInteractions.invalidateAll()
        publicPages.invalidateAll()
        accountPages.invalidateAll()
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
