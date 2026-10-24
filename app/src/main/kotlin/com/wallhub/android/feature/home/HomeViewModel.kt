@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.home

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.model.DownloadEnqueueResult
import com.wallhub.android.core.model.WorkshopDownloads
import com.wallhub.android.core.model.toQuickDownloadRequest
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.workshopDetailTagSearch
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.data.downloads.DownloadTimingTelemetry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        @ApplicationContext private val applicationContext: Context,
        private val workshopRepository: WorkshopRepository,
        private val settingsRepository: SettingsRepository,
        private val downloadTaskRepository: WorkshopDownloads,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val mutableState =
            MutableStateFlow(
                initialHomeUiState(
                    authorSearchCreator = savedStateHandle.get(HOME_AUTHOR_SEARCH_CREATOR_ARGUMENT),
                    tagSearchTag = savedStateHandle.get(HOME_TAG_SEARCH_ARGUMENT),
                ),
            )
        private var loadJob: Job? = null
        private var requestVersion = 0L
        private var preferencesInitialized = false
        private var unsubmittedQuery: String? = null
        private val authorNameRequests = mutableSetOf<Long>()
        private val effectChannel = Channel<HomeEffect>(capacity = Channel.BUFFERED)

        val uiState: StateFlow<HomeUiState> = mutableState.asStateFlow()
        val effects: Flow<HomeEffect> = effectChannel.receiveAsFlow()

        init {
            viewModelScope.launch {
                settingsRepository.preferences.collect { preferences ->
                    val previous = mutableState.value
                    val firstPreferences = !preferencesInitialized
                    val sanitizedRatings =
                        if (
                            firstPreferences && previous.creatorId != null && preferences.matureContentEnabled
                        ) {
                            setOf(WorkshopRating.ALL)
                        } else {
                            previous.selectedRatings.normalizedRatings(preferences.matureContentEnabled)
                        }
                    val requiresReload =
                        previous.pageSize != preferences.homePageSize ||
                            previous.matureContentEnabled != preferences.matureContentEnabled ||
                            previous.paginationMode != preferences.homePaginationMode ||
                            previous.steamApiKey != preferences.steamApiKey ||
                            previous.steamAccessEnabled != preferences.steamAccessEnabled ||
                            previous.steamWorkshopDataSource != preferences.steamWorkshopDataSource
                    mutableState.value =
                        previous.copy(
                            pageSize = preferences.homePageSize,
                            columns = preferences.homeColumns,
                            multiSelect = preferences.homeFilterMultiSelect,
                            homeSearchFab = preferences.homeSearchFab,
                            steamApiKey = preferences.steamApiKey,
                            steamAccessEnabled = preferences.steamAccessEnabled,
                            steamWorkshopDataSource = preferences.steamWorkshopDataSource,
                            cardAction = preferences.homeCardAction,
                            paginationMode = preferences.homePaginationMode,
                            matureContentEnabled = preferences.matureContentEnabled,
                            outputTreeUri = preferences.outputTreeUri,
                            selectedRatings = sanitizedRatings,
                        )
                    preferencesInitialized = true
                    if (firstPreferences || requiresReload) refresh()
                }
            }
        }

        fun onAction(action: HomeAction) {
            action.immediateEffect()?.let(::emitEffect) ?: handleStateAction(action)
        }

        private fun handleStateAction(action: HomeAction) {
            if (handleSearchAndFilterAction(action) || handlePaginationAction(action)) return
            when (action) {
                is HomeAction.LegacyStoragePermissionResult -> {
                    if (action.granted) {
                        enqueueCardDownload(action.request)
                    } else {
                        emitEffect(
                            HomeEffect.ShowMessage(
                                R.string.home_storage_permission_denied,
                            ),
                        )
                    }
                }
                is HomeAction.RequestAuthorDisplayName -> requestAuthorDisplayName(action.item)
                else -> Unit
            }
        }

        private fun handleSearchAndFilterAction(action: HomeAction): Boolean {
            when (action) {
                is HomeAction.QueryChanged -> updateQuery(action.query)
                HomeAction.SubmitSearch -> submitSearch()
                HomeAction.RestoreUnsubmittedQuery -> restoreUnsubmittedQuery()
                HomeAction.ToggleExactPhrase -> toggleExactPhrase()
                is HomeAction.ApplyFilters -> applyFilters(action.selection, action.exactPhrase)
                is HomeAction.SelectViewMode -> setViewMode(action.viewMode)
                HomeAction.ResetAndRefresh -> resetAndRefresh()
                else -> return false
            }
            return true
        }

        private fun handlePaginationAction(action: HomeAction): Boolean {
            when (action) {
                HomeAction.Refresh -> refresh()
                HomeAction.LoadNextPage -> loadNextPage()
                is HomeAction.SelectPage -> selectPage(action.page)
                else -> return false
            }
            return true
        }

        private fun emitEffect(effect: HomeEffect) {
            effectChannel.trySend(effect)
        }

        private fun updateQuery(query: String) {
            val current = mutableState.value
            if (unsubmittedQuery == null) unsubmittedQuery = current.query
            mutableState.value =
                current.copy(
                    query = query,
                    creatorId = null,
                    error = null,
                    errorRes = null,
                )
        }

        private fun submitSearch() {
            val current = mutableState.value
            unsubmittedQuery = null
            mutableState.value = current.copy(creatorId = current.query.creatorIdOrNull())
            refreshAfterSearch()
        }

        private fun restoreUnsubmittedQuery() {
            val restoredQuery = unsubmittedQuery ?: return
            unsubmittedQuery = null
            mutableState.value =
                mutableState.value.copy(
                    query = restoredQuery,
                    creatorId = restoredQuery.creatorIdOrNull(),
                    error = null,
                    errorRes = null,
                )
        }

        private fun updateFirstContentCompletion(state: HomeUiState): HomeUiState {
            if (state.firstContentCompleteAtElapsedMs != null || state.items.isEmpty()) return state
            val firstItems = state.items.take((state.columns.coerceAtLeast(1) * 2))
            val complete = firstItems.all { item ->
                item.authorPlaceholder == WorkshopAuthorPlaceholder.NONE ||
                    state.authorDisplayNames[item.id] != null ||
                    item.id in state.personaDegradedItemIds
            }
            return if (complete) {
                state.copy(firstContentCompleteAtElapsedMs = SystemClock.elapsedRealtime())
            } else {
                state
            }
        }

        private fun requestAuthorDisplayName(item: WorkshopSummary) {
            if (item.authorPlaceholder == WorkshopAuthorPlaceholder.NONE || item.id in authorNameRequests) return
            authorNameRequests += item.id
            viewModelScope.launch {
                val authorName =
                    withTimeoutOrNull(PERSONA_COMPLETION_TIMEOUT_MS) {
                        runCatching {
                            workshopRepository.getDetail(item.id).summary
                        }.getOrNull()?.takeIf { it.authorPlaceholder == WorkshopAuthorPlaceholder.NONE }?.author
                    }
                if (!authorName.isNullOrBlank()) {
                    mutableState.value =
                        mutableState.value.let { state ->
                            updateFirstContentCompletion(
                                state.copy(authorDisplayNames = state.authorDisplayNames + (item.id to authorName)),
                            )
                        }
                } else {
                    authorNameRequests -= item.id
                    mutableState.value =
                        updateFirstContentCompletion(
                            mutableState.value.copy(personaDegradedItemIds = mutableState.value.personaDegradedItemIds + item.id),
                        )
                }
            }
        }

        private fun toggleExactPhrase() {
            mutableState.value = mutableState.value.copy(exactPhrase = !mutableState.value.exactPhrase)
            refresh()
        }

        private fun applyFilters(
            selection: HomeFilterSelection,
            exactPhrase: Boolean?,
        ) {
            val current = mutableState.value
            val normalized = selection.normalized(current.matureContentEnabled)
            val resolvedExactPhrase = exactPhrase ?: current.exactPhrase
            if (normalized == current.filterSelection() && resolvedExactPhrase == current.exactPhrase) return
            mutableState.value =
                current.copy(
                    exactPhrase = resolvedExactPhrase,
                    sort = normalized.sort,
                    days = normalized.days,
                    selectedTypes = normalized.types,
                    selectedRatings = normalized.ratings,
                    selectedGenres = normalized.genres,
                    selectedOfficialTags = normalized.officialTags,
                    selectedExcludedOfficialTags = normalized.excludedOfficialTags,
                    selectedResolutions = normalized.resolutions,
                )
            refresh()
        }

        private fun setViewMode(viewMode: HomeViewMode) {
            val current = mutableState.value
            if (current.viewMode == viewMode) return
            mutableState.value = current.copy(viewMode = viewMode)
        }

        private fun refresh() {
            reloadFirstPage(scrollToTopOnSuccess = false)
        }

        private fun refreshAfterSearch() {
            reloadFirstPage(scrollToTopOnSuccess = true)
        }

        private fun reloadFirstPage(scrollToTopOnSuccess: Boolean) {
            loadJob?.cancel()
            requestVersion += 1
            loadPage(
                page = 1,
                append = false,
                version = requestVersion,
                scrollToTopOnSuccess = scrollToTopOnSuccess,
            )
        }

        private fun resetAndRefresh() {
            unsubmittedQuery = null
            val current = mutableState.value
            mutableState.value =
                current.copy(
                    query = "",
                    creatorId = null,
                    exactPhrase = false,
                    selectedTypes = emptySet(),
                    selectedRatings = setOf(WorkshopRating.EVERYONE),
                    selectedGenres = DEFAULT_HOME_GENRE_SELECTION,
                    selectedOfficialTags = emptySet(),
                    selectedResolutions = DEFAULT_HOME_RESOLUTION_SELECTION,
                    requiredTags = emptySet(),
                    sort = WorkshopSort.TRENDING,
                    days = 30,
                    error = null,
                    errorRes = null,
                )
            refresh()
        }

        private fun loadNextPage() {
            val state = mutableState.value
            if (
                state.paginationMode != HomePaginationMode.INFINITE_SCROLL ||
                state.isInitialLoading ||
                state.isLoadingMore ||
                !state.hasNextPage
            ) {
                return
            }
            loadPage(page = state.nextPage, append = true, version = requestVersion)
        }

        private fun selectPage(page: Int) {
            val state = mutableState.value
            if (state.paginationMode != HomePaginationMode.PAGED || state.isInitialLoading || state.isPageLoading) return
            val targetPage = page.coerceAtLeast(1)
            if (targetPage == state.currentPage) return
            loadJob?.cancel()
            requestVersion += 1
            loadPage(page = targetPage, append = false, version = requestVersion)
        }

        private fun enqueueCardDownload(request: HomeAction.RequestDownload) {
            val item = request.item
            viewModelScope.launch {
                runCatching {
                    val preferences = settingsRepository.preferences.first()
                    downloadTaskRepository.enqueue(
                        item.toQuickDownloadRequest(
                            preferences = preferences,
                            localizedTitle = applicationContext.localizedTitle(item),
                            taskId = request.taskId,
                            outputTreeUri = mutableState.value.outputTreeUri,
                        ),
                    )
                }.onSuccess { result ->
                    val task = result.task
                    DownloadTimingTelemetry.log(
                        event = DownloadTimingTelemetry.HOMEPAGE_DOWNLOAD_CLICK,
                        taskId = task.id,
                        workshopId = item.id,
                        elapsedRealtimeMs = request.clickAtElapsedRealtimeMs,
                    )
                    effectChannel.send(
                        HomeEffect.ShowMessage(
                            if (result is DownloadEnqueueResult.Created) {
                                R.string.home_added_to_download_queue
                            } else {
                                R.string.download_already_active
                            },
                            listOf(task.title),
                        ),
                    )
                }.onFailure {
                    effectChannel.send(
                        HomeEffect.ShowMessage(R.string.home_unable_to_queue_download),
                    )
                }
            }
        }

        private fun loadPage(
            page: Int,
            append: Boolean,
            version: Long,
            scrollToTopOnSuccess: Boolean = false,
        ) {
            val requestState = mutableState.value
            loadJob =
                viewModelScope.launch {
                    mutableState.value =
                        requestState.copy(
                            isInitialLoading = !append && requestState.items.isEmpty(),
                            isLoadingMore = append,
                            isPageLoading = !append && requestState.items.isNotEmpty(),
                            error = null,
                            errorRes = null,
                        )
                    try {
                        val response =
                            withTimeout(
                                if (!append && requestState.items.isEmpty()) HOME_INITIAL_LOAD_TIMEOUT_MS else Long.MAX_VALUE,
                            ) {
                                workshopRepository.browse(
                                WorkshopBrowseQuery(
                                    page = page,
                                    pageSize = requestState.pageSize,
                                    searchText = requestState.query,
                                    creatorId = requestState.creatorId,
                                    tags = requestState.requiredTags + requestState.query.workshopDetailTagSearch(),
                                    types = requestState.selectedTypes,
                                    genres = requestState.selectedGenres.asEffectiveFilter(DEFAULT_HOME_GENRE_SELECTION),
                                    officialTags = requestState.selectedOfficialTags,
                                    excludedOfficialTags = requestState.selectedExcludedOfficialTags,
                                    resolutions = requestState.selectedResolutions.asEffectiveFilter(DEFAULT_HOME_RESOLUTION_SELECTION),
                                    ratings = requestState.selectedRatings,
                                    days = requestState.days,
                                    exactPhrase = requestState.exactPhrase,
                                    sort = requestState.sort,
                                    allowNsfw = requestState.matureContentEnabled,
                                ),
                            )
                            }
                        if (version != requestVersion) return@launch
                        val mergedState =
                            withContext(Dispatchers.Default) {
                                response.mergeInto(
                                    previous = requestState,
                                    append = append,
                                )
                            }
                        if (version != requestVersion) return@launch
                        val visibleAt = mergedState.firstContentVisibleAtElapsedMs
                            ?: mergedState.items.take((mergedState.columns.coerceAtLeast(1) * 2))
                                .takeIf { it.isNotEmpty() }
                                ?.let { SystemClock.elapsedRealtime() }
                        val completedState = updateFirstContentCompletion(
                            mergedState.copy(firstContentVisibleAtElapsedMs = visibleAt),
                        )
                        mutableState.value =
                            if (scrollToTopOnSuccess) {
                                completedState.copy(successfulSearchToken = requestState.successfulSearchToken + 1L)
                            } else {
                                completedState
                            }
                    } catch (error: TimeoutCancellationException) {
                        if (version != requestVersion) return@launch
                        Log.w(HOME_LOG_TAG, "home initial load timed out after ${HOME_INITIAL_LOAD_TIMEOUT_MS}ms")
                        mutableState.value =
                            requestState.copy(
                                isInitialLoading = false,
                                isLoadingMore = false,
                                isPageLoading = false,
                                error = null,
                                errorRes = R.string.home_unable_to_load_workshop,
                            )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (version != requestVersion) return@launch
                        Log.w(HOME_LOG_TAG, "home load failed stage=browse", error)
                        mutableState.value =
                            requestState.copy(
                                isInitialLoading = false,
                                isLoadingMore = false,
                                isPageLoading = false,
                                error = null,
                                errorRes = R.string.home_unable_to_load_workshop,
                            )
                    }
                }
        }

        private fun WorkshopPage.mergeInto(
            previous: HomeUiState,
            append: Boolean,
        ): HomeUiState {
            val resolvedTotalCount =
                resolveHomeTotalCount(
                    reportedTotalCount = totalCount,
                    previousTotalCount = previous.totalCount,
                    append = append,
                )
            val mergedItems =
                if (append) {
                    (previous.items + items).distinctBy(WorkshopSummary::id)
                } else {
                    items
                }
            return previous.copy(
                items = mergedItems,
                nextPage = page.nextPageOrLast(),
                currentPage = page,
                totalPages =
                    resolveHomeTotalPages(
                        reportedTotalPages = this.totalPages,
                        totalCount = resolvedTotalCount,
                        pageSize = previous.pageSize,
                        page = page,
                        hasNextPage = hasNextPage,
                    ),
                hasNextPage = hasNextPage,
                totalCount = resolvedTotalCount,
                isInitialLoading = false,
                isLoadingMore = false,
                isPageLoading = false,
                error = null,
                errorRes = null,
            )
        }
    }

internal fun HomeAction.immediateEffect(): HomeEffect? =
    when (this) {
        is HomeAction.RequestDownload -> HomeEffect.ResolveLegacyStoragePermission(this)
        is HomeAction.OpenDetail -> HomeEffect.OpenDetail(workshopId)
        is HomeAction.SearchAuthor -> HomeEffect.SearchAuthor(creator)
        is HomeAction.CopyText -> HomeEffect.CopyText(text, messageRes)
        is HomeAction.OpenSteam -> HomeEffect.OpenSteam(workshopId)
        is HomeAction.LegacyStoragePermissionResult -> null
        is HomeAction.QueryChanged,
        HomeAction.SubmitSearch,
        HomeAction.RestoreUnsubmittedQuery,
        HomeAction.ToggleExactPhrase,
        is HomeAction.ApplyFilters,
        is HomeAction.SelectViewMode,
        HomeAction.ResetAndRefresh,
        HomeAction.Refresh,
        HomeAction.LoadNextPage,
        is HomeAction.SelectPage,
        is HomeAction.RequestAuthorDisplayName,
        -> null
    }

private fun Set<String>.asEffectiveFilter(allOptions: Set<String>): Set<String> = takeUnless { it == allOptions }.orEmpty()

internal fun resolveHomeTotalCount(
    reportedTotalCount: Int?,
    previousTotalCount: Int?,
    append: Boolean,
): Int? = reportedTotalCount ?: previousTotalCount.takeIf { append }

internal fun resolveHomeTotalPages(
    reportedTotalPages: Int?,
    totalCount: Int?,
    pageSize: Int,
    page: Int,
    hasNextPage: Boolean,
): Int {
    reportedTotalPages?.let { return it.coerceAtLeast(1) }
    totalCount?.let { count ->
        val safePageSize = pageSize.coerceAtLeast(1).toLong()
        return ((count.coerceAtLeast(0).toLong() + safePageSize - 1L) / safePageSize)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
    }
    return if (hasNextPage) page.nextPageOrLast() else page.coerceAtLeast(1)
}

private fun Int.nextPageOrLast(): Int = if (this < Int.MAX_VALUE) this + 1 else Int.MAX_VALUE

/** The log tag the home flow writes its own stages under, so one filter reads the whole load. */
private const val HOME_LOG_TAG = "WallHubHome"
private const val HOME_INITIAL_LOAD_TIMEOUT_MS = 8_000L
private const val PERSONA_COMPLETION_TIMEOUT_MS = 5_000L
