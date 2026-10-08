package com.wallhub.android.feature.discover

import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.matchesSteamWallpaper
import javax.inject.Inject
import javax.inject.Singleton

/** Canonical, feature-owned recipe for one Discover page, shared by rails and result screens. */
data class DiscoverPageRecipe(
    val query: WorkshopBrowseQuery,
    val collectionId: Long? = null,
    val clientRequiredTagGroups: List<Set<String>> = emptyList(),
)

/** Hides collection/query dispatch and Discover-only post-filtering behind one page seam. */
interface DiscoverPageSource {
    suspend fun page(
        recipe: DiscoverPageRecipe,
        page: Int,
        pageSize: Int,
    ): WorkshopPage
}

@Singleton
class DefaultDiscoverPageSource
    @Inject
    constructor(
        private val workshopRepository: WorkshopRepository,
        private val collectionResolver: DiscoverCollectionResolver,
        private val networkBudget: DiscoverNetworkBudget,
    ) : DiscoverPageSource {
        override suspend fun page(
            recipe: DiscoverPageRecipe,
            page: Int,
            pageSize: Int,
        ): WorkshopPage {
            val query = recipe.query.copy(page = page, pageSize = pageSize)
            val loaded =
                recipe.collectionId?.let { collectionId ->
                    collectionResolver.browse(collectionId, page, pageSize)
                } ?: networkBudget.withPermit {
                    workshopRepository.browse(query)
                }
            val filtered =
                loaded.items
                    .filter { item ->
                        recipe.clientRequiredTagGroups.all { group ->
                            group.any { required -> item.tags.any { tag -> tag.equals(required, ignoreCase = true) } }
                        }
                    }.let { items ->
                        if (recipe.collectionId == null) items else items.filter(query::matchesSteamWallpaper)
                    }
            return loaded.copy(items = filtered)
        }
    }
