package com.wallhub.android.feature.discover

import com.wallhub.android.core.model.DiscoverRailFeedback
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.feature.discover.model.DiscoverMetadataSource

enum class DiscoverCategory {
    WALLPAPER,
    CREATOR,
    COLLECTION,
    KEYWORD,
    GENRE,
    TOP_YEAR,
    HIGHLIGHT,
    CREATOR_SHOWCASE,
    COLLECTION_SHOWCASE,
}

enum class DiscoverSpecSource {
    OFFICIAL_METADATA,
    STATIC_OFFICIAL_COMPAT,
    DERIVED_FROM_CANDIDATES,
    USER_CUSTOM,
}

enum class DiscoverDrillDown {
    DETAIL,
    CREATOR_RESULTS,
    COLLECTION_RESULTS,
    KEYWORD_RESULTS,
    FULL_QUERY_RESULTS,
}

enum class DiscoverTitleKind {
    RECENT_APPROVED,
    TRENDING_MONTH,
    TRENDING_YEAR,
    MOBILE,
    AUDIO_RESPONSIVE,
    GENRE,
    CREATOR,
    COLLECTION,
    KEYWORD,
    TOP_YEAR,
    SEASONAL_SPRING,
    SEASONAL_SUMMER,
    SEASONAL_FALL,
    SEASONAL_HALLOWEEN,
    SEASONAL_WINTER,
    FRIEND_FAVORITES,
    FRIEND_CREATED,
}

data class DiscoverQualityRules(
    val requirePreview: Boolean = true,
    val requireKnownType: Boolean = true,
    val targetItemCount: Int = 20,
    val candidatePageCount: Int = 4,
)

data class DiscoverRailSpec(
    val id: String,
    val category: DiscoverCategory,
    val semantic: String,
    val titleKind: DiscoverTitleKind,
    val titleArgument: String? = null,
    val coverSubmissionId: Long? = null,
    val diversityTags: Set<String> = emptySet(),
    val queryPlan: DiscoverQueryPlan,
    val priority: Float,
    val weight: Int,
    val sticky: Boolean,
    val source: DiscoverSpecSource,
    val drillDown: DiscoverDrillDown,
    val qualityRules: DiscoverQualityRules = DiscoverQualityRules(),
    val children: List<DiscoverRailSpec> = emptyList(),
) {
    val feedbackKey: String
        get() = id
}

enum class DiscoverRailLoadState {
    QUEUED,
    LOADING,
    READY,
    EMPTY,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    EVICTED,
}

data class DiscoverRailMetrics(
    val startedAtMillis: Long? = null,
    val durationMillis: Long? = null,
    val attempts: Int = 0,
    val candidateCount: Int = 0,
    val resultCount: Int = 0,
    val degradationCount: Int = 0,
)

data class DiscoverRailState(
    val spec: DiscoverRailSpec,
    val loadState: DiscoverRailLoadState = DiscoverRailLoadState.QUEUED,
    val items: List<WorkshopSummary> = emptyList(),
    val resolvedTitle: String? = null,
    val error: String? = null,
    val metrics: DiscoverRailMetrics = DiscoverRailMetrics(),
    val featuredItems: List<DiscoverFeaturedItem> = emptyList(),
)

data class DiscoverFeaturedItem(
    val spec: DiscoverRailSpec,
    val cover: WorkshopSummary,
    val title: String,
)

data class DiscoverFeedState(
    val rails: List<DiscoverRailState> = emptyList(),
    val feedback: Map<String, DiscoverRailFeedback> = emptyMap(),
    val isPreparing: Boolean = false,
    val generation: Long = 0L,
    val metadataSource: DiscoverMetadataSource? = null,
    val metadataVersion: String? = null,
    val hasMore: Boolean = true,
    val error: String? = null,
) {
    val isLoading: Boolean
        get() = isPreparing || rails.any { it.loadState == DiscoverRailLoadState.QUEUED || it.loadState == DiscoverRailLoadState.LOADING }
}

data class DiscoverGeneration(
    val generation: Long,
    val metadataSource: DiscoverMetadataSource,
    val metadataVersion: String,
    val specs: List<DiscoverRailSpec>,
)

data class DiscoverRailLoadResult(
    val items: List<WorkshopSummary>,
    val resolvedTitle: String?,
    val metrics: DiscoverRailMetrics,
    val featuredItems: List<DiscoverFeaturedItem> = emptyList(),
)

internal fun DiscoverFeedState.updateRailIfCurrent(
    expectedGeneration: Long,
    railId: String,
    transform: (DiscoverRailState) -> DiscoverRailState,
): DiscoverFeedState {
    if (generation != expectedGeneration || rails.none { it.spec.id == railId }) return this
    return copy(rails = rails.map { rail -> if (rail.spec.id == railId) transform(rail) else rail })
}
