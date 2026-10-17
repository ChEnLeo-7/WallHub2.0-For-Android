package com.wallhub.android.data.discover

import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.feature.discover.DiscoverCollectionResolver
import com.wallhub.android.feature.discover.DiscoverNetworkBudget
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SteamDiscoverCollectionResolver
    @Inject
    constructor(
        private val unifiedWorkshopRepository: SteamUnifiedWorkshopRepository,
        private val networkBudget: DiscoverNetworkBudget,
    ) : DiscoverCollectionResolver {

        override suspend fun invalidateDiscoverCache() = Unit

        override suspend fun browse(
            collectionId: Long,
            page: Int,
            pageSize: Int,
        ): WorkshopPage {
            require(collectionId > 0L) { "Invalid Workshop collection ID" }
            val normalizedPage = page.coerceAtLeast(1)
            val normalizedPageSize = pageSize.coerceIn(1, MAX_COLLECTION_PAGE_SIZE)
            val unifiedMembers =
                networkBudget.withPermit {
                    unifiedWorkshopRepository.getPublicCollectionChildren(collectionId)
                }
            val members =
                unifiedMembers ?: error("Steam CM collection members are temporarily unavailable")
            val start = ((normalizedPage - 1L) * normalizedPageSize).coerceAtMost(members.size.toLong()).toInt()
            val end = (start + normalizedPageSize).coerceAtMost(members.size)
            val pageIds = members.subList(start, end)
            val items = resolveDetails(pageIds)
            return WorkshopPage(
                items = items,
                page = normalizedPage,
                hasNextPage = end < members.size,
                totalCount = members.size,
                totalPages = ((members.size + normalizedPageSize - 1) / normalizedPageSize).coerceAtLeast(1),
            )
        }

        private suspend fun resolveDetails(ids: List<Long>): List<WorkshopSummary> =
            runCatching {
                networkBudget.withPermit {
                    val primary = unifiedWorkshopRepository.getPublicDetailsResult(ids)
                    val resolved = primary.available.associateBy { it.summary.id }
                    ids.mapNotNull(resolved::get).map(WorkshopDetail::summary)
                }
            }.getOrDefault(emptyList())
    }

internal fun parseCollectionMemberIds(
    payload: String,
    expectedCollectionId: Long,
): List<Long> {
    val collections = JSONObject(payload).optJSONObject("response")?.optJSONArray("collectiondetails")
        ?: return emptyList()
    for (index in 0 until collections.length()) {
        val collection = collections.optJSONObject(index) ?: continue
        if (collection.opt("publishedfileid")?.toString()?.toLongOrNull() != expectedCollectionId) continue
        val children = collection.optJSONArray("children") ?: return emptyList()
        return buildList {
            for (childIndex in 0 until children.length()) {
                children
                    .optJSONObject(childIndex)
                    ?.opt("publishedfileid")
                    ?.toString()
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?.let(::add)
            }
        }.distinct()
    }
    return emptyList()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DiscoverCollectionResolverModule {
    @Binds
    @Singleton
    abstract fun bindDiscoverCollectionResolver(
        resolver: SteamDiscoverCollectionResolver,
    ): DiscoverCollectionResolver
}

private const val MAX_COLLECTION_PAGE_SIZE = 50
