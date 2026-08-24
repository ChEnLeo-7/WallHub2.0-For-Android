@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubToolbarSearchTitle
import com.wallhub.android.core.designsystem.WallHubPaginationControl
import com.wallhub.android.core.designsystem.WallHubShapeTokens
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.format.formatByteSize
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.designsystem.rememberWallHubDirectionalCollapseConnection
import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadTaskRepository
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.feature.home.DEFAULT_HOME_GENRE_SELECTION
import com.wallhub.android.feature.home.DEFAULT_HOME_RATING_SELECTION
import com.wallhub.android.feature.home.DEFAULT_HOME_RESOLUTION_SELECTION
import com.wallhub.android.feature.home.HomeAction
import com.wallhub.android.feature.home.HomeFilterDrawer
import com.wallhub.android.feature.home.HomeFilterSelection
import com.wallhub.android.feature.home.HomeUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder

enum class LibraryCollectionTab(
    val collection: AccountWorkshopCollection,
) {
    SUBSCRIPTIONS(AccountWorkshopCollection.SUBSCRIPTIONS),
    FAVORITES(AccountWorkshopCollection.FAVORITES),
    VOTED(AccountWorkshopCollection.VOTED),
}

data class LibraryUiState(
    val session: SteamSessionState = SteamSessionState(),
    val collection: LibraryCollectionTab = LibraryCollectionTab.SUBSCRIPTIONS,
    val selectedTypes: Set<WorkshopType> = emptySet(),
    val selectedRatings: Set<WorkshopRating> = DEFAULT_HOME_RATING_SELECTION,
    val selectedGenres: Set<String> = DEFAULT_HOME_GENRE_SELECTION,
    val selectedOfficialTags: Set<String> = emptySet(),
    val selectedExcludedOfficialTags: Set<String> = emptySet(),
    val selectedResolutions: Set<String> = DEFAULT_HOME_RESOLUTION_SELECTION,
    val searchQuery: String = "",
    val exactPhrase: Boolean = false,
    val matureContentEnabled: Boolean = false,
    val paginationMode: HomePaginationMode = HomePaginationMode.INFINITE_SCROLL,
    val items: List<WorkshopSummary> = emptyList(),
    val authorDisplayNames: Map<Long, String> = emptyMap(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val nextPage: Int = 2,
    val hasNextPage: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    @StringRes val errorRes: Int? = null,
    val appliedSearchQuery: String = "",
    val searchResultGeneration: Long = 0L,
    val searchAnimationItemIds: Set<Long> = emptySet(),
)

private fun LibraryUiState.filterSelection(): HomeFilterSelection =
    HomeFilterSelection(
        sort = WorkshopSort.TRENDING,
        days = 30,
        types = selectedTypes,
        ratings = selectedRatings,
        genres = selectedGenres,
        officialTags = selectedOfficialTags,
        excludedOfficialTags = selectedExcludedOfficialTags,
        resolutions = selectedResolutions,
    )

private fun LibraryUiState.asHomeFilterUiState(): HomeUiState =
    HomeUiState(
        query = searchQuery,
        exactPhrase = exactPhrase,
        selectedTypes = selectedTypes,
        selectedRatings = selectedRatings,
        selectedGenres = selectedGenres,
        selectedOfficialTags = selectedOfficialTags,
        selectedExcludedOfficialTags = selectedExcludedOfficialTags,
        selectedResolutions = selectedResolutions,
        matureContentEnabled = matureContentEnabled,
    )

sealed interface LibraryAction {
    data class SelectCollection(
        val collection: LibraryCollectionTab,
    ) : LibraryAction

    data class ApplyFilters(
        val selection: HomeFilterSelection,
        val exactPhrase: Boolean,
    ) : LibraryAction

    data class UpdateSearchQuery(
        val query: String,
    ) : LibraryAction

    data object SubmitSearch : LibraryAction

    data class SelectPaginationMode(
        val mode: HomePaginationMode,
    ) : LibraryAction

    data object ResetFilters : LibraryAction

    data object Refresh : LibraryAction

    data object LoadNextPage : LibraryAction

    data class SelectPage(
        val page: Int,
    ) : LibraryAction

    data class RequestAuthorDisplayName(
        val item: WorkshopSummary,
    ) : LibraryAction

    data class OpenDetail(
        val workshopId: Long,
    ) : LibraryAction

    data class PlayVideo(
        val workshopId: Long,
    ) : LibraryAction

    data class SearchAuthor(
        val author: String,
    ) : LibraryAction

    data class Download(
        val item: WorkshopSummary,
    ) : LibraryAction

    data class CopyText(
        val text: String,
        val message: String,
    ) : LibraryAction

    data class OpenSteam(
        val workshopId: Long,
    ) : LibraryAction

    data class RemoveFromCollection(
        val item: WorkshopSummary,
    ) : LibraryAction
}

sealed interface LibraryEffect {
    data class OpenDetail(
        val workshopId: Long,
    ) : LibraryEffect

    data class PlayVideo(
        val workshopId: Long,
    ) : LibraryEffect

    data class SearchAuthor(
        val author: String,
    ) : LibraryEffect

    data class CopyText(
        val text: String,
        val message: String,
    ) : LibraryEffect

    data class OpenSteam(
        val workshopId: Long,
    ) : LibraryEffect

    data class ShowMessage(
        @StringRes val messageRes: Int,
        val formatArgs: List<Any> = emptyList(),
    ) : LibraryEffect
}

private data class LibraryQueryKey(
    val collection: LibraryCollectionTab,
    val selectedTypes: Set<WorkshopType>,
    val selectedRatings: Set<WorkshopRating>,
    val selectedGenres: Set<String>,
    val selectedOfficialTags: Set<String>,
    val selectedExcludedOfficialTags: Set<String>,
    val selectedResolutions: Set<String>,
    val searchQuery: String,
    val exactPhrase: Boolean,
    val paginationMode: HomePaginationMode,
)

private data class LibraryCacheEntry(
    val items: List<WorkshopSummary>,
    val nextPage: Int,
    val hasNextPage: Boolean,
    val currentPage: Int,
    val totalPages: Int,
)

@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        @ApplicationContext private val applicationContext: Context,
        private val steamSessionRepository: SteamSessionRepository,
        private val accountWorkshopRepository: AccountWorkshopRepository,
        private val downloadTaskRepository: DownloadTaskRepository,
        private val settingsRepository: SettingsRepository,
        private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(savedStateHandle.libraryState())
        private val effectChannel = Channel<LibraryEffect>(capacity = Channel.BUFFERED)
        private var loadJob: Job? = null
        private var searchJob: Job? = null
        private var requestVersion = 0L
        private val cachedPages = mutableMapOf<LibraryQueryKey, LibraryCacheEntry>()
        private val authorNameRequests = mutableSetOf<Long>()
        private val collectionMutationIds = mutableSetOf<Long>()
        private var activeAccountName: String? = null
        private var refreshWhenSignedIn = false

        val uiState: StateFlow<LibraryUiState> = mutableState.asStateFlow()
        val effects: Flow<LibraryEffect> = effectChannel.receiveAsFlow()

        init {
            viewModelScope.launch {
                mutableState.collect { state -> savedStateHandle.saveLibraryState(state) }
            }
            viewModelScope.launch {
                settingsRepository.preferences.collect { preferences ->
                    val current = mutableState.value
                    val normalized = current.filterSelection().normalized(preferences.matureContentEnabled)
                    mutableState.value =
                        current.copy(
                            selectedRatings = normalized.ratings,
                            matureContentEnabled = preferences.matureContentEnabled,
                        )
                }
            }
            viewModelScope.launch {
                steamSessionRepository.session.collect { session ->
                    val previous = mutableState.value
                    if (session.phase != SteamSessionPhase.SIGNED_IN) {
                        if (previous.session.phase == SteamSessionPhase.SIGNED_IN) {
                            clearCache()
                        } else {
                            loadJob?.cancel()
                            requestVersion += 1L
                        }
                        mutableState.value =
                            previous.copy(
                                session = session,
                                isLoading = false,
                                isRefreshing = false,
                                isLoadingMore = false,
                            )
                        return@collect
                    }
                    val accountChanged =
                        activeAccountName != null &&
                            !activeAccountName.equals(session.accountName, ignoreCase = true)
                    val becameSignedIn = previous.session.phase != SteamSessionPhase.SIGNED_IN
                    if (accountChanged) clearCache()
                    activeAccountName = session.accountName
                    mutableState.value = previous.copy(session = session)
                    if (becameSignedIn || accountChanged || refreshWhenSignedIn) {
                        refreshWhenSignedIn = false
                        loadCurrentPage(forceRefresh = true, refreshing = false)
                    }
                }
            }
        }

        fun onAction(action: LibraryAction) {
            action.toEffect()?.let(::emitEffect) ?: handleStateAction(action)
        }

        private fun handleStateAction(action: LibraryAction) {
            when (action) {
                is LibraryAction.SelectCollection -> selectCollection(action.collection)
                is LibraryAction.ApplyFilters -> applyFilters(action.selection, action.exactPhrase)
                is LibraryAction.UpdateSearchQuery -> updateSearchQuery(action.query)
                LibraryAction.SubmitSearch -> submitSearch()
                is LibraryAction.SelectPaginationMode -> selectPaginationMode(action.mode)
                LibraryAction.ResetFilters -> resetManagementFilters()
                LibraryAction.Refresh -> refresh()
                LibraryAction.LoadNextPage -> loadNextPage()
                is LibraryAction.SelectPage -> selectPage(action.page)
                is LibraryAction.RequestAuthorDisplayName -> requestAuthorDisplayName(action.item)
                is LibraryAction.Download -> enqueueDownload(action.item)
                is LibraryAction.RemoveFromCollection -> removeFromCollection(action.item)
                else -> Unit
            }
        }

        private fun emitEffect(effect: LibraryEffect) {
            effectChannel.trySend(effect)
        }

        private fun selectCollection(collection: LibraryCollectionTab) {
            if (mutableState.value.collection == collection) return
            searchJob?.cancel()
            mutableState.value = mutableState.value.copy(collection = collection, currentPage = 1)
            loadCurrentPage(forceRefresh = false, refreshing = false)
        }

        private fun applyFilters(
            selection: HomeFilterSelection,
            exactPhrase: Boolean,
        ) {
            val current = mutableState.value
            val normalized = selection.normalized(current.matureContentEnabled)
            if (
                current.filterSelection() == normalized &&
                current.exactPhrase == exactPhrase
            ) {
                return
            }
            searchJob?.cancel()
            mutableState.value =
                current.copy(
                    selectedTypes = normalized.types,
                    selectedRatings = normalized.ratings,
                    selectedGenres = normalized.genres,
                    selectedOfficialTags = normalized.officialTags,
                    selectedExcludedOfficialTags = normalized.excludedOfficialTags,
                    selectedResolutions = normalized.resolutions,
                    exactPhrase = exactPhrase,
                    currentPage = 1,
                )
            loadCurrentPage(forceRefresh = false, refreshing = false)
        }

        private fun removeFromCollection(item: WorkshopSummary) {
            val collection = mutableState.value.collection
            if (collection == LibraryCollectionTab.VOTED || !collectionMutationIds.add(item.id)) return
            viewModelScope.launch {
                try {
                    when (collection) {
                        LibraryCollectionTab.SUBSCRIPTIONS -> accountWorkshopRepository.setSubscribed(item.id, false)
                        LibraryCollectionTab.FAVORITES -> accountWorkshopRepository.setFavorited(item.id, false)
                        LibraryCollectionTab.VOTED -> return@launch
                    }
                    cachedPages.keys
                        .filter { key -> key.collection == collection }
                        .forEach { key ->
                            cachedPages[key] =
                                requireNotNull(cachedPages[key]).let { cached ->
                                    cached.copy(items = cached.items.filterNot { cachedItem -> cachedItem.id == item.id })
                                }
                        }
                    mutableState.value =
                        mutableState.value.let { state ->
                            if (state.collection == collection) {
                                state.copy(
                                    items = state.items.filterNot { current -> current.id == item.id },
                                    searchAnimationItemIds = state.searchAnimationItemIds - item.id,
                                )
                            } else {
                                state
                            }
                        }
                    emitEffect(
                        LibraryEffect.ShowMessage(
                            if (collection == LibraryCollectionTab.SUBSCRIPTIONS) {
                                R.string.library_unsubscribed
                            } else {
                                R.string.library_removed_from_favorites
                            },
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    emitEffect(LibraryEffect.ShowMessage(R.string.library_remove_failed))
                } finally {
                    collectionMutationIds -= item.id
                }
            }
        }

        private fun enqueueDownload(item: WorkshopSummary) {
            viewModelScope.launch {
                runCatching {
                    val preferences = settingsRepository.preferences.first()
                    downloadTaskRepository.enqueue(
                        DownloadRequest(
                            workshopId = item.id,
                            title = applicationContext.localizedTitle(item),
                            type = item.type,
                            previewUrl = item.previewUrl,
                            expectedTotalBytes = item.fileSizeBytes ?: 0L,
                            outputTreeUri = preferences.outputTreeUri,
                            exportFormat = ExportFormat.AUTO,
                        ),
                    )
                }.onSuccess { task ->
                    emitEffect(
                        LibraryEffect.ShowMessage(
                            messageRes = R.string.home_added_to_download_queue,
                            formatArgs = listOf(task.title),
                        ),
                    )
                }.onFailure {
                    emitEffect(LibraryEffect.ShowMessage(R.string.home_unable_to_queue_download))
                }
            }
        }

        private fun updateSearchQuery(query: String) {
            val normalized = query.take(MAX_SEARCH_LENGTH)
            if (mutableState.value.searchQuery == normalized) return
            loadJob?.cancel()
            requestVersion += 1L
            mutableState.value = mutableState.value.copy(searchQuery = normalized, currentPage = 1)
            searchJob?.cancel()
            searchJob =
                viewModelScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    loadCurrentPage(forceRefresh = false, refreshing = false)
                }
        }

        private fun submitSearch() {
            searchJob?.cancel()
            loadCurrentPage(forceRefresh = false, refreshing = false)
        }

        private fun requestAuthorDisplayName(item: WorkshopSummary) {
            if (item.authorPlaceholder == WorkshopAuthorPlaceholder.NONE || item.id in authorNameRequests) return
            authorNameRequests += item.id
            viewModelScope.launch {
                val authorName =
                    runCatching {
                        accountWorkshopRepository.resolveAuthorDisplayName(item.id)
                    }.getOrNull()
                if (!authorName.isNullOrBlank()) {
                    mutableState.value =
                        mutableState.value.let { state ->
                            state.copy(authorDisplayNames = state.authorDisplayNames + (item.id to authorName))
                        }
                } else {
                    authorNameRequests -= item.id
                }
            }
        }

        private fun selectPaginationMode(mode: HomePaginationMode) {
            if (mutableState.value.paginationMode == mode) return
            searchJob?.cancel()
            mutableState.value = mutableState.value.copy(paginationMode = mode, currentPage = 1)
            loadCurrentPage(forceRefresh = false, refreshing = false)
        }

        private fun resetManagementFilters() {
            searchJob?.cancel()
            val state = mutableState.value
            if (
                state.collection == LibraryCollectionTab.SUBSCRIPTIONS &&
                state.filterSelection() == HomeFilterSelection.defaults() &&
                !state.exactPhrase &&
                state.paginationMode == HomePaginationMode.INFINITE_SCROLL
            ) {
                return
            }
            mutableState.value =
                state.copy(
                    collection = LibraryCollectionTab.SUBSCRIPTIONS,
                    selectedTypes = emptySet(),
                    selectedRatings = DEFAULT_HOME_RATING_SELECTION,
                    selectedGenres = DEFAULT_HOME_GENRE_SELECTION,
                    selectedOfficialTags = emptySet(),
                    selectedExcludedOfficialTags = emptySet(),
                    selectedResolutions = DEFAULT_HOME_RESOLUTION_SELECTION,
                    exactPhrase = false,
                    paginationMode = HomePaginationMode.INFINITE_SCROLL,
                    currentPage = 1,
                )
            loadCurrentPage(forceRefresh = false, refreshing = false)
        }

        private fun refresh() {
            val session = mutableState.value.session
            if (session.phase != SteamSessionPhase.SIGNED_IN) {
                refreshWhenSignedIn = true
                if (session.isRestoreRetryable) {
                    steamSessionRepository.restorePersistedSession()
                }
                return
            }
            val page =
                if (mutableState.value.paginationMode == HomePaginationMode.PAGED) {
                    mutableState.value.currentPage
                } else {
                    1
                }
            loadCurrentPage(forceRefresh = true, refreshing = true, page = page)
        }

        private fun loadNextPage() {
            val state = mutableState.value
            if (
                state.paginationMode != HomePaginationMode.INFINITE_SCROLL ||
                state.searchQuery.trim() != state.appliedSearchQuery.trim() ||
                state.isLoading ||
                state.isLoadingMore ||
                !state.hasNextPage
            ) {
                return
            }
            loadPage(
                page = state.nextPage,
                append = true,
                version = requestVersion,
                key = state.cacheKey(),
                refreshing = false,
            )
        }

        private fun selectPage(page: Int) {
            val state = mutableState.value
            if (
                state.paginationMode != HomePaginationMode.PAGED ||
                state.searchQuery.trim() != state.appliedSearchQuery.trim() ||
                state.isLoading ||
                state.isLoadingMore
            ) {
                return
            }
            val targetPage = page.coerceAtLeast(1)
            if (targetPage == state.currentPage) return
            loadJob?.cancel()
            requestVersion += 1L
            loadPage(
                page = targetPage,
                append = false,
                version = requestVersion,
                key = state.cacheKey(),
                refreshing = false,
            )
        }

        private fun loadCurrentPage(
            forceRefresh: Boolean,
            refreshing: Boolean,
            page: Int = 1,
        ) {
            val state = mutableState.value
            if (state.session.phase != SteamSessionPhase.SIGNED_IN) return
            val key = state.cacheKey()
            loadJob?.cancel()
            requestVersion += 1L
            if (!forceRefresh) {
                cachedPages[key]?.let { cached ->
                    mutableState.value =
                        state.copy(
                            items = cached.items,
                            nextPage = cached.nextPage,
                            hasNextPage = cached.hasNextPage,
                            currentPage = cached.currentPage,
                            totalPages = cached.totalPages,
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            error = null,
                            errorRes = null,
                            appliedSearchQuery = state.searchQuery,
                            searchResultGeneration =
                                if (state.searchQuery.trim() != state.appliedSearchQuery.trim()) {
                                    state.searchResultGeneration + 1L
                                } else {
                                    state.searchResultGeneration
                                },
                            searchAnimationItemIds =
                                if (state.searchQuery.trim() != state.appliedSearchQuery.trim()) {
                                    cached.items.mapTo(mutableSetOf()) { item -> item.id }
                                } else {
                                    state.searchAnimationItemIds
                                },
                        )
                    return
                }
            }
            loadPage(
                page = page,
                append = false,
                version = requestVersion,
                key = key,
                refreshing = refreshing,
            )
        }

        private fun loadPage(
            page: Int,
            append: Boolean,
            version: Long,
            key: LibraryQueryKey,
            refreshing: Boolean,
        ) {
            val snapshot = mutableState.value
            loadJob =
                viewModelScope.launch {
                    mutableState.value =
                        snapshot.copy(
                            isLoading = !append,
                            isRefreshing = refreshing && !append,
                            isLoadingMore = append,
                            error = null,
                            errorRes = null,
                        )
                    try {
                        val query =
                            AccountWorkshopQuery(
                                collection = snapshot.collection.collection,
                                page = page,
                                pageSize = LIBRARY_PAGE_SIZE,
                                searchText = snapshot.searchQuery,
                                exactPhrase = snapshot.exactPhrase,
                                resolveTotalCount = snapshot.paginationMode == HomePaginationMode.PAGED,
                                types = snapshot.selectedTypes,
                                ratings = snapshot.selectedRatings,
                                genres = snapshot.selectedGenres,
                                officialTags = snapshot.selectedOfficialTags,
                                excludedOfficialTags = snapshot.selectedExcludedOfficialTags,
                                resolutions = snapshot.selectedResolutions,
                            )
                        val result = browseWithInitialSessionRetry(query, retry = !append && page == 1)
                        if (version != requestVersion) return@launch
                        val merged = result.mergeInto(snapshot, append)
                        cachedPages[key] =
                            LibraryCacheEntry(
                                items = merged.items,
                                nextPage = merged.nextPage,
                                hasNextPage = merged.hasNextPage,
                                currentPage = merged.currentPage,
                                totalPages = merged.totalPages,
                            )
                        mutableState.value = merged
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (version != requestVersion) return@launch
                        mutableState.value =
                            snapshot.copy(
                                isLoading = false,
                                isRefreshing = false,
                                isLoadingMore = false,
                                error = null,
                                errorRes = R.string.library_load_failed,
                            )
                    }
                }
        }

        private suspend fun browseWithInitialSessionRetry(
            query: AccountWorkshopQuery,
            retry: Boolean,
        ): WorkshopPage {
            var lastFailure: Throwable? = null
            repeat(if (retry) INITIAL_SESSION_LOAD_ATTEMPTS else 1) { attempt ->
                try {
                    return accountWorkshopRepository.browseCollection(query)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    lastFailure = error
                    if (attempt + 1 < INITIAL_SESSION_LOAD_ATTEMPTS) {
                        delay(INITIAL_SESSION_RETRY_DELAY_MS)
                    }
                }
            }
            throw lastFailure ?: IllegalStateException()
        }

        private fun WorkshopPage.mergeInto(
            previous: LibraryUiState,
            append: Boolean,
        ): LibraryUiState =
            previous.copy(
                items =
                    if (append) {
                        (previous.items + items)
                            .distinctBy(WorkshopSummary::id)
                    } else {
                        items
                    },
                nextPage = page.nextLibraryPageOrLast(),
                hasNextPage = hasNextPage,
                currentPage = page,
                totalPages =
                    resolveLibraryTotalPages(
                        totalCount = totalCount,
                        page = page,
                        hasNextPage = hasNextPage,
                    ),
                isLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                error = null,
                errorRes = null,
                appliedSearchQuery = previous.searchQuery,
                searchResultGeneration =
                    if (!append && previous.searchQuery.trim() != previous.appliedSearchQuery.trim()) {
                        previous.searchResultGeneration + 1L
                    } else {
                        previous.searchResultGeneration
                    },
                searchAnimationItemIds =
                    when {
                        !append && previous.searchQuery.trim() != previous.appliedSearchQuery.trim() -> {
                            items.mapTo(mutableSetOf()) { item -> item.id }
                        }
                        append -> previous.searchAnimationItemIds
                        else -> previous.searchAnimationItemIds
                    },
            )

        private fun LibraryUiState.cacheKey(): LibraryQueryKey =
            LibraryQueryKey(
                collection = collection,
                selectedTypes = selectedTypes,
                selectedRatings = selectedRatings,
                selectedGenres = selectedGenres,
                selectedOfficialTags = selectedOfficialTags,
                selectedExcludedOfficialTags = selectedExcludedOfficialTags,
                selectedResolutions = selectedResolutions,
                searchQuery = searchQuery.trim(),
                exactPhrase = exactPhrase,
                paginationMode = paginationMode,
            )

        private fun clearCache() {
            loadJob?.cancel()
            searchJob?.cancel()
            requestVersion += 1L
            cachedPages.clear()
            activeAccountName = null
        }

        private companion object {
            const val INITIAL_SESSION_LOAD_ATTEMPTS = 2
            const val INITIAL_SESSION_RETRY_DELAY_MS = 450L
            const val SEARCH_DEBOUNCE_MS = 320L
            const val MAX_SEARCH_LENGTH = 120
        }
    }

private fun SavedStateHandle.libraryState(): LibraryUiState =
    LibraryUiState(
        collection = libraryEnumValueOrDefault(get(LIBRARY_COLLECTION_KEY), LibraryCollectionTab.SUBSCRIPTIONS),
        selectedTypes = savedWorkshopTypes(LIBRARY_TYPE_FILTER_KEY),
        selectedRatings = savedEnumSet(LIBRARY_RATING_FILTER_KEY, DEFAULT_HOME_RATING_SELECTION),
        selectedGenres = savedStringSet(LIBRARY_GENRE_FILTER_KEY, DEFAULT_HOME_GENRE_SELECTION),
        selectedOfficialTags = savedStringSet(LIBRARY_OFFICIAL_TAG_FILTER_KEY),
        selectedExcludedOfficialTags = savedStringSet(LIBRARY_EXCLUDED_OFFICIAL_TAG_FILTER_KEY),
        selectedResolutions = savedStringSet(LIBRARY_RESOLUTION_FILTER_KEY, DEFAULT_HOME_RESOLUTION_SELECTION),
        searchQuery = get<String>(LIBRARY_SEARCH_QUERY_KEY).orEmpty(),
        exactPhrase = get<Boolean>(LIBRARY_EXACT_PHRASE_KEY) ?: false,
        paginationMode = libraryEnumValueOrDefault(get(LIBRARY_PAGINATION_MODE_KEY), HomePaginationMode.INFINITE_SCROLL),
        currentPage = (get<Int>(LIBRARY_CURRENT_PAGE_KEY) ?: 1).coerceAtLeast(1),
    )

private fun SavedStateHandle.saveLibraryState(state: LibraryUiState) {
    this[LIBRARY_COLLECTION_KEY] = state.collection.name
    this[LIBRARY_TYPE_FILTER_KEY] = ArrayList(state.selectedTypes.map(WorkshopType::name))
    this[LIBRARY_RATING_FILTER_KEY] = ArrayList(state.selectedRatings.map(WorkshopRating::name))
    this[LIBRARY_GENRE_FILTER_KEY] = ArrayList(state.selectedGenres)
    this[LIBRARY_OFFICIAL_TAG_FILTER_KEY] = ArrayList(state.selectedOfficialTags)
    this[LIBRARY_EXCLUDED_OFFICIAL_TAG_FILTER_KEY] = ArrayList(state.selectedExcludedOfficialTags)
    this[LIBRARY_RESOLUTION_FILTER_KEY] = ArrayList(state.selectedResolutions)
    this[LIBRARY_SEARCH_QUERY_KEY] = state.searchQuery
    this[LIBRARY_EXACT_PHRASE_KEY] = state.exactPhrase
    this[LIBRARY_PAGINATION_MODE_KEY] = state.paginationMode.name
    this[LIBRARY_CURRENT_PAGE_KEY] = state.currentPage
}

private inline fun <reified T : Enum<T>> libraryEnumValueOrDefault(
    value: String?,
    default: T,
): T = value?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

private fun SavedStateHandle.savedWorkshopTypes(key: String): Set<WorkshopType> =
    when (val saved = get<Any>(key)) {
        is ArrayList<*> ->
            saved.mapNotNullTo(linkedSetOf()) { name ->
                WorkshopType.entries.firstOrNull { it.name == name }
            }
        "VIDEO" -> setOf(WorkshopType.VIDEO)
        "SCENE" -> setOf(WorkshopType.SCENE)
        "WEB" -> setOf(WorkshopType.WEB)
        else -> emptySet()
    }.takeUnless { it == LIBRARY_FILTER_TYPES }.orEmpty()

private inline fun <reified T : Enum<T>> SavedStateHandle.savedEnumSet(
    key: String,
    default: Set<T>,
): Set<T> =
    get<ArrayList<String>>(key)
        ?.mapNotNullTo(linkedSetOf()) { name -> enumValues<T>().firstOrNull { it.name == name } }
        ?: default

private fun SavedStateHandle.savedStringSet(
    key: String,
    default: Set<String> = emptySet(),
): Set<String> = get<ArrayList<String>>(key)?.toSet() ?: default

private const val LIBRARY_COLLECTION_KEY = "library.collection"
private const val LIBRARY_TYPE_FILTER_KEY = "library.typeFilter"
private const val LIBRARY_RATING_FILTER_KEY = "library.ratingFilter"
private const val LIBRARY_GENRE_FILTER_KEY = "library.genreFilter"
private const val LIBRARY_OFFICIAL_TAG_FILTER_KEY = "library.officialTagFilter"
private const val LIBRARY_EXCLUDED_OFFICIAL_TAG_FILTER_KEY = "library.excludedOfficialTagFilter"
private const val LIBRARY_RESOLUTION_FILTER_KEY = "library.resolutionFilter"
private const val LIBRARY_SEARCH_QUERY_KEY = "library.searchQuery"
private const val LIBRARY_EXACT_PHRASE_KEY = "library.exactPhrase"
private const val LIBRARY_PAGINATION_MODE_KEY = "library.paginationMode"
private const val LIBRARY_CURRENT_PAGE_KEY = "library.currentPage"

private fun LibraryAction.toEffect(): LibraryEffect? =
    when (this) {
        is LibraryAction.OpenDetail -> LibraryEffect.OpenDetail(workshopId)
        is LibraryAction.PlayVideo -> LibraryEffect.PlayVideo(workshopId)
        is LibraryAction.SearchAuthor -> LibraryEffect.SearchAuthor(author)
        is LibraryAction.CopyText -> LibraryEffect.CopyText(text, message)
        is LibraryAction.OpenSteam -> LibraryEffect.OpenSteam(workshopId)
        else -> null
    }

private fun Int.nextLibraryPageOrLast(): Int = if (this < Int.MAX_VALUE) this + 1 else Int.MAX_VALUE

private fun resolveLibraryTotalPages(
    totalCount: Int?,
    page: Int,
    hasNextPage: Boolean,
): Int =
    totalCount?.let { count ->
        ((count.coerceAtLeast(0).toLong() + LIBRARY_PAGE_SIZE - 1L) / LIBRARY_PAGE_SIZE)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
    } ?: if (hasNextPage) page.nextLibraryPageOrLast() else page.coerceAtLeast(1)

private const val LIBRARY_PAGE_SIZE = 16

@Composable
fun LibraryRoute(
    initialCollection: String? = null,
    onOpenSettings: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpenDetail: (Long) -> Unit,
    onPlayVideo: (Long) -> Unit = {},
    onSearchAuthor: (String) -> Unit = {},
    onContextMenuActiveChanged: (Boolean) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(initialCollection) {
        initialCollection
            ?.let { value -> LibraryCollectionTab.entries.firstOrNull { it.name == value } }
            ?.let { viewModel.onAction(LibraryAction.SelectCollection(it)) }
    }
    LibraryEffectHandler(
        viewModel = viewModel,
        onOpenDetail = onOpenDetail,
        onPlayVideo = onPlayVideo,
        onSearchAuthor = onSearchAuthor,
    )
    LibraryScreen(
        state = state,
        onAction = viewModel::onAction,
        onOpenSettings = onOpenSettings,
        onBack = onBack,
        onContextMenuActiveChanged = onContextMenuActiveChanged,
    )
}

@Composable
fun LibraryEffectHandler(
    viewModel: LibraryViewModel,
    onOpenDetail: (Long) -> Unit,
    onPlayVideo: (Long) -> Unit = {},
    onSearchAuthor: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboardManager.current
    val currentOnOpenDetail by rememberUpdatedState(onOpenDetail)
    val currentOnPlayVideo by rememberUpdatedState(onPlayVideo)
    val currentOnSearchAuthor by rememberUpdatedState(onSearchAuthor)
    LaunchedEffect(viewModel, context, resources) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is LibraryEffect.OpenDetail -> currentOnOpenDetail(effect.workshopId)
                is LibraryEffect.PlayVideo -> currentOnPlayVideo(effect.workshopId)
                is LibraryEffect.SearchAuthor -> currentOnSearchAuthor(effect.author)
                is LibraryEffect.CopyText -> {
                    clipboard.setText(AnnotatedString(effect.text))
                    Toast.makeText(context.applicationContext, effect.message, Toast.LENGTH_SHORT).show()
                }
                is LibraryEffect.OpenSteam -> {
                    val intent =
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://steamcommunity.com/sharedfiles/filedetails/?id=${effect.workshopId}"),
                        )
                    runCatching { context.startActivity(intent) }
                        .onFailure { currentOnOpenDetail(effect.workshopId) }
                }
                is LibraryEffect.ShowMessage ->
                    Toast.makeText(
                        context.applicationContext,
                        resources.getString(effect.messageRes, *effect.formatArgs.toTypedArray()),
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        }
    }
}

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onAction: (LibraryAction) -> Unit,
    onOpenSettings: () -> Unit = {},
    onBack: () -> Unit = {},
    onContextMenuActiveChanged: (Boolean) -> Unit = {},
) {
    var searchToolbarExpanded by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<WorkshopSummary?>(null) }
    val filterDrawerState = rememberDrawerState(DrawerValue.Closed)
    val filterDrawerScope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = filterDrawerState,
        gesturesEnabled = filterDrawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxHeight().widthIn(max = 420.dp),
            ) {
                HomeFilterDrawer(
                    state = state.asHomeFilterUiState(),
                    isOpen = filterDrawerState.isOpen,
                    showSortAndTime = false,
                    title = stringResource(R.string.management_library_filters),
                    onAction = { action ->
                        if (action is HomeAction.ApplyFilters) {
                            onAction(
                                LibraryAction.ApplyFilters(
                                    selection = action.selection,
                                    exactPhrase = action.exactPhrase ?: state.exactPhrase,
                                ),
                            )
                        }
                    },
                    onDismiss = { filterDrawerScope.launch { filterDrawerState.close() } },
                )
            }
        },
    ) {
        WallHubPageScaffold(
            title = state.collection.label(),
            showBackButton = !searchToolbarExpanded,
            onNavigateUp = onBack,
            titleContent = {
                WallHubToolbarSearchTitle(
                    title = state.collection.label(),
                    query = state.searchQuery,
                    expanded = searchToolbarExpanded,
                    placeholder = stringResource(R.string.library_search_placeholder),
                    onQueryChanged = { onAction(LibraryAction.UpdateSearchQuery(it)) },
                    onSubmit = { onAction(LibraryAction.SubmitSearch) },
                    onExpand = { searchToolbarExpanded = true },
                    onCollapse = { searchToolbarExpanded = false },
                    enabled = state.session.phase == SteamSessionPhase.SIGNED_IN,
                )
            },
            actions = {
                IconButton(
                    onClick = { filterDrawerScope.launch { filterDrawerState.open() } },
                    enabled = state.session.phase == SteamSessionPhase.SIGNED_IN,
                ) {
                    Icon(Icons.Outlined.FilterAlt, contentDescription = stringResource(R.string.home_open_all_filters))
                }
                IconButton(onClick = { onAction(LibraryAction.Refresh) }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.library_refresh))
                }
            },
        ) { padding ->
            LibraryContent(
                state = state,
                onAction = onAction,
                onRemoveFromCollection = { pendingRemoval = it },
                onContextMenuActiveChanged = onContextMenuActiveChanged,
                showSearch = false,
                modifier = Modifier.padding(padding),
            )
        }
    }
    pendingRemoval?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            icon = { Icon(state.collection.icon(), contentDescription = null) },
            title = { Text(state.collection.removeDialogTitle()) },
            text = { Text(stringResource(R.string.library_remove_confirmation, item.localizedTitle())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        onAction(LibraryAction.RemoveFromCollection(item))
                    },
                ) {
                    Text(state.collection.removeActionLabel())
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(stringResource(R.string.library_cancel))
                }
            },
        )
    }
}

@Composable
fun LibraryContent(
    state: LibraryUiState,
    onAction: (LibraryAction) -> Unit,
    onRemoveFromCollection: (WorkshopSummary) -> Unit,
    onContextMenuActiveChanged: (Boolean) -> Unit,
    showSearch: Boolean = true,
    onScrollChromeCollapsedChanged: (Boolean) -> Unit = {},
    scrollToTopRequest: Int = 0,
    floatingActionButton: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val gridState = rememberLazyGridState()
    val contextMenuCoordinator = rememberLibraryContextMenuCoordinator()
    var searchBoundsInContent by remember { mutableStateOf<IntRect?>(null) }
    var previousSearchQuery by remember { mutableStateOf(state.searchQuery) }
    var scrollChromeCollapsed by remember(gridState) { mutableStateOf(false) }
    var handledScrollToTopRequest by rememberSaveable {
        mutableIntStateOf(scrollToTopRequest)
    }
    val updateScrollChromeCollapsed: (Boolean) -> Unit = { collapsed ->
        if (collapsed != scrollChromeCollapsed) {
            scrollChromeCollapsed = collapsed
            onScrollChromeCollapsedChanged(collapsed)
        }
    }
    val chromeScrollConnection =
        rememberWallHubDirectionalCollapseConnection(
            collapsed = scrollChromeCollapsed,
            onCollapsedChanged = updateScrollChromeCollapsed,
            collapseDistance = LIBRARY_HEADER_COLLAPSE_DISTANCE,
            expandDistance = LIBRARY_HEADER_EXPAND_DISTANCE,
        )
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > handledScrollToTopRequest) {
            gridState.animateScrollToItem(0)
            updateScrollChromeCollapsed(false)
            handledScrollToTopRequest = scrollToTopRequest
        }
    }
    LaunchedEffect(
        state.collection,
        state.selectedTypes,
        state.selectedRatings,
        state.selectedGenres,
        state.selectedOfficialTags,
        state.selectedExcludedOfficialTags,
        state.selectedResolutions,
        state.exactPhrase,
        state.searchQuery,
        state.paginationMode,
    ) {
        val searchChanged = state.searchQuery != previousSearchQuery
        previousSearchQuery = state.searchQuery
        if (!searchChanged && (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0)) {
            gridState.scrollToItem(0)
            updateScrollChromeCollapsed(false)
        }
    }
    LaunchedEffect(state.currentPage, state.paginationMode) {
        if (
            state.paginationMode == HomePaginationMode.PAGED &&
            (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0)
        ) {
            gridState.scrollToItem(0)
            updateScrollChromeCollapsed(false)
        }
    }
    LibraryContextMenuLayer(
        coordinator = contextMenuCoordinator,
        onActiveChanged = onContextMenuActiveChanged,
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .nestedScroll(chromeScrollConnection)
                    .pointerInput(searchBoundsInContent) {
                        awaitEachGesture {
                            val down =
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                            val point =
                                IntOffset(
                                    down.position.x.toInt(),
                                    down.position.y.toInt(),
                                )
                            if (searchBoundsInContent?.contains(point) != true) {
                                focusManager.clearFocus(force = true)
                            }
                        }
                    },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = showSearch && !scrollChromeCollapsed,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(tween(180)),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
                    label = "LibrarySearchChrome",
                ) {
                    LibrarySearchField(
                        query = state.searchQuery,
                        enabled = state.session.phase == SteamSessionPhase.SIGNED_IN,
                        onQueryChanged = { onAction(LibraryAction.UpdateSearchQuery(it)) },
                        onSubmit = { onAction(LibraryAction.SubmitSearch) },
                        modifier =
                            Modifier
                                .padding(horizontal = WallHubSpacing.sm, vertical = WallHubSpacing.dense)
                                .onGloballyPositioned { coordinates ->
                                    val position = coordinates.positionInParent()
                                    searchBoundsInContent =
                                        IntRect(
                                            left = position.x.toInt(),
                                            top = position.y.toInt(),
                                            right = (position.x + coordinates.size.width).toInt(),
                                            bottom = (position.y + coordinates.size.height).toInt(),
                                        )
                                },
                    )
                }
                LibraryResults(
                    state = state,
                    onRefresh = { onAction(LibraryAction.Refresh) },
                    onLoadNextPage = { onAction(LibraryAction.LoadNextPage) },
                    onPageSelected = { onAction(LibraryAction.SelectPage(it)) },
                    onOpenDetail = { onAction(LibraryAction.OpenDetail(it)) },
                    onPlayVideo = { onAction(LibraryAction.PlayVideo(it)) },
                    onSearchAuthor = { onAction(LibraryAction.SearchAuthor(it)) },
                    authorDisplayNames = state.authorDisplayNames,
                    onAuthorNameRequested = { onAction(LibraryAction.RequestAuthorDisplayName(it)) },
                    onDownload = { onAction(LibraryAction.Download(it)) },
                    onRemoveFromCollection = onRemoveFromCollection,
                    onCopyText = { text, message -> onAction(LibraryAction.CopyText(text, message)) },
                    onOpenSteam = { onAction(LibraryAction.OpenSteam(it)) },
                    gridState = gridState,
                    contextMenuCoordinator = contextMenuCoordinator,
                    modifier = Modifier.weight(1f),
                )
            }
            floatingActionButton?.let { fab ->
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(WallHubSpacing.md),
                ) {
                    fab()
                }
            }
        }
    }
}

@Composable
private fun LibrarySearchField(
    query: String,
    enabled: Boolean,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        placeholder = {
            Text(stringResource(R.string.library_search_placeholder))
        },
        leadingIcon = {
            Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
        },
        trailingIcon =
            if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChanged("") }) {
                        Icon(
                            imageVector = Icons.Outlined.Cancel,
                            contentDescription = stringResource(R.string.library_clear_search),
                        )
                    }
                }
            } else {
                null
            },
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
            ),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
    )
}

@Composable
private fun LibraryLoadingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
                .clickable(onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.controlInset),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.library_loading),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.library_loading_supporting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryResults(
    state: LibraryUiState,
    onRefresh: () -> Unit,
    onLoadNextPage: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onPlayVideo: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
    authorDisplayNames: Map<Long, String>,
    onAuthorNameRequested: (WorkshopSummary) -> Unit,
    onDownload: (WorkshopSummary) -> Unit,
    onRemoveFromCollection: (WorkshopSummary) -> Unit,
    onCopyText: (String, String) -> Unit,
    onOpenSteam: (Long) -> Unit,
    gridState: LazyGridState,
    contextMenuCoordinator: LibraryContextMenuCoordinator,
    modifier: Modifier = Modifier,
) {
    val shouldAutoLoadMore by remember(gridState, state) {
        derivedStateOf {
            if (
                state.paginationMode != HomePaginationMode.INFINITE_SCROLL ||
                state.searchQuery.trim() != state.appliedSearchQuery.trim() ||
                state.isLoading ||
                state.isLoadingMore ||
                !state.hasNextPage
            ) {
                false
            } else {
                val lastVisible =
                    gridState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: -1
                lastVisible >= (state.items.lastIndex - LIBRARY_AUTO_LOAD_MORE_THRESHOLD).coerceAtLeast(0)
            }
        }
    }
    LaunchedEffect(shouldAutoLoadMore, state.nextPage, state.paginationMode) {
        if (shouldAutoLoadMore) onLoadNextPage()
    }
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxWidth(),
    ) {
        LibraryResultsContent(
            state = state,
            onRefresh = onRefresh,
            onPageSelected = onPageSelected,
            onOpenDetail = onOpenDetail,
            onPlayVideo = onPlayVideo,
            onSearchAuthor = onSearchAuthor,
            authorDisplayNames = authorDisplayNames,
            onAuthorNameRequested = onAuthorNameRequested,
            onDownload = onDownload,
            onRemoveFromCollection = onRemoveFromCollection,
            onCopyText = onCopyText,
            onOpenSteam = onOpenSteam,
            gridState = gridState,
            contextMenuCoordinator = contextMenuCoordinator,
            modifier = Modifier.fillMaxSize(),
        )
        AnimatedVisibility(
            visible =
                state.isLoading &&
                    !state.isRefreshing &&
                    state.session.phase == SteamSessionPhase.SIGNED_IN,
            enter = fadeIn(tween(durationMillis = 140)) + scaleIn(initialScale = 0.96f),
            exit = fadeOut(tween(durationMillis = 180)) + scaleOut(targetScale = 0.98f),
            modifier = Modifier.fillMaxSize(),
        ) {
            LibraryLoadingOverlay(modifier = Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryResultsContent(
    state: LibraryUiState,
    onRefresh: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onPlayVideo: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
    authorDisplayNames: Map<Long, String>,
    onAuthorNameRequested: (WorkshopSummary) -> Unit,
    onDownload: (WorkshopSummary) -> Unit,
    onRemoveFromCollection: (WorkshopSummary) -> Unit,
    onCopyText: (String, String) -> Unit,
    onOpenSteam: (Long) -> Unit,
    gridState: LazyGridState,
    contextMenuCoordinator: LibraryContextMenuCoordinator,
    modifier: Modifier = Modifier,
) {
    when {
        state.session.phase != SteamSessionPhase.SIGNED_IN -> {
            WallHubEmptyState(
                icon = Icons.Outlined.BookmarkBorder,
                title = state.session.libraryMessage(),
                actionLabel =
                    if (state.session.isRestoreRetryable) {
                        stringResource(R.string.library_retry_restore)
                    } else {
                        null
                    },
                onAction = if (state.session.isRestoreRetryable) onRefresh else null,
                modifier = modifier.refreshableEmptyState(),
            )
        }

        state.isLoading && state.items.isEmpty() -> {
            Box(modifier = modifier.fillMaxSize())
        }

        (state.error != null || state.errorRes != null) && state.items.isEmpty() -> {
            WallHubEmptyState(
                icon = Icons.Outlined.Refresh,
                title = state.error ?: stringResource(requireNotNull(state.errorRes)),
                actionLabel = stringResource(R.string.library_retry),
                onAction = onRefresh,
                modifier = modifier.refreshableEmptyState(),
            )
        }

        state.items.isEmpty() -> {
            WallHubEmptyState(
                icon = if (state.searchQuery.isNotBlank()) Icons.Outlined.Search else state.collection.icon(),
                title =
                    if (state.searchQuery.isNotBlank()) {
                        stringResource(R.string.library_empty_search)
                    } else if (state.collection == LibraryCollectionTab.SUBSCRIPTIONS) {
                        stringResource(R.string.library_empty_subscriptions)
                    } else if (state.collection == LibraryCollectionTab.FAVORITES) {
                        stringResource(R.string.library_empty_favorites)
                    } else {
                        stringResource(R.string.library_empty_voted)
                    },
                actionLabel = stringResource(R.string.library_refresh),
                onAction = onRefresh,
                modifier = modifier.refreshableEmptyState(),
            )
        }

        else -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                state = gridState,
                modifier =
                    modifier
                        .fillMaxSize()
                        .onGloballyPositioned { contextMenuCoordinator.gridCoordinates = it },
                contentPadding =
                    PaddingValues(
                        start = WallHubSpacing.md,
                        top = WallHubSpacing.sm,
                        end = WallHubSpacing.md,
                        bottom = 84.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
            ) {
                itemsIndexed(
                    items = state.items,
                    key = { _, item -> item.id },
                    contentType = { _, item -> item.type },
                ) { index, item ->
                    LibrarySearchResultMotion(
                        shouldAnimate = item.id in state.searchAnimationItemIds,
                        animationGeneration = state.searchResultGeneration,
                        index = index,
                        modifier =
                            Modifier.animateItem(
                                placementSpec =
                                    tween(
                                        durationMillis = LIBRARY_SEARCH_REORDER_DURATION_MS,
                                        easing = LIBRARY_SEARCH_REORDER_EASING,
                                    ),
                            ),
                    ) {
                        LibraryWorkshopCard(
                            item = item,
                            collection = state.collection,
                            authorDisplayName = authorDisplayNames[item.id],
                            onClick = { onOpenDetail(item.id) },
                            onPlayVideo = { onPlayVideo(item.id) },
                            onSearchAuthor = { onSearchAuthor(item.creatorId ?: item.author) },
                            onAuthorNameRequested = { onAuthorNameRequested(item) },
                            onDownload = { onDownload(item) },
                            onRemoveFromCollection = { onRemoveFromCollection(item) },
                            onCopyText = onCopyText,
                            onOpenSteam = { onOpenSteam(item.id) },
                            contextMenuCoordinator = contextMenuCoordinator,
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    when {
                        state.paginationMode == HomePaginationMode.INFINITE_SCROLL &&
                            state.isLoadingMore ->
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(WallHubSpacing.sm),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator() }

                        state.paginationMode == HomePaginationMode.PAGED ->
                            LibraryPagination(
                                currentPage = state.currentPage,
                                totalPages = state.totalPages,
                                isLoading = state.isLoading,
                                onPageSelected = onPageSelected,
                            )

                        else -> Spacer(modifier = Modifier.height(WallHubSpacing.xxs))
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.refreshableEmptyState(): Modifier =
    this
        .fillMaxSize()
        .verticalScroll(rememberScrollState())

@Composable
private fun LibraryPagination(
    currentPage: Int,
    totalPages: Int,
    isLoading: Boolean,
    onPageSelected: (Int) -> Unit,
) {
    WallHubPaginationControl(
        currentPage = currentPage,
        totalPages = totalPages,
        isLoading = isLoading,
        currentContentDescription =
            stringResource(R.string.library_current_page_description, currentPage, totalPages),
        onPageSelected = onPageSelected,
        modifier = Modifier.padding(vertical = WallHubSpacing.xs),
    )
}

@Composable
private fun LibraryWorkshopCard(
    item: WorkshopSummary,
    collection: LibraryCollectionTab,
    authorDisplayName: String?,
    onClick: () -> Unit,
    onPlayVideo: () -> Unit,
    onSearchAuthor: () -> Unit,
    onAuthorNameRequested: () -> Unit,
    onDownload: () -> Unit,
    onRemoveFromCollection: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onOpenSteam: () -> Unit,
    contextMenuCoordinator: LibraryContextMenuCoordinator,
) {
    val title = item.localizedTitle()
    LibraryContextMenuCard(
        item = item,
        coordinator = contextMenuCoordinator,
        onOpen = onClick,
        onSearchAuthor = onSearchAuthor,
        authorDisplayName = authorDisplayName,
        onAuthorDisplayNameRequested = onAuthorNameRequested,
        onDownload = onDownload,
        removeActionLabel = collection.removeActionLabelOrNull(),
        onRemoveFromCollection = onRemoveFromCollection,
        onPlayVideo = onPlayVideo,
        onCopyText = onCopyText,
        onOpenSteam = onOpenSteam,
        modifier = Modifier.fillMaxWidth(),
    ) { onShowActions ->
        Column {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f),
            ) {
                if (item.previewUrl != null) {
                    AsyncImage(
                        model = item.previewUrl,
                        contentDescription = stringResource(R.string.library_preview, title),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ImageNotSupported,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Surface(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(WallHubSpacing.xs),
                    shape = WallHubShapeTokens.badge,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                ) {
                    Text(
                        text = item.type.label(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = WallHubSpacing.xs, vertical = WallHubSpacing.xxs),
                    )
                }
                Surface(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(WallHubSpacing.xs),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                ) {
                    IconButton(
                        onClick = onShowActions,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.library_open_actions_menu),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            BoxWithConstraints(
                modifier =
                    Modifier.padding(
                        start = WallHubSpacing.compact,
                        top = WallHubSpacing.compact,
                        end = WallHubSpacing.compact,
                        bottom = WallHubSpacing.compact,
                    ),
            ) {
                val statisticsMetrics = LibraryCardStatisticsMetrics.forAvailableWidth(maxWidth)
                val statisticsStyle =
                    MaterialTheme.typography.bodySmall.copy(
                        fontSize = statisticsMetrics.fontSize.sp,
                    )
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.height(LIBRARY_CARD_TITLE_HEIGHT),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                space = statisticsMetrics.itemSpacing,
                                alignment = Alignment.Start,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LibraryWorkshopCardStatistic(
                            icon = Icons.Outlined.FavoriteBorder,
                            value = item.subscriptions?.let(::formatCompact) ?: "—",
                            contentDescription = stringResource(R.string.library_subscriptions_count),
                            textStyle = statisticsStyle,
                            iconSize = statisticsMetrics.iconSize,
                            iconSpacing = statisticsMetrics.iconSpacing,
                            modifier = Modifier,
                        )
                        LibraryWorkshopCardStatistic(
                            icon = Icons.Outlined.StarBorder,
                            value = item.favorites?.let(::formatCompact) ?: "—",
                            contentDescription = stringResource(R.string.library_favorites_count),
                            textStyle = statisticsStyle,
                            iconSize = statisticsMetrics.iconSize,
                            iconSpacing = statisticsMetrics.iconSpacing,
                            modifier = Modifier,
                        )
                        Text(
                            text = item.fileSizeBytes?.let(::formatByteSize) ?: "— MB",
                            modifier = Modifier,
                            style = statisticsStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrarySearchResultMotion(
    shouldAnimate: Boolean,
    animationGeneration: Long,
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val progress =
        remember(animationGeneration, shouldAnimate) {
            Animatable(if (shouldAnimate) 0f else 1f)
        }
    val entryOffsetPx =
        with(LocalDensity.current) {
            LIBRARY_SEARCH_ENTRY_OFFSET_DP.dp.toPx()
        }
    LaunchedEffect(animationGeneration, shouldAnimate) {
        if (shouldAnimate) {
            delay((index.coerceAtMost(LIBRARY_SEARCH_STAGGER_LIMIT) * LIBRARY_SEARCH_STAGGER_MS).toLong())
            progress.animateTo(
                targetValue = 1f,
                animationSpec =
                    spring(
                        dampingRatio = 0.88f,
                        stiffness = 420f,
                    ),
            )
        } else {
            progress.snapTo(1f)
        }
    }
    Box(
        modifier =
            modifier.graphicsLayer {
                alpha = progress.value
                val offset = (1f - progress.value) * entryOffsetPx
                translationY = offset
                scaleX = 0.98f + progress.value * 0.02f
                scaleY = 0.98f + progress.value * 0.02f
            },
    ) {
        content()
    }
}

@Composable
private fun LibraryWorkshopCardStatistic(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    contentDescription: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    iconSize: Dp,
    iconSpacing: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(iconSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

private data class LibraryCardStatisticsMetrics(
    val fontSize: Float,
    val iconSize: Dp,
    val iconSpacing: Dp,
    val itemSpacing: Dp,
) {
    companion object {
        fun forAvailableWidth(availableWidth: Dp): LibraryCardStatisticsMetrics {
            val statisticSlotWidth = availableWidth.value / 3f
            val fontSize = (statisticSlotWidth / 5.5f).coerceIn(8.5f, 13f)
            return LibraryCardStatisticsMetrics(
                fontSize = fontSize,
                iconSize =
                    when {
                        fontSize <= 9.5f -> 11.dp
                        fontSize <= 10.5f -> WallHubSpacing.sm
                        else -> 15.dp
                    },
                iconSpacing = if (fontSize <= 9.5f) WallHubSpacing.xxxs else 3.dp,
                itemSpacing = if (fontSize <= 9.5f) WallHubSpacing.xxxs else 5.dp,
            )
        }
    }
}

@Composable
private fun LibraryCollectionTab.label(): String =
    when (this) {
        LibraryCollectionTab.SUBSCRIPTIONS -> stringResource(R.string.library_collection_subscriptions)
        LibraryCollectionTab.FAVORITES -> stringResource(R.string.library_collection_favorites)
        LibraryCollectionTab.VOTED -> stringResource(R.string.library_collection_voted)
    }

private fun LibraryCollectionTab.icon() =
    when (this) {
        LibraryCollectionTab.SUBSCRIPTIONS -> Icons.Outlined.BookmarkBorder
        LibraryCollectionTab.FAVORITES -> Icons.Outlined.StarBorder
        LibraryCollectionTab.VOTED -> Icons.Outlined.HowToVote
    }

@Composable
private fun LibraryCollectionTab.removeActionLabelOrNull(): String? =
    when (this) {
        LibraryCollectionTab.SUBSCRIPTIONS -> stringResource(R.string.library_unsubscribe)
        LibraryCollectionTab.FAVORITES -> stringResource(R.string.library_remove_favorite)
        LibraryCollectionTab.VOTED -> null
    }

@Composable
private fun LibraryCollectionTab.removeActionLabel(): String =
    requireNotNull(removeActionLabelOrNull())

@Composable
private fun LibraryCollectionTab.removeDialogTitle(): String =
    when (this) {
        LibraryCollectionTab.SUBSCRIPTIONS -> stringResource(R.string.library_unsubscribe_title)
        LibraryCollectionTab.FAVORITES -> stringResource(R.string.library_remove_favorite_title)
        LibraryCollectionTab.VOTED -> stringResource(R.string.library_collection_voted)
    }

@Composable
private fun SteamSessionState.libraryMessage(): String =
    when (phase) {
        SteamSessionPhase.SIGNED_OUT -> stringResource(R.string.library_sign_in_required)
        SteamSessionPhase.SIGNING_IN,
        SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
        SteamSessionPhase.WAITING_FOR_CODE,
        -> stringResource(R.string.library_restoring_sign_in)

        SteamSessionPhase.EXPIRED,
        SteamSessionPhase.FAILED,
        -> stringResource(R.string.library_sign_in_verification_required)

        SteamSessionPhase.RESTORABLE -> stringResource(R.string.library_session_unavailable)
        SteamSessionPhase.SIGNED_IN -> stringResource(R.string.library_loading_short)
    }

private val SteamSessionState.isRestoreRetryable: Boolean
    get() =
        hasStoredSession &&
            (
                phase == SteamSessionPhase.RESTORABLE || phase == SteamSessionPhase.FAILED
            )

@Composable
private fun WorkshopType.label(): String =
    when (this) {
        WorkshopType.VIDEO -> stringResource(R.string.library_type_video)
        WorkshopType.SCENE -> stringResource(R.string.library_type_scene)
        WorkshopType.WEB -> stringResource(R.string.library_type_web)
        WorkshopType.UNKNOWN -> stringResource(R.string.library_type_wallpaper)
    }

private fun formatCompact(value: Long): String {
    val locale = Locale.getDefault()
    val isChinese = locale.language == Locale.CHINESE.language
    return when {
        (isChinese && value >= 10_000) || (!isChinese && value >= 1_000_000) ->
            String.format(
                locale,
                if (isChinese) "%.1f 万" else "%.1fM",
                if (isChinese) value / 10_000.0 else value / 1_000_000.0,
            )

        value >= 1_000 -> String.format(locale, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}

private val LIBRARY_CARD_TITLE_HEIGHT = WallHubSizeTokens.cardTitleHeight
private val LIBRARY_FILTER_TYPES = setOf(WorkshopType.VIDEO, WorkshopType.SCENE, WorkshopType.WEB)
private const val LIBRARY_AUTO_LOAD_MORE_THRESHOLD = 2
private const val LIBRARY_SEARCH_REORDER_DURATION_MS = 280
private const val LIBRARY_SEARCH_STAGGER_MS = 40
private const val LIBRARY_SEARCH_STAGGER_LIMIT = 7
private const val LIBRARY_SEARCH_ENTRY_OFFSET_DP = 24
private val LIBRARY_HEADER_COLLAPSE_DISTANCE = WallHubSpacing.xxl
private val LIBRARY_HEADER_EXPAND_DISTANCE = 20.dp
private val LIBRARY_SEARCH_REORDER_EASING = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
