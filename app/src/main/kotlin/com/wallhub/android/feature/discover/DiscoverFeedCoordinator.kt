package com.wallhub.android.feature.discover

import android.util.Log
import com.wallhub.android.core.model.DiscoverFeedbackRepository
import com.wallhub.android.core.model.toDiscoverFeedbackWeight
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.feature.discover.model.OfficialDiscoverCategory
import com.wallhub.android.feature.discover.model.OfficialDiscoverDescriptor
import com.wallhub.android.feature.discover.model.OfficialDiscoverMetadataRepository
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import java.util.Locale
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class DiscoverFeedCoordinator
    @Inject
    constructor(
        private val metadataRepository: OfficialDiscoverMetadataRepository,
        private val queryAdapter: DiscoverQueryAdapter,
        private val collectionResolver: DiscoverCollectionResolver,
        private val networkBudget: DiscoverNetworkBudget,
        private val workshopRepository: WorkshopRepository,
        private val unifiedWorkshopRepository: SteamUnifiedWorkshopRepository,
        private val feedbackRepository: DiscoverFeedbackRepository,
        private val settingsRepository: SettingsRepository,
    ) {
        private val requestBudget = Semaphore(DISCOVER_NETWORK_CONCURRENCY)

        suspend fun prepareGeneration(generation: Long): DiscoverGeneration {
            val preferences = settingsRepository.preferences.first()
            val snapshot = metadataRepository.loadMetadata()
            val rawCandidates =
                buildList {
                    addAll(staticOfficialSpecs(preferences.steamWorkshopDataSource, preferences.matureContentEnabled))
                    addAll(seasonalCollectionSpecs())
                    addAll(bestOfYearSpecs(preferences.matureContentEnabled))
                    snapshot.descriptors.forEach { descriptor ->
                        addAll(descriptor.toRailSpecs(preferences.steamWorkshopDataSource, preferences.matureContentEnabled))
                    }
                }.distinctBy(DiscoverRailSpec::id)
            val candidates = aggregateOfficialShowcases(rawCandidates)
            val feedbackSnapshot = feedbackRepository.feedback.first()
            val feedbackWeights = candidates.associate { spec ->
                spec.feedbackKey to (feedbackSnapshot[spec.feedbackKey]?.toDiscoverFeedbackWeight() ?: com.wallhub.android.core.model.DiscoverFeedbackWeight())
            }
            val categoryAffinity =
                candidates.groupBy(DiscoverRailSpec::category).mapValues { (_, categorySpecs) ->
                    categorySpecs.sumOf { spec ->
                        feedbackWeights.getValue(spec.feedbackKey).scoreAdjustment.toDouble()
                    }.toFloat() * RELATED_TOPIC_FEEDBACK_FACTOR
                }
            val feedbackAdjustments = candidates.associate { spec ->
                spec.feedbackKey to
                    (feedbackWeights.getValue(spec.feedbackKey).scoreAdjustment + categoryAffinity.getOrDefault(spec.category, 0f))
            }
            val ranked = rankSpecs(candidates, feedbackAdjustments, generation)
                .filterNot { feedbackWeights.getValue(it.feedbackKey).suppressCurrentGeneration }
            return DiscoverGeneration(
                generation = generation,
                metadataSource = snapshot.source,
                metadataVersion = snapshot.version,
                specs = ranked,
            )
        }

        suspend fun loadRail(
            spec: DiscoverRailSpec,
            generation: Long,
            excludedIds: Set<Long>,
        ): DiscoverRailLoadResult = requestBudget.withPermit {
            val startedAt = System.currentTimeMillis()
            if (spec.children.isNotEmpty()) {
                return@withPermit loadFeaturedRail(spec, generation, excludedIds, startedAt)
            }
            var attempts = 0
            var lastFailure: Throwable? = null
            repeat(MAX_RAIL_ATTEMPTS) { attempt ->
                attempts = attempt + 1
                try {
                    val candidates = includeCoverSubmission(spec, loadCandidates(spec))
                    val selected = sampleCandidates(spec, candidates, excludedIds, generation)
                    val resolvedTitle =
                        when (spec.category) {
                            DiscoverCategory.CREATOR -> selected.firstOrNull()?.author
                            else -> spec.titleArgument
                        }
                    val metrics =
                        DiscoverRailMetrics(
                            startedAtMillis = startedAt,
                            durationMillis = System.currentTimeMillis() - startedAt,
                            attempts = attempts,
                            candidateCount = candidates.size,
                            resultCount = selected.size,
                            degradationCount = spec.queryPlan.degradations.size,
                        )
                    Log.i(
                        TAG,
                        "rail=${spec.id} semantic=${spec.semantic} attempts=$attempts candidates=${candidates.size} " +
                            "results=${selected.size} durationMs=${metrics.durationMillis} fidelity=${spec.queryPlan.fidelity}",
                    )
                    return DiscoverRailLoadResult(selected, resolvedTitle, metrics)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    lastFailure = error
                    if (attempt + 1 < MAX_RAIL_ATTEMPTS) delay(RETRY_BASE_DELAY_MILLIS shl attempt)
                }
            }
            throw DiscoverRailLoadException(attempts, lastFailure)
        }

        private suspend fun loadCandidates(spec: DiscoverRailSpec): List<com.wallhub.android.core.model.WorkshopSummary> {
            spec.queryPlan.query?.let { baseQuery ->
                val candidates = mutableListOf<com.wallhub.android.core.model.WorkshopSummary>()
                for (page in 1..spec.qualityRules.candidatePageCount) {
                    val result = networkBudget.withPermit {
                        workshopRepository.browse(baseQuery.copy(page = page, pageSize = DISCOVER_QUERY_PAGE_SIZE))
                    }
                    candidates += spec.queryPlan.filter(result.items)
                    if (!result.hasNextPage || candidates.size >= spec.qualityRules.targetItemCount) break
                }
                return candidates.distinctBy { it.id }
            }
            spec.queryPlan.collectionId?.let { collectionId ->
                val candidates = mutableListOf<com.wallhub.android.core.model.WorkshopSummary>()
                for (page in 1..spec.qualityRules.candidatePageCount) {
                    val result = collectionResolver.browse(collectionId, page, DISCOVER_QUERY_PAGE_SIZE)
                    candidates += spec.queryPlan.filter(result.items)
                    if (!result.hasNextPage || candidates.size >= spec.qualityRules.targetItemCount) break
                }
                return candidates.distinctBy { it.id }
            }
            return emptyList()
        }

        private suspend fun loadFeaturedRail(
            spec: DiscoverRailSpec,
            generation: Long,
            excludedIds: Set<Long>,
            startedAt: Long,
        ): DiscoverRailLoadResult = coroutineScope {
            val children =
                spec.children
                    .shuffled(Random(generation xor spec.id.hashCode().toLong()))
                    .take(DISCOVER_FEATURED_ITEM_COUNT)
            val coverIds = children.mapNotNull(DiscoverRailSpec::coverSubmissionId)
            val collectionIds = children.mapNotNull { it.queryPlan.collectionId }
            val details =
                runCatching {
                    networkBudget.withPermit {
                        val ids = (coverIds + collectionIds).distinct()
                        unifiedWorkshopRepository.getPublicDetails(ids).ifEmpty { workshopRepository.getDetails(ids) }
                    }
                }.getOrDefault(emptyList())
                .associateBy { it.summary.id }
            val fallbackCollectionCovers =
                children
                    .filter { child ->
                        val primaryCover = child.coverSubmissionId?.let(details::get)?.summary
                        child.queryPlan.collectionId != null && primaryCover?.previewUrl.isNullOrBlank()
                    }.map { child ->
                        async {
                            val fallback =
                                runCatching {
                                    collectionResolver.browse(child.queryPlan.collectionId!!, page = 1, pageSize = 1).items.firstOrNull()
                                }.getOrNull()
                            child.id to fallback
                        }
                    }.awaitAll()
                    .mapNotNull { (childId, cover) -> cover?.let { childId to it } }
                    .toMap()
            val featured =
                children.mapNotNull { child ->
                    val coverId = child.coverSubmissionId ?: return@mapNotNull null
                    val cover =
                        details[coverId]?.summary
                            ?.takeUnless { it.previewUrl.isNullOrBlank() }
                            ?: fallbackCollectionCovers[child.id]
                            ?: details[coverId]?.summary
                            ?: return@mapNotNull null
                    if (cover.id in excludedIds) return@mapNotNull null
                    val title =
                        when (child.category) {
                            DiscoverCategory.CREATOR -> cover.author
                            DiscoverCategory.COLLECTION -> child.queryPlan.collectionId?.let(details::get)?.summary?.title ?: cover.title
                            else -> child.titleArgument ?: cover.title
                        }
                    DiscoverFeaturedItem(child, cover, title)
                }.distinctBy { it.spec.id }
            val metrics =
                DiscoverRailMetrics(
                    startedAtMillis = startedAt,
                    durationMillis = System.currentTimeMillis() - startedAt,
                    attempts = 1,
                    candidateCount = spec.children.size,
                    resultCount = featured.size,
                )
            Log.i(
                TAG,
                "showcase=${spec.id} pool=${spec.children.size} requested=${children.size} resolved=${featured.size} durationMs=${metrics.durationMillis}",
            )
            DiscoverRailLoadResult(
                items = featured.map(DiscoverFeaturedItem::cover),
                resolvedTitle = null,
                metrics = metrics,
                featuredItems = featured,
            )
        }

        private fun sampleCandidates(
            spec: DiscoverRailSpec,
            candidates: List<com.wallhub.android.core.model.WorkshopSummary>,
            excludedIds: Set<Long>,
            generation: Long,
        ): List<com.wallhub.android.core.model.WorkshopSummary> {
            val eligible = candidates
            .asSequence()
            .filter { it.id > 0L && it.id !in excludedIds }
            .filter { !spec.qualityRules.requirePreview || !it.previewUrl.isNullOrBlank() }
            .filter { !spec.qualityRules.requireKnownType || it.type != WorkshopType.UNKNOWN }
            .toList()
            val cover = spec.coverSubmissionId?.let { coverId -> eligible.firstOrNull { it.id == coverId } }
            val remaining = eligible.filterNot { it.id == cover?.id }.shuffled(Random(generation xor spec.id.hashCode().toLong()))
            return (listOfNotNull(cover) + remaining).take(spec.qualityRules.targetItemCount)
        }

        private suspend fun includeCoverSubmission(
            spec: DiscoverRailSpec,
            candidates: List<com.wallhub.android.core.model.WorkshopSummary>,
        ): List<com.wallhub.android.core.model.WorkshopSummary> {
            val coverId = spec.coverSubmissionId ?: return candidates
            if (candidates.any { it.id == coverId }) return candidates
            val cover = runCatching {
                networkBudget.withPermit { workshopRepository.getDetail(coverId).summary }
            }.getOrNull() ?: return candidates
            return listOf(cover) + candidates
        }

        private fun OfficialDiscoverDescriptor.toRailSpecs(
            dataSource: SteamWorkshopDataSource,
            allowNsfw: Boolean,
        ): List<DiscoverRailSpec> =
            queryAdapter.adapt(this, dataSource).mapNotNull { rawPlan ->
                val plan = rawPlan.copy(query = rawPlan.query?.copy(allowNsfw = allowNsfw))
                if (!plan.isExecutable) return@mapNotNull null
                val category =
                    when (category) {
                        OfficialDiscoverCategory.CREATOR -> DiscoverCategory.CREATOR
                        OfficialDiscoverCategory.KEYWORD -> DiscoverCategory.KEYWORD
                        OfficialDiscoverCategory.COLLECTION -> DiscoverCategory.COLLECTION
                    }
                DiscoverRailSpec(
                    id = "$stableId:${plan.semantic}",
                    category = category,
                    semantic = plan.semantic,
                    titleKind =
                        when (category) {
                            DiscoverCategory.CREATOR -> DiscoverTitleKind.CREATOR
                            DiscoverCategory.COLLECTION -> DiscoverTitleKind.COLLECTION
                            else -> DiscoverTitleKind.KEYWORD
                        },
                    titleArgument = keyword ?: itemId,
                    coverSubmissionId = coverSubmissionId,
                    diversityTags = (tags + includeTags + dependentTags).mapTo(linkedSetOf()) { it.lowercase(Locale.ROOT) },
                    queryPlan = plan,
                    priority = priority.coerceIn(-5f, 10f) + OFFICIAL_METADATA_PRIORITY_BONUS,
                    weight = weight.coerceAtLeast(1),
                    sticky = sticky,
                    source = DiscoverSpecSource.OFFICIAL_METADATA,
                    drillDown =
                        when (category) {
                            DiscoverCategory.CREATOR -> DiscoverDrillDown.CREATOR_RESULTS
                            DiscoverCategory.COLLECTION -> DiscoverDrillDown.COLLECTION_RESULTS
                            DiscoverCategory.KEYWORD -> DiscoverDrillDown.KEYWORD_RESULTS
                            else -> DiscoverDrillDown.FULL_QUERY_RESULTS
                        },
                )
            }

        private fun aggregateOfficialShowcases(specs: List<DiscoverRailSpec>): List<DiscoverRailSpec> {
            val creators = specs.filter { it.category == DiscoverCategory.CREATOR && !it.sticky }
            val collections =
                specs.filter { spec ->
                    spec.category == DiscoverCategory.COLLECTION ||
                        spec.category == DiscoverCategory.TOP_YEAR
                }
            return buildList {
                addAll(specs.filterNot { it in creators || (it in collections && !it.sticky) })
                creators.takeIf(List<DiscoverRailSpec>::isNotEmpty)?.let { add(showcaseSpec(DiscoverCategory.CREATOR_SHOWCASE, it)) }
                collections.takeIf(List<DiscoverRailSpec>::isNotEmpty)?.let { add(showcaseSpec(DiscoverCategory.COLLECTION_SHOWCASE, it)) }
            }
        }

        private fun seasonalCollectionSpecs(now: LocalDate = LocalDate.now()): List<DiscoverRailSpec> =
            SEASONAL_COLLECTIONS.map { definition ->
                val active = MonthDay.from(now).isWithin(definition.from, definition.to)
                DiscoverRailSpec(
                    id = "static:seasonal:${definition.key}",
                    category = DiscoverCategory.COLLECTION,
                    semantic = "collection",
                    titleKind = definition.titleKind,
                    coverSubmissionId = definition.collectionId,
                    diversityTags = setOf("curated", "seasonal", definition.key),
                    queryPlan =
                        DiscoverQueryPlan(
                            descriptorId = "static:seasonal:${definition.key}",
                            semantic = "collection",
                            query = null,
                            collectionId = definition.collectionId,
                            fidelity = DiscoverQueryFidelity.EXACT,
                        ),
                    priority = if (active) ACTIVE_SEASON_PRIORITY else 0f,
                    weight = 1,
                    sticky = active,
                    source = DiscoverSpecSource.STATIC_OFFICIAL_COMPAT,
                    drillDown = DiscoverDrillDown.COLLECTION_RESULTS,
                )
            }

        private fun bestOfYearSpecs(allowNsfw: Boolean): List<DiscoverRailSpec> {
            val zone = ZoneId.systemDefault()
            return BEST_OF_YEAR_COVERS.map { (year, coverId) ->
                val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toEpochSecond()
                val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toEpochSecond() - 1L
                val id = "static:best-of-$year"
                DiscoverRailSpec(
                    id = id,
                    category = DiscoverCategory.TOP_YEAR,
                    semantic = "top_rated",
                    titleKind = DiscoverTitleKind.TOP_YEAR,
                    titleArgument = year.toString(),
                    coverSubmissionId = coverId,
                    diversityTags = setOf("curated", "best-of-year", year.toString()),
                    queryPlan =
                        DiscoverQueryPlan(
                            descriptorId = id,
                            semantic = "top_rated",
                            query =
                                WorkshopBrowseQuery(
                                    sort = WorkshopSort.TOP_RATED,
                                    days = 0,
                                    excludedTags = setOf("Anime", "MMD", "Unspecified"),
                                    allowNsfw = allowNsfw,
                                    createdAfterEpochSeconds = start,
                                    createdBeforeEpochSeconds = end,
                                ),
                            fidelity = DiscoverQueryFidelity.EXACT,
                        ),
                    priority = 0f,
                    weight = 1,
                    sticky = false,
                    source = DiscoverSpecSource.STATIC_OFFICIAL_COMPAT,
                    drillDown = DiscoverDrillDown.FULL_QUERY_RESULTS,
                )
            }
        }

        private fun showcaseSpec(
            category: DiscoverCategory,
            children: List<DiscoverRailSpec>,
        ): DiscoverRailSpec =
            DiscoverRailSpec(
                id = if (category == DiscoverCategory.CREATOR_SHOWCASE) "official:focus-creators" else "official:focus-collections",
                category = category,
                semantic = if (category == DiscoverCategory.CREATOR_SHOWCASE) "creator_showcase" else "collection_showcase",
                titleKind = if (category == DiscoverCategory.CREATOR_SHOWCASE) DiscoverTitleKind.CREATOR else DiscoverTitleKind.COLLECTION,
                diversityTags = children.flatMapTo(linkedSetOf()) { it.diversityTags },
                queryPlan =
                    DiscoverQueryPlan(
                        descriptorId = "showcase",
                        semantic = "showcase",
                        query = null,
                        fidelity = DiscoverQueryFidelity.UNSUPPORTED,
                    ),
                priority = children.maxOf(DiscoverRailSpec::priority) + SHOWCASE_PRIORITY_BONUS,
                weight = children.sumOf(DiscoverRailSpec::weight),
                sticky = true,
                source = DiscoverSpecSource.OFFICIAL_METADATA,
                drillDown = DiscoverDrillDown.FULL_QUERY_RESULTS,
                children = children,
            )

        private fun staticOfficialSpecs(
            dataSource: SteamWorkshopDataSource,
            allowNsfw: Boolean,
        ): List<DiscoverRailSpec> =
            listOf(
                staticSpec(
                    id = "static:recent-approved",
                    titleKind = DiscoverTitleKind.RECENT_APPROVED,
                    semantic = "most_recent",
                    query = WorkshopBrowseQuery(sort = WorkshopSort.MOST_RECENT, officialTags = setOf("Approved"), excludedTags = setOf("Unspecified"), allowNsfw = allowNsfw),
                    diversityTags = setOf("approved", "recent"),
                    priority = 10f,
                    sticky = true,
                ),
                staticSpec(
                    id = "static:trend-month-approved",
                    titleKind = DiscoverTitleKind.TRENDING_MONTH,
                    semantic = "trend_month",
                    query = WorkshopBrowseQuery(sort = WorkshopSort.TRENDING, days = 30, officialTags = setOf("Approved"), allowNsfw = allowNsfw),
                    diversityTags = setOf("approved", "trending"),
                    priority = 9.5f,
                    sticky = true,
                ),
                staticSpec(
                    id = "static:mobile-approved",
                    titleKind = DiscoverTitleKind.MOBILE,
                    semantic = "most_recent",
                    query = WorkshopBrowseQuery(
                        sort = WorkshopSort.MOST_RECENT,
                        officialTags = setOf("Approved"),
                        types = setOf(WorkshopType.SCENE, WorkshopType.VIDEO),
                        requiredTagGroups = listOf(setOf("Scene", "Video")),
                        mobileCompatibleOnly = true,
                        allowNsfw = allowNsfw,
                    ),
                    diversityTags = setOf("approved", "mobile"),
                    priority = 8.5f,
                ),
                staticSpec(
                    id = "static:audio-responsive",
                    titleKind = DiscoverTitleKind.AUDIO_RESPONSIVE,
                    semantic = "trend_year",
                    query = WorkshopBrowseQuery(sort = WorkshopSort.TRENDING, days = 365, officialTags = setOf("Audio responsive"), allowNsfw = allowNsfw),
                    diversityTags = setOf("audio responsive"),
                    priority = 7.5f,
                ),
                staticSpec(
                    id = "static:trend-year-approved",
                    titleKind = DiscoverTitleKind.TRENDING_YEAR,
                    semantic = "trend_year",
                    query = WorkshopBrowseQuery(sort = WorkshopSort.TRENDING, days = 365, officialTags = setOf("Approved"), allowNsfw = allowNsfw),
                    diversityTags = setOf("approved", "year"),
                    priority = 7f,
                ),
            ).map { spec ->
                val degradation =
                    if (dataSource == SteamWorkshopDataSource.COMMUNITY_HTML && spec.queryPlan.query?.requiredTagGroups?.isNotEmpty() == true) {
                        setOf(DiscoverQueryDegradation.COMMUNITY_TAG_GROUPS_CLIENT_FILTERED)
                    } else {
                        emptySet()
                    }
                spec.copy(
                    queryPlan =
                        spec.queryPlan.copy(
                            clientRequiredTagGroups =
                                if (degradation.isEmpty()) emptyList() else spec.queryPlan.query?.requiredTagGroups.orEmpty(),
                            query =
                                if (degradation.isEmpty()) spec.queryPlan.query else spec.queryPlan.query?.copy(requiredTagGroups = emptyList()),
                            fidelity = if (degradation.isEmpty()) DiscoverQueryFidelity.EXACT else DiscoverQueryFidelity.DEGRADED,
                            degradations = degradation,
                        ),
                )
            }

        private fun staticSpec(
            id: String,
            titleKind: DiscoverTitleKind,
            semantic: String,
            query: WorkshopBrowseQuery,
            diversityTags: Set<String>,
            priority: Float,
            sticky: Boolean = false,
        ) = DiscoverRailSpec(
            id = id,
            category = DiscoverCategory.WALLPAPER,
            semantic = semantic,
            titleKind = titleKind,
            diversityTags = diversityTags,
            queryPlan =
                DiscoverQueryPlan(
                    descriptorId = id,
                    semantic = semantic,
                    query = query,
                    fidelity = DiscoverQueryFidelity.EXACT,
                ),
            priority = priority,
            weight = 1,
            sticky = sticky,
            source = DiscoverSpecSource.STATIC_OFFICIAL_COMPAT,
            drillDown = DiscoverDrillDown.FULL_QUERY_RESULTS,
        )
    }

internal fun rankSpecs(
    specs: List<DiscoverRailSpec>,
    feedbackAdjustments: Map<String, Float>,
    generation: Long,
): List<DiscoverRailSpec> {
    val remaining = specs.toMutableList()
    val selected = mutableListOf<DiscoverRailSpec>()
    val categoryCounts = mutableMapOf<DiscoverCategory, Int>()
    val subjectCounts = mutableMapOf<String, Int>()
    val tagCounts = mutableMapOf<String, Int>()
    val random = Random(generation)
    val jitter = remaining.associateWith { random.nextFloat() * NOVELTY_JITTER }
    while (remaining.isNotEmpty()) {
        val next = remaining.maxByOrNull { spec ->
            val subject = spec.titleArgument?.lowercase(Locale.ROOT)
            val unseenTagCount = spec.diversityTags.count { tagCounts.getOrDefault(it, 0) == 0 }
            val repeatedTagCount = spec.diversityTags.count { tagCounts.getOrDefault(it, 0) > 0 }
            spec.priority +
                ln(spec.weight.coerceAtLeast(1).toFloat() + 1f) * WEIGHT_FACTOR +
                feedbackAdjustments.getOrDefault(spec.feedbackKey, 0f) +
                (if (categoryCounts.getOrDefault(spec.category, 0) == 0) CATEGORY_COVERAGE_BONUS else 0f) +
                unseenTagCount.coerceAtMost(2) * TAG_COVERAGE_BONUS +
                repeatedTagCount.coerceAtMost(3) * TAG_REPETITION_PENALTY +
                (if (subject != null && subjectCounts.getOrDefault(subject, 0) > 0) SUBJECT_REPETITION_PENALTY else 0f) +
                jitter.getValue(spec)
        } ?: break
        selected += next
        remaining -= next
        categoryCounts[next.category] = categoryCounts.getOrDefault(next.category, 0) + 1
        next.titleArgument?.lowercase(Locale.ROOT)?.let { subject ->
            subjectCounts[subject] = subjectCounts.getOrDefault(subject, 0) + 1
        }
        next.diversityTags.forEach { tag -> tagCounts[tag] = tagCounts.getOrDefault(tag, 0) + 1 }
    }
    return selected
}

class DiscoverRailLoadException(
    val attempts: Int,
    cause: Throwable?,
) : RuntimeException(cause?.message ?: "Discover rail load failed", cause)

const val DISCOVER_INITIAL_RAIL_COUNT = 5
const val DISCOVER_APPEND_RAIL_COUNT = 5
const val DISCOVER_MAX_NON_STICKY_RAILS = 25

private const val TAG = "DiscoverFeed"
private const val DISCOVER_NETWORK_CONCURRENCY = 4
private const val DISCOVER_QUERY_PAGE_SIZE = 20
private const val MAX_RAIL_ATTEMPTS = 3
private const val RETRY_BASE_DELAY_MILLIS = 350L
private const val OFFICIAL_METADATA_PRIORITY_BONUS = 5f
private const val WEIGHT_FACTOR = 0.15f
private const val NOVELTY_JITTER = 0.35f
private const val CATEGORY_COVERAGE_BONUS = 5f
private const val TAG_COVERAGE_BONUS = 0.35f
private const val TAG_REPETITION_PENALTY = -0.15f
private const val SUBJECT_REPETITION_PENALTY = -0.75f
private const val RELATED_TOPIC_FEEDBACK_FACTOR = 0.15f
private const val SHOWCASE_PRIORITY_BONUS = 1f
private const val DISCOVER_FEATURED_ITEM_COUNT = 20
private const val ACTIVE_SEASON_PRIORITY = 0.8f

private data class SeasonalCollectionDefinition(
    val key: String,
    val from: MonthDay,
    val to: MonthDay,
    val collectionId: Long,
    val titleKind: DiscoverTitleKind,
)

private val SEASONAL_COLLECTIONS =
    listOf(
        SeasonalCollectionDefinition("spring", MonthDay.of(3, 20), MonthDay.of(4, 20), 1884277090L, DiscoverTitleKind.SEASONAL_SPRING),
        SeasonalCollectionDefinition("summer", MonthDay.of(6, 19), MonthDay.of(7, 31), 1884277844L, DiscoverTitleKind.SEASONAL_SUMMER),
        SeasonalCollectionDefinition("fall", MonthDay.of(9, 23), MonthDay.of(10, 23), 1884263373L, DiscoverTitleKind.SEASONAL_FALL),
        SeasonalCollectionDefinition("halloween", MonthDay.of(10, 24), MonthDay.of(11, 2), 1873353144L, DiscoverTitleKind.SEASONAL_HALLOWEEN),
        SeasonalCollectionDefinition("winter", MonthDay.of(12, 1), MonthDay.of(1, 31), 1884258271L, DiscoverTitleKind.SEASONAL_WINTER),
    )

private val BEST_OF_YEAR_COVERS =
    linkedMapOf(
        2016 to 818603284L,
        2017 to 932995255L,
        2018 to 1339064732L,
        2019 to 1962375063L,
        2020 to 2292710588L,
        2021 to 2388299037L,
        2022 to 2784382079L,
        2023 to 2944773634L,
        2024 to 3163333989L,
        2025 to 3441873795L,
        2026 to 3696323523L,
    )

private fun MonthDay.isWithin(
    from: MonthDay,
    to: MonthDay,
): Boolean =
    if (from <= to) {
        this >= from && this <= to
    } else {
        this >= from || this <= to
    }
