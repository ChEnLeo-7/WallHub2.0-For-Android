@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallhub.android.core.designsystem.WallHubFabActiveElevation
import com.wallhub.android.core.designsystem.WallHubFabDefaultElevation
import com.wallhub.android.core.designsystem.WallHubFilterChip
import com.wallhub.android.core.designsystem.WallHubSlidingSingleChoiceControl
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.feature.downloads.DownloadFilter
import com.wallhub.android.feature.downloads.DownloadTypeFilter
import com.wallhub.android.feature.downloads.DownloadsAction
import com.wallhub.android.feature.downloads.DownloadsContent
import com.wallhub.android.feature.downloads.DownloadsEffectHandler
import com.wallhub.android.feature.downloads.DownloadsUiState
import com.wallhub.android.feature.downloads.DownloadsViewModel
import com.wallhub.android.feature.library.LibraryAction
import com.wallhub.android.feature.library.LibraryCollectionTab
import com.wallhub.android.feature.library.LibraryContent
import com.wallhub.android.feature.library.LibraryEffectHandler
import com.wallhub.android.feature.library.LibraryTypeFilter
import com.wallhub.android.feature.library.LibraryUiState
import com.wallhub.android.feature.library.LibraryViewModel
import com.wallhub.android.feature.local.LocalWallpaperAction
import com.wallhub.android.feature.local.LocalWallpaperFormatFilter
import com.wallhub.android.feature.local.LocalWallpaperImportFilter
import com.wallhub.android.feature.local.LocalWallpaperRoute
import com.wallhub.android.feature.local.LocalWallpaperSort
import com.wallhub.android.feature.local.LocalWallpaperUiState
import com.wallhub.android.feature.local.LocalWallpaperViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.uwuaosp.compose.settingslib.SettingsToolbarActionButton
import kotlin.math.abs
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

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
    onOpenSettings: () -> Unit = {},
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
    DownloadsEffectHandler(
        viewModel = downloadsViewModel,
        onPlayVideo = onOpenLocalVideo,
    )
    LibraryEffectHandler(
        viewModel = libraryViewModel,
        onOpenDetail = onOpenDetail,
        onPlayVideo = onOpenOnlineVideo,
        onSearchAuthor = onSearchAuthor,
        onDownload = { item ->
            downloadsViewModel.onAction(DownloadsAction.EnqueueWorkshop(item))
        },
    )
    ManagementScreen(
        downloadsState = downloadsState,
        libraryState = libraryState,
        onDownloadsAction = downloadsViewModel::onAction,
        onLibraryAction = libraryViewModel::onAction,
        onLocalAction = localWallpaperViewModel::onAction,
        libraryScrollToTopRequest = libraryScrollToTopRequest,
        localWallpaperState = localWallpaperState,
        localWallpaperViewModel = localWallpaperViewModel,
        onContextMenuActiveChanged = onContextMenuActiveChanged,
        onOpenSettings = onOpenSettings,
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
    onDownloadsAction: (DownloadsAction) -> Unit,
    onLibraryAction: (LibraryAction) -> Unit,
    onLocalAction: (LocalWallpaperAction) -> Unit,
    libraryScrollToTopRequest: Int,
    onContextMenuActiveChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onNavigatePreviousTopLevel: () -> Unit,
    onNavigateNextTopLevel: () -> Unit,
    localWallpaperViewModel: LocalWallpaperViewModel,
) {
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
    val workspaceIndicatorPosition =
        (
            pagerState.currentPage + pagerState.currentPageOffsetFraction
        ).coerceIn(0f, managementContents.lastIndex.toFloat())
    val pagerScope = rememberCoroutineScope()
    val edgeThresholdPx = with(LocalDensity.current) { MANAGEMENT_EDGE_NAVIGATION_DISTANCE.toPx() }
    val edgeAccumulator =
        remember(edgeThresholdPx) {
            ManagementEdgeSwipeAccumulator(edgeThresholdPx)
        }
    val edgeNavigationModifier =
        Modifier.managementBoundaryNavigation(
            pagerState = pagerState,
            lastPageIndex = managementContents.lastIndex,
            edgeAccumulator = edgeAccumulator,
            onNavigatePreviousTopLevel = onNavigatePreviousTopLevel,
            onNavigateNextTopLevel = onNavigateNextTopLevel,
        )
    val selectContent: (ManagementContent) -> Unit = { selected ->
        if (
            !libraryContextMenuActive &&
            (selected != content || pagerState.settledPage != selected.ordinal)
        ) {
            filtersVisible = false
            content = selected
            pageAnimationJob?.cancel()
            pageAnimationJob =
                pagerScope.launch {
                    pagerState.animateScrollToPage(
                        page = selected.ordinal,
                        animationSpec =
                            spring(
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
    val activeFilterCount =
        content.activeFilterCount(
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
                    onClick = { filtersVisible = true },
                )
            }
        },
    ) { padding ->
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
        ) {
            ManagementWorkspaceLayout(
                expanded = maxWidth >= MANAGEMENT_EXPANDED_BREAKPOINT,
                content = content,
                onContentSelected = selectContent,
                navigationEnabled = !libraryContextMenuActive,
                indicatorPosition = workspaceIndicatorPosition,
                contextMenuActive = libraryContextMenuActive,
                onOpenSettings = onOpenSettings,
            ) { pagerModifier ->
                ManagementWorkspacePager(
                    pagerState = pagerState,
                    edgeNavigationModifier = edgeNavigationModifier,
                    contextMenuActive = libraryContextMenuActive,
                    downloadsState = downloadsState,
                    libraryState = libraryState,
                    onDownloadsAction = onDownloadsAction,
                    onLibraryAction = onLibraryAction,
                    onLibraryContextMenuActiveChanged = { active ->
                        libraryContextMenuActive = active
                        onContextMenuActiveChanged(active)
                    },
                    libraryScrollToTopRequest = libraryContentScrollToTopRequest,
                    localWallpaperViewModel = localWallpaperViewModel,
                    modifier = pagerModifier,
                )
            }
        }
    }
    if (filtersVisible) {
        ManagementFiltersSheet(
            content = content,
            downloadsState = downloadsState,
            libraryState = libraryState,
            localWallpaperState = localWallpaperState,
            onDownloadFilterSelected = { filter ->
                onDownloadsAction(DownloadsAction.SelectFilter(filter))
            },
            onDownloadTypeFilterSelected = { filter ->
                onDownloadsAction(DownloadsAction.SelectTypeFilter(filter))
            },
            onLibraryCollectionSelected = {
                onLibraryAction(LibraryAction.SelectCollection(it))
            },
            onLibraryTypeSelected = { onLibraryAction(LibraryAction.SelectType(it)) },
            onLibraryPaginationModeSelected = {
                onLibraryAction(LibraryAction.SelectPaginationMode(it))
            },
            onLibraryResetFilters = { onLibraryAction(LibraryAction.ResetFilters) },
            onLocalAction = onLocalAction,
            onDismiss = { filtersVisible = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ManagementWorkspacePager(
    pagerState: PagerState,
    edgeNavigationModifier: Modifier,
    contextMenuActive: Boolean,
    downloadsState: DownloadsUiState,
    libraryState: LibraryUiState,
    onDownloadsAction: (DownloadsAction) -> Unit,
    onLibraryAction: (LibraryAction) -> Unit,
    onLibraryContextMenuActiveChanged: (Boolean) -> Unit,
    libraryScrollToTopRequest: Int,
    localWallpaperViewModel: LocalWallpaperViewModel,
    modifier: Modifier = Modifier,
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier.then(if (contextMenuActive) Modifier else edgeNavigationModifier),
        key = { page -> ManagementContent.entries[page].name },
        userScrollEnabled = !contextMenuActive,
    ) { page ->
        when (ManagementContent.entries[page]) {
            ManagementContent.DOWNLOADS ->
                DownloadsContent(
                    state = downloadsState,
                    onAction = onDownloadsAction,
                    showFilters = false,
                )

            ManagementContent.LIBRARY ->
                LibraryContent(
                    state = libraryState,
                    onAction = onLibraryAction,
                    onContextMenuActiveChanged = onLibraryContextMenuActiveChanged,
                    onScrollChromeCollapsedChanged = {},
                    scrollToTopRequest = libraryScrollToTopRequest,
                    showFilters = false,
                )

            ManagementContent.LOCAL ->
                LocalWallpaperRoute(
                    onScrollChromeCollapsedChanged = {},
                    isPageActive = pagerState.settledPage == ManagementContent.LOCAL.ordinal,
                    viewModel = localWallpaperViewModel,
                )
        }
    }
}

@Composable
private fun ManagementWorkspaceLayout(
    expanded: Boolean,
    content: ManagementContent,
    onContentSelected: (ManagementContent) -> Unit,
    navigationEnabled: Boolean,
    indicatorPosition: Float,
    contextMenuActive: Boolean,
    onOpenSettings: () -> Unit,
    pager: @Composable (Modifier) -> Unit,
) {
    if (expanded) {
        Row(modifier = Modifier.fillMaxSize()) {
            ManagementNavigationPanel(
                content = content,
                onContentSelected = onContentSelected,
                navigationEnabled = navigationEnabled,
                indicatorPosition = indicatorPosition,
                expanded = true,
                onOpenSettings = onOpenSettings,
                modifier =
                    Modifier
                        .width(MANAGEMENT_SIDE_PANEL_WIDTH)
                        .fillMaxHeight()
                        .managementContextMenuBackdrop(contextMenuActive),
            )
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            pager(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            ManagementNavigationPanel(
                content = content,
                onContentSelected = onContentSelected,
                navigationEnabled = navigationEnabled,
                indicatorPosition = indicatorPosition,
                expanded = false,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.managementContextMenuBackdrop(contextMenuActive),
            )
            pager(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.managementBoundaryNavigation(
    pagerState: PagerState,
    lastPageIndex: Int,
    edgeAccumulator: ManagementEdgeSwipeAccumulator,
    onNavigatePreviousTopLevel: () -> Unit,
    onNavigateNextTopLevel: () -> Unit,
): Modifier =
    pointerInput(
        pagerState,
        edgeAccumulator,
        onNavigatePreviousTopLevel,
        onNavigateNextTopLevel,
    ) {
        awaitEachGesture {
            val down =
                awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
            val startedAtFirstPage =
                pagerState.currentPage == 0 && abs(pagerState.currentPageOffsetFraction) < 0.01f
            val startedAtLastPage =
                pagerState.currentPage == lastPageIndex && abs(pagerState.currentPageOffsetFraction) < 0.01f
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
                when (
                    edgeAccumulator.onDrag(
                        deltaX = delta.x,
                        atFirstPage = startedAtFirstPage,
                        atLastPage = startedAtLastPage,
                        horizontalDominant = abs(totalDelta.x) > abs(totalDelta.y) * 1.2f,
                    )
                ) {
                    ManagementBoundaryDirection.PREVIOUS -> onNavigatePreviousTopLevel()
                    ManagementBoundaryDirection.NEXT -> onNavigateNextTopLevel()
                    null -> Unit
                }
                pressed = change.pressed
            }
            edgeAccumulator.reset()
        }
    }

@Composable
private fun ManagementNavigationPanel(
    content: ManagementContent,
    onContentSelected: (ManagementContent) -> Unit,
    navigationEnabled: Boolean,
    indicatorPosition: Float,
    expanded: Boolean,
    onOpenSettings: () -> Unit,
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
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ManagementPageHeading(
                    content = content,
                    onOpenSettings = onOpenSettings,
                    modifier =
                        Modifier
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
                    onOpenSettings = onOpenSettings,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                )
                Row(
                    modifier =
                        Modifier
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
                                    text = destination.label(),
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
    onOpenSettings: () -> Unit,
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
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = content.label(),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = content.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SettingsToolbarActionButton(
            imageVector = Icons.Outlined.Settings,
            contentDescription = stringResource(R.string.management_settings),
            onClick = onOpenSettings,
            buttonSize = 64.dp,
            containerSize = 48.dp,
        )
    }
}

@Composable
private fun ManagementWorkspaceDestination(
    destination: ManagementContent,
    selected: Boolean,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.background
        }
    val contentColor =
        if (selected) {
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
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = onClick,
                        ).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = destination.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = destination.label(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = onClick,
                        ).padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = destination.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = destination.label(),
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
    onClick: () -> Unit,
) {
    BadgedBox(
        badge = { ManagementFilterBadge(activeFilterCount) },
    ) {
        FloatingActionButton(
            onClick = onClick,
            elevation =
                FloatingActionButtonDefaults.elevation(
                    defaultElevation = WallHubFabDefaultElevation,
                    pressedElevation = WallHubFabActiveElevation,
                    focusedElevation = WallHubFabDefaultElevation,
                    hoveredElevation = WallHubFabActiveElevation,
                ),
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription =
                    if (activeFilterCount > 0) {
                        pluralStringResource(
                            R.plurals.management_filters_active,
                            activeFilterCount,
                            activeFilterCount,
                        )
                    } else {
                        stringResource(R.string.management_filters)
                    },
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
private fun ManagementSingleChoiceFlow(content: @Composable () -> Unit) {
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
    onDownloadFilterSelected: (DownloadFilter) -> Unit,
    onDownloadTypeFilterSelected: (DownloadTypeFilter) -> Unit,
    onLibraryCollectionSelected: (LibraryCollectionTab) -> Unit,
    onLibraryTypeSelected: (LibraryTypeFilter) -> Unit,
    onLibraryPaginationModeSelected: (HomePaginationMode) -> Unit,
    onLibraryResetFilters: () -> Unit,
    onLocalAction: (LocalWallpaperAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeFilterCount =
        when (content) {
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
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(if (compact) 1f else 0.94f)
                        .widthIn(max = 880.dp)
                        .heightIn(max = maxHeight * if (compact) 0.92f else 0.84f),
            ) {
                Row(
                    modifier =
                        Modifier
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
                            contentDescription = stringResource(R.string.management_close_filters),
                        )
                    }
                    Text(
                        text = content.filterTitle(),
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
                                ManagementContent.LOCAL -> onLocalAction(LocalWallpaperAction.ResetFilters)
                            }
                        },
                        enabled = activeFilterCount > 0,
                    ) {
                        Text(stringResource(R.string.management_reset))
                    }
                }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .padding(horizontal = horizontalPadding, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (content) {
                        ManagementContent.DOWNLOADS ->
                            ManagementDownloadFilterSections(
                                state = downloadsState,
                                onFilterSelected = onDownloadFilterSelected,
                                onTypeFilterSelected = onDownloadTypeFilterSelected,
                            )

                        ManagementContent.LIBRARY ->
                            ManagementLibraryFilterSections(
                                state = libraryState,
                                onCollectionSelected = onLibraryCollectionSelected,
                                onTypeSelected = onLibraryTypeSelected,
                                onPaginationModeSelected = onLibraryPaginationModeSelected,
                            )

                        ManagementContent.LOCAL ->
                            ManagementLocalFilterSections(
                                state = localWallpaperState,
                                onAction = onLocalAction,
                            )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun ManagementDownloadFilterSections(
    state: DownloadsUiState,
    onFilterSelected: (DownloadFilter) -> Unit,
    onTypeFilterSelected: (DownloadTypeFilter) -> Unit,
) {
    ManagementFilterSectionCard(
        title = stringResource(R.string.management_download_status),
        supportingText = stringResource(R.string.management_download_status_supporting),
    ) {
        ManagementSingleChoiceFlow {
            DownloadFilter.entries.forEach { filter ->
                ManagementChoiceChip(
                    selected = state.filter == filter,
                    onClick = { onFilterSelected(filter) },
                    label = filter.label(),
                    singleChoice = true,
                )
            }
        }
    }
    ManagementFilterSectionCard(
        title = stringResource(R.string.management_wallpaper_type),
        supportingText = stringResource(R.string.management_download_type_supporting),
    ) {
        ManagementSingleChoiceFlow {
            DownloadTypeFilter.entries.forEach { filter ->
                ManagementChoiceChip(
                    selected = state.typeFilter == filter,
                    onClick = { onTypeFilterSelected(filter) },
                    label = filter.label(),
                    singleChoice = true,
                )
            }
        }
    }
}

@Composable
private fun ManagementLibraryFilterSections(
    state: LibraryUiState,
    onCollectionSelected: (LibraryCollectionTab) -> Unit,
    onTypeSelected: (LibraryTypeFilter) -> Unit,
    onPaginationModeSelected: (HomePaginationMode) -> Unit,
) {
    ManagementFilterSectionCard(
        title = stringResource(R.string.management_collection),
        supportingText = stringResource(R.string.management_collection_supporting),
    ) {
        ManagementSingleChoiceFlow {
            LibraryCollectionTab.entries.forEach { collection ->
                ManagementChoiceChip(
                    selected = state.collection == collection,
                    onClick = { onCollectionSelected(collection) },
                    label = collection.label(),
                    singleChoice = true,
                )
            }
        }
    }
    ManagementFilterSectionCard(
        title = stringResource(R.string.management_wallpaper_type),
        supportingText = stringResource(R.string.management_library_type_supporting),
    ) {
        ManagementSingleChoiceFlow {
            LibraryTypeFilter.entries.forEach { filter ->
                ManagementChoiceChip(
                    selected = state.typeFilter == filter,
                    onClick = { onTypeSelected(filter) },
                    label = filter.label(),
                    singleChoice = true,
                )
            }
        }
    }
    ManagementFilterSectionCard(
        title = stringResource(R.string.management_browsing_mode),
        supportingText = stringResource(R.string.management_browsing_mode_supporting),
    ) {
        ManagementSingleChoiceFlow {
            HomePaginationMode.entries.forEach { mode ->
                ManagementChoiceChip(
                    selected = state.paginationMode == mode,
                    onClick = { onPaginationModeSelected(mode) },
                    label = mode.libraryLabel(),
                    singleChoice = true,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManagementLocalFilterSections(
    state: LocalWallpaperUiState,
    onAction: (LocalWallpaperAction) -> Unit,
) {
    ManagementFilterSectionCard(
        title = stringResource(R.string.management_format),
        supportingText = stringResource(R.string.management_format_supporting),
    ) {
        ManagementSingleChoiceFlow {
            LocalWallpaperFormatFilter.entries.forEach { filter ->
                ManagementChoiceChip(
                    selected = state.formatFilter == filter,
                    onClick = { onAction(LocalWallpaperAction.SelectFormatFilter(filter)) },
                    label = filter.managementLabel(),
                    singleChoice = true,
                )
            }
        }
    }
    ManagementFilterSectionCard(
        title = stringResource(R.string.management_import_state),
        supportingText = stringResource(R.string.management_import_state_supporting),
    ) {
        ManagementSingleChoiceFlow {
            LocalWallpaperImportFilter.entries.forEach { filter ->
                ManagementChoiceChip(
                    selected = state.importFilter == filter,
                    onClick = { onAction(LocalWallpaperAction.SelectImportFilter(filter)) },
                    label = filter.managementLabel(),
                    singleChoice = true,
                )
            }
        }
    }
    ManagementFilterSectionCard(
        title = stringResource(R.string.management_source),
        supportingText = stringResource(R.string.management_source_supporting),
    ) {
        ManagementSingleChoiceFlow {
            ManagementChoiceChip(
                selected = state.sourceId == null,
                onClick = { onAction(LocalWallpaperAction.SelectSource(null)) },
                label = stringResource(R.string.management_all_locations),
                singleChoice = true,
            )
            state.scan.sources.forEach { source ->
                ManagementChoiceChip(
                    selected = state.sourceId == source.id,
                    onClick = { onAction(LocalWallpaperAction.SelectSource(source.id)) },
                    label = source.label,
                    singleChoice = true,
                )
            }
        }
    }
    ManagementLocalOrganizationFilters(
        state = state,
        onAction = onAction,
    )
    ManagementFilterSectionCard(
        title = stringResource(R.string.management_sort),
        supportingText = stringResource(R.string.management_sort_supporting),
    ) {
        ManagementSingleChoiceFlow {
            LocalWallpaperSort.entries.forEach { sort ->
                ManagementChoiceChip(
                    selected = state.sort == sort,
                    onClick = { onAction(LocalWallpaperAction.SelectSort(sort)) },
                    label = sort.managementLabel(),
                    singleChoice = true,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManagementLocalOrganizationFilters(
    state: LocalWallpaperUiState,
    onAction: (LocalWallpaperAction) -> Unit,
) {
    ManagementFilterSectionCard(
        title = stringResource(R.string.management_organization),
        supportingText = stringResource(R.string.management_organization_supporting),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ManagementChoiceChip(
                selected = state.favoriteOnly,
                onClick = { onAction(LocalWallpaperAction.SetFavoriteOnly(!state.favoriteOnly)) },
                label = stringResource(R.string.management_favorites_only),
            )
        }
        ManagementSingleChoiceFlow {
            ManagementChoiceChip(
                selected = state.selectedTag == null,
                onClick = { onAction(LocalWallpaperAction.SelectTag(null)) },
                label = stringResource(R.string.management_all_tags),
                singleChoice = true,
            )
            state.allTags.forEach { tag ->
                ManagementChoiceChip(
                    selected = state.selectedTag == tag,
                    onClick = { onAction(LocalWallpaperAction.SelectTag(tag)) },
                    label = tag,
                    singleChoice = true,
                )
            }
        }
    }
}

@Composable
private fun ManagementContent.label(): String = stringResource(labelRes())

@StringRes
private fun ManagementContent.labelRes(): Int =
    when (this) {
        ManagementContent.DOWNLOADS -> R.string.management_downloads
        ManagementContent.LIBRARY -> R.string.management_library
        ManagementContent.LOCAL -> R.string.management_local
    }

@Composable
private fun ManagementContent.description(): String =
    stringResource(
        when (this) {
            ManagementContent.DOWNLOADS -> R.string.management_downloads_description
            ManagementContent.LIBRARY -> R.string.management_library_description
            ManagementContent.LOCAL -> R.string.management_local_description
        },
    )

private fun ManagementContent.icon(): ImageVector =
    when (this) {
        ManagementContent.DOWNLOADS -> Icons.Outlined.Download
        ManagementContent.LIBRARY -> Icons.Outlined.Bookmarks
        ManagementContent.LOCAL -> Icons.Outlined.FolderOpen
    }

@Composable
private fun ManagementContent.filterTitle(): String =
    stringResource(
        when (this) {
            ManagementContent.DOWNLOADS -> R.string.management_download_filters
            ManagementContent.LIBRARY -> R.string.management_library_filters
            ManagementContent.LOCAL -> R.string.management_local_filters
        },
    )

private fun ManagementContent.activeFilterCount(
    downloadsState: DownloadsUiState,
    libraryState: LibraryUiState,
    localWallpaperState: LocalWallpaperUiState,
): Int =
    when (this) {
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

@Composable
private fun LocalWallpaperFormatFilter.managementLabel(): String =
    when (this) {
        LocalWallpaperFormatFilter.ALL -> stringResource(R.string.management_all)
        LocalWallpaperFormatFilter.MPKG -> "MPKG"
        LocalWallpaperFormatFilter.PKG -> "PKG"
        LocalWallpaperFormatFilter.VIDEO -> stringResource(R.string.management_video)
        LocalWallpaperFormatFilter.HTML -> "HTML"
        LocalWallpaperFormatFilter.UNKNOWN -> stringResource(R.string.management_unknown)
    }

@Composable
private fun LocalWallpaperImportFilter.managementLabel(): String =
    when (this) {
        LocalWallpaperImportFilter.ALL -> stringResource(R.string.management_all)
        LocalWallpaperImportFilter.NOT_IMPORTED -> stringResource(R.string.management_not_imported)
        LocalWallpaperImportFilter.IMPORT_REQUESTED -> stringResource(R.string.management_import_requested)
    }

@Composable
private fun LocalWallpaperSort.managementLabel(): String =
    when (this) {
        LocalWallpaperSort.RECENT -> stringResource(R.string.management_recent)
        LocalWallpaperSort.NAME -> stringResource(R.string.management_name)
        LocalWallpaperSort.SIZE -> stringResource(R.string.management_size)
        LocalWallpaperSort.TYPE -> stringResource(R.string.management_type)
    }

@Composable
private fun DownloadFilter.label(): String =
    when (this) {
        DownloadFilter.ALL -> stringResource(R.string.management_all)
        DownloadFilter.COMPLETED -> stringResource(R.string.management_completed)
        DownloadFilter.DOWNLOADING -> stringResource(R.string.management_active)
        DownloadFilter.QUEUED -> stringResource(R.string.management_queued)
        DownloadFilter.FAILED -> stringResource(R.string.management_failed)
    }

@Composable
private fun DownloadTypeFilter.label(): String =
    when (this) {
        DownloadTypeFilter.ALL -> stringResource(R.string.management_all)
        DownloadTypeFilter.VIDEO -> stringResource(R.string.management_video)
        DownloadTypeFilter.SCENE -> stringResource(R.string.management_scene)
        DownloadTypeFilter.WEB -> stringResource(R.string.management_web)
    }

@Composable
private fun LibraryCollectionTab.label(): String =
    when (this) {
        LibraryCollectionTab.SUBSCRIPTIONS -> stringResource(R.string.management_subscriptions)
        LibraryCollectionTab.FAVORITES -> stringResource(R.string.management_favorites)
        LibraryCollectionTab.VOTED -> stringResource(R.string.management_voted)
    }

@Composable
private fun LibraryTypeFilter.label(): String =
    when (this) {
        LibraryTypeFilter.ALL -> stringResource(R.string.management_all)
        LibraryTypeFilter.VIDEO -> stringResource(R.string.management_video)
        LibraryTypeFilter.SCENE -> stringResource(R.string.management_scene)
        LibraryTypeFilter.WEB -> stringResource(R.string.management_web)
    }

@Composable
private fun HomePaginationMode.libraryLabel(): String =
    when (this) {
        HomePaginationMode.INFINITE_SCROLL -> stringResource(R.string.management_infinite_scroll)
        HomePaginationMode.PAGED -> stringResource(R.string.management_page_controls)
    }

@Composable
private fun Modifier.managementContextMenuBackdrop(active: Boolean): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec =
            tween(
                durationMillis =
                    if (active) {
                        MANAGEMENT_CONTEXT_MENU_ENTER_DURATION_MS
                    } else {
                        MANAGEMENT_CONTEXT_MENU_EXIT_DURATION_MS
                    },
                easing = MANAGEMENT_CONTEXT_MENU_EASING,
            ),
        label = "ManagementContextMenuBackdrop",
    )
    if (!active && progress <= 0f) return this
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val scrim =
        MaterialTheme.colorScheme.scrim.copy(
            alpha = (if (dark) MANAGEMENT_CONTEXT_MENU_DARK_SCRIM_ALPHA else MANAGEMENT_CONTEXT_MENU_LIGHT_SCRIM_ALPHA) * progress,
        )
    val scrimModifier =
        drawWithContent {
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
