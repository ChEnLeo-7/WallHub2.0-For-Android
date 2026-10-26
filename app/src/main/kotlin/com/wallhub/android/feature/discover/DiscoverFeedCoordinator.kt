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
import com.wallhub.android.feature.discover.model.DiscoverCuratorCategory
import com.wallhub.android.feature.discover.model.DiscoverMetadataSource
import com.wallhub.android.feature.discover.model.DiscoverCuratorDescriptor
import com.wallhub.android.feature.discover.model.DiscoverCuratorRepository
import java.io.IOException
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

interface DiscoverFeedLoader {
    suspend fun prepareStaticGeneration(generation: Long): DiscoverGeneration

    suspend fun prepareCachedMetadataGeneration(generation: Long): DiscoverGeneration? = null

    suspend fun prepareMetadataGeneration(generation: Long): DiscoverGeneration

    suspend fun loadRail(
        spec: DiscoverRailSpec,
        generation: Long,
        excludedIds: Set<Long>,
    ): DiscoverRailLoadResult
}

@Singleton
class DiscoverFeedCoordinator
    @Inject
    constructor(
        private val metadataRepository: DiscoverCuratorRepository,
        private val queryAdapter: DiscoverQueryAdapter,
        private val collectionResolver: DiscoverCollectionResolver,
        private val pageSource: DiscoverPageSource,
        private val networkBudget: DiscoverNetworkBudget,
        private val workshopRepository: WorkshopRepository,
        private val unifiedWorkshopRepository: SteamUnifiedWorkshopRepository,
        private val feedbackRepository: DiscoverFeedbackRepository,
        private val settingsRepository: SettingsRepository,
    ) : DiscoverFeedLoader {
        override suspend fun prepareStaticGeneration(generation: Long): DiscoverGeneration {
            unifiedWorkshopRepository.invalidateDiscoverCache()
            workshopRepository.invalidateDiscoverCache()
            collectionResolver.invalidateDiscoverCache()
            val preferences = settingsRepository.preferences.first()
            val candidates =
                genericFallbackSpecs(
                    preferences.steamWorkshopDataSource,
                    preferences.matureContentEnabled,
                ).distinctBy(DiscoverRailSpec::id)
            return rankGeneration(
                generation = generation,
                candidates = candidates,
                metadataSource = DiscoverMetadataSource.GENERIC_FALLBACK,
                metadataVersion = "static",
            )
        }

        override suspend fun prepareCachedMetadataGeneration(generation: Long): DiscoverGeneration? {
            val snapshot = metadataRepository.loadCachedMetadata() ?: return null
            return prepareMetadataSnapshot(generation, snapshot)
        }

        override suspend fun prepareMetadataGeneration(generation: Long): DiscoverGeneration =
            prepareMetadataSnapshot(generation, metadataRepository.loadMetadata())

        private suspend fun prepareMetadataSnapshot(
            generation: Long,
            snapshot: com.wallhub.android.feature.discover.model.DiscoverMetadataSnapshot,
        ): DiscoverGeneration {
            val preferences = settingsRepository.preferences.first()
            val candidates =
                snapshot.descriptors
                    .flatMap { descriptor ->
                        descriptor.toRailSpecs(preferences.steamWorkshopDataSource, preferences.matureContentEnabled)
                    }.distinctBy(DiscoverRailSpec::id)
            return rankGeneration(generation, candidates, snapshot.source, snapshot.version)
        }

        private suspend fun rankGeneration(
            generation: Long,
            candidates: List<DiscoverRailSpec>,
            metadataSource: DiscoverMetadataSource,
            metadataVersion: String,
        ): DiscoverGeneration {
            val rawCandidates = candidates
            val candidates = aggregateCuratorShowcases(rawCandidates)
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
            val ranked =
                deferInitialShowcases(
                    rankSpecs(candidates, feedbackAdjustments, generation)
                        .filterNot { feedbackWeights.getValue(it.feedbackKey).suppressCurrentGeneration },
                )
            return DiscoverGeneration(
                generation = generation,
                metadataSource = metadataSource,
                metadataVersion = metadataVersion,
                specs = ranked,
            )
        }

        override suspend fun loadRail(
            spec: DiscoverRailSpec,
            generation: Long,
            excludedIds: Set<Long>,
        ): DiscoverRailLoadResult {
            val startedAt = System.currentTimeMillis()
            if (spec.children.isNotEmpty()) {
                return loadFeaturedRail(spec, generation, excludedIds, startedAt)
            }
            var attempts = 0
            var lastFailure: Throwable? = null
            repeat(MAX_RAIL_ATTEMPTS) { attempt ->
                attempts = attempt + 1
                try {
                    val candidates = loadCandidates(spec)
                    val selected = selectDiscoverCandidates(spec, candidates, excludedIds)
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
                    if (!isTransientDiscoverFailure(error) || attempt + 1 >= MAX_RAIL_ATTEMPTS) {
                        throw DiscoverRailLoadException(attempts, error)
                    }
                    delay(RETRY_BASE_DELAY_MILLIS shl attempt)
                }
            }
            throw DiscoverRailLoadException(attempts, lastFailure)
        }

        private suspend fun loadCandidates(spec: DiscoverRailSpec): List<com.wallhub.android.core.model.WorkshopSummary> {
            val baseQuery = spec.queryPlan.query ?: WorkshopBrowseQuery()
            if (spec.queryPlan.query == null && spec.queryPlan.collectionId == null) return emptyList()
            val recipe =
                DiscoverPageRecipe(
                    query = baseQuery,
                    collectionId = spec.queryPlan.collectionId,
                    clientRequiredTagGroups = spec.queryPlan.clientRequiredTagGroups,
                )
            val candidates = mutableListOf<com.wallhub.android.core.model.WorkshopSummary>()
            for (page in 1..spec.qualityRules.candidatePageCount) {
                val result = pageSource.page(recipe, page, DISCOVER_QUERY_PAGE_SIZE)
                candidates += spec.queryPlan.filter(result.items)
                if (!result.hasNextPage || candidates.size >= spec.qualityRules.targetItemCount) break
            }
            return candidates.distinctBy { it.id }
        }

        private suspend fun loadFeaturedRail(
            spec: DiscoverRailSpec,
            generation: Long,
            excludedIds: Set<Long>,
            startedAt: Long,
        ): DiscoverRailLoadResult = coroutineScope {
            val children = sampleFeaturedChildren(spec.children, generation, spec.id)
            val featured =
                children
                    .map { child ->
                        async {
                            val candidates = discoverOptional { loadCandidates(child) }.orEmpty()
                            val cover =
                                candidates.firstOrNull { candidate ->
                                    candidate.id !in excludedIds &&
                                        (!child.qualityRules.requirePreview || !candidate.previewUrl.isNullOrBlank()) &&
                                        (!child.qualityRules.requireKnownType || candidate.type != WorkshopType.UNKNOWN)
                                } ?: return@async null
                            val title =
                                when (child.category) {
                                    DiscoverCategory.CREATOR -> cover.author
                                    else -> child.titleArgument ?: cover.title
                                }
                            DiscoverFeaturedItem(child, cover, title)
                        }
                    }.awaitAll()
                    .filterNotNull()
                    .distinctBy { it.spec.id }
                    .take(DISCOVER_FEATURED_ITEM_COUNT)
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

        private fun DiscoverCuratorDescriptor.toRailSpecs(
            dataSource: SteamWorkshopDataSource,
            allowNsfw: Boolean,
        ): List<DiscoverRailSpec> =
            queryAdapter.adapt(this, dataSource).mapNotNull { rawPlan ->
                val plan = rawPlan.copy(query = rawPlan.query?.copy(allowNsfw = allowNsfw))
                if (!plan.isExecutable) return@mapNotNull null
                val category =
                    when (category) {
                        DiscoverCuratorCategory.CREATOR -> DiscoverCategory.CREATOR
                        DiscoverCuratorCategory.KEYWORD -> DiscoverCategory.KEYWORD
                        DiscoverCuratorCategory.COLLECTION -> DiscoverCategory.COLLECTION
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
                    diversityTags = (tags + includeTags + dependentTags).mapTo(linkedSetOf()) { it.lowercase(Locale.ROOT) },
                    queryPlan = plan,
                    priority = priority.coerceIn(-5f, 10f) + CURATOR_PRIORITY_BONUS,
                    weight = weight.coerceAtLeast(1),
                    sticky = sticky,
                    source = DiscoverSpecSource.CURATOR_MANIFEST,
                    drillDown =
                        when (category) {
                            DiscoverCategory.CREATOR -> DiscoverDrillDown.CREATOR_RESULTS
                            DiscoverCategory.COLLECTION -> DiscoverDrillDown.COLLECTION_RESULTS
                            DiscoverCategory.KEYWORD -> DiscoverDrillDown.KEYWORD_RESULTS
                            else -> DiscoverDrillDown.FULL_QUERY_RESULTS
                        },
                )
            }

        private fun aggregateCuratorShowcases(specs: List<DiscoverRailSpec>): List<DiscoverRailSpec> {
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

        private fun showcaseSpec(
            category: DiscoverCategory,
            children: List<DiscoverRailSpec>,
        ): DiscoverRailSpec =
            DiscoverRailSpec(
                id = if (category == DiscoverCategory.CREATOR_SHOWCASE) "curator:focus-creators" else "curator:focus-collections",
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
                source = DiscoverSpecSource.CURATOR_MANIFEST,
                drillDown = DiscoverDrillDown.FULL_QUERY_RESULTS,
                children = children,
            )

        private fun genericFallbackSpecs(
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
            source = DiscoverSpecSource.GENERIC_FALLBACK,
            drillDown = DiscoverDrillDown.FULL_QUERY_RESULTS,
        )
    }

internal fun selectDiscoverCandidates(
    spec: DiscoverRailSpec,
    candidates: List<com.wallhub.android.core.model.WorkshopSummary>,
    excludedIds: Set<Long>,
): List<com.wallhub.android.core.model.WorkshopSummary> =
    candidates
        .asSequence()
        .filter { it.id > 0L && it.id !in excludedIds }
        .filter { !spec.qualityRules.requirePreview || !it.previewUrl.isNullOrBlank() }
        .filter { !spec.qualityRules.requireKnownType || it.type != WorkshopType.UNKNOWN }
        .take(spec.qualityRules.targetItemCount)
        .toList()

internal suspend fun invalidateDiscoverCaches(
    unifiedWorkshopRepository: SteamUnifiedWorkshopRepository,
    workshopRepository: WorkshopRepository,
    collectionResolver: DiscoverCollectionResolver,
) {
    unifiedWorkshopRepository.invalidateDiscoverCache()
    workshopRepository.invalidateDiscoverCache()
    collectionResolver.invalidateDiscoverCache()
}

internal fun DiscoverCategory.isFocusShowcase(): Boolean =
    this == DiscoverCategory.CREATOR_SHOWCASE || this == DiscoverCategory.COLLECTION_SHOWCASE

/** Moves only first-screen showcases past five ordinary rails without disturbing the remaining ranking. */
internal fun deferInitialShowcases(specs: List<DiscoverRailSpec>): List<DiscoverRailSpec> {
    val result = specs.toMutableList()
    val deferred = mutableListOf<DiscoverRailSpec>()
    var index = 0
    while (index < DISCOVER_INITIAL_RAIL_COUNT && index < result.size) {
        if (result[index].category.isFocusShowcase()) {
            deferred += result.removeAt(index)
        } else {
            index++
        }
    }
    result.addAll(index, deferred)
    return result
}

internal fun initialDiscoverRailCount(specs: List<DiscoverRailSpec>): Int =
    DISCOVER_INITIAL_RAIL_COUNT.coerceAtMost(specs.size)

/** One focus rail resolves a bounded oversample, then publishes at most one horizontal page. */
internal fun sampleFeaturedChildren(
    children: List<DiscoverRailSpec>,
    generation: Long,
    railId: String,
): List<DiscoverRailSpec> =
    children
        .shuffled(Random(generation xor railId.hashCode().toLong()))
        .take(DISCOVER_FEATURED_RESOLUTION_COUNT)

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

private suspend fun <T> discoverOptional(block: suspend () -> T): T? =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

internal fun isTransientDiscoverFailure(error: Throwable): Boolean {
    if (error is CancellationException) return false
    val message = error.message?.lowercase(Locale.ROOT).orEmpty()
    if (error is IllegalArgumentException || error is UnsupportedOperationException ||
        "invalidparam" in message || "invalid param" in message || "unsupported" in message
    ) return false
    if (error is IOException) return true
    return error is IllegalStateException &&
        ("session unavailable" in message || "session_unavailable" in message || "refused before it was sent" in message)
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
private const val CURATOR_PRIORITY_BONUS = 5f
private const val WEIGHT_FACTOR = 0.15f
private const val NOVELTY_JITTER = 0.35f
private const val CATEGORY_COVERAGE_BONUS = 5f
private const val TAG_COVERAGE_BONUS = 0.35f
private const val TAG_REPETITION_PENALTY = -0.15f
private const val SUBJECT_REPETITION_PENALTY = -0.75f
private const val RELATED_TOPIC_FEEDBACK_FACTOR = 0.15f
private const val SHOWCASE_PRIORITY_BONUS = 1f
private const val DISCOVER_FEATURED_ITEM_COUNT = 8
private const val DISCOVER_FEATURED_RESOLUTION_COUNT = 8
