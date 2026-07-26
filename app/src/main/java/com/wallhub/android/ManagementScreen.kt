package com.wallhub.android

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallhub.android.core.designsystem.LocalWallHubLanguage
import com.wallhub.android.core.designsystem.WallHubFabActiveElevation
import com.wallhub.android.core.designsystem.WallHubFabDefaultElevation
import com.wallhub.android.core.designsystem.WallHubFilterChip
import com.wallhub.android.core.designsystem.WallHubIcons as Icons
import com.wallhub.android.core.designsystem.WallHubSlidingSingleChoiceControl
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.requiresLegacyPublicDownloadPermission
import com.wallhub.android.feature.downloads.DownloadFilter
import com.wallhub.android.feature.downloads.DownloadTypeFilter
import com.wallhub.android.feature.downloads.DownloadsContent
import com.wallhub.android.feature.downloads.DownloadsUiState
import com.wallhub.android.feature.downloads.DownloadsViewModel
import com.wallhub.android.feature.library.LibraryCollectionTab
import com.wallhub.android.feature.library.LibraryContent
import com.wallhub.android.feature.library.LibraryTypeFilter
import com.wallhub.android.feature.library.LibraryUiState
import com.wallhub.android.feature.library.LibraryViewModel
import com.wallhub.android.feature.local.LocalWallpaperFormatFilter
import com.wallhub.android.feature.local.LocalWallpaperImportFilter
import com.wallhub.android.feature.local.LocalWallpaperRoute
import com.wallhub.android.feature.local.LocalWallpaperSort
import com.wallhub.android.feature.local.LocalWallpaperUiState
import com.wallhub.android.feature.local.LocalWallpaperViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class ManagementContent {
    DOWNLOADS,
    LIBRARY,
    LOCAL,
}

@Composable
fun ManagementRoute(
    onOpenDetail: (Long) -> Unit,
    onOpenLocalVideo: (String) -> Unit,
    onOpenOnlineVideo: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit = {},
    onContextMenuActiveChanged: (Boolean) -> Unit = {},
    onNavigatePreviousTopLevel: () -> Unit = {},
    onNavigateNextTopLevel: () -> Unit = {},
    downloadsViewModel: DownloadsViewModel = hiltViewModel(),
    libraryViewModel: LibraryViewModel = hiltViewModel(),
    localWallpaperViewModel: LocalWallpaperViewModel = hiltViewModel(),
    libraryScrollToTopRequest: Int = 0,
) {
    val downloadsState by downloadsViewModel.uiState.collectAsStateWithLifecycle()
    val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val localWallpaperState by localWallpaperViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingLegacyStorageAction by remember { mutableStateOf<Pair<String, DownloadAction>?>(null) }
    var pendingLibraryDownload by remember { mutableStateOf<WorkshopSummary?>(null) }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pendingAction = pendingLegacyStorageAction
        val pendingDownload = pendingLibraryDownload
        pendingLegacyStorageAction = null
        pendingLibraryDownload = null
        if (granted && pendingAction != null) {
            downloadsViewModel.requestAction(pendingAction.first, pendingAction.second)
        } else if (granted && pendingDownload != null) {
            downloadsViewModel.enqueueWorkshop(pendingDownload)
        } else if (!granted) {
            downloadsViewModel.reportLegacyStoragePermissionDenied()
        }
    }
    ManagementScreen(
        downloadsState = downloadsState,
        libraryState = libraryState,
        onDownloadFilterSelected = downloadsViewModel::setFilter,
        onDownloadTypeFilterSelected = downloadsViewModel::setTypeFilter,
        onDownloadAction = { taskId, action ->
            val task = downloadsState.tasks.firstOrNull { it.id == taskId }
            val requiresPermission = action == DownloadAction.EXPORT ||
                (action == DownloadAction.RETRY && !task?.stagingDirectory.isNullOrBlank())
            if (requiresPermission && context.requiresLegacyPublicDownloadPermission()) {
                pendingLegacyStorageAction = taskId to action
                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                downloadsViewModel.requestAction(taskId, action)
            }
        },
        onDownloadReorder = downloadsViewModel::reorderTasks,
        onOpenLocalVideo = onOpenLocalVideo,
        onOpenOnlineVideo = onOpenOnlineVideo,
        onLibraryCollectionSelected = libraryViewModel::selectCollection,
        onLibraryTypeSelected = libraryViewModel::selectType,
        onLibraryRefresh = libraryViewModel::refresh,
        onLibraryLoadNextPage = libraryViewModel::loadNextPage,
        onLibraryPageSelected = libraryViewModel::selectPage,
        onLibrarySearchQueryChanged = libraryViewModel::updateSearchQuery,
        onLibrarySubmitSearch = libraryViewModel::submitSearch,
        onLibraryPaginationModeSelected = libraryViewModel::selectPaginationMode,
        onLibraryResetFilters = libraryViewModel::resetManagementFilters,
        onLibraryAuthorNameRequested = libraryViewModel::requestAuthorDisplayName,
        onLibraryDownload = { item ->
            if (context.requiresLegacyPublicDownloadPermission()) {
                pendingLibraryDownload = item
                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                downloadsViewModel.enqueueWorkshop(item)
            }
        },
        onOpenDetail = onOpenDetail,
        onSearchAuthor = onSearchAuthor,
        onLocalFormatFilterSelected = localWallpaperViewModel::setFormatFilter,
        onLocalImportFilterSelected = localWallpaperViewModel::setImportFilter,
        onLocalSourceSelected = localWallpaperViewModel::setSource,
        onLocalFavoriteOnlyChanged = localWallpaperViewModel::setFavoriteOnly,
        onLocalTagSelected = localWallpaperViewModel::setSelectedTag,
        onLocalSortSelected = localWallpaperViewModel::setSort,
        onLocalResetFilters = localWallpaperViewModel::resetFilters,
        libraryScrollToTopRequest = libraryScrollToTopRequest,
        localWallpaperState = localWallpaperState,
        localWallpaperViewModel = localWallpaperViewModel,
        onContextMenuActiveChanged = onContextMenuActiveChanged,
        onNavigatePreviousTopLevel = onNavigatePreviousTopLevel,
        onNavigateNextTopLevel = onNavigateNextTopLevel,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ManagementScreen(
    downloadsState: DownloadsUiState,
    libraryState: LibraryUiState,
    localWallpaperState: LocalWallpaperUiState,
    onDownloadFilterSelected: (DownloadFilter) -> Unit,
    onDownloadTypeFilterSelected: (DownloadTypeFilter) -> Unit,
    onDownloadAction: (String, DownloadAction) -> Unit,
    onDownloadReorder: (List<String>) -> Unit,
    onOpenLocalVideo: (String) -> Unit,
    onOpenOnlineVideo: (Long) -> Unit,
    onLibraryCollectionSelected: (LibraryCollectionTab) -> Unit,
    onLibraryTypeSelected: (LibraryTypeFilter) -> Unit,
    onLibraryRefresh: () -> Unit,
    onLibraryLoadNextPage: () -> Unit,
    onLibraryPageSelected: (Int) -> Unit,
    onLibrarySearchQueryChanged: (String) -> Unit,
    onLibrarySubmitSearch: () -> Unit,
    onLibraryPaginationModeSelected: (HomePaginationMode) -> Unit,
    onLibraryResetFilters: () -> Unit,
    onLibraryAuthorNameRequested: (WorkshopSummary) -> Unit,
    onLibraryDownload: (WorkshopSummary) -> Unit,
    onLocalFormatFilterSelected: (LocalWallpaperFormatFilter) -> Unit,
    onLocalImportFilterSelected: (LocalWallpaperImportFilter) -> Unit,
    onLocalSourceSelected: (String?) -> Unit,
    onLocalFavoriteOnlyChanged: (Boolean) -> Unit,
    onLocalTagSelected: (String?) -> Unit,
    onLocalSortSelected: (LocalWallpaperSort) -> Unit,
    onLocalResetFilters: () -> Unit,
    libraryScrollToTopRequest: Int,
    onOpenDetail: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
    onContextMenuActiveChanged: (Boolean) -> Unit,
    onNavigatePreviousTopLevel: () -> Unit,
    onNavigateNextTopLevel: () -> Unit,
    localWallpaperViewModel: LocalWallpaperViewModel,
) {
    val language = LocalWallHubLanguage.current
    val managementContents = ManagementContent.entries
    var content by rememberSaveable { mutableStateOf(ManagementContent.DOWNLOADS) }
    var filtersVisible by rememberSaveable { mutableStateOf(false) }
    var libraryContextMenuActive by remember { mutableStateOf(false) }
    var handledLibraryScrollToTopRequest by rememberSaveable {
        mutableIntStateOf(libraryScrollToTopRequest)
    }
    var libraryContentScrollToTopRequest by rememberSaveable { mutableIntStateOf(0) }
    var pageAnimationJob by remember { mutableStateOf<Job?>(null) }
    val pagerState = rememberPagerState(initialPage = content.ordinal) { managementContents.size }
    val workspaceIndicatorPosition = (
        pagerState.currentPage + pagerState.currentPageOffsetFraction
        ).coerceIn(0f, managementContents.lastIndex.toFloat())
    val pagerScope = rememberCoroutineScope()
    val edgeThresholdPx = with(LocalDensity.current) { MANAGEMENT_EDGE_NAVIGATION_DISTANCE.toPx() }
    val edgeAccumulator = remember(edgeThresholdPx) {
        ManagementEdgeSwipeAccumulator(edgeThresholdPx)
    }
    val edgeNavigationModifier = Modifier.pointerInput(
        pagerState,
        edgeAccumulator,
        onNavigatePreviousTopLevel,
        onNavigateNextTopLevel,
    ) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            val startedAtFirstPage = pagerState.currentPage == 0 &&
                abs(pagerState.currentPageOffsetFraction) < 0.01f
            val startedAtLastPage = pagerState.currentPage == managementContents.lastIndex &&
                abs(pagerState.currentPageOffsetFraction) < 0.01f
            var previousPosition = down.position
            var totalDelta = Offset.Zero
            edgeAccumulator.reset()
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.position - previousPosition
                totalDelta += delta
                previousPosition = change.position
                val direction = edgeAccumulator.onDrag(
                    deltaX = delta.x,
                    atFirstPage = startedAtFirstPage,
                    atLastPage = startedAtLastPage,
                    horizontalDominant = abs(totalDelta.x) > abs(totalDelta.y) * 1.2f,
                )
                when (direction) {
                    ManagementBoundaryDirection.PREVIOUS -> onNavigatePreviousTopLevel()
                    ManagementBoundaryDirection.NEXT -> onNavigateNextTopLevel()
                    null -> Unit
                }
                pressed = change.pressed
            }
            edgeAccumulator.reset()
        }
    }
    val selectContent: (ManagementContent) -> Unit = { selected ->
        if (
            !libraryContextMenuActive &&
            (selected != content || pagerState.settledPage != selected.ordinal)
        ) {
            filtersVisible = false
            content = selected
            pageAnimationJob?.cancel()
            pageAnimationJob = pagerScope.launch {
                pagerState.animateScrollToPage(
                    page = selected.ordinal,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
            }
        }
    }
    LaunchedEffect(pagerState.settledPage, pagerState.isScrollInProgress) {
        val selected = managementContents[pagerState.settledPage]
        if (!pagerState.isScrollInProgress && selected != content) {
            filtersVisible = false
            content = selected
        }
    }
    LaunchedEffect(libraryScrollToTopRequest, libraryContextMenuActive) {
        if (
            !libraryContextMenuActive &&
            libraryScrollToTopRequest > handledLibraryScrollToTopRequest
        ) {
            filtersVisible = false
            content = ManagementContent.LIBRARY
            pageAnimationJob?.cancel()
            pagerState.scrollToPage(ManagementContent.LIBRARY.ordinal)
            withFrameNanos { }
            libraryContentScrollToTopRequest += 1
            handledLibraryScrollToTopRequest = libraryScrollToTopRequest
        }
    }
    DisposableEffect(Unit) {
        onDispose { onContextMenuActiveChanged(false) }
    }
    val managementPager: @Composable (Modifier) -> Unit = { modifier ->
        HorizontalPager(
            state = pagerState,
            modifier = modifier.then(
                if (libraryContextMenuActive) Modifier else edgeNavigationModifier,
            ),
            key = { page -> managementContents[page].name },
            userScrollEnabled = !libraryContextMenuActive,
        ) { page ->
            when (managementContents[page]) {
                ManagementContent.DOWNLOADS -> DownloadsContent(
                    state = downloadsState,
                    onAction = onDownloadAction,
                    showFilters = false,
                    onReorder = onDownloadReorder,
                    onPlayVideo = onOpenLocalVideo,
                )

                ManagementContent.LIBRARY -> LibraryContent(
                    state = libraryState,
                    onRefresh = onLibraryRefresh,
                    onLoadNextPage = onLibraryLoadNextPage,
                    onPageSelected = onLibraryPageSelected,
                    onSearchQueryChanged = onLibrarySearchQueryChanged,
                    onSubmitSearch = onLibrarySubmitSearch,
                    onOpenDetail = onOpenDetail,
                    onPlayVideo = onOpenOnlineVideo,
                    onSearchAuthor = onSearchAuthor,
                    onAuthorNameRequested = onLibraryAuthorNameRequested,
                    onDownload = onLibraryDownload,
                    onContextMenuActiveChanged = { active ->
                        libraryContextMenuActive = active
                        onContextMenuActiveChanged(active)
                    },
                    onScrollChromeCollapsedChanged = {},
                    scrollToTopRequest = libraryContentScrollToTopRequest,
                    showFilters = false,
                )

                ManagementContent.LOCAL -> LocalWallpaperRoute(
                    onScrollChromeCollapsedChanged = {},
                    isPageActive = pagerState.settledPage == ManagementContent.LOCAL.ordinal,
                    viewModel = localWallpaperViewModel,
                )
            }
        }
    }
    val activeFilterCount = content.activeFilterCount(
        downloadsState,
        libraryState,
        localWallpaperState,
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButtonPosition = FabPosition.End,
        floatingActionButton = {
            if (!libraryContextMenuActive) {
                ManagementFilterFab(
                    activeFilterCount = activeFilterCount,
                    language = language,
                    onClick = { filtersVisible = true },
                )
            }
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            val expanded = maxWidth >= MANAGEMENT_EXPANDED_BREAKPOINT
            if (expanded) {
                Row(modifier = Modifier.fillMaxSize()) {
                    ManagementNavigationPanel(
                        content = content,
                        language = language,
                        onContentSelected = selectContent,
                        navigationEnabled = !libraryContextMenuActive,
                        indicatorPosition = workspaceIndicatorPosition,
                        expanded = true,
                        modifier = Modifier
                            .width(MANAGEMENT_SIDE_PANEL_WIDTH)
                            .fillMaxHeight()
                            .managementContextMenuBackdrop(libraryContextMenuActive),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    managementPager(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    ManagementNavigationPanel(
                        content = content,
                        language = language,
                        onContentSelected = selectContent,
                        navigationEnabled = !libraryContextMenuActive,
                        indicatorPosition = workspaceIndicatorPosition,
                        expanded = false,
                        modifier = Modifier.managementContextMenuBackdrop(libraryContextMenuActive),
                    )
                    managementPager(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }
        }
    }
    if (filtersVisible) {
        ManagementFiltersSheet(
            content = content,
            downloadsState = downloadsState,
            libraryState = libraryState,
            localWallpaperState = localWallpaperState,
            language = language,
            onDownloadFilterSelected = onDownloadFilterSelected,
            onDownloadTypeFilterSelected = onDownloadTypeFilterSelected,
            onLibraryCollectionSelected = onLibraryCollectionSelected,
            onLibraryTypeSelected = onLibraryTypeSelected,
            onLibraryPaginationModeSelected = onLibraryPaginationModeSelected,
            onLibraryResetFilters = onLibraryResetFilters,
            onLocalFormatFilterSelected = onLocalFormatFilterSelected,
            onLocalImportFilterSelected = onLocalImportFilterSelected,
            onLocalSourceSelected = onLocalSourceSelected,
            onLocalFavoriteOnlyChanged = onLocalFavoriteOnlyChanged,
            onLocalTagSelected = onLocalTagSelected,
            onLocalSortSelected = onLocalSortSelected,
            onLocalResetFilters = onLocalResetFilters,
            onDismiss = { filtersVisible = false },
        )
    }
}

@Composable
private fun ManagementNavigationPanel(
    content: ManagementContent,
    language: AppLanguage,
    onContentSelected: (ManagementContent) -> Unit,
    navigationEnabled: Boolean,
    indicatorPosition: Float,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ManagementPageHeading(
                    content = content,
                    language = language,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ManagementContent.entries.forEach { destination ->
                        ManagementWorkspaceDestination(
                            destination = destination,
                            selected = content == destination,
                            language = language,
                            expanded = true,
                            enabled = navigationEnabled,
                            onClick = { onContentSelected(destination) },
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                ManagementPageHeading(
                    content = content,
                    language = language,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WallHubSlidingSingleChoiceControl(
                        options = ManagementContent.entries,
                        selected = content,
                        onSelected = onContentSelected,
                        modifier = Modifier.weight(1f),
                        enabled = navigationEnabled,
                        role = Role.Tab,
                        indicatorPosition = indicatorPosition,
                        height = 52.dp,
                        showPressIndication = false,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        containerShape = MaterialTheme.shapes.large,
                        indicatorShape = MaterialTheme.shapes.medium,
                        label = { destination ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = destination.icon(),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = destination.label(language),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ManagementPageHeading(
    content: ManagementContent,
    language: AppLanguage,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = content.icon(),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = content.label(language),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = content.description(language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ManagementWorkspaceDestination(
    destination: ManagementContent,
    selected: Boolean,
    language: AppLanguage,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.background
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp,
    ) {
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = destination.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = destination.label(language),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = destination.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = destination.label(language),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ManagementFilterFab(
    activeFilterCount: Int,
    language: AppLanguage,
    onClick: () -> Unit,
) {
    BadgedBox(
        badge = { ManagementFilterBadge(activeFilterCount) },
    ) {
        FloatingActionButton(
            onClick = onClick,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = WallHubFabDefaultElevation,
                pressedElevation = WallHubFabActiveElevation,
                focusedElevation = WallHubFabDefaultElevation,
                hoveredElevation = WallHubFabActiveElevation,
            ),
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = language.text(
                    if (activeFilterCount > 0) "筛选，已启用 $activeFilterCount 项" else "筛选",
                    if (activeFilterCount > 0) "Filters, $activeFilterCount active" else "Filters",
                ),
            )
        }
    }
}

@Composable
private fun ManagementFilterBadge(activeFilterCount: Int) {
    if (activeFilterCount > 0) {
        Badge { Text(activeFilterCount.toString()) }
    }
}

@Composable
private fun ManagementChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    singleChoice: Boolean = false,
) {
    WallHubFilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        singleChoice = singleChoice,
        minHeight = 48.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun ManagementFilterSectionCard(
    title: String,
    supportingText: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManagementSingleChoiceFlow(
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ManagementFiltersSheet(
    content: ManagementContent,
    downloadsState: DownloadsUiState,
    libraryState: LibraryUiState,
    localWallpaperState: LocalWallpaperUiState,
    language: AppLanguage,
    onDownloadFilterSelected: (DownloadFilter) -> Unit,
    onDownloadTypeFilterSelected: (DownloadTypeFilter) -> Unit,
    onLibraryCollectionSelected: (LibraryCollectionTab) -> Unit,
    onLibraryTypeSelected: (LibraryTypeFilter) -> Unit,
    onLibraryPaginationModeSelected: (HomePaginationMode) -> Unit,
    onLibraryResetFilters: () -> Unit,
    onLocalFormatFilterSelected: (LocalWallpaperFormatFilter) -> Unit,
    onLocalImportFilterSelected: (LocalWallpaperImportFilter) -> Unit,
    onLocalSourceSelected: (String?) -> Unit,
    onLocalFavoriteOnlyChanged: (Boolean) -> Unit,
    onLocalTagSelected: (String?) -> Unit,
    onLocalSortSelected: (LocalWallpaperSort) -> Unit,
    onLocalResetFilters: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeFilterCount = when (content) {
        ManagementContent.DOWNLOADS -> downloadsState.activeFilterCount()
        ManagementContent.LIBRARY -> libraryState.activeFilterCount()
        ManagementContent.LOCAL -> localWallpaperState.activeFilterCount
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        sheetMaxWidth = 880.dp,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < 600.dp
            val horizontalPadding = if (compact) 16.dp else 24.dp
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(if (compact) 1f else 0.94f)
                    .widthIn(max = 880.dp)
                    .heightIn(max = maxHeight * if (compact) 0.92f else 0.84f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = horizontalPadding,
                            end = horizontalPadding,
                            bottom = 12.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Cancel,
                            contentDescription = language.text("关闭筛选", "Close filters"),
                        )
                    }
                    Text(
                        text = content.filterTitle(language),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            when (content) {
                                ManagementContent.DOWNLOADS -> {
                                    onDownloadFilterSelected(DownloadFilter.ALL)
                                    onDownloadTypeFilterSelected(DownloadTypeFilter.ALL)
                                }

                                ManagementContent.LIBRARY -> onLibraryResetFilters()
                                ManagementContent.LOCAL -> onLocalResetFilters()
                            }
                        },
                        enabled = activeFilterCount > 0,
                    ) {
                        Text(language.text("恢复默认", "Reset"))
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                    .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = horizontalPadding, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (content) {
                        ManagementContent.DOWNLOADS -> {
                            ManagementFilterSectionCard(
                                title = language.text("下载状态", "Download status"),
                                supportingText = language.text(
                                    "按任务当前阶段筛选",
                                    "Filter by the current task phase",
                                ),
                            ) {
                                ManagementSingleChoiceFlow {
                                    DownloadFilter.entries.forEach { filter ->
                                        ManagementChoiceChip(
                                            selected = downloadsState.filter == filter,
                                            onClick = { onDownloadFilterSelected(filter) },
                                            label = filter.label(language),
                                            singleChoice = true,
                                        )
                                    }
                                }
                            }
                            ManagementFilterSectionCard(
                                title = language.text("壁纸类型", "Wallpaper type"),
                                supportingText = language.text(
                                    "仅显示指定类型的下载任务",
                                    "Show download tasks of a specific type",
                                ),
                            ) {
                                ManagementSingleChoiceFlow {
                                    DownloadTypeFilter.entries.forEach { filter ->
                                        ManagementChoiceChip(
                                            selected = downloadsState.typeFilter == filter,
                                            onClick = { onDownloadTypeFilterSelected(filter) },
                                            label = filter.label(language),
                                            singleChoice = true,
                                        )
                                    }
                                }
                            }
                        }

                        ManagementContent.LIBRARY -> {
                            ManagementFilterSectionCard(
                                title = language.text("资料库分类", "Collection"),
                                supportingText = language.text(
                                    "切换 Steam 账户资源集合",
                                    "Switch the Steam account collection",
                                ),
                            ) {
                                ManagementSingleChoiceFlow {
                                    LibraryCollectionTab.entries.forEach { collection ->
                                        ManagementChoiceChip(
                                            selected = libraryState.collection == collection,
                                            onClick = { onLibraryCollectionSelected(collection) },
                                            label = collection.label(language),
                                            singleChoice = true,
                                        )
                                    }
                                }
                            }
                            ManagementFilterSectionCard(
                                title = language.text("壁纸类型", "Wallpaper type"),
                                supportingText = language.text(
                                    "缩小当前集合中的内容范围",
                                    "Narrow the content in this collection",
                                ),
                            ) {
                                ManagementSingleChoiceFlow {
                                    LibraryTypeFilter.entries.forEach { filter ->
                                        ManagementChoiceChip(
                                            selected = libraryState.typeFilter == filter,
                                            onClick = { onLibraryTypeSelected(filter) },
                                            label = filter.label(language),
                                            singleChoice = true,
                                        )
                                    }
                                }
                            }
                            ManagementFilterSectionCard(
                                title = language.text("浏览方式", "Browsing mode"),
                                supportingText = language.text(
                                    "选择连续加载或页码导航",
                                    "Choose continuous loading or page controls",
                                ),
                            ) {
                                ManagementSingleChoiceFlow {
                                    HomePaginationMode.entries.forEach { mode ->
                                        ManagementChoiceChip(
                                            selected = libraryState.paginationMode == mode,
                                            onClick = { onLibraryPaginationModeSelected(mode) },
                                            label = mode.libraryLabel(language),
                                            singleChoice = true,
                                        )
                                    }
                                }
                            }
                        }

                        ManagementContent.LOCAL -> {
                            ManagementFilterSectionCard(
                                title = language.text("格式", "Format"),
                                supportingText = language.text(
                                    "按本地资源格式筛选",
                                    "Filter by local resource format",
                                ),
                            ) {
                                ManagementSingleChoiceFlow {
                                    LocalWallpaperFormatFilter.entries.forEach { filter ->
                                        ManagementChoiceChip(
                                            selected = localWallpaperState.formatFilter == filter,
                                            onClick = { onLocalFormatFilterSelected(filter) },
                                            label = filter.managementLabel(language),
                                            singleChoice = true,
                                        )
                                    }
                                }
                            }
                            ManagementFilterSectionCard(
                                title = language.text("导入状态", "Import state"),
                                supportingText = language.text(
                                    "查看尚未导入或已发起导入的资源",
                                    "View resources by requested import state",
                                ),
                            ) {
                                ManagementSingleChoiceFlow {
                                    LocalWallpaperImportFilter.entries.forEach { filter ->
                                        ManagementChoiceChip(
                                            selected = localWallpaperState.importFilter == filter,
                                            onClick = { onLocalImportFilterSelected(filter) },
                                            label = filter.managementLabel(language),
                                            singleChoice = true,
                                        )
                                    }
                                }
                            }
                            ManagementFilterSectionCard(
                                title = language.text("来源", "Source"),
                                supportingText = language.text(
                                    "限定扫描目录来源",
                                    "Limit results to a scanned location",
                                ),
                            ) {
                                ManagementSingleChoiceFlow {
                                    ManagementChoiceChip(
                                        selected = localWallpaperState.sourceId == null,
                                        onClick = { onLocalSourceSelected(null) },
                                        label = language.text("全部目录", "All locations"),
                                        singleChoice = true,
                                    )
                                    localWallpaperState.scan.sources.forEach { source ->
                                        ManagementChoiceChip(
                                            selected = localWallpaperState.sourceId == source.id,
                                            onClick = { onLocalSourceSelected(source.id) },
                                            label = source.label,
                                            singleChoice = true,
                                        )
                                    }
                                }
                            }
                            ManagementFilterSectionCard(
                                title = language.text("整理", "Organization"),
                                supportingText = language.text(
                                    "组合收藏状态与单个标签",
                                    "Combine favorites with one tag",
                                ),
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ManagementChoiceChip(
                                        selected = localWallpaperState.favoriteOnly,
                                        onClick = {
                                            onLocalFavoriteOnlyChanged(!localWallpaperState.favoriteOnly)
                                        },
                                        label = language.text("仅收藏", "Favorites only"),
                                    )
                                }
                                ManagementSingleChoiceFlow {
                                    ManagementChoiceChip(
                                        selected = localWallpaperState.selectedTag == null,
                                        onClick = { onLocalTagSelected(null) },
                                        label = language.text("全部标签", "All tags"),
                                        singleChoice = true,
                                    )
                                    localWallpaperState.allTags.forEach { tag ->
                                        ManagementChoiceChip(
                                            selected = localWallpaperState.selectedTag == tag,
                                            onClick = { onLocalTagSelected(tag) },
                                            label = tag,
                                            singleChoice = true,
                                        )
                                    }
                                }
                            }
                            ManagementFilterSectionCard(
                                title = language.text("排序", "Sort"),
                                supportingText = language.text(
                                    "决定本地资源的排列顺序",
                                    "Choose the order of local resources",
                                ),
                            ) {
                                ManagementSingleChoiceFlow {
                                    LocalWallpaperSort.entries.forEach { sort ->
                                        ManagementChoiceChip(
                                            selected = localWallpaperState.sort == sort,
                                            onClick = { onLocalSortSelected(sort) },
                                            label = sort.managementLabel(language),
                                            singleChoice = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

private fun ManagementContent.label(language: AppLanguage): String = when (this) {
    ManagementContent.DOWNLOADS -> language.text("下载", "Downloads")
    ManagementContent.LIBRARY -> language.text("资料库", "Library")
    ManagementContent.LOCAL -> language.text("本地", "Local")
}

private fun ManagementContent.description(language: AppLanguage): String = when (this) {
    ManagementContent.DOWNLOADS -> language.text("跟踪下载任务", "Track download tasks")
    ManagementContent.LIBRARY -> language.text("管理云端收藏", "Manage cloud favorites")
    ManagementContent.LOCAL -> language.text("整理本地壁纸", "Organize local wallpapers")
}

private fun ManagementContent.icon(): ImageVector = when (this) {
    ManagementContent.DOWNLOADS -> Icons.Outlined.Download
    ManagementContent.LIBRARY -> Icons.Outlined.Bookmarks
    ManagementContent.LOCAL -> Icons.Outlined.FolderOpen
}

private fun ManagementContent.filterTitle(language: AppLanguage): String = when (this) {
    ManagementContent.DOWNLOADS -> language.text("下载筛选", "Download filters")
    ManagementContent.LIBRARY -> language.text("资料库筛选", "Library filters")
    ManagementContent.LOCAL -> language.text("本地筛选", "Local filters")
}

private fun ManagementContent.activeFilterCount(
    downloadsState: DownloadsUiState,
    libraryState: LibraryUiState,
    localWallpaperState: LocalWallpaperUiState,
): Int = when (this) {
    ManagementContent.DOWNLOADS -> downloadsState.activeFilterCount()
    ManagementContent.LIBRARY -> libraryState.activeFilterCount()
    ManagementContent.LOCAL -> localWallpaperState.activeFilterCount
}

private fun DownloadsUiState.activeFilterCount(): Int =
    (if (filter != DownloadFilter.ALL) 1 else 0) +
        (if (typeFilter != DownloadTypeFilter.ALL) 1 else 0)

private fun LibraryUiState.activeFilterCount(): Int =
    (if (collection != LibraryCollectionTab.SUBSCRIPTIONS) 1 else 0) +
        (if (typeFilter != LibraryTypeFilter.ALL) 1 else 0) +
        (if (paginationMode != HomePaginationMode.INFINITE_SCROLL) 1 else 0)

private fun AppLanguage.text(zh: String, en: String): String = if (this == AppLanguage.EN) en else zh

private fun LocalWallpaperFormatFilter.managementLabel(language: AppLanguage): String = when (this) {
    LocalWallpaperFormatFilter.ALL -> language.text("全部", "All")
    LocalWallpaperFormatFilter.MPKG -> "MPKG"
    LocalWallpaperFormatFilter.PKG -> "PKG"
    LocalWallpaperFormatFilter.VIDEO -> language.text("视频", "Video")
    LocalWallpaperFormatFilter.HTML -> "HTML"
    LocalWallpaperFormatFilter.UNKNOWN -> language.text("未知", "Unknown")
}

private fun LocalWallpaperImportFilter.managementLabel(language: AppLanguage): String = when (this) {
    LocalWallpaperImportFilter.ALL -> language.text("全部", "All")
    LocalWallpaperImportFilter.NOT_IMPORTED -> language.text("未导入", "Not imported")
    LocalWallpaperImportFilter.IMPORT_REQUESTED -> language.text("已发起导入", "Import requested")
}

private fun LocalWallpaperSort.managementLabel(
    language: AppLanguage,
): String = when (this) {
    LocalWallpaperSort.RECENT -> language.text("最近修改", "Recent")
    LocalWallpaperSort.NAME -> language.text("名称", "Name")
    LocalWallpaperSort.SIZE -> language.text("文件大小", "Size")
    LocalWallpaperSort.TYPE -> language.text("类型", "Type")
}

private fun DownloadFilter.label(language: AppLanguage): String = when (this) {
    DownloadFilter.ALL -> language.text("全部", "All")
    DownloadFilter.COMPLETED -> language.text("已完成", "Completed")
    DownloadFilter.DOWNLOADING -> language.text("下载中", "Active")
    DownloadFilter.QUEUED -> language.text("待下载", "Queued")
    DownloadFilter.FAILED -> language.text("失败", "Failed")
}

private fun DownloadTypeFilter.label(language: AppLanguage): String = when (this) {
    DownloadTypeFilter.ALL -> language.text("全部", "All")
    DownloadTypeFilter.VIDEO -> language.text("视频", "Video")
    DownloadTypeFilter.SCENE -> language.text("场景", "Scene")
    DownloadTypeFilter.WEB -> language.text("网站", "Web")
}

private fun LibraryCollectionTab.label(language: AppLanguage): String = when (this) {
    LibraryCollectionTab.SUBSCRIPTIONS -> language.text("个人订阅", "Subscriptions")
    LibraryCollectionTab.FAVORITES -> language.text("我的收藏", "Favorites")
    LibraryCollectionTab.VOTED -> language.text("我的投票", "Voted")
}

private fun LibraryTypeFilter.label(language: AppLanguage): String = when (this) {
    LibraryTypeFilter.ALL -> language.text("全部", "All")
    LibraryTypeFilter.VIDEO -> language.text("视频", "Video")
    LibraryTypeFilter.SCENE -> language.text("场景", "Scene")
    LibraryTypeFilter.WEB -> language.text("网站", "Web")
}

private fun HomePaginationMode.libraryLabel(language: AppLanguage): String = when (this) {
    HomePaginationMode.INFINITE_SCROLL -> language.text("瀑布流拼接", "Infinite scroll")
    HomePaginationMode.PAGED -> language.text("页数组件", "Page controls")
}

@Composable
private fun Modifier.managementContextMenuBackdrop(active: Boolean): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (active) MANAGEMENT_CONTEXT_MENU_ENTER_DURATION_MS
            else MANAGEMENT_CONTEXT_MENU_EXIT_DURATION_MS,
            easing = MANAGEMENT_CONTEXT_MENU_EASING,
        ),
        label = "ManagementContextMenuBackdrop",
    )
    if (!active && progress <= 0f) return this
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val scrim = MaterialTheme.colorScheme.scrim.copy(
        alpha = (if (dark) MANAGEMENT_CONTEXT_MENU_DARK_SCRIM_ALPHA else MANAGEMENT_CONTEXT_MENU_LIGHT_SCRIM_ALPHA) * progress,
    )
    val scrimModifier = drawWithContent {
        drawContent()
        drawRect(scrim)
    }.then(
        if (active) Modifier.semantics { invisibleToUser() } else Modifier,
    )
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && progress > 0f) {
        scrimModifier.blur(MANAGEMENT_CONTEXT_MENU_BLUR_RADIUS * progress)
    } else {
        scrimModifier
    }
}

private val MANAGEMENT_EDGE_NAVIGATION_DISTANCE = 72.dp
private val MANAGEMENT_EXPANDED_BREAKPOINT = 960.dp
private val MANAGEMENT_SIDE_PANEL_WIDTH = 240.dp
private const val MANAGEMENT_CONTEXT_MENU_ENTER_DURATION_MS = 160
private const val MANAGEMENT_CONTEXT_MENU_EXIT_DURATION_MS = 130
private const val MANAGEMENT_CONTEXT_MENU_LIGHT_SCRIM_ALPHA = 0.14f
private const val MANAGEMENT_CONTEXT_MENU_DARK_SCRIM_ALPHA = 0.20f
private val MANAGEMENT_CONTEXT_MENU_BLUR_RADIUS = 12.dp
private val MANAGEMENT_CONTEXT_MENU_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
