package com.wallhub.android.feature.discover

import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.feature.discover.model.OfficialDiscoverDescriptor
import java.util.Locale
import javax.inject.Inject

enum class DiscoverQueryFidelity {
    EXACT,
    DEGRADED,
    UNSUPPORTED,
}

enum class DiscoverQueryDegradation {
    UNKNOWN_QUERY_TYPE,
    INVALID_COLLECTION_ID,
    COMMUNITY_CREATED_RANGE_UNSUPPORTED,
    COMMUNITY_TAG_GROUPS_CLIENT_FILTERED,
    COMMUNITY_MOBILE_COMPATIBILITY_APPROXIMATED,
    CREATOR_CREATED_RANGE_UNSUPPORTED,
    CREATOR_TAG_GROUPS_CLIENT_FILTERED,
    CREATOR_SORT_APPROXIMATED_BY_RECENCY,
    EXACT_KEYWORD_USES_QUOTED_SEARCH,
}

data class DiscoverQueryPlan(
    val descriptorId: String,
    val semantic: String,
    val query: WorkshopBrowseQuery?,
    val collectionId: Long? = null,
    val clientRequiredTagGroups: List<Set<String>> = emptyList(),
    val fidelity: DiscoverQueryFidelity,
    val degradations: Set<DiscoverQueryDegradation> = emptySet(),
) {
    val isExecutable: Boolean
        get() = query != null || collectionId != null

    fun filter(items: List<WorkshopSummary>): List<WorkshopSummary> {
        if (clientRequiredTagGroups.isEmpty()) return items
        return items.filter { item ->
            val itemTags = item.tags.map { it.trim().lowercase(Locale.ROOT) }.toSet()
            clientRequiredTagGroups.all { group ->
                group.any { tag -> tag.trim().lowercase(Locale.ROOT) in itemTags }
            }
        }
    }
}

/** Converts official Discover descriptors without leaking Discover-only semantics into Home. */
class DiscoverQueryAdapter
    @Inject
    constructor() {
        fun recentPositive(
            page: Int = 1,
            pageSize: Int = DEFAULT_DISCOVER_PAGE_SIZE,
        ): DiscoverQueryPlan =
            DiscoverQueryPlan(
                descriptorId = STATIC_RECENT_POSITIVE_ID,
                semantic = SEMANTIC_MOST_RECENT,
                query = WorkshopBrowseQuery(
                    page = page,
                    pageSize = pageSize,
                    officialTags = setOf("Approved"),
                    excludedTags = setOf("Unspecified"),
                    sort = WorkshopSort.MOST_RECENT,
                    days = 0,
                ),
                fidelity = DiscoverQueryFidelity.EXACT,
            )

        fun mobileEssentials(
            dataSource: SteamWorkshopDataSource,
            requiredTagGroups: List<Set<String>> = emptyList(),
            page: Int = 1,
            pageSize: Int = DEFAULT_DISCOVER_PAGE_SIZE,
        ): DiscoverQueryPlan {
            val community = dataSource == SteamWorkshopDataSource.COMMUNITY_HTML
            return DiscoverQueryPlan(
                descriptorId = STATIC_MOBILE_ESSENTIALS_ID,
                semantic = SEMANTIC_MOST_RECENT,
                query = WorkshopBrowseQuery(
                    page = page,
                    pageSize = pageSize,
                    officialTags = setOf("Approved"),
                    excludedTags = setOf("Unspecified"),
                    requiredTagGroups = if (community) emptyList() else requiredTagGroups,
                    sort = WorkshopSort.MOST_RECENT,
                    days = 0,
                    mobileCompatibleOnly = true,
                ),
                clientRequiredTagGroups = if (community) requiredTagGroups else emptyList(),
                fidelity = if (community) DiscoverQueryFidelity.DEGRADED else DiscoverQueryFidelity.EXACT,
                degradations =
                    if (community) {
                        buildSet {
                            add(DiscoverQueryDegradation.COMMUNITY_MOBILE_COMPATIBILITY_APPROXIMATED)
                            if (requiredTagGroups.isNotEmpty()) {
                                add(DiscoverQueryDegradation.COMMUNITY_TAG_GROUPS_CLIENT_FILTERED)
                            }
                        }
                    } else {
                        emptySet()
                    },
            )
        }

        fun adapt(
            descriptor: OfficialDiscoverDescriptor,
            dataSource: SteamWorkshopDataSource,
            page: Int = 1,
            pageSize: Int = DEFAULT_DISCOVER_PAGE_SIZE,
        ): List<DiscoverQueryPlan> {
            val category = descriptor.category.name.lowercase(Locale.ROOT)
            if (category == CATEGORY_COLLECTION) {
                return listOf(collectionPlan(descriptor))
            }

            val semantics = descriptor.queryTypes.ifEmpty { listOf(defaultSemantic(category)) }
            return semantics.distinct().map { semantic ->
                adaptSemantic(descriptor, category, semantic, dataSource, page, pageSize)
            }
        }

        private fun adaptSemantic(
            descriptor: OfficialDiscoverDescriptor,
            category: String,
            rawSemantic: String,
            dataSource: SteamWorkshopDataSource,
            page: Int,
            pageSize: Int,
        ): DiscoverQueryPlan {
            val semantic = rawSemantic.trim().lowercase(Locale.ROOT)
            val sortAndDays = semantic.toSortAndDays()
                ?: return unsupportedPlan(descriptor, semantic, DiscoverQueryDegradation.UNKNOWN_QUERY_TYPE)
            val isCreator = category == CATEGORY_CREATOR
            val requiredTagGroups = descriptor.requiredTagGroups.map(List<String>::toSet).filter(Set<String>::isNotEmpty)
            val degradations = linkedSetOf<DiscoverQueryDegradation>()
            var createdAfter = descriptor.timestampStart
            var createdBefore = descriptor.timestampEnd
            var transportTagGroups = requiredTagGroups
            var clientTagGroups = emptyList<Set<String>>()

            if (dataSource == SteamWorkshopDataSource.COMMUNITY_HTML && (createdAfter != null || createdBefore != null)) {
                createdAfter = null
                createdBefore = null
                degradations += DiscoverQueryDegradation.COMMUNITY_CREATED_RANGE_UNSUPPORTED
            }
            if (dataSource == SteamWorkshopDataSource.COMMUNITY_HTML && requiredTagGroups.isNotEmpty()) {
                transportTagGroups = emptyList()
                clientTagGroups = requiredTagGroups
                degradations += DiscoverQueryDegradation.COMMUNITY_TAG_GROUPS_CLIENT_FILTERED
            }
            if (isCreator && (createdAfter != null || createdBefore != null)) {
                createdAfter = null
                createdBefore = null
                degradations += DiscoverQueryDegradation.CREATOR_CREATED_RANGE_UNSUPPORTED
            }
            if (isCreator && transportTagGroups.isNotEmpty()) {
                transportTagGroups = emptyList()
                clientTagGroups = requiredTagGroups
                degradations += DiscoverQueryDegradation.CREATOR_TAG_GROUPS_CLIENT_FILTERED
            }
            if (isCreator) {
                degradations += DiscoverQueryDegradation.CREATOR_SORT_APPROXIMATED_BY_RECENCY
            }
            if (descriptor.exact && !descriptor.keyword.isNullOrBlank()) {
                degradations += DiscoverQueryDegradation.EXACT_KEYWORD_USES_QUOTED_SEARCH
            }

            val query = WorkshopBrowseQuery(
                page = page,
                pageSize = pageSize,
                searchText = descriptor.keyword.orEmpty(),
                creatorId = descriptor.itemId.takeIf { isCreator },
                // Descriptor tags classify the rail for diversity scoring; only includetags filter Workshop results.
                tags = descriptor.includeTags.toNormalizedTagSet(),
                excludedTags = descriptor.excludeTags.toNormalizedTagSet(),
                requiredTagGroups = transportTagGroups,
                days = sortAndDays.second,
                exactPhrase = descriptor.exact,
                sort = sortAndDays.first,
                createdAfterEpochSeconds = createdAfter,
                createdBeforeEpochSeconds = createdBefore,
            )
            return DiscoverQueryPlan(
                descriptorId = descriptor.stableId,
                semantic = semantic,
                query = query,
                clientRequiredTagGroups = clientTagGroups,
                fidelity = if (degradations.isEmpty()) DiscoverQueryFidelity.EXACT else DiscoverQueryFidelity.DEGRADED,
                degradations = degradations,
            )
        }

        private fun collectionPlan(descriptor: OfficialDiscoverDescriptor): DiscoverQueryPlan {
            val collectionId = descriptor.itemId?.toLongOrNull()?.takeIf { it > 0L }
            return DiscoverQueryPlan(
                descriptorId = descriptor.stableId,
                semantic = SEMANTIC_COLLECTION,
                query = null,
                collectionId = collectionId,
                fidelity = if (collectionId == null) DiscoverQueryFidelity.UNSUPPORTED else DiscoverQueryFidelity.EXACT,
                degradations =
                    if (collectionId == null) {
                        setOf(DiscoverQueryDegradation.INVALID_COLLECTION_ID)
                    } else {
                        emptySet()
                    },
            )
        }

        private fun unsupportedPlan(
            descriptor: OfficialDiscoverDescriptor,
            semantic: String,
            degradation: DiscoverQueryDegradation,
        ) = DiscoverQueryPlan(
            descriptorId = descriptor.stableId,
            semantic = semantic,
            query = null,
            fidelity = DiscoverQueryFidelity.UNSUPPORTED,
            degradations = setOf(degradation),
        )
    }

private fun String.toSortAndDays(): Pair<WorkshopSort, Int>? =
    when (this) {
        SEMANTIC_TREND_MONTH -> WorkshopSort.TRENDING to 30
        SEMANTIC_TREND_YEAR -> WorkshopSort.TRENDING to 365
        SEMANTIC_PUBLISHED_VOTES -> WorkshopSort.MOST_VOTES to 0
        SEMANTIC_PUBLISHED_DESC, SEMANTIC_MOST_RECENT -> WorkshopSort.MOST_RECENT to 0
        SEMANTIC_TOP_RATED -> WorkshopSort.TOP_RATED to 0
        else -> null
    }

private fun List<String>.toNormalizedTagSet(): Set<String> =
    map(String::trim).filter(String::isNotBlank).toSet()

private fun defaultSemantic(category: String): String =
    if (category == CATEGORY_CREATOR) SEMANTIC_PUBLISHED_DESC else SEMANTIC_TOP_RATED

private const val DEFAULT_DISCOVER_PAGE_SIZE = 20
private const val STATIC_RECENT_POSITIVE_ID = "static:recent-positive"
private const val STATIC_MOBILE_ESSENTIALS_ID = "static:mobile-essentials"
private const val CATEGORY_CREATOR = "creator"
private const val CATEGORY_COLLECTION = "collection"
private const val SEMANTIC_COLLECTION = "collection"
private const val SEMANTIC_TREND_MONTH = "trend_month"
private const val SEMANTIC_TREND_YEAR = "trend_year"
private const val SEMANTIC_PUBLISHED_VOTES = "published_votes"
private const val SEMANTIC_PUBLISHED_DESC = "published_desc"
private const val SEMANTIC_MOST_RECENT = "most_recent"
private const val SEMANTIC_TOP_RATED = "top_rated"
