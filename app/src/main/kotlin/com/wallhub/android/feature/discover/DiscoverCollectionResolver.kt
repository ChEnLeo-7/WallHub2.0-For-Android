package com.wallhub.android.feature.discover

import com.wallhub.android.core.model.WorkshopPage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Resolves a public Workshop collection independently from ordinary browse queries. */
interface DiscoverCollectionResolver {
    /** Drops fallback collection membership before a realtime generation. */
    suspend fun invalidateDiscoverCache() = Unit

    suspend fun browse(
        collectionId: Long,
        page: Int = 1,
        pageSize: Int = 20,
    ): WorkshopPage
}

/** Shared hard cap for every Workshop request initiated by Discover. */
@Singleton
class DiscoverNetworkBudget
    @Inject
    constructor() {
        private val permits = Semaphore(MAX_DISCOVER_NETWORK_REQUESTS)

        suspend fun <T> withPermit(block: suspend () -> T): T = permits.withPermit { block() }
    }

private const val MAX_DISCOVER_NETWORK_REQUESTS = 6
