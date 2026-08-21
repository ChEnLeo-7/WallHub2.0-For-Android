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

@HiltViewModel
class DiscoverViewModel
    @Inject
    constructor(
        private val coordinator: DiscoverFeedCoordinator,
        private val feedbackRepository: DiscoverFeedbackRepository,
        private val savedQueryRepository: DiscoverSavedQueryRepository,
        settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(DiscoverFeedState())
        val state: StateFlow<DiscoverFeedState> = mutableState.asStateFlow()

        private var generationSpecs: List<DiscoverRailSpec> = emptyList()
        private var nextSpecIndex = 0
        private var generationJob: Job? = null
        private val railJobs = mutableMapOf<String, Job>()
        private var generationStartedAtMillis = 0L
        private var firstReadyLogged = false

        init {
            viewModelScope.launch {
                feedbackRepository.feedback.collect { feedback ->
                    mutableState.value = mutableState.value.copy(feedback = feedback)
                }
            }
            viewModelScope.launch {
                settingsRepository.preferences
                    .map { it.steamWorkshopDataSource to it.matureContentEnabled }
                    .distinctUntilChanged()
                    .collect { refresh() }
            }
        }

        fun refresh() {
            generationJob?.cancel()
            railJobs.values.toList().forEach(Job::cancel)
            railJobs.clear()
            generationSpecs = emptyList()
            nextSpecIndex = 0
            val generation = mutableState.value.generation + 1L
            generationStartedAtMillis = System.currentTimeMillis()
            firstReadyLogged = false
            mutableState.value =
                DiscoverFeedState(
                    feedback = mutableState.value.feedback,
                    isPreparing = true,
                    generation = generation,
                )
            generationJob =
                viewModelScope.launch {
                    try {
                        val prepared = coordinator.prepareGeneration(generation)
                        if (mutableState.value.generation != generation) return@launch
                        generationSpecs = prepared.specs
                        Log.i(
                            TAG,
                            "generation=$generation metadata=${prepared.metadataSource} version=${prepared.metadataVersion} specs=${prepared.specs.size} " +
                                "focusCreators=${prepared.specs.firstOrNull { it.category == DiscoverCategory.CREATOR_SHOWCASE }?.children?.size ?: 0} " +
                                "focusCollections=${prepared.specs.firstOrNull { it.category == DiscoverCategory.COLLECTION_SHOWCASE }?.children?.size ?: 0}",
                        )
                        mutableState.value =
                            mutableState.value.copy(
                                isPreparing = false,
                                metadataSource = prepared.metadataSource,
                                metadataVersion = prepared.metadataVersion,
                                hasMore = prepared.specs.isNotEmpty(),
                                error = null,
                            )
                        appendRails(DISCOVER_INITIAL_RAIL_COUNT)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (mutableState.value.generation == generation) {
                            mutableState.value =
                                mutableState.value.copy(
                                    isPreparing = false,
                                    hasMore = false,
                                    error = error.message ?: "discover_prepare_failed",
                                )
                        }
                    }
                }
        }

        fun loadMore() {
            if (mutableState.value.isPreparing || nextSpecIndex >= generationSpecs.size) return
            appendRails(DISCOVER_APPEND_RAIL_COUNT)
        }

        fun setVisible(visible: Boolean) {
            if (!visible) {
                railJobs.values.toList().forEach(Job::cancel)
                railJobs.clear()
                mutableState.value =
                    mutableState.value.copy(
                        rails =
                            mutableState.value.rails.map { rail ->
                                if (rail.loadState == DiscoverRailLoadState.LOADING) rail.copy(loadState = DiscoverRailLoadState.QUEUED) else rail
                            },
                    )
                return
            }
            val generation = mutableState.value.generation
            mutableState.value.rails
                .filter { it.loadState == DiscoverRailLoadState.QUEUED }
                .forEach { launchRail(it.spec, generation) }
        }

        fun retryRail(railId: String) {
            val rail = mutableState.value.rails.firstOrNull { it.spec.id == railId } ?: return
            if (railJobs[railId]?.isActive == true) return
            updateRail(railId) { it.copy(loadState = DiscoverRailLoadState.QUEUED, error = null) }
            launchRail(rail.spec, mutableState.value.generation)
        }

        fun toggleLike(railId: String) {
            val feedback = mutableState.value.feedback[railId]
            viewModelScope.launch { feedbackRepository.setLiked(railId, feedback?.liked != true) }
        }

        fun toggleDislike(railId: String) {
            val feedback = mutableState.value.feedback[railId]
            viewModelScope.launch {
                val disliked = feedback?.disliked != true
                feedbackRepository.setDisliked(railId, disliked)
                if (disliked) {
                    railJobs.remove(railId)?.cancel()
                    mutableState.value = mutableState.value.copy(rails = mutableState.value.rails.filterNot { it.spec.id == railId })
                    loadMore()
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
            val retainedIds = retained.mapTo(hashSetOf()) { it.spec.id }
            railJobs.keys.filterNot(retainedIds::contains).toList().forEach { evictedId ->
                railJobs.remove(evictedId)?.cancel()
            }
            mutableState.value =
                mutableState.value.copy(
                    rails = retained,
                    hasMore = nextSpecIndex < generationSpecs.size,
                )
            specs.filter { it.id in retainedIds }.forEach { launchRail(it, generation) }
        }

        private fun launchRail(
            spec: DiscoverRailSpec,
            generation: Long,
        ) {
            if (railJobs[spec.id]?.isActive == true) return
            val job =
                viewModelScope.launch {
                    updateRail(spec.id, generation) {
                        it.copy(
                            loadState = DiscoverRailLoadState.LOADING,
                            metrics = it.metrics.copy(startedAtMillis = System.currentTimeMillis()),
                        )
                    }
                    try {
                        val loaded = coordinator.loadRail(spec, generation, emptySet())
                        if (mutableState.value.generation != generation) return@launch
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
                            )
                        }
                        if (loaded.items.isEmpty() && nextSpecIndex < generationSpecs.size) {
                            mutableState.value =
                                mutableState.value.copy(
                                    rails = mutableState.value.rails.filterNot { it.spec.id == spec.id },
                                )
                            appendRails(1)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (mutableState.value.generation == generation) {
                            val attempts = (error as? DiscoverRailLoadException)?.attempts ?: 1
                            updateRail(spec.id, generation) { rail ->
                                rail.copy(
                                    loadState = DiscoverRailLoadState.FAILED_FINAL,
                                    error = error.message ?: "discover_rail_failed",
                                    metrics =
                                        rail.metrics.copy(
                                            durationMillis = rail.metrics.startedAtMillis?.let { System.currentTimeMillis() - it },
                                            attempts = attempts,
                                            degradationCount = spec.queryPlan.degradations.size,
                                        ),
                                )
                            }
                        }
                    } finally {
                        railJobs.remove(spec.id)
                    }
                }
            railJobs[spec.id] = job
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
        sort = query?.sort ?: com.wallhub.android.core.model.WorkshopSort.TRENDING,
        days = query?.days ?: 30,
        exactPhrase = query?.exactPhrase == true,
        allowNsfw = query?.allowNsfw == true,
        mobileCompatibleOnly = query?.mobileCompatibleOnly == true,
        previewUrl = featuredItems.firstOrNull()?.cover?.previewUrl ?: items.firstOrNull()?.previewUrl,
    )
}

private const val TAG = "DiscoverFeed"

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
