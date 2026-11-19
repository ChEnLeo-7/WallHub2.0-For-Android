package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopPage
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Owns CM cache data and its epochs so invalidation cannot diverge from freshness checks. */
internal class SteamWorkshopCacheStore(
    clockMillis: () -> Long = System::currentTimeMillis,
) {
    val publicEpoch = AtomicLong()
    val accountEpoch = AtomicLong()
    private val accountCollectionGenerationMutex = Mutex()
    private val accountCollectionGenerations = mutableMapOf<AccountCollectionScopeKey, Long>()
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

    suspend fun accountCollectionGeneration(
        accountId: Long,
        collection: AccountWorkshopCollection,
    ): Long {
        val scope = AccountCollectionScopeKey(accountId, collection)
        accountCollectionGenerationMutex.lock()
        return try {
            accountCollectionGenerations[scope] ?: 0L
        } finally {
            accountCollectionGenerationMutex.unlock()
        }
    }

    /** Starts a cold personal-collection generation and resets only its paging cursor state. */
    suspend fun beginAccountCollectionRefresh(
        accountId: Long,
        collection: AccountWorkshopCollection,
    ): Long {
        val scope = AccountCollectionScopeKey(accountId, collection)
        accountCollectionGenerationMutex.lock()
        return try {
            withContext(NonCancellable) {
                val generation = (accountCollectionGenerations[scope] ?: 0L) + 1L
                accountCollectionGenerations[scope] = generation
                accountEpoch.incrementAndGet()
                accountPager.invalidateAll()
                generation
            }
        } finally {
            accountCollectionGenerationMutex.unlock()
        }
    }

    private companion object {
        const val FRESH_MILLIS = 120_000L
    }
}
