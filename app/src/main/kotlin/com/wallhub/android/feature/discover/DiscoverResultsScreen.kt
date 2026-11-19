@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.discover

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil.compose.AsyncImage
import com.wallhub.android.NAVIGATION_EASING
import com.wallhub.android.DISCOVER_GROUP_SEPARATOR
import com.wallhub.android.core.designsystem.wallHubStyle
import com.wallhub.android.DISCOVER_VALUE_SEPARATOR
import com.wallhub.android.DiscoverResultsDestination
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.LocalWallHubOverlayBottomClearance
import com.wallhub.android.core.designsystem.WallHubActionSheetAction
import com.wallhub.android.core.designsystem.WallHubActionSheetActionEmphasis
import com.wallhub.android.core.designsystem.WallHubActionSheetHeader
import com.wallhub.android.core.designsystem.WallHubActionSheetHost
import com.wallhub.android.core.designsystem.WallHubActionSheetMetadataItem
import com.wallhub.android.core.designsystem.WallHubActionSheetStat
import com.wallhub.android.core.designsystem.WallHubActionSheetTrigger
import com.wallhub.android.core.designsystem.WallHubModalBottomSheet
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.rememberWallHubActionSheetState
import com.wallhub.android.core.format.formatCompactCount
import com.wallhub.android.core.designsystem.localizedAuthor
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.designsystem.requiresLegacyPublicDownloadPermission
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.DownloadEnqueueResult
import com.wallhub.android.core.model.WorkshopDownloads
import com.wallhub.android.core.model.toQuickDownloadRequest
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.core.model.matchesSteamWallpaper
import com.wallhub.android.feature.home.DEFAULT_HOME_GENRE_SELECTION
import com.wallhub.android.feature.home.DEFAULT_HOME_RESOLUTION_SELECTION
import com.wallhub.android.feature.home.HomeAction
import com.wallhub.android.feature.home.HomeFilterDrawer
import com.wallhub.android.feature.home.HomeFilterSelection
import com.wallhub.android.feature.home.HomeUiState
import com.wallhub.android.feature.home.HomeViewMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewAgenda

private enum class DiscoverResultsPresentation {
    SignInRequired,
    RestoringSession,
    Loading,
    Error,
    Empty,
    Content,
}

data class DiscoverResultsState(
    val items: List<WorkshopSummary> = emptyList(),
    val resultColumns: Int = DISCOVER_RESULTS_GRID_COLUMNS,
    val filterSelection: HomeFilterSelection = HomeFilterSelection.defaults(),
    val exactPhrase: Boolean = false,
    val matureContentEnabled: Boolean = false,
    val initialized: Boolean = false,
    val isLoading: Boolean = false,
    val page: Int = 0,
    val hasMore: Boolean = true,
    val error: String? = null,
    val requiresSteamLogin: Boolean = false,
    val isRestoringSteamSession: Boolean = false,
)

internal fun DiscoverResultsState.withMatureContentPolicy(enabled: Boolean): DiscoverResultsState =
    copy(
        items = emptyList(),
        filterSelection = filterSelection.normalized(enabled),
        matureContentEnabled = enabled,
        isLoading = false,
        page = 0,
        hasMore = true,
        error = null,
    )

internal data class DiscoverResultsRequest(
    val generation: Long,
    val destination: DiscoverResultsDestination,
) {
    fun isCurrent(
        currentGeneration: Long,
        currentDestination: DiscoverResultsDestination,
    ): Boolean = generation == currentGeneration && destination == currentDestination
}

sealed interface DiscoverResultsEffect {
    data class ShowMessage(
        val messageRes: Int,
        val formatArgs: List<Any> = emptyList(),
    ) : DiscoverResultsEffect
}

@HiltViewModel
class DiscoverResultsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        @ApplicationContext private val applicationContext: Context,
        private val pageSource: DiscoverPageSource,
        private val downloadTaskRepository: WorkshopDownloads,
        private val settingsRepository: SettingsRepository,
        private val steamSessionRepository: SteamSessionRepository,
    ) : ViewModel() {
        internal val destination = savedStateHandle.toRoute<DiscoverResultsDestination>()
        private val mutableState = MutableStateFlow(DiscoverResultsState())
        val state: StateFlow<DiscoverResultsState> = mutableState.asStateFlow()
        private var loadJob: Job? = null
        private var requestGeneration = 0L
        private val effectChannel = Channel<DiscoverResultsEffect>(Channel.BUFFERED)
        val effects: Flow<DiscoverResultsEffect> = effectChannel.receiveAsFlow()

        init {
            viewModelScope.launch {
                settingsRepository.preferences
                    .map { preferences -> preferences.discoverResultsColumns.coerceIn(1, 2) }
                    .distinctUntilChanged()
                    .collect { columns ->
                        mutableState.value = mutableState.value.copy(resultColumns = columns)
                    }
            }
            viewModelScope.launch {
                var previousPolicy: Boolean? = null
                settingsRepository.preferences
                    .map { it.matureContentEnabled }
                    .distinctUntilChanged()
                    .collect { matureContentEnabled ->
                        val current = mutableState.value
                        if (!current.initialized) {
                            previousPolicy = matureContentEnabled
                            return@collect
                        }
                        val changed = previousPolicy != null && previousPolicy != matureContentEnabled
                        previousPolicy = matureContentEnabled
                        if (!changed) return@collect
                        requestGeneration += 1L
                        loadJob?.cancel()
                        mutableState.value = current.withMatureContentPolicy(matureContentEnabled)
                        loadNextPage()
                    }
            }
            viewModelScope.launch {
                val preferences = settingsRepository.preferences.first()
                mutableState.value =
                    mutableState.value.copy(
                        filterSelection = destination.initialFilterSelection(preferences.matureContentEnabled),
                        exactPhrase = destination.exactPhrase,
                        matureContentEnabled = preferences.matureContentEnabled,
                        initialized = true,
                    )
                if (!destination.usesPcFriendActivitySemantics()) {
                    loadNextPage()
                    return@launch
                }
                var restoreRequested = false
                steamSessionRepository.session.collect { session ->
                    when (val access = friendDiscoverAccess(mutableState.value.filterSelection.sort, session)) {
                        FriendDiscoverAccess.LOGIN_REQUIRED -> {
                            loadJob?.cancel()
                            mutableState.value = mutableState.value.withFriendAccess(access)
                        }
                        FriendDiscoverAccess.RESTORE_ACCOUNT -> {
                            loadJob?.cancel()
                            mutableState.value = mutableState.value.withFriendAccess(access)
                            if (!restoreRequested) {
                                restoreRequested = true
                                steamSessionRepository.restorePersistedSession()
                            }
                        }
                        FriendDiscoverAccess.ACCOUNT_READY -> {
                            restoreRequested = false
                            val shouldLoad =
                                mutableState.value.requiresSteamLogin ||
                                    mutableState.value.isRestoringSteamSession ||
                                    mutableState.value.page == 0
                            mutableState.value = mutableState.value.withFriendAccess(access)
                            if (shouldLoad) loadNextPage()
                        }
                        FriendDiscoverAccess.PUBLIC -> Unit
                    }
                }
            }
        }

        fun retry() {
            val current = mutableState.value
            if (!current.initialized) return
            if (current.items.isEmpty()) {
                mutableState.value = current.copy(page = 0, hasMore = true, isLoading = false, error = null)
            }
            loadNextPage()
        }

        fun applyFilters(
            selection: HomeFilterSelection,
            exactPhrase: Boolean,
        ) {
            val current = mutableState.value
            val normalized = selection.normalized(current.matureContentEnabled)
            val effective =
                if (destination.supportsResultSortFiltering()) {
                    normalized
                } else {
                    normalized.copy(
                        sort = current.filterSelection.sort,
                        days = current.filterSelection.days,
                    )
                }
            if (current.filterSelection == effective && current.exactPhrase == exactPhrase) return
            requestGeneration++
            loadJob?.cancel()
            mutableState.value =
                current.copy(
                    items = emptyList(),
                    filterSelection = effective,
                    exactPhrase = exactPhrase,
                    isLoading = false,
                    page = 0,
                    hasMore = true,
                    error = null,
                )
            loadNextPage()
        }

        fun setResultColumns(columns: Int) {
            val normalized = columns.coerceIn(1, 2)
            if (mutableState.value.resultColumns == normalized) return
            mutableState.value = mutableState.value.copy(resultColumns = normalized)
            viewModelScope.launch { settingsRepository.setDiscoverResultsColumns(normalized) }
        }

        fun loadNextPage() {
            val current = mutableState.value
            if (
                !current.initialized || current.isLoading || !current.hasMore ||
                current.requiresSteamLogin || current.isRestoringSteamSession
            ) return
            val nextPage = current.page + 1
            val request = DiscoverResultsRequest(requestGeneration, destination)
            loadJob =
                viewModelScope.launch {
                    if (!request.isCurrent(requestGeneration, destination)) return@launch
                    mutableState.value = current.copy(isLoading = true, error = null)
                    try {
                        val browseQuery =
                            destination.toWorkshopQuery(
                                page = nextPage,
                                matureContentEnabled = current.matureContentEnabled,
                                filterSelection = current.filterSelection,
                                exactPhraseOverride = current.exactPhrase,
                            )
                        val result =
                            pageSource.page(
                                recipe = DiscoverPageRecipe(query = browseQuery, collectionId = destination.collectionId),
                                page = nextPage,
                                pageSize = RESULTS_PAGE_SIZE,
                            )
                        currentCoroutineContext().ensureActive()
                        if (!request.isCurrent(requestGeneration, destination)) return@launch
                        val filtered =
                            request.destination
                                .filterRequiredTagGroups(result.items)
                                .let { items ->
                                    if (request.destination.collectionId == null) items else items.filter(browseQuery::matchesSteamWallpaper)
                                }
                        currentCoroutineContext().ensureActive()
                        if (!request.isCurrent(requestGeneration, destination)) return@launch
                        val latest = mutableState.value
                        mutableState.value =
                            latest.copy(
                                items = (latest.items + filtered).distinctBy(WorkshopSummary::id),
                                isLoading = false,
                                page = nextPage,
                                hasMore = result.hasNextPage,
                                error = null,
                            )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        currentCoroutineContext().ensureActive()
                        if (!request.isCurrent(requestGeneration, destination)) return@launch
                        mutableState.value =
                            mutableState.value.copy(
                                isLoading = false,
                                error = error.message ?: "discover_results_failed",
                            )
                    }
                }
        }

        fun download(item: WorkshopSummary) {
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
                    effectChannel.send(
                        DiscoverResultsEffect.ShowMessage(
                            if (result is DownloadEnqueueResult.Created) {
                                R.string.home_added_to_download_queue
                            } else {
                                R.string.download_already_active
                            },
                            listOf(result.task.title),
                        ),
                    )
                }.onFailure {
                    effectChannel.send(DiscoverResultsEffect.ShowMessage(R.string.home_unable_to_queue_download))
                }
            }
        }
    }

@Composable
fun DiscoverResultsRoute(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
    onOpenDownloads: () -> Unit = {},
    onOpenLogin: () -> Unit = {},
    viewModel: DiscoverResultsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val contextMenuState = rememberWallHubActionSheetState<WorkshopSummary>()
    val context = LocalContext.current
    val resources = LocalResources.current
    val currentOpenDetail by rememberUpdatedState(onOpenDetail)
    val currentSearchAuthor by rememberUpdatedState(onSearchAuthor)
    val currentOpenDownloads by rememberUpdatedState(onOpenDownloads)
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDownload by remember { mutableStateOf<WorkshopSummary?>(null) }
    val filterDrawerState = rememberDrawerState(DrawerValue.Closed)
    val filterDrawerScope = rememberCoroutineScope()
    val storagePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val item = pendingDownload ?: return@rememberLauncherForActivityResult
            pendingDownload = null
            if (granted) {
                viewModel.download(item)
            } else {
                Toast.makeText(context, R.string.home_storage_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    LaunchedEffect(viewModel, context, resources) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DiscoverResultsEffect.ShowMessage -> {
                    val message = resources.getString(effect.messageRes, *effect.formatArgs.toTypedArray())
                    if (effect.messageRes == R.string.home_added_to_download_queue) {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        val result =
                            snackbarHostState.showSnackbar(
                                message = message,
                                actionLabel = resources.getString(R.string.downloads_view_queue),
                                duration = SnackbarDuration.Long,
                            )
                        if (result == SnackbarResult.ActionPerformed) currentOpenDownloads()
                    } else {
                        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    LaunchedEffect(gridState, state.items.size, state.hasMore, state.isLoading) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .map { it >= (gridState.layoutInfo.totalItemsCount - 4).coerceAtLeast(0) }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) viewModel.loadNextPage() }
    }
    ModalNavigationDrawer(
        drawerState = filterDrawerState,
        gesturesEnabled = filterDrawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxHeight().widthIn(max = 420.dp)) {
                HomeFilterDrawer(
                    state = state.asHomeFilterUiState(viewModel.destination),
                    isOpen = filterDrawerState.isOpen,
                    showSortAndTime = viewModel.destination.supportsResultSortFiltering(),
                    showExactPhrase = viewModel.destination.searchText.isNotBlank(),
                    title = stringResource(R.string.discover_result_filters),
                    onAction = { action ->
                        if (action is HomeAction.ApplyFilters) {
                            viewModel.applyFilters(
                                selection = action.selection,
                                exactPhrase = action.exactPhrase ?: state.exactPhrase,
                            )
                        }
                    },
                    onDismiss = { filterDrawerScope.launch { filterDrawerState.close() } },
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            WallHubPageScaffold(
                title = discoverResultsTitle(viewModel.destination, state.items.firstOrNull()?.author),
                showBackButton = true,
                onNavigateUp = onBack,
                actions = {
                    if (viewModel.destination.filtersEnabled) {
                        IconButton(onClick = { filterDrawerScope.launch { filterDrawerState.open() } }) {
                            Icon(Icons.Outlined.FilterAlt, stringResource(R.string.home_open_all_filters))
                        }
                    }
                    DiscoverResultsViewModeToggle(
                        columns = state.resultColumns,
                        onColumnsChanged = { targetColumns -> viewModel.setResultColumns(targetColumns) },
                    )
                },
            ) { padding ->
                    val presentation =
                        when {
                            state.items.isEmpty() && state.requiresSteamLogin -> DiscoverResultsPresentation.SignInRequired
                            state.items.isEmpty() && state.isRestoringSteamSession -> DiscoverResultsPresentation.RestoringSession
                            state.items.isEmpty() && state.isLoading -> DiscoverResultsPresentation.Loading
                            state.items.isEmpty() && state.error != null -> DiscoverResultsPresentation.Error
                            state.items.isEmpty() -> DiscoverResultsPresentation.Empty
                            else -> DiscoverResultsPresentation.Content
                        }
                    Crossfade(
                        targetState = presentation,
                        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        modifier = Modifier.fillMaxSize(),
                        label = "DiscoverResultsPresentation",
                    ) { targetPresentation ->
                        when (targetPresentation) {
                        DiscoverResultsPresentation.SignInRequired ->
                            WallHubEmptyState(
                                icon = Icons.Outlined.PersonOutline,
                                title = stringResource(R.string.discover_friend_sign_in_required),
                                actionLabel = stringResource(R.string.discover_friend_sign_in_action),
                                onAction = onOpenLogin,
                                modifier = Modifier.fillMaxSize().padding(padding),
                            )
                        DiscoverResultsPresentation.RestoringSession ->
                            WallHubEmptyState(
                                icon = Icons.Outlined.PersonOutline,
                                title = stringResource(R.string.discover_friend_restoring_sign_in),
                                modifier = Modifier.fillMaxSize().padding(padding),
                            )
                        DiscoverResultsPresentation.Loading ->
                            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        DiscoverResultsPresentation.Error ->
                            WallHubEmptyState(
                                icon = Icons.Outlined.ErrorOutline,
                                title = stringResource(R.string.discover_results_load_failed),
                                actionLabel = stringResource(R.string.discover_retry),
                                onAction = viewModel::retry,
                                modifier = Modifier.fillMaxSize().padding(padding),
                            )
                        DiscoverResultsPresentation.Empty ->
                            WallHubEmptyState(
                                icon = Icons.Outlined.Collections,
                                title = stringResource(R.string.discover_rail_empty),
                                actionLabel = stringResource(R.string.discover_retry),
                                onAction = viewModel::retry,
                                modifier = Modifier.fillMaxSize().padding(padding),
                            )
                        DiscoverResultsPresentation.Content -> {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(state.resultColumns),
                                state = gridState,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(padding),
                                contentPadding =
                                    PaddingValues(
                                        start = 16.dp,
                                        top = 12.dp,
                                        end = 16.dp,
                                        bottom = LocalWallHubOverlayBottomClearance.current + 16.dp,
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(DISCOVER_RESULTS_GRID_SPACING),
                                verticalArrangement = Arrangement.spacedBy(DISCOVER_RESULTS_GRID_SPACING),
                            ) {
                                itemsIndexed(state.items, key = { _, item -> item.id }) { _, item ->
                                    val itemTitle = item.localizedTitle()
                                    val itemAuthor = item.localizedAuthor()
                                    WallHubActionSheetTrigger(
                                        state = contextMenuState,
                                        item = item,
                                        onClick = { currentOpenDetail(item.id) },
                                        clickLabel = stringResource(R.string.home_view_details),
                                        longClickLabel = stringResource(R.string.home_open_actions_menu),
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .animateItem(
                                                    placementSpec =
                                                        spring(
                                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                                            stiffness = Spring.StiffnessMediumLow,
                                                        ),
                                                ),
                                    ) {
                                        val cardStyle = wallHubStyle()
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = cardStyle.surfaces.browseCard),
                                            elevation = CardDefaults.cardElevation(defaultElevation = cardStyle.elevations.card),
                                            shape = cardStyle.shapes.card,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            AsyncImage(
                                                model = item.previewUrl,
                                                contentDescription = itemTitle,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                                            )
                                            Text(
                                                itemTitle,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
                                            )
                                            Text(
                                                itemAuthor,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
                                            )
                                        }
                                    }
                                }
                                if (state.isLoading) {
                                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            WallHubActionSheetHost(state = contextMenuState) { item ->
                DiscoverResultsActionSheet(
                    item = item,
                    onDismiss = contextMenuState::dismiss,
                    onOpenDetail = currentOpenDetail,
                    onSearchAuthor = currentSearchAuthor,
                    onDownload = { requested ->
                        if (context.requiresLegacyPublicDownloadPermission()) {
                            pendingDownload = requested
                            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            viewModel.download(requested)
                        }
                    },
                )
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverResultsActionSheet(
    item: WorkshopSummary,
    onDismiss: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
    onDownload: (WorkshopSummary) -> Unit,
) {
    val title = item.localizedTitle()
    val author = item.localizedAuthor()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    WallHubModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            WallHubActionSheetHeader(
                previewModel = item.previewUrl,
                title = title,
                subtitle = author,
                contentDescription = title,
                stats =
                    buildList {
                        item.subscriptions?.let { count ->
                            add(WallHubActionSheetStat(formatCompactCount(count), Icons.Outlined.PeopleOutline))
                        }
                        item.favorites?.let { count ->
                            add(WallHubActionSheetStat(formatCompactCount(count), Icons.Outlined.FavoriteBorder))
                        }
                        item.views?.let { count ->
                            add(WallHubActionSheetStat(formatCompactCount(count), Icons.Outlined.Visibility))
                        }
                    },
            )
            WallHubActionSheetMetadataItem(
                label = stringResource(R.string.home_wallpaper_title),
                value = title,
                icon = Icons.Outlined.ContentCopy,
                onClick = {
                    clipboard.setText(AnnotatedString(title))
                    Toast.makeText(context, R.string.home_wallpaper_title_copied, Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
            )
            WallHubActionSheetMetadataItem(
                label = stringResource(R.string.home_author),
                value = author,
                icon = Icons.Outlined.PersonOutline,
                onClick = {
                    onDismiss()
                    onSearchAuthor(item.creatorId ?: item.author)
                },
            )
            WallHubActionSheetMetadataItem(
                label = stringResource(R.string.home_project_id),
                value = item.id.toString(),
                icon = Icons.Outlined.ContentCopy,
                onClick = {
                    clipboard.setText(AnnotatedString(item.id.toString()))
                    Toast.makeText(context, R.string.home_project_id_copied, Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
            )
            WallHubActionSheetAction(
                text = stringResource(R.string.home_download),
                icon = Icons.Outlined.Download,
                emphasis = WallHubActionSheetActionEmphasis.Primary,
                onClick = {
                    onDismiss()
                    onDownload(item)
                },
            )
            if (item.type == WorkshopType.VIDEO) {
                WallHubActionSheetAction(
                    text = stringResource(R.string.home_open_video_details),
                    icon = Icons.Outlined.PlayArrow,
                    onClick = {
                        onDismiss()
                        onOpenDetail(item.id)
                    },
                )
            }
            WallHubActionSheetAction(
                text = stringResource(R.string.home_open_in_steam),
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                onClick = {
                    onDismiss()
                    val intent =
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://steamcommunity.com/sharedfiles/filedetails/?id=${item.id}"),
                        )
                    runCatching { context.startActivity(intent) }
                        .onFailure { onOpenDetail(item.id) }
                },
            )
        }
    }
}

@Composable
private fun DiscoverResultsViewModeToggle(
    columns: Int,
    onColumnsChanged: (Int) -> Unit,
) {
    SingleChoiceSegmentedButtonRow {
        SegmentedButton(
            selected = columns == DISCOVER_RESULTS_LARGE_COLUMNS,
            onClick = { onColumnsChanged(DISCOVER_RESULTS_LARGE_COLUMNS) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = {
                Icon(
                    imageVector = Icons.Outlined.ViewAgenda,
                    contentDescription = stringResource(R.string.discover_results_large_view),
                )
            },
        ) {}
        SegmentedButton(
            selected = columns == DISCOVER_RESULTS_GRID_COLUMNS,
            onClick = { onColumnsChanged(DISCOVER_RESULTS_GRID_COLUMNS) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = {
                Icon(
                    imageVector = Icons.Outlined.GridView,
                    contentDescription = stringResource(R.string.discover_results_grid_view),
                )
            },
        ) {}
    }
}

@Composable
private fun discoverResultsTitle(
    destination: DiscoverResultsDestination,
    resolvedAuthor: String?,
): String =
    when (destination.titleKind) {
        DiscoverTitleKind.RECENT_APPROVED.name -> stringResource(R.string.discover_rail_recent_positive)
        DiscoverTitleKind.TRENDING_MONTH.name -> stringResource(R.string.discover_rail_trending)
        DiscoverTitleKind.TRENDING_YEAR.name -> stringResource(R.string.discover_rail_trending_year)
        DiscoverTitleKind.MOBILE.name -> stringResource(R.string.discover_rail_mobile)
        DiscoverTitleKind.AUDIO_RESPONSIVE.name -> stringResource(R.string.discover_rail_audio_responsive)
        DiscoverTitleKind.CREATOR.name ->
            destination.titleArgument
                ?.takeUnless { it == destination.creatorId }
                ?: resolvedAuthor
                ?: stringResource(R.string.discover_rail_creator)
        DiscoverTitleKind.COLLECTION.name -> stringResource(R.string.discover_rail_collection)
        DiscoverTitleKind.GENRE.name -> stringResource(R.string.discover_rail_genre, destination.titleArgument.orEmpty())
        DiscoverTitleKind.TOP_YEAR.name -> stringResource(R.string.discover_top_year, destination.titleArgument.orEmpty())
        DiscoverTitleKind.SEASONAL_SPRING.name -> stringResource(R.string.discover_seasonal_spring)
        DiscoverTitleKind.SEASONAL_SUMMER.name -> stringResource(R.string.discover_seasonal_summer)
        DiscoverTitleKind.SEASONAL_FALL.name -> stringResource(R.string.discover_seasonal_fall)
        DiscoverTitleKind.SEASONAL_HALLOWEEN.name -> stringResource(R.string.discover_seasonal_halloween)
        DiscoverTitleKind.SEASONAL_WINTER.name -> stringResource(R.string.discover_seasonal_winter)
        DiscoverTitleKind.FRIEND_FAVORITES.name -> stringResource(R.string.discover_friend_favorites)
        DiscoverTitleKind.FRIEND_CREATED.name -> stringResource(R.string.discover_friend_created)
        else -> stringResource(R.string.discover_rail_keyword, destination.titleArgument.orEmpty())
    }

internal fun DiscoverResultsDestination.toWorkshopQuery(
    page: Int,
    matureContentEnabled: Boolean? = null,
    filterSelection: HomeFilterSelection? = null,
    exactPhraseOverride: Boolean? = null,
): WorkshopBrowseQuery {
    val resolvedSort = WorkshopSort.entries.firstOrNull { it.name == sort } ?: WorkshopSort.TRENDING
    val usePcFriendActivitySemantics = usesPcFriendActivitySemantics()
    val resolvedMatureContent = matureContentEnabled ?: allowNsfw
    val filters = filterSelection ?: initialFilterSelection(resolvedMatureContent)
    return WorkshopBrowseQuery(
        page = page,
        pageSize = RESULTS_PAGE_SIZE,
        searchText = searchText,
        creatorId = creatorId,
        types = filters.types,
        tags = tags.splitValues().toSet(),
        excludedTags = excludedTags.splitValues().toSet(),
        genres = filters.genres,
        officialTags = filters.officialTags,
        excludedOfficialTags = filters.excludedOfficialTags,
        resolutions = filters.resolutions,
        requiredTagGroups = requiredTagGroups.splitGroups(),
        ratings = filters.ratings,
        days = if (usePcFriendActivitySemantics) days else filters.days,
        exactPhrase = exactPhraseOverride ?: exactPhrase,
        sort = if (usePcFriendActivitySemantics) resolvedSort else filters.sort,
        allowNsfw = resolvedMatureContent,
        mobileCompatibleOnly = mobileCompatibleOnly,
        createdAfterEpochSeconds = createdAfterEpochSeconds,
        createdBeforeEpochSeconds = createdBeforeEpochSeconds,
    )
}

internal enum class FriendDiscoverAccess {
    PUBLIC,
    LOGIN_REQUIRED,
    RESTORE_ACCOUNT,
    ACCOUNT_READY,
}

internal fun friendDiscoverAccess(
    sort: WorkshopSort,
    session: SteamSessionState,
): FriendDiscoverAccess {
    if (sort != WorkshopSort.FRIENDS_FAVORITES && sort != WorkshopSort.FRIENDS_CREATED) {
        return FriendDiscoverAccess.PUBLIC
    }
    return when {
        session.phase == SteamSessionPhase.SIGNED_IN && session.isConnected -> FriendDiscoverAccess.ACCOUNT_READY
        session.hasStoredSession || session.phase == SteamSessionPhase.RESTORABLE ||
            session.phase == SteamSessionPhase.SIGNING_IN -> FriendDiscoverAccess.RESTORE_ACCOUNT
        else -> FriendDiscoverAccess.LOGIN_REQUIRED
    }
}

internal fun DiscoverResultsState.withFriendAccess(access: FriendDiscoverAccess): DiscoverResultsState =
    when (access) {
        FriendDiscoverAccess.PUBLIC -> copy(
            requiresSteamLogin = false,
            isRestoringSteamSession = false,
        )
        FriendDiscoverAccess.ACCOUNT_READY -> copy(
            requiresSteamLogin = false,
            isRestoringSteamSession = false,
            hasMore = hasMore || requiresSteamLogin || isRestoringSteamSession,
        )
        FriendDiscoverAccess.LOGIN_REQUIRED -> copy(
            items = emptyList(),
            isLoading = false,
            page = 0,
            hasMore = false,
            error = null,
            requiresSteamLogin = true,
            isRestoringSteamSession = false,
        )
        FriendDiscoverAccess.RESTORE_ACCOUNT -> copy(
            items = emptyList(),
            isLoading = false,
            page = 0,
            hasMore = false,
            error = null,
            requiresSteamLogin = false,
            isRestoringSteamSession = true,
        )
    }

internal fun DiscoverResultsDestination.usesPcFriendActivitySemantics(): Boolean =
    sort == WorkshopSort.FRIENDS_FAVORITES.name || sort == WorkshopSort.FRIENDS_CREATED.name

internal fun DiscoverResultsDestination.supportsResultSortFiltering(): Boolean =
    collectionId == null && !usesPcFriendActivitySemantics()

internal fun DiscoverResultsDestination.initialFilterSelection(matureContentEnabled: Boolean): HomeFilterSelection {
    val resolvedSort = WorkshopSort.entries.firstOrNull { it.name == sort } ?: WorkshopSort.TRENDING
    val resolvedTypes =
        types.splitValues().mapNotNullTo(linkedSetOf()) { name -> WorkshopType.entries.firstOrNull { it.name == name } }
    val resolvedRatings =
        ratings.splitValues().mapNotNullTo(linkedSetOf()) { name -> WorkshopRating.entries.firstOrNull { it.name == name } }
            .ifEmpty {
                if (usesPcFriendActivitySemantics()) {
                    WorkshopRating.entries
                        .filterTo(linkedSetOf()) { rating ->
                            rating != WorkshopRating.ALL && (matureContentEnabled || rating != WorkshopRating.MATURE)
                        }
                } else {
                    setOf(WorkshopRating.EVERYONE)
                }
            }
    return HomeFilterSelection(
        sort = resolvedSort,
        days = days,
        types = resolvedTypes,
        ratings = resolvedRatings,
        genres = genres.splitValues().toSet().ifEmpty { DEFAULT_HOME_GENRE_SELECTION },
        officialTags = officialTags.splitValues().toSet(),
        excludedOfficialTags = excludedOfficialTags.splitValues().toSet(),
        resolutions = resolutions.splitValues().toSet().ifEmpty { DEFAULT_HOME_RESOLUTION_SELECTION },
    ).normalized(matureContentEnabled)
}

private fun DiscoverResultsState.asHomeFilterUiState(destination: DiscoverResultsDestination): HomeUiState =
    HomeUiState(
        exactPhrase = exactPhrase,
        selectedTypes = filterSelection.types,
        selectedRatings = filterSelection.ratings,
        selectedGenres = filterSelection.genres,
        selectedOfficialTags = filterSelection.officialTags,
        selectedExcludedOfficialTags = filterSelection.excludedOfficialTags,
        selectedResolutions = filterSelection.resolutions,
        sort = filterSelection.sort.takeIf { destination.supportsResultSortFiltering() } ?: WorkshopSort.TRENDING,
        days = filterSelection.days,
        matureContentEnabled = matureContentEnabled,
    )

private fun DiscoverResultsDestination.filterRequiredTagGroups(items: List<WorkshopSummary>): List<WorkshopSummary> {
    val groups = requiredTagGroups.splitGroups()
    if (groups.isEmpty()) return items
    return items.filter { item ->
        val itemTags = item.tags.mapTo(hashSetOf()) { it.lowercase() }
        groups.all { group -> group.any { it.lowercase() in itemTags } }
    }
}

private fun String.splitValues(): List<String> =
    split(DISCOVER_VALUE_SEPARATOR).map(String::trim).filter(String::isNotEmpty)

private fun String.splitGroups(): List<Set<String>> =
    split(DISCOVER_GROUP_SEPARATOR).map(String::splitValues).filter(List<String>::isNotEmpty).map(List<String>::toSet)

private const val RESULTS_PAGE_SIZE = 20
private const val DISCOVER_RESULTS_LARGE_COLUMNS = 1
private const val DISCOVER_RESULTS_GRID_COLUMNS = 2
private val DISCOVER_RESULTS_TOGGLE_INSET = 3.dp
private val DISCOVER_RESULTS_TOGGLE_BUTTON_SIZE = 42.dp
private val DISCOVER_RESULTS_TOGGLE_HEIGHT = 48.dp
private val DISCOVER_RESULTS_TOGGLE_WIDTH = 90.dp
private const val DISCOVER_RESULTS_TOGGLE_MOTION_MS = 220
private val DISCOVER_RESULTS_GRID_SPACING = 12.dp
