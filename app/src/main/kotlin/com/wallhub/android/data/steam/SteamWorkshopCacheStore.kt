package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.WorkshopBrowseQuery
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
