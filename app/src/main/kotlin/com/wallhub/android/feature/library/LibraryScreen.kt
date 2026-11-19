@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
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
import com.wallhub.android.core.format.formatCompactCount
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubHorizontalDivider
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubToolbarSearchTitle
import com.wallhub.android.core.designsystem.WallHubPaginationControl
import com.wallhub.android.core.designsystem.WallHubShapeTokens
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubAlertDialog
import com.wallhub.android.core.designsystem.wallHubStyle
import com.wallhub.android.core.format.formatByteSize
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.designsystem.rememberWallHubDirectionalCollapseConnection
import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.AccountWorkshopSort
import com.wallhub.android.core.model.defaultSort
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.DownloadEnqueueResult
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopDownloads
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.toQuickDownloadRequest
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Locale
import javax.inject.Inject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder

enum class LibraryCollectionTab(
    val collection: AccountWorkshopCollection,
) {
    SUBSCRIPTIONS(AccountWorkshopCollection.SUBSCRIPTIONS),
    FAVORITES(AccountWorkshopCollection.FAVORITES),
}

private data class LibraryCollectionMutationKey(
    val accountId: Long,
    val collection: LibraryCollectionTab,
    val workshopId: Long,
)

data class LibraryUiState(
    val session: SteamSessionState = SteamSessionState(),
    val collection: LibraryCollectionTab = LibraryCollectionTab.SUBSCRIPTIONS,
    val collectionSorts: Map<LibraryCollectionTab, AccountWorkshopSort> = emptyMap(),
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
    @StringRes val appendErrorRes: Int? = null,
    val failedAppendPage: Int? = null,
    val error: String? = null,
    @StringRes val errorRes: Int? = null,
    val appliedSearchQuery: String = "",
    val searchResultGeneration: Long = 0L,
    val searchAnimationItemIds: Set<Long> = emptySet(),
    val collectionStates: Map<LibraryCollectionTab, LibraryCollectionPageState> = emptyMap(),
)

data class LibraryCollectionPageState(
    val items: List<WorkshopSummary> = emptyList(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val nextPage: Int = 2,
    val hasNextPage: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    @StringRes val appendErrorRes: Int? = null,
    val failedAppendPage: Int? = null,
    val error: String? = null,
    @StringRes val errorRes: Int? = null,
    val appliedSearchQuery: String = "",
    val searchResultGeneration: Long = 0L,
    val searchAnimationItemIds: Set<Long> = emptySet(),
)

internal fun LibraryUiState.collectionState(tab: LibraryCollectionTab): LibraryCollectionPageState =
    if (tab == collection) {
        LibraryCollectionPageState(
            items = items,
            currentPage = currentPage,
            totalPages = totalPages,
            nextPage = nextPage,
            hasNextPage = hasNextPage,
            isLoading = isLoading,
            isRefreshing = isRefreshing,
            isLoadingMore = isLoadingMore,
            appendErrorRes = appendErrorRes,
            failedAppendPage = failedAppendPage,
            error = error,
            errorRes = errorRes,
            appliedSearchQuery = appliedSearchQuery,
            searchResultGeneration = searchResultGeneration,
            searchAnimationItemIds = searchAnimationItemIds,
        )
    } else {
        collectionStates[tab] ?: LibraryCollectionPageState()
    }

private fun LibraryUiState.withCollectionState(
    tab: LibraryCollectionTab,
    page: LibraryCollectionPageState,
): LibraryUiState {
    val stored = collectionStates + (tab to page)
    if (tab != collection) return copy(collectionStates = stored)
    return copy(
        items = page.items,
        currentPage = page.currentPage,
        totalPages = page.totalPages,
        nextPage = page.nextPage,
        hasNextPage = page.hasNextPage,
        isLoading = page.isLoading,
        isRefreshing = page.isRefreshing,
        isLoadingMore = page.isLoadingMore,
        appendErrorRes = page.appendErrorRes,
        failedAppendPage = page.failedAppendPage,
        error = page.error,
        errorRes = page.errorRes,
        appliedSearchQuery = page.appliedSearchQuery,
        searchResultGeneration = page.searchResultGeneration,
        searchAnimationItemIds = page.searchAnimationItemIds,
        collectionStates = stored,
    )
}

private fun LibraryUiState.updateCollectionState(
    tab: LibraryCollectionTab,
    transform: (LibraryCollectionPageState) -> LibraryCollectionPageState,
): LibraryUiState = withCollectionState(tab, transform(collectionState(tab)))

internal fun LibraryUiState.selectCollectionState(collection: LibraryCollectionTab): LibraryUiState {
    if (this.collection == collection) return this
    val saved = collectionStates + (this.collection to collectionState(this.collection))
    val target = saved[collection] ?: LibraryCollectionPageState()
    return copy(collection = collection, collectionStates = saved).withCollectionState(collection, target)
}

internal fun LibraryUiState.forCollection(collection: LibraryCollectionTab): LibraryUiState {
    if (this.collection == collection) return this
    return copy(collection = collection).withCollectionState(collection, collectionState(collection))
}

internal fun LibraryUiState.sortFor(collection: LibraryCollectionTab = this.collection): AccountWorkshopSort =
    collectionSorts[collection]
        ?.takeUnless { collection == LibraryCollectionTab.FAVORITES && it == AccountWorkshopSort.RELATIONSHIP_DATE_DESC }
        ?: collection.collection.defaultSort()

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

internal fun LibraryUiState.withMatureContentPolicy(
    enabled: Boolean,
    invalidateItems: Boolean,
): LibraryUiState {
    val normalized = filterSelection().normalized(enabled)
    val updated =
        copy(
            selectedRatings = normalized.ratings,
            matureContentEnabled = enabled,
        )
    if (!invalidateItems) return updated
    return LibraryCollectionTab.entries.fold(updated) { state, tab ->
        state.withCollectionState(tab, LibraryCollectionPageState())
    }
}

internal fun LibraryUiState.resetLibraryFiltersState(): LibraryUiState =
    copy(
        collectionSorts = collectionSorts + (collection to collection.collection.defaultSort()),
        selectedTypes = emptySet(),
        selectedRatings = DEFAULT_HOME_RATING_SELECTION,
        selectedGenres = DEFAULT_HOME_GENRE_SELECTION,
        selectedOfficialTags = emptySet(),
        selectedExcludedOfficialTags = emptySet(),
        selectedResolutions = DEFAULT_HOME_RESOLUTION_SELECTION,
        exactPhrase = false,
        paginationMode = HomePaginationMode.INFINITE_SCROLL,
    )

enum class LibraryEmptyKind { TRUE_EMPTY, SEARCH_NO_RESULTS, FILTERED_NO_RESULTS }

internal fun LibraryUiState.hasActiveLibraryFilters(): Boolean =
    filterSelection() != HomeFilterSelection.defaults() || exactPhrase

internal fun LibraryUiState.emptyKind(): LibraryEmptyKind =
    when {
        appliedSearchQuery.isNotBlank() -> LibraryEmptyKind.SEARCH_NO_RESULTS
        hasActiveLibraryFilters() -> LibraryEmptyKind.FILTERED_NO_RESULTS
        else -> LibraryEmptyKind.TRUE_EMPTY
    }

internal fun shouldAutoLoadNextLibraryPage(state: LibraryUiState): Boolean =
    state.paginationMode == HomePaginationMode.INFINITE_SCROLL &&
        state.searchQuery.trim() == state.appliedSearchQuery.trim() &&
        !state.isLoading &&
        !state.isLoadingMore &&
        state.appendErrorRes == null &&
        state.hasNextPage

internal fun canRetryNextLibraryPage(state: LibraryUiState): Boolean =
    state.appendErrorRes != null && state.failedAppendPage == state.nextPage && state.hasNextPage

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
        val sort: AccountWorkshopSort? = null,
    ) : LibraryAction

    data class UpdateSearchQuery(
        val query: String,
    ) : LibraryAction

    data object SubmitSearch : LibraryAction

    data class SelectPaginationMode(
        val mode: HomePaginationMode,
    ) : LibraryAction

    data object ResetFilters : LibraryAction

    data object Enter : LibraryAction

    data object Leave : LibraryAction

    data object Refresh : LibraryAction

    data class RefreshCollection(
        val collection: LibraryCollectionTab,
    ) : LibraryAction

    data object LoadNextPage : LibraryAction

    data class LoadNextPageFor(
        val collection: LibraryCollectionTab,
    ) : LibraryAction

    data object RetryNextPage : LibraryAction

    data class RetryNextPageFor(
        val collection: LibraryCollectionTab,
    ) : LibraryAction

    data class SelectPage(
        val page: Int,
    ) : LibraryAction

    data class SelectCollectionPage(
        val collection: LibraryCollectionTab,
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
        val collection: LibraryCollectionTab? = null,
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

internal val SteamSessionState.hasValidLibrarySession: Boolean
    get() = phase == SteamSessionPhase.SIGNED_IN && isConnected && !accountName.isNullOrBlank()

internal fun shouldLoadLibraryForSignedInSession(
    previousPhase: SteamSessionPhase,
    activeAccountName: String?,
    observedAccountName: String?,
    refreshWhenSignedIn: Boolean,
): Boolean =
    activeAccountName == null ||
        previousPhase != SteamSessionPhase.SIGNED_IN ||
        !activeAccountName.equals(observedAccountName, ignoreCase = true) ||
        refreshWhenSignedIn

@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        @ApplicationContext private val applicationContext: Context,
        private val steamSessionRepository: SteamSessionRepository,
        private val accountWorkshopRepository: AccountWorkshopRepository,
        private val workshopRepository: WorkshopRepository,
        private val downloadTaskRepository: WorkshopDownloads,
        private val settingsRepository: SettingsRepository,
        private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(savedStateHandle.libraryState())
        private val effectChannel = Channel<LibraryEffect>(capacity = Channel.BUFFERED)
        private val loadJobs = mutableMapOf<LibraryCollectionTab, Job>()
        private val searchJobs = mutableMapOf<LibraryCollectionTab, Job>()
        private val requestVersions = LibraryCollectionTab.entries.associateWith { 0L }.toMutableMap()
        private val authorNameRequests = mutableSetOf<Long>()
        private val collectionMutations = mutableSetOf<LibraryCollectionMutationKey>()
        private val expectedCollectionChanges = mutableSetOf<LibraryCollectionMutationKey>()
        private var activeAccountName: String? = null
        private var refreshWhenSignedIn = false
        private var isVisible = false
        private var entryColdLoadStarted = false
        private var screenGeneration = 0L

        val uiState: StateFlow<LibraryUiState> = mutableState.asStateFlow()
        val effects: Flow<LibraryEffect> = effectChannel.receiveAsFlow()

        init {
            viewModelScope.launch {
                mutableState.collect { state -> savedStateHandle.saveLibraryState(state) }
            }
            viewModelScope.launch {
                var previousPolicy: Boolean? = null
                settingsRepository.preferences
                    .map { it.matureContentEnabled }
                    .distinctUntilChanged()
                    .collect { matureContentEnabled ->
                        val current = mutableState.value
                        val policyChanged = previousPolicy != null && previousPolicy != matureContentEnabled
                        previousPolicy = matureContentEnabled
                        if (policyChanged) cancelAllCollectionWork()
                        mutableState.value = current.withMatureContentPolicy(matureContentEnabled, policyChanged)
                        if (policyChanged && isVisible && current.session.hasValidLibrarySession) {
                            loadBothFirstPages()
                        }
                    }
            }
            viewModelScope.launch {
                accountWorkshopRepository.collectionChanges.collect { change ->
                    val mutation =
                        LibraryCollectionMutationKey(
                            accountId = change.accountId,
                            collection = LibraryCollectionTab.entries.first { it.collection == change.collection },
                            workshopId = change.workshopId,
                        )
                    if (expectedCollectionChanges.remove(mutation)) return@collect
                    if (change.accountId != accountWorkshopRepository.activeAccountId()) return@collect
                    val current = mutableState.value
                    if (isVisible && current.session.hasValidLibrarySession) {
                        loadCurrentPage(collection = mutation.collection, refreshing = false, page = 1)
                    }
                }
            }
            viewModelScope.launch {
                steamSessionRepository.session.collect { session ->
                    val previous = mutableState.value
                    if (!session.hasValidLibrarySession) {
                        entryColdLoadStarted = false
                        if (previous.session.hasValidLibrarySession) advanceScreenGeneration()
                        if (previous.session.phase == SteamSessionPhase.SIGNED_IN) {
                            clearCache()
                        } else {
                            cancelAllCollectionWork()
                        }
                        mutableState.value = clearCollectionPages(previous.copy(session = session))
                        return@collect
                    }
                    val accountChanged =
                        activeAccountName != null &&
                            !activeAccountName.equals(session.accountName, ignoreCase = true)
                    val shouldLoad =
                        shouldLoadLibraryForSignedInSession(
                            previousPhase = previous.session.phase,
                            activeAccountName = activeAccountName,
                            observedAccountName = session.accountName,
                            refreshWhenSignedIn = refreshWhenSignedIn,
                        )
                    val accountState =
                        if (accountChanged) {
                            advanceScreenGeneration()
                            entryColdLoadStarted = false
                            cancelAllCollectionWork()
                            clearCollectionPages(previous)
                        } else {
                            previous
                        }
                    activeAccountName = session.accountName
                    mutableState.value = accountState.copy(session = session)
                    if (shouldLoad && isVisible) {
                        refreshWhenSignedIn = false
                        startEntryColdLoadIfNeeded()
                    }
                }
            }
        }

        fun onAction(action: LibraryAction) {
            if (action is LibraryAction.OpenDetail) {
                val item = mutableState.value.items.firstOrNull { it.id == action.workshopId }
                viewModelScope.launch {
                    item?.let {
                        workshopRepository.primeDetailSeed(
                            WorkshopDetail(summary = it, fileSizeBytes = it.fileSizeBytes, updatedAt = it.updatedAt),
                        )
                    }
                    emitEffect(LibraryEffect.OpenDetail(action.workshopId))
                }
                return
            }
            action.toEffect()?.let(::emitEffect) ?: handleStateAction(action)
        }

        private fun handleStateAction(action: LibraryAction) {
            when (action) {
                is LibraryAction.SelectCollection -> selectCollection(action.collection)
                is LibraryAction.ApplyFilters -> applyFilters(action.selection, action.exactPhrase, action.sort)
                is LibraryAction.UpdateSearchQuery -> updateSearchQuery(action.query)
                LibraryAction.SubmitSearch -> submitSearch()
                is LibraryAction.SelectPaginationMode -> selectPaginationMode(action.mode)
                LibraryAction.ResetFilters -> resetManagementFilters()
                LibraryAction.Enter -> enter()
                LibraryAction.Leave -> leave()
                LibraryAction.Refresh -> refresh(mutableState.value.collection)
                is LibraryAction.RefreshCollection -> refresh(action.collection)
                LibraryAction.LoadNextPage -> loadNextPage(mutableState.value.collection)
                is LibraryAction.LoadNextPageFor -> loadNextPage(action.collection)
                LibraryAction.RetryNextPage -> retryNextPage(mutableState.value.collection)
                is LibraryAction.RetryNextPageFor -> retryNextPage(action.collection)
                is LibraryAction.SelectPage -> selectPage(mutableState.value.collection, action.page)
                is LibraryAction.SelectCollectionPage -> selectPage(action.collection, action.page)
                is LibraryAction.RequestAuthorDisplayName -> requestAuthorDisplayName(action.item)
                is LibraryAction.Download -> enqueueDownload(action.item)
                is LibraryAction.RemoveFromCollection ->
                    removeFromCollection(action.collection ?: mutableState.value.collection, action.item)
                else -> Unit
            }
        }

        private fun emitEffect(effect: LibraryEffect) {
            effectChannel.trySend(effect)
        }

        private fun requestVersion(collection: LibraryCollectionTab): Long = requestVersions.getValue(collection)

        private fun invalidateRequests(collection: LibraryCollectionTab): Long {
            loadJobs.remove(collection)?.cancel()
            val next = requestVersion(collection) + 1L
            requestVersions[collection] = next
            return next
        }

        private fun cancelAllCollectionWork() {
            LibraryCollectionTab.entries.forEach { collection ->
                loadJobs.remove(collection)?.cancel()
                searchJobs.remove(collection)?.cancel()
                requestVersions[collection] = requestVersion(collection) + 1L
            }
        }

        private fun updateCollection(
            collection: LibraryCollectionTab,
            transform: (LibraryCollectionPageState) -> LibraryCollectionPageState,
        ) {
            mutableState.value = mutableState.value.updateCollectionState(collection, transform)
        }

        private fun clearCollectionPages(state: LibraryUiState = mutableState.value): LibraryUiState =
            LibraryCollectionTab.entries.fold(state) { current, collection ->
                current.withCollectionState(collection, LibraryCollectionPageState())
            }

        private fun loadBothFirstPages() {
            LibraryCollectionTab.entries.forEach { collection ->
                loadCurrentPage(collection = collection, refreshing = false, page = 1)
            }
        }

        private fun startEntryColdLoadIfNeeded() {
            if (entryColdLoadStarted || !isVisible || !mutableState.value.session.hasValidLibrarySession) return
            entryColdLoadStarted = true
            loadBothFirstPages()
        }

        private fun selectCollection(collection: LibraryCollectionTab) {
            if (mutableState.value.collection == collection) return
            mutableState.value = mutableState.value.selectCollectionState(collection)
        }

        private fun applyFilters(
            selection: HomeFilterSelection,
            exactPhrase: Boolean,
            sort: AccountWorkshopSort?,
        ) {
            val current = mutableState.value
            val selectedSort = sort ?: current.sortFor()
            val normalized = selection.normalized(current.matureContentEnabled)
            if (
                current.filterSelection() == normalized &&
                    current.exactPhrase == exactPhrase &&
                    current.sortFor() == selectedSort
            ) {
                return
            }
            searchJobs.values.forEach(Job::cancel)
            searchJobs.clear()
            mutableState.value =
                current.copy(
                    collectionSorts = current.collectionSorts + (current.collection to selectedSort),
                    selectedTypes = normalized.types,
                    selectedRatings = normalized.ratings,
                    selectedGenres = normalized.genres,
                    selectedOfficialTags = normalized.officialTags,
                    selectedExcludedOfficialTags = normalized.excludedOfficialTags,
                    selectedResolutions = normalized.resolutions,
                    exactPhrase = exactPhrase,
                )
            loadBothFirstPages()
        }

        private fun removeFromCollection(
            collection: LibraryCollectionTab,
            item: WorkshopSummary,
        ) {
            val accountId = accountWorkshopRepository.activeAccountId()
            if (accountId == null) {
                emitEffect(LibraryEffect.ShowMessage(R.string.library_remove_failed))
                return
            }
            val mutation = LibraryCollectionMutationKey(accountId, collection, item.id)
            if (!collectionMutations.add(mutation)) return
            expectedCollectionChanges += mutation
            val requestVersionAtStart = requestVersion(collection)
            val screenGenerationAtStart = screenGeneration
            viewModelScope.launch {
                var removalConfirmedSuccessfully = false
                try {
                    val removalConfirmed =
                        when (collection) {
                            LibraryCollectionTab.SUBSCRIPTIONS ->
                                accountWorkshopRepository
                                    .setSubscribedForAccount(accountId, item.id, false, item)
                                    .subscriptionState == SubscriptionState.NOT_SUBSCRIBED
                            LibraryCollectionTab.FAVORITES ->
                                accountWorkshopRepository
                                    .setFavoritedForAccount(accountId, item.id, false, item)
                                    .favoriteState == FavoriteState.NOT_FAVORITED
                        }
                    check(removalConfirmed) { "The account collection removal was not confirmed" }
                    removalConfirmedSuccessfully = true
                    if (!isMutationContextCurrent(accountId, screenGenerationAtStart)) return@launch
                    val current = mutableState.value
                    val cancelsPreMutationRequest = requestVersion(collection) == requestVersionAtStart
                    if (cancelsPreMutationRequest) invalidateRequests(collection)
                    mutableState.value =
                        current.updateCollectionState(collection) { page ->
                            page.copy(
                                items = page.items.filterNot { currentItem -> currentItem.id == item.id },
                                searchAnimationItemIds = page.searchAnimationItemIds - item.id,
                                isLoading = if (cancelsPreMutationRequest) false else page.isLoading,
                                isRefreshing = if (cancelsPreMutationRequest) false else page.isRefreshing,
                                isLoadingMore = if (cancelsPreMutationRequest) false else page.isLoadingMore,
                            )
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
                    if (isMutationContextCurrent(accountId, screenGenerationAtStart)) {
                        emitEffect(LibraryEffect.ShowMessage(R.string.library_remove_failed))
                    }
                    throw error
                } catch (_: Throwable) {
                    if (isMutationContextCurrent(accountId, screenGenerationAtStart)) {
                        emitEffect(LibraryEffect.ShowMessage(R.string.library_remove_failed))
                    }
                } finally {
                    collectionMutations -= mutation
                    if (!removalConfirmedSuccessfully) expectedCollectionChanges -= mutation
                }
            }
        }

        private fun enqueueDownload(item: WorkshopSummary) {
            viewModelScope.launch {
                runCatching {
                    val preferences = settingsRepository.preferences.first()
                    downloadTaskRepository.enqueue(
                        item.toQuickDownloadRequest(
                            preferences = preferences,
                            localizedTitle = applicationContext.localizedTitle(item),
                        ),
                    )
                }.onSuccess { result ->
                    emitEffect(
                        LibraryEffect.ShowMessage(
                            messageRes =
                                if (result is DownloadEnqueueResult.Created) {
                                    R.string.home_added_to_download_queue
                                } else {
                                    R.string.download_already_active
                                },
                            formatArgs = listOf(result.task.title),
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
            LibraryCollectionTab.entries.forEach(::invalidateRequests)
            mutableState.value = mutableState.value.copy(searchQuery = normalized)
            searchJobs.values.forEach(Job::cancel)
            searchJobs.clear()
            LibraryCollectionTab.entries.forEach { collection ->
                searchJobs[collection] =
                    viewModelScope.launch {
                        delay(SEARCH_DEBOUNCE_MS)
                        loadCurrentPage(collection = collection, refreshing = false)
                    }
            }
        }

        private fun submitSearch() {
            searchJobs.values.forEach(Job::cancel)
            searchJobs.clear()
            loadBothFirstPages()
        }

        private fun requestAuthorDisplayName(item: WorkshopSummary) {
            if (item.authorPlaceholder == WorkshopAuthorPlaceholder.NONE || item.id in authorNameRequests) return
            authorNameRequests += item.id
            viewModelScope.launch {
                val authorName =
                    runCatching {
                        item.creatorId?.let {
                            accountWorkshopRepository.resolveAuthorDisplayNameByAccountId(it)
                        } ?: accountWorkshopRepository.resolveAuthorDisplayName(item.id)
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
            searchJobs.values.forEach(Job::cancel)
            searchJobs.clear()
            mutableState.value = mutableState.value.copy(paginationMode = mode)
            loadBothFirstPages()
        }

        private fun resetManagementFilters() {
            searchJobs.values.forEach(Job::cancel)
            searchJobs.clear()
            val state = mutableState.value
            if (
                !state.hasActiveLibraryFilters() &&
                state.sortFor() == state.collection.collection.defaultSort() &&
                state.paginationMode == HomePaginationMode.INFINITE_SCROLL
            ) return
            mutableState.value = state.resetLibraryFiltersState()
            loadBothFirstPages()
        }

        private fun enter() {
            advanceScreenGeneration()
            entryColdLoadStarted = false
            cancelAllCollectionWork()
            mutableState.value = clearCollectionPages()
            isVisible = true
            startEntryColdLoadIfNeeded()
        }

        private fun leave() {
            isVisible = false
            entryColdLoadStarted = false
            advanceScreenGeneration()
            cancelAllCollectionWork()
            mutableState.value = clearCollectionPages()
        }

        private fun refresh(collection: LibraryCollectionTab) {
            val session = mutableState.value.session
            if (!session.hasValidLibrarySession) {
                refreshWhenSignedIn = true
                if (session.shouldRestoreSession(explicit = true, nowMs = System.currentTimeMillis())) {
                    steamSessionRepository.restorePersistedSession()
                }
                return
            }
            loadCurrentPage(collection = collection, refreshing = true, page = 1)
        }

        private fun loadNextPage(collection: LibraryCollectionTab) {
            val state = mutableState.value.forCollection(collection)
            if (
                state.paginationMode != HomePaginationMode.INFINITE_SCROLL ||
                state.searchQuery.trim() != state.appliedSearchQuery.trim() ||
                state.isLoading ||
                state.isLoadingMore ||
                state.appendErrorRes != null ||
                !state.hasNextPage
            ) {
                return
            }
            loadPage(
                collection = collection,
                page = state.nextPage,
                append = true,
                version = requestVersion(collection),
                refreshing = false,
            )
        }

        private fun retryNextPage(collection: LibraryCollectionTab) {
            val state = mutableState.value.forCollection(collection)
            if (!canRetryNextLibraryPage(state)) return
            val page = requireNotNull(state.failedAppendPage)
            updateCollection(collection) {
                it.copy(isLoadingMore = true, appendErrorRes = null, failedAppendPage = null)
            }
            loadPage(
                collection = collection,
                page = page,
                append = true,
                version = requestVersion(collection),
                refreshing = false,
            )
        }

        private fun selectPage(
            collection: LibraryCollectionTab,
            page: Int,
        ) {
            val state = mutableState.value.forCollection(collection)
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
            val version = invalidateRequests(collection)
            loadPage(
                collection = collection,
                page = targetPage,
                append = false,
                version = version,
                refreshing = false,
            )
        }

        private fun loadCurrentPage(
            collection: LibraryCollectionTab = mutableState.value.collection,
            refreshing: Boolean,
            page: Int = 1,
        ) {
            if (!mutableState.value.session.hasValidLibrarySession) return
            val version = invalidateRequests(collection)
            updateCollection(collection) {
                it.copy(
                    items = emptyList(),
                    nextPage = 2,
                    hasNextPage = false,
                    currentPage = page,
                    totalPages = 1,
                    isLoading = true,
                    isRefreshing = refreshing,
                    isLoadingMore = false,
                    appendErrorRes = null,
                    failedAppendPage = null,
                    error = null,
                    errorRes = null,
                    searchAnimationItemIds = emptySet(),
                )
            }
            loadPage(
                collection = collection,
                page = page,
                append = false,
                version = version,
                refreshing = refreshing,
            )
        }

        private fun loadPage(
            collection: LibraryCollectionTab,
            page: Int,
            append: Boolean,
            version: Long,
            refreshing: Boolean,
        ) {
            val snapshot = mutableState.value.forCollection(collection)
            loadJobs[collection] =
                viewModelScope.launch {
                    updateCollection(collection) {
                        snapshot.collectionState(collection).copy(
                            isLoading = !append,
                            isRefreshing = refreshing && !append,
                            isLoadingMore = append,
                            appendErrorRes = null,
                            failedAppendPage = null,
                            error = null,
                            errorRes = null,
                        )
                    }
                    try {
                        val query =
                            AccountWorkshopQuery(
                                collection = collection.collection,
                                sort = snapshot.sortFor(collection),
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
                        val result =
                            withTimeout(LIBRARY_FOREGROUND_LOAD_TIMEOUT_MS) {
                                if (append) {
                                    accountWorkshopRepository.browseCollection(query)
                                } else {
                                    accountWorkshopRepository.refreshCollection(query)
                                }
                            }
                        if (version != requestVersion(collection)) return@launch
                        mutableState.value = result.mergeInto(mutableState.value, collection, snapshot, append)
                    } catch (error: TimeoutCancellationException) {
                        if (version != requestVersion(collection)) return@launch
                        mutableState.value = mutableState.value.libraryLoadFailure(collection, append, page)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (version != requestVersion(collection)) return@launch
                        mutableState.value = mutableState.value.libraryLoadFailure(collection, append, page)
                    }
                }
        }

        private fun LibraryUiState.libraryLoadFailure(
            collection: LibraryCollectionTab,
            append: Boolean,
            page: Int,
        ): LibraryUiState =
            updateCollectionState(collection) {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    appendErrorRes = if (append) R.string.library_load_failed else null,
                    failedAppendPage = page.takeIf { append },
                    error = null,
                    errorRes = if (append) null else R.string.library_load_failed,
                )
            }

        private fun WorkshopPage.mergeInto(
            state: LibraryUiState,
            collection: LibraryCollectionTab,
            snapshot: LibraryUiState,
            append: Boolean,
        ): LibraryUiState {
            val previous = snapshot.collectionState(collection)
            val pageState =
                previous.copy(
                    items =
                        if (append) {
                            (previous.items + items).distinctBy(WorkshopSummary::id)
                        } else {
                            items.distinctBy(WorkshopSummary::id)
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
                    appendErrorRes = null,
                    failedAppendPage = null,
                    error = null,
                    errorRes = null,
                    appliedSearchQuery = snapshot.searchQuery,
                    searchResultGeneration =
                        if (!append && snapshot.searchQuery.trim() != previous.appliedSearchQuery.trim()) {
                            previous.searchResultGeneration + 1L
                        } else {
                            previous.searchResultGeneration
                        },
                    searchAnimationItemIds =
                        if (!append && snapshot.searchQuery.trim() != previous.appliedSearchQuery.trim()) {
                            items.mapTo(mutableSetOf()) { item -> item.id }
                        } else {
                            previous.searchAnimationItemIds
                        },
                )
            return state.withCollectionState(collection, pageState)
        }

        private fun isMutationContextCurrent(accountId: Long, generation: Long): Boolean =
            isVisible &&
                screenGeneration == generation &&
                accountWorkshopRepository.activeAccountId() == accountId

        private fun advanceScreenGeneration() {
            screenGeneration += 1L
            collectionMutations.clear()
            expectedCollectionChanges.clear()
        }

        private fun clearCache() {
            cancelAllCollectionWork()
            mutableState.value = clearCollectionPages()
            activeAccountName = null
        }

        private companion object {
            const val SEARCH_DEBOUNCE_MS = 320L
            const val MAX_SEARCH_LENGTH = 120
        }
    }

internal fun SavedStateHandle.libraryState(): LibraryUiState =
    LibraryUiState(
        collection =
            resolveInitialLibraryCollection(
                navigationCollection = get(LIBRARY_NAV_COLLECTION_KEY),
                persistedCollection = get(LIBRARY_COLLECTION_KEY),
            ),
        collectionSorts = LibraryCollectionTab.entries.associateWith { collection ->
            libraryEnumValueOrDefault(
                get("$LIBRARY_SORT_KEY.${collection.name}"),
                collection.collection.defaultSort(),
            )
        },
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
    LibraryCollectionTab.entries.forEach { collection ->
        this["$LIBRARY_SORT_KEY.${collection.name}"] = state.sortFor(collection).name
    }
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

internal fun resolveInitialLibraryCollection(
    navigationCollection: String?,
    persistedCollection: String?,
): LibraryCollectionTab =
    libraryEnumValueOrDefault(
        navigationCollection ?: persistedCollection,
        LibraryCollectionTab.SUBSCRIPTIONS,
    )

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

private const val LIBRARY_NAV_COLLECTION_KEY = "collection"
private const val LIBRARY_COLLECTION_KEY = "library.collection"
private const val LIBRARY_SORT_KEY = "library.sort"
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
private const val LIBRARY_FOREGROUND_LOAD_TIMEOUT_MS = 12_000L

@Composable
fun LibraryRoute(
    onOpenLogin: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpenDetail: (Long) -> Unit,
    onPlayVideo: (Long) -> Unit = {},
    onSearchAuthor: (String) -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onContextMenuActiveChanged: (Boolean) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    DisposableEffect(viewModel) {
        viewModel.onAction(LibraryAction.Enter)
        onDispose { viewModel.onAction(LibraryAction.Leave) }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LibraryEffectHandler(
            viewModel = viewModel,
            onOpenDetail = onOpenDetail,
            onPlayVideo = onPlayVideo,
            onSearchAuthor = onSearchAuthor,
            onOpenDownloads = onOpenDownloads,
            snackbarHostState = snackbarHostState,
        )
        LibraryScreen(
            state = state,
            onAction = viewModel::onAction,
            onOpenLogin = onOpenLogin,
            onBack = onBack,
            onContextMenuActiveChanged = onContextMenuActiveChanged,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

@Composable
fun LibraryEffectHandler(
    viewModel: LibraryViewModel,
    onOpenDetail: (Long) -> Unit,
    onPlayVideo: (Long) -> Unit = {},
    onSearchAuthor: (String) -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboardManager.current
    val currentOnOpenDetail by rememberUpdatedState(onOpenDetail)
    val currentOnPlayVideo by rememberUpdatedState(onPlayVideo)
    val currentOnSearchAuthor by rememberUpdatedState(onSearchAuthor)
    val currentOnOpenDownloads by rememberUpdatedState(onOpenDownloads)
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
                is LibraryEffect.ShowMessage -> {
                    val message = resources.getString(effect.messageRes, *effect.formatArgs.toTypedArray())
                    if (effect.messageRes == R.string.home_added_to_download_queue) {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        val result =
                            snackbarHostState.showSnackbar(
                                message = message,
                                actionLabel = resources.getString(R.string.downloads_view_queue),
                                duration = SnackbarDuration.Long,
                            )
                        if (result == SnackbarResult.ActionPerformed) currentOnOpenDownloads()
                    } else {
                        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onAction: (LibraryAction) -> Unit,
    onOpenLogin: () -> Unit = {},
    onBack: () -> Unit = {},
    onContextMenuActiveChanged: (Boolean) -> Unit = {},
) {
    var searchToolbarExpanded by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<Pair<LibraryCollectionTab, WorkshopSummary>?>(null) }
    val filterDrawerState = rememberDrawerState(DrawerValue.Closed)
    var draftSort by remember { mutableStateOf(state.sortFor()) }
    LaunchedEffect(filterDrawerState.isOpen, state.collection) {
        if (filterDrawerState.isOpen) draftSort = state.sortFor()
    }
    val filterDrawerScope = rememberCoroutineScope()
    val pagerScope = rememberCoroutineScope()
    val subscriptionsGridState = rememberLazyGridState()
    val favoritesGridState = rememberLazyGridState()
    val pagerState = rememberPagerState(initialPage = state.collection.ordinal) { LibraryCollectionTab.entries.size }
    LaunchedEffect(state.collection) {
        if (pagerState.settledPage != state.collection.ordinal) pagerState.animateScrollToPage(state.collection.ordinal)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page -> onAction(LibraryAction.SelectCollection(LibraryCollectionTab.entries[page])) }
    }
    val pageContainer = wallHubStyle().surfaces.page
    BackHandler(enabled = searchToolbarExpanded) { searchToolbarExpanded = false }
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
                    additionalFilters = {
                        LibrarySortSection(
                            collection = state.collection,
                            sort = draftSort,
                            onSelect = { draftSort = it },
                        )
                    },
                    onResetAdditionalFilters = { draftSort = state.collection.collection.defaultSort() },
                    onAction = { action ->
                        if (action is HomeAction.ApplyFilters) {
                            onAction(
                                LibraryAction.ApplyFilters(
                                    selection = action.selection,
                                    exactPhrase = action.exactPhrase ?: state.exactPhrase,
                                    sort = draftSort,
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
                    collapseContentDescription = stringResource(R.string.library_collapse_search),
                    clearContentDescription = stringResource(R.string.library_clear_search),
                    enabled = state.session.hasValidLibrarySession,
                )
            },
            containerColor = pageContainer,
            actions = {
                if (!searchToolbarExpanded) {
                    IconButton(
                        onClick = { filterDrawerScope.launch { filterDrawerState.open() } },
                        enabled = state.session.hasValidLibrarySession,
                    ) {
                        Icon(Icons.Outlined.FilterAlt, contentDescription = stringResource(R.string.home_open_all_filters))
                    }
                    IconButton(
                        onClick = { onAction(LibraryAction.Refresh) },
                        enabled = state.session.hasValidLibrarySession || state.session.isRestoreRetryable,
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.library_refresh))
                    }
                }
            },
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    PrimaryTabRow(
                        selectedTabIndex = pagerState.currentPage,
                        containerColor = pageContainer,
                        divider = { if (wallHubStyle().dividers.showTabDivider) WallHubHorizontalDivider() },
                    ) {
                        LibraryCollectionTab.entries.forEachIndexed { index, collection ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { pagerScope.launch { pagerState.animateScrollToPage(index) } },
                                text = { Text(collection.label()) },
                            )
                        }
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    key = { page -> LibraryCollectionTab.entries[page].name },
                    modifier = Modifier.weight(1f),
                ) { page ->
                    val collection = LibraryCollectionTab.entries[page]
                    val pageState = state.forCollection(collection)
                    val gridState =
                        if (collection == LibraryCollectionTab.SUBSCRIPTIONS) {
                            subscriptionsGridState
                        } else {
                            favoritesGridState
                        }
                    val pageAction: (LibraryAction) -> Unit = { action ->
                        onAction(
                            when (action) {
                                LibraryAction.Refresh -> LibraryAction.RefreshCollection(collection)
                                LibraryAction.LoadNextPage -> LibraryAction.LoadNextPageFor(collection)
                                LibraryAction.RetryNextPage -> LibraryAction.RetryNextPageFor(collection)
                                is LibraryAction.SelectPage -> LibraryAction.SelectCollectionPage(collection, action.page)
                                is LibraryAction.RemoveFromCollection -> action.copy(collection = collection)
                                else -> action
                            },
                        )
                    }
                    LibraryContent(
                        state = pageState,
                        gridState = gridState,
                        onAction = pageAction,
                        onOpenLogin = onOpenLogin,
                        onRemoveFromCollection = { pendingRemoval = collection to it },
                        onContextMenuActiveChanged = onContextMenuActiveChanged,
                        showSearch = false,
                        isPageActive = page == pagerState.settledPage,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
    pendingRemoval?.let { (collection, item) ->
        WallHubAlertDialog(
            onDismissRequest = { pendingRemoval = null },
            icon = { Icon(collection.icon(), contentDescription = null) },
            title = { Text(collection.removeDialogTitle()) },
            text = { Text(stringResource(R.string.library_remove_confirmation, item.localizedTitle())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        onAction(LibraryAction.RemoveFromCollection(item, collection))
                    },
                ) {
                    Text(collection.removeActionLabel())
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
    gridState: LazyGridState = rememberLazyGridState(),
    onAction: (LibraryAction) -> Unit,
    onOpenLogin: () -> Unit = {},
    onRemoveFromCollection: (WorkshopSummary) -> Unit,
    onContextMenuActiveChanged: (Boolean) -> Unit,
    showSearch: Boolean = true,
    isPageActive: Boolean = true,
    onScrollChromeCollapsedChanged: (Boolean) -> Unit = {},
    scrollToTopRequest: Int = 0,
    floatingActionButton: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val contextMenuCoordinator = rememberLibraryContextMenuCoordinator()
    var searchBoundsInContent by remember { mutableStateOf<IntRect?>(null) }
    var previousSearchQuery by remember { mutableStateOf(state.searchQuery) }
    var scrollChromeCollapsed by remember(gridState) { mutableStateOf(false) }
    var handledScrollToTopRequest by rememberSaveable {
        mutableIntStateOf(scrollToTopRequest)
    }
    var handledSearchResultGeneration by rememberSaveable {
        mutableStateOf(state.searchResultGeneration)
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
    LaunchedEffect(state.searchResultGeneration) {
        if (state.searchResultGeneration > handledSearchResultGeneration) {
            gridState.scrollToItem(0)
            updateScrollChromeCollapsed(false)
            handledSearchResultGeneration = state.searchResultGeneration
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
                        enabled = state.session.hasValidLibrarySession,
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
                    isPageActive = isPageActive,
                    onRefresh = { onAction(LibraryAction.Refresh) },
                    onClearQuery = { onAction(LibraryAction.UpdateSearchQuery("")) },
                    onResetFilters = { onAction(LibraryAction.ResetFilters) },
                    onOpenLogin = onOpenLogin,
                    onLoadNextPage = { onAction(LibraryAction.LoadNextPage) },
                    onPageSelected = { onAction(LibraryAction.SelectPage(it)) },
            onRetryNextPage = { onAction(LibraryAction.RetryNextPage) },
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
    val loadingDescription = stringResource(R.string.library_loading)
    Box(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(pass = PointerEventPass.Initial).consume()
                    }
                }
                .clearAndSetSemantics {
                    contentDescription = loadingDescription
                    liveRegion = LiveRegionMode.Polite
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                },
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
    isPageActive: Boolean,
    onRefresh: () -> Unit,
    onClearQuery: () -> Unit,
    onResetFilters: () -> Unit,
    onOpenLogin: () -> Unit,
    onLoadNextPage: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onRetryNextPage: () -> Unit,
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
    val shouldAutoLoadMore by remember(gridState, state, isPageActive) {
        derivedStateOf {
            if (
                !isPageActive ||
                state.paginationMode != HomePaginationMode.INFINITE_SCROLL ||
                state.searchQuery.trim() != state.appliedSearchQuery.trim() ||
                state.isLoading ||
                state.isLoadingMore ||
                state.appendErrorRes != null ||
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
            onClearQuery = onClearQuery,
            onResetFilters = onResetFilters,
            onOpenLogin = onOpenLogin,
            onPageSelected = onPageSelected,
            onRetryNextPage = onRetryNextPage,
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
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (state.isLoading && !state.isRefreshing) Modifier.semantics { invisibleToUser() } else Modifier),
        )
        AnimatedVisibility(
            visible =
                state.isLoading &&
                    !state.isRefreshing &&
                    state.session.hasValidLibrarySession,
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
    onClearQuery: () -> Unit,
    onResetFilters: () -> Unit,
    onOpenLogin: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onRetryNextPage: () -> Unit,
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
        !state.session.hasValidLibrarySession -> {
            WallHubEmptyState(
                icon = Icons.Outlined.BookmarkBorder,
                title = state.session.libraryMessage(),
                actionLabel =
                    if (state.session.isRestoreRetryable) {
                        stringResource(R.string.library_retry_restore)
                    } else {
                        stringResource(R.string.profile_sign_in)
                    },
                onAction = if (state.session.isRestoreRetryable) onRefresh else onOpenLogin,
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
            val emptyKind = state.emptyKind()
            WallHubEmptyState(
                icon = if (emptyKind == LibraryEmptyKind.SEARCH_NO_RESULTS) Icons.Outlined.Search else state.collection.icon(),
                title =
                    when (emptyKind) {
                        LibraryEmptyKind.SEARCH_NO_RESULTS -> stringResource(R.string.library_empty_search)
                        LibraryEmptyKind.FILTERED_NO_RESULTS -> stringResource(R.string.library_empty_filtered)
                        LibraryEmptyKind.TRUE_EMPTY ->
                            if (state.collection == LibraryCollectionTab.SUBSCRIPTIONS) {
                                stringResource(R.string.library_empty_true_subscriptions)
                            } else {
                                stringResource(R.string.library_empty_true_favorites)
                            }
                    },
                actionLabel =
                    when (emptyKind) {
                        LibraryEmptyKind.SEARCH_NO_RESULTS -> stringResource(R.string.library_clear_search)
                        LibraryEmptyKind.FILTERED_NO_RESULTS -> stringResource(R.string.home_reset)
                        LibraryEmptyKind.TRUE_EMPTY -> stringResource(R.string.library_refresh)
                    },
                onAction =
                    when (emptyKind) {
                        LibraryEmptyKind.SEARCH_NO_RESULTS -> onClearQuery
                        LibraryEmptyKind.FILTERED_NO_RESULTS -> onResetFilters
                        LibraryEmptyKind.TRUE_EMPTY -> onRefresh
                    },
                modifier = modifier.refreshableEmptyState(),
            )
        }

        else -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                state = gridState,
                modifier = modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = WallHubSpacing.md,
                        top = WallHubSpacing.sm,
                        end = WallHubSpacing.md,
                        bottom = WallHubSpacing.xs,
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
                        canRetryNextLibraryPage(state) ->
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(WallHubSpacing.sm)
                                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(stringResource(requireNotNull(state.appendErrorRes)))
                                TextButton(onClick = onRetryNextPage) {
                                    Text(stringResource(R.string.library_retry_next_page))
                                }
                            }

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
    ) {
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
                        lineHeight = 14.sp,
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
                            value = item.subscriptions?.let(::formatCompactCount) ?: "—",
                            contentDescription = stringResource(R.string.library_subscriptions_count),
                            textStyle = statisticsStyle,
                            iconSize = statisticsMetrics.iconSize,
                            iconSpacing = statisticsMetrics.iconSpacing,
                            modifier = Modifier,
                        )
                        LibraryWorkshopCardStatistic(
                            icon = Icons.Outlined.StarBorder,
                            value = item.favorites?.let(::formatCompactCount) ?: "—",
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
            val fontSize = (statisticSlotWidth / 5.5f).coerceIn(10.5f, 11.5f)
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
    }

private fun LibraryCollectionTab.icon() =
    when (this) {
        LibraryCollectionTab.SUBSCRIPTIONS -> Icons.Outlined.BookmarkBorder
        LibraryCollectionTab.FAVORITES -> Icons.Outlined.StarBorder
    }

@Composable
private fun LibraryCollectionTab.removeActionLabelOrNull(): String? =
    when (this) {
        LibraryCollectionTab.SUBSCRIPTIONS -> stringResource(R.string.library_unsubscribe)
        LibraryCollectionTab.FAVORITES -> stringResource(R.string.library_remove_favorite)
    }

@Composable
private fun LibraryCollectionTab.removeActionLabel(): String =
    requireNotNull(removeActionLabelOrNull())

@Composable
private fun LibraryCollectionTab.removeDialogTitle(): String =
    when (this) {
        LibraryCollectionTab.SUBSCRIPTIONS -> stringResource(R.string.library_unsubscribe_title)
        LibraryCollectionTab.FAVORITES -> stringResource(R.string.library_remove_favorite_title)
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

/**
 * The shortest gap between two *automatic* restore attempts.
 *
 * A failed restore leaves the state retry-eligible so the user can ask again, but [refresh] runs at UI rate:
 * without a floor, one bad network produced a fresh logon against Steam on every refresh. The floor is on the
 * automatic path only - a tap on the restore affordance calls the repository directly and is never delayed.
 */
internal const val RESTORE_RETRY_MIN_INTERVAL_MS = 10_000L

/**
 * Whether a refresh may start another restore attempt now.
 *
 * [SteamSessionState.updatedAt] moves on every publish, so it is the time of the failure that made the state
 * retry-eligible: a state younger than the floor is one that was just attempted.
 */
internal fun SteamSessionState.shouldAutoRestore(nowMs: Long): Boolean =
    shouldRestoreSession(explicit = false, nowMs = nowMs)

internal fun SteamSessionState.shouldRestoreSession(
    explicit: Boolean,
    nowMs: Long,
): Boolean =
    if (explicit) {
        hasStoredSession && phase != SteamSessionPhase.SIGNED_IN
    } else {
        isRestoreRetryable && nowMs - updatedAt >= RESTORE_RETRY_MIN_INTERVAL_MS
    }

@Composable
private fun WorkshopType.label(): String =
    when (this) {
        WorkshopType.VIDEO -> stringResource(R.string.library_type_video)
        WorkshopType.SCENE -> stringResource(R.string.library_type_scene)
        WorkshopType.WEB -> stringResource(R.string.library_type_web)
        WorkshopType.UNKNOWN -> stringResource(R.string.library_type_wallpaper)
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
