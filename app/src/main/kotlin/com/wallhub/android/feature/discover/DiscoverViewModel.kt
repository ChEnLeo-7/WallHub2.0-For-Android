package com.wallhub.android.feature.discover

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wallhub.android.core.model.DiscoverFeedbackRepository
import com.wallhub.android.core.model.DiscoverSavedQuery
import com.wallhub.android.core.model.DiscoverSavedQueryCategory
import com.wallhub.android.core.model.DiscoverSavedQueryRepository
import com.wallhub.android.core.model.DiscoverSavedQuerySource
import com.wallhub.android.core.model.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@HiltViewModel
class DiscoverViewModel
    @Inject
    constructor(
        private val coordinator: DiscoverFeedLoader,
        private val feedbackRepository: DiscoverFeedbackRepository,
        private val savedQueryRepository: DiscoverSavedQueryRepository,
        settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(DiscoverFeedState())
        val state: StateFlow<DiscoverFeedState> = mutableState.asStateFlow()

        private var generationSpecs: List<DiscoverRailSpec> = emptyList()
        private var nextSpecIndex = 0
        private var generationJob: Job? = null
        private var metadataJob: Job? = null
        private var feedWorker: Job? = null
        private val feedLoadMutex = Mutex()
        private var visible = false
        private var generationStartedAtMillis = 0L
        private var firstReadyLogged = false
        private val suppressedFeedbackKeys = mutableSetOf<String>()
        private val refreshRequests = DiscoverRefreshRequestTracker()

        init {
            viewModelScope.launch {
                feedbackRepository.feedback.collect { feedback ->
                    mutableState.value = mutableState.value.copy(feedback = feedback)
                }
            }
            viewModelScope.launch {
                var previousMatureContentEnabled: Boolean? = null
                settingsRepository.preferences
                    .map { it.steamWorkshopDataSource to it.matureContentEnabled }
                    .distinctUntilChanged()
                    .collect { policy ->
                        val policyTightened = previousMatureContentEnabled == true && !policy.second
                        previousMatureContentEnabled = policy.second
                        refresh(preserveCommittedRows = !policyTightened)
                    }
            }
        }

        /** Consumes a bottom-navigation request once for this restored Discover back-stack entry. */
        fun consumeRefreshRequest(request: Int): Boolean = refreshRequests.consume(request)

        fun refresh(preserveCommittedRows: Boolean = true) {
            generationJob?.cancel()
            metadataJob?.cancel()
            feedWorker?.cancel()
            generationSpecs = emptyList()
            nextSpecIndex = 0
            val previous = mutableState.value
            val generation = previous.generation + 1L
            generationStartedAtMillis = System.currentTimeMillis()
            firstReadyLogged = false
            val retainedRows = if (preserveCommittedRows) previous.rails else emptyList()
            mutableState.value =
                previous.copy(
                    rails = retainedRows,
                    isPreparing = retainedRows.isEmpty(),
                    isRefreshing = retainedRows.isNotEmpty(),
                    generation = generation,
                    hasMore = true,
                    error = null,
                )
            val bootstrapJob =
                viewModelScope.launch {
                    try {
                        val cached = coordinator.prepareCachedMetadataGeneration(generation)
                        val prepared = cached ?: coordinator.prepareStaticGeneration(generation)
                        if (mutableState.value.generation != generation) return@launch
                        commitGeneration(prepared, preserveCommittedRows = retainedRows.isNotEmpty())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (mutableState.value.generation == generation) {
                            mutableState.value =
                                mutableState.value.copy(
                                    isPreparing = false,
                                    isRefreshing = false,
                                    hasMore = false,
                                    error = error.message ?: "discover_prepare_failed",
                                )
                        }
                    }
                }
            generationJob = bootstrapJob
            metadataJob =
                viewModelScope.launch {
                    try {
                        // Bootstrap prefers the validated LKG; only a device without one sees generic rails.
                        // Revalidation then atomically replaces that generation without flashing an empty feed.
                        val refreshed = coordinator.prepareMetadataGeneration(generation)
                        bootstrapJob.join()
                        if (mutableState.value.generation != generation) return@launch
                        if (refreshed.specs.isNotEmpty()) replaceMetadataGeneration(refreshed)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        // The persisted curator snapshot or generic rails remain usable.
                    }
                }
        }

        fun loadMore() {
            if (mutableState.value.isPreparing || nextSpecIndex >= generationSpecs.size) return
            appendRails(DISCOVER_APPEND_RAIL_COUNT)
        }

        fun setVisible(visible: Boolean) {
            this.visible = visible
            if (!visible) {
                feedWorker?.cancel()
                feedWorker = null
                mutableState.value =
                    mutableState.value.copy(
                        rails = mutableState.value.rails.map { rail ->
                            if (rail.loadState == DiscoverRailLoadState.LOADING) rail.copy(loadState = DiscoverRailLoadState.QUEUED) else rail
                        },
                    )
                return
            }
            startFeedWorker()
        }

        fun retryRail(railId: String) {
            if (mutableState.value.rails.none { it.spec.id == railId }) return
            updateRail(railId) { it.copy(loadState = DiscoverRailLoadState.QUEUED, error = null) }
            startFeedWorker()
        }

        fun toggleLike(railId: String) {
            val feedback = mutableState.value.feedback[railId]
            viewModelScope.launch { feedbackRepository.setLiked(railId, feedback?.liked != true) }
        }

        fun toggleDislike(railId: String) {
            val feedback = mutableState.value.feedback[railId]
            viewModelScope.launch {
                val disliked = feedback?.disliked != true
                if (disliked) suppressedFeedbackKeys += railId else suppressedFeedbackKeys -= railId
                feedbackRepository.setDisliked(railId, disliked)
                if (disliked) {
                    val wasLoading = mutableState.value.rails.any {
                        it.spec.id == railId && it.loadState == DiscoverRailLoadState.LOADING
                    }
                    if (wasLoading) {
                        feedWorker?.cancel()
                        feedWorker = null
                    }
                    mutableState.value = mutableState.value.copy(rails = mutableState.value.rails.filterNot { it.spec.id == railId })
                    loadMore()
                    if (wasLoading) startFeedWorker()
                }
            }
        }

        fun toggleFavorite(
            rail: DiscoverRailState,
            title: String,
        ) {
            val feedback = mutableState.value.feedback[rail.spec.feedbackKey]
            viewModelScope.launch {
                val favorited = feedback?.favorited != true
                if (favorited) savedQueryRepository.upsert(rail.toSavedQuery(title)) else savedQueryRepository.remove(rail.spec.feedbackKey)
                feedbackRepository.setFavorited(rail.spec.feedbackKey, favorited)
            }
        }

        private fun appendRails(count: Int) {
            val generation = mutableState.value.generation
            val specs = generationSpecs.drop(nextSpecIndex).take(count)
            if (specs.isEmpty()) {
                mutableState.value = mutableState.value.copy(hasMore = false)
                return
            }
            nextSpecIndex += specs.size
            val appended = mutableState.value.rails + specs.map(::DiscoverRailState)
            val retained = retainBoundedRails(appended)
            mutableState.value =
                mutableState.value.copy(
                    rails = retained,
                    hasMore = nextSpecIndex < generationSpecs.size,
                )
            startFeedWorker()
        }

        private fun startFeedWorker() {
            if (!visible || feedWorker?.isActive == true) return
            feedWorker =
                viewModelScope.launch {
                    while (true) {
                        val generation = mutableState.value.generation
                        val rail = mutableState.value.rails.firstOrNull { it.loadState == DiscoverRailLoadState.QUEUED } ?: break
                        loadRail(rail.spec, generation)
                    }
                    mutableState.value = mutableState.value.copy(isRefreshing = false)
                }
        }

        private suspend fun loadRail(
            spec: DiscoverRailSpec,
            generation: Long,
        ) {
            updateRail(spec.id, generation) {
                it.copy(
                    loadState = DiscoverRailLoadState.LOADING,
                    metrics = it.metrics.copy(startedAtMillis = System.currentTimeMillis()),
                )
            }
            try {
                val loaded = feedLoadMutex.withLock { coordinator.loadRail(spec, generation, emptySet()) }
                if (mutableState.value.generation != generation) return
                val currentSpec = mutableState.value.rails.firstOrNull { it.spec.id == spec.id }?.spec
                if (currentSpec != spec) return
                if (loaded.items.isNotEmpty() && !firstReadyLogged) {
                    firstReadyLogged = true
                    Log.i(TAG, "generation=$generation firstRailReadyMs=${System.currentTimeMillis() - generationStartedAtMillis}")
                }
                updateRail(spec.id, generation) { rail ->
                    rail.copy(
                        loadState = if (loaded.items.isEmpty()) DiscoverRailLoadState.EMPTY else DiscoverRailLoadState.READY,
                        items = loaded.items,
                        resolvedTitle = loaded.resolvedTitle,
                        error = null,
                        metrics = loaded.metrics,
                        featuredItems = loaded.featuredItems,
                        partialMissingCount = loaded.partialMissingCount,
                    )
                }
                if (loaded.items.isEmpty() && nextSpecIndex < generationSpecs.size) {
                    mutableState.value = mutableState.value.copy(rails = mutableState.value.rails.filterNot { it.spec.id == spec.id })
                    appendRails(1)
                }
            } catch (error: CancellationException) {
                updateRail(spec.id, generation) { rail ->
                    if (rail.loadState == DiscoverRailLoadState.LOADING) {
                        rail.copy(loadState = DiscoverRailLoadState.QUEUED)
                    } else {
                        rail
                    }
                }
                throw error
            } catch (error: Throwable) {
                if (mutableState.value.generation == generation) {
                    val attempts = (error as? DiscoverRailLoadException)?.attempts ?: 1
                    updateRail(spec.id, generation) { rail ->
                        if (rail.spec != spec) return@updateRail rail
                        rail.copy(
                            loadState = DiscoverRailLoadState.FAILED_FINAL,
                            error = error.message ?: "discover_rail_failed",
                            metrics = rail.metrics.copy(
                                durationMillis = rail.metrics.startedAtMillis?.let { System.currentTimeMillis() - it },
                                attempts = attempts,
                                degradationCount = spec.queryPlan.degradations.size,
                            ),
                        )
                    }
                }
            }
        }

        private fun commitGeneration(
            prepared: DiscoverGeneration,
            preserveCommittedRows: Boolean,
        ) {
            val previousRows = mutableState.value.rails
            val initialCount = initialDiscoverRailCount(prepared.specs)
            val freshRows =
                retainBoundedRails(
                    if (preserveCommittedRows) {
                        rowsForStaticRefresh(
                            specs = prepared.specs,
                            previousRows = previousRows,
                            initialCount = initialCount,
                        )
                    } else {
                        prepared.specs.take(initialCount).map(::DiscoverRailState)
                    },
                )
            val materializedIds = freshRows.mapTo(hashSetOf()) { it.spec.id }
            generationSpecs =
                freshRows.map(DiscoverRailState::spec) +
                    prepared.specs.filterNot { it.id in materializedIds }
            nextSpecIndex = freshRows.size
            mutableState.value =
                mutableState.value.copy(
                    rails = freshRows,
                    isPreparing = false,
                    isRefreshing = preserveCommittedRows,
                    metadataSource = prepared.metadataSource,
                    metadataVersion = prepared.metadataVersion,
                    hasMore = nextSpecIndex < generationSpecs.size,
                    error = null,
                )
            startFeedWorker()
        }

        private fun replaceMetadataGeneration(prepared: DiscoverGeneration) {
            val currentRows = mutableState.value.rails.associateBy { it.spec.id }
            val feedback = mutableState.value.feedback
            val specs =
                prepared.specs.filterNot { spec ->
                    spec.feedbackKey in suppressedFeedbackKeys || feedback[spec.feedbackKey]?.disliked == true
                }
            val initialCount = initialDiscoverRailCount(specs)
            val rows =
                specs.take(initialCount).map { spec ->
                    currentRows[spec.id]?.copy(spec = spec, loadState = DiscoverRailLoadState.QUEUED, error = null)
                        ?: DiscoverRailState(spec)
                }
            val materializedIds = rows.mapTo(hashSetOf()) { it.spec.id }
            generationSpecs = rows.map(DiscoverRailState::spec) + specs.filterNot { it.id in materializedIds }
            nextSpecIndex = rows.size
            mutableState.value =
                mutableState.value.copy(
                    rails = rows,
                    isPreparing = false,
                    isRefreshing = rows.isNotEmpty(),
                    metadataSource = prepared.metadataSource,
                    metadataVersion = prepared.metadataVersion,
                    hasMore = nextSpecIndex < generationSpecs.size,
                    error = null,
                )
            startFeedWorker()
        }

        private fun updateRail(
            railId: String,
            expectedGeneration: Long = mutableState.value.generation,
            transform: (DiscoverRailState) -> DiscoverRailState,
        ) {
            mutableState.value = mutableState.value.updateRailIfCurrent(expectedGeneration, railId, transform)
        }
    }

private fun DiscoverRailState.toSavedQuery(title: String): DiscoverSavedQuery {
    val query = spec.queryPlan.query
    return DiscoverSavedQuery(
        id = spec.feedbackKey,
        title = title,
        category =
            when (spec.category) {
                DiscoverCategory.CREATOR -> DiscoverSavedQueryCategory.CREATOR
                DiscoverCategory.COLLECTION -> DiscoverSavedQueryCategory.COLLECTION
                DiscoverCategory.KEYWORD -> DiscoverSavedQueryCategory.KEYWORD
                else -> DiscoverSavedQueryCategory.TOPIC
            },
        source = DiscoverSavedQuerySource.FAVORITE,
        semantic = spec.semantic,
        searchText = query?.searchText.orEmpty(),
        creatorId = query?.creatorId,
        collectionId = spec.queryPlan.collectionId,
        tags = query?.tags.orEmpty(),
        excludedTags = query?.excludedTags.orEmpty(),
        officialTags = query?.officialTags.orEmpty(),
        excludedOfficialTags = query?.excludedOfficialTags.orEmpty(),
        requiredTagGroups = query?.requiredTagGroups.orEmpty() + spec.queryPlan.clientRequiredTagGroups,
        types = query?.types.orEmpty(),
        ratings = query?.ratings.orEmpty(),
        genres = query?.genres.orEmpty(),
        resolutions = query?.resolutions.orEmpty(),
        sort = query?.sort ?: com.wallhub.android.core.model.WorkshopSort.TRENDING,
        days = query?.days ?: 30,
        exactPhrase = query?.exactPhrase == true,
        allowNsfw = query?.allowNsfw == true,
        mobileCompatibleOnly = query?.mobileCompatibleOnly == true,
        previewUrl = featuredItems.firstOrNull()?.cover?.previewUrl ?: items.firstOrNull()?.previewUrl,
    )
}

private const val TAG = "DiscoverFeed"

/** Tracks explicit bottom-navigation refresh requests for the lifetime of one Discover ViewModel. */
internal class DiscoverRefreshRequestTracker {
    private var handledRequest: Int? = null

    fun consume(request: Int): Boolean {
        val previous = handledRequest
        handledRequest = request
        return previous != null && previous != request
    }
}

internal fun rowsForStaticRefresh(
    specs: List<DiscoverRailSpec>,
    previousRows: List<DiscoverRailState>,
    initialCount: Int,
): List<DiscoverRailState> {
    val staticById = specs.associateBy(DiscoverRailSpec::id)
    val retained =
        previousRows.map { row ->
            val freshStatic = staticById[row.spec.id]
            if (freshStatic != null) {
                row.copy(spec = freshStatic, loadState = DiscoverRailLoadState.QUEUED, error = null)
            } else {
                row
            }
        }
    val retainedIds = retained.mapTo(hashSetOf()) { it.spec.id }
    return retained +
        specs
            .filterNot { it.id in retainedIds }
            .take((initialCount - retained.count { it.spec.source != DiscoverSpecSource.CURATOR_MANIFEST }).coerceAtLeast(0))
            .map(::DiscoverRailState)
}

internal fun rowsForDiscoverGeneration(
    specs: List<DiscoverRailSpec>,
    previousRows: List<DiscoverRailState>,
    preserveCommittedRows: Boolean,
): List<DiscoverRailState> {
    if (!preserveCommittedRows) return specs.map(::DiscoverRailState)
    val freshById = specs.associateBy(DiscoverRailSpec::id)
    val retained =
        previousRows.map { committed ->
            committed.copy(
                spec = freshById[committed.spec.id] ?: committed.spec,
                loadState = DiscoverRailLoadState.QUEUED,
                error = null,
            )
        }
    val retainedIds = retained.mapTo(hashSetOf()) { it.spec.id }
    return retained + specs.filterNot { it.id in retainedIds }.map(::DiscoverRailState)
}

internal fun retainBoundedRails(rails: List<DiscoverRailState>): List<DiscoverRailState> {
    val nonStickyCount = rails.count { !it.spec.sticky }
    if (nonStickyCount <= DISCOVER_MAX_NON_STICKY_RAILS) return rails
    var toEvict = nonStickyCount - DISCOVER_MAX_NON_STICKY_RAILS
    return rails.filter { rail ->
        if (!rail.spec.sticky && toEvict > 0) {
            toEvict -= 1
            false
        } else {
            true
        }
    }
}
