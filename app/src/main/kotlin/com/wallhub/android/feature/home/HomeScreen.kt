@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.home

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.wallhub.android.NAVIGATION_EASING
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.LocalWallHubAdaptiveLayout
import com.wallhub.android.core.designsystem.LocalWallHubOverlayBottomClearance
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubLoadingLayout
import com.wallhub.android.core.designsystem.WallHubPageLoading
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubPaginationControl
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubWindowLayout
import com.wallhub.android.core.designsystem.wallHubStyle
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.designsystem.rememberWallHubDirectionalCollapseConnection
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.WorkshopType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.uwuaosp.compose.settingslib.SettingsToolbarActionButton
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    scrollToTopRequest: Int = 0,
    gridState: LazyGridState = rememberLazyGridState(),
    cardScene: HomeCardSceneState = remember { HomeCardSceneState() },
    onContextMenuActiveChanged: (Boolean) -> Unit = {},
    onBottomNavigationCollapsedChanged: (Boolean) -> Unit = {},
    floatingBottomBarAutoHideActive: Boolean = false,
    floatingNavigation: @Composable BoxScope.() -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val pullToRefreshState = rememberPullToRefreshState()
    val showPullToRefresh = state.showPullToRefresh()
    val homeChromeAutoHideActive = state.homeBottomBarAutoHide && floatingBottomBarAutoHideActive
    var bottomNavigationCollapsed by remember { mutableStateOf(false) }
    val activeItemId = cardScene.activeItemId
    val bottomNavigationCollapseConnection =
        rememberWallHubDirectionalCollapseConnection(
            collapsed = bottomNavigationCollapsed,
            onCollapsedChanged = { collapsed ->
                if (
                    homeChromeAutoHideActive &&
                    (!collapsed || gridState.canScrollForward)
                ) {
                    bottomNavigationCollapsed = collapsed
                    onBottomNavigationCollapsedChanged(collapsed)
                }
            },
        )
    LaunchedEffect(homeChromeAutoHideActive) {
        if (!homeChromeAutoHideActive) {
            bottomNavigationCollapsed = false
            onBottomNavigationCollapsedChanged(false)
        }
    }
    DisposableEffect(Unit) {
        onDispose { onBottomNavigationCollapsedChanged(false) }
    }
    val filterDrawerState = rememberDrawerState(DrawerValue.Closed)
    val filterDrawerScope = rememberCoroutineScope()
    val filterDrawerModalActive =
        filterDrawerState.currentValue == DrawerValue.Open ||
            filterDrawerState.targetValue == DrawerValue.Open
    val filterDrawerTitle = stringResource(R.string.home_filter_drawer_title)
    LaunchedEffect(filterDrawerModalActive, activeItemId, cardScene.closing) {
        onContextMenuActiveChanged(
            filterDrawerModalActive || (activeItemId != null && !cardScene.closing),
        )
    }
    DisposableEffect(Unit) {
        onDispose { onContextMenuActiveChanged(false) }
    }
    BackHandler(enabled = filterDrawerModalActive) {
        filterDrawerScope.launch { filterDrawerState.close() }
    }
    var handledScrollToTopRequest by rememberSaveable { mutableIntStateOf(scrollToTopRequest) }
    var handledSearchToken by remember { mutableLongStateOf(state.successfulSearchToken) }
    var searchBoundsInRoot by remember { mutableStateOf<IntRect?>(null) }
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > handledScrollToTopRequest) {
            val isAtTop =
                gridState.firstVisibleItemIndex == 0 &&
                    gridState.firstVisibleItemScrollOffset == 0
            if (isAtTop && !state.isInitialLoading && !state.isLoadingMore) {
                onAction(HomeAction.Refresh)
            } else {
                gridState.animateScrollToItem(0)
            }
            handledScrollToTopRequest = scrollToTopRequest
        }
    }
    LaunchedEffect(state.successfulSearchToken) {
        if (state.successfulSearchToken > handledSearchToken) {
            gridState.scrollToItem(0)
            handledSearchToken = state.successfulSearchToken
        }
    }
    val filtersCollapsed by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 ||
                gridState.firstVisibleItemScrollOffset > FILTER_COLLAPSE_OFFSET_PX
        }
    }
    ModalNavigationDrawer(
        drawerState = filterDrawerState,
        gesturesEnabled = filterDrawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .widthIn(max = 420.dp)
                        .imePadding()
                        .semantics { paneTitle = filterDrawerTitle },
                windowInsets =
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Vertical + WindowInsetsSides.Start,
                    ),
            ) {
                HomeFilterDrawer(
                    state = state,
                    isOpen = filterDrawerModalActive,
                    onAction = onAction,
                    onDismiss = { filterDrawerScope.launch { filterDrawerState.close() } },
                    title = filterDrawerTitle,
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val chromeHidden = filterDrawerModalActive || (activeItemId != null && !cardScene.closing)
            var homeContentTopPx by remember { mutableStateOf(0) }
            val homeTopMaskPx =
                with(LocalDensity.current) {
                    (32.dp + WindowInsets.statusBars.getTop(this).toDp()).toPx()
                }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(bottomNavigationCollapseConnection),
            ) {
                HomeScreenFrame(
                    state = state,
                    onAction = onAction,
                    onOpenSettings = onOpenSettings,
                    onBack = onBack,
                    gridState = gridState,
                    cardScene = cardScene,
                    filtersCollapsed = filtersCollapsed,
                    filtersOpen = filterDrawerModalActive,
                    bottomNavigationCollapsed = bottomNavigationCollapsed,
                    onOpenFilters = { filterDrawerScope.launch { filterDrawerState.open() } },
                    searchBoundsInRoot = searchBoundsInRoot,
                    onSearchBoundsChanged = { searchBoundsInRoot = it },
                    focusManager = focusManager,
                    chromeHidden = chromeHidden,
                    pullToRefreshState = pullToRefreshState,
                    showPullToRefresh = showPullToRefresh,
                    onContentTopChanged = { top ->
                        if (top > 0 && top != homeContentTopPx) homeContentTopPx = top
                    },
                )
            }
            cardScene.hostContent?.let { content ->
                if (activeItemId != null) {
                    HomeCardQuickSheet(
                        content = content,
                        itemId = activeItemId,
                        onDismiss = { cardScene.finishClose() },
                    )
                }
            }
            PullToRefreshDefaults.Indicator(
                state = pullToRefreshState,
                isRefreshing = showPullToRefresh,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(
                            top =
                                with(LocalDensity.current) { homeTopMaskPx.toDp() } +
                                    WallHubSpacing.xs,
                        )
                        .size(48.dp),
            )
            val searchBottomPadding =
                LocalWallHubOverlayBottomClearance.current + WallHubSpacing.xs
            AnimatedVisibility(
                visible =
                    state.homeSearchFab &&
                        onBack == null &&
                        !bottomNavigationCollapsed &&
                        !chromeHidden &&
                        activeItemId == null,
                modifier = Modifier.align(Alignment.BottomEnd),
                enter =
                    fadeIn(tween(180)) +
                        slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                        ),
                exit =
                    fadeOut(tween(120)) +
                        slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                        ),
            ) {
                HomeFloatingSearch(
                    state = state,
                    showFilters = filterDrawerModalActive,
                    onQueryChanged = { onAction(HomeAction.QueryChanged(it)) },
                    onSubmitSearch = { onAction(HomeAction.SubmitSearch) },
                    onClear = { onAction(HomeAction.QueryChanged("")) },
                    onToggleFilters = { filterDrawerScope.launch { filterDrawerState.open() } },
                    onSearchBoundsChanged = { searchBoundsInRoot = it },
                    modifier =
                        Modifier
                            .padding(horizontal = WallHubSpacing.md)
                            .imePadding()
                            .padding(bottom = searchBottomPadding),
                )
            }
            if (filterDrawerModalActive) floatingNavigation()
        }
    }
}

private val HOME_FLOATING_SEARCH_SIZE = 64.dp
private val HOME_FLOATING_SEARCH_MAX_WIDTH = 560.dp
private val HOME_FLOATING_FILTER_SLOT_WIDTH = 56.dp + WallHubSpacing.xs
private const val HOME_FLOATING_FILTER_FADE_DURATION_MS = 140

@Composable
private fun HomeScreenFrame(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    onOpenSettings: (() -> Unit)?,
    onBack: (() -> Unit)?,
    gridState: LazyGridState,
    cardScene: HomeCardSceneState,
    filtersCollapsed: Boolean,
    filtersOpen: Boolean,
    bottomNavigationCollapsed: Boolean,
    onOpenFilters: () -> Unit,
    searchBoundsInRoot: IntRect?,
    onSearchBoundsChanged: (IntRect) -> Unit,
    focusManager: androidx.compose.ui.focus.FocusManager,
    chromeHidden: Boolean,
    pullToRefreshState: PullToRefreshState,
    showPullToRefresh: Boolean,
    onContentTopChanged: (Int) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(searchBoundsInRoot) {
                    awaitEachGesture {
                        val down =
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                        val point =
                            IntOffset(
                                down.position.x.roundToInt(),
                                down.position.y.roundToInt(),
                            )
                        if (searchBoundsInRoot?.contains(point) != true) {
                            focusManager.clearFocus(force = true)
                        }
                    }
                },
    ) {
        HomeScreenBody(
            state = state,
            onAction = onAction,
            onOpenSettings = onOpenSettings,
            onBack = onBack,
            gridState = gridState,
            filtersCollapsed = filtersCollapsed,
            filtersOpen = filtersOpen,
            bottomNavigationCollapsed = bottomNavigationCollapsed,
            onOpenFilters = onOpenFilters,
            onSearchBoundsChanged = onSearchBoundsChanged,
            focusManager = focusManager,
            cardScene = cardScene,
            chromeHidden = chromeHidden,
            pullToRefreshState = pullToRefreshState,
            showPullToRefresh = showPullToRefresh,
            onContentTopChanged = onContentTopChanged,
        )
    }
}

@Composable
private fun HomeScreenBody(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    onOpenSettings: (() -> Unit)?,
    onBack: (() -> Unit)?,
    gridState: LazyGridState,
    cardScene: HomeCardSceneState,
    filtersCollapsed: Boolean,
    filtersOpen: Boolean,
    bottomNavigationCollapsed: Boolean,
    onOpenFilters: () -> Unit,
    onSearchBoundsChanged: (IntRect) -> Unit,
    focusManager: androidx.compose.ui.focus.FocusManager,
    chromeHidden: Boolean,
    pullToRefreshState: PullToRefreshState,
    showPullToRefresh: Boolean,
    onContentTopChanged: (Int) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val useFloatingSearch = state.homeSearchFab && onBack == null
    val navigationClearance = LocalWallHubOverlayBottomClearance.current + WallHubSpacing.xs
    val searchBottomPadding = navigationClearance
    val searchClearance =
        if (useFloatingSearch) {
            searchBottomPadding + HOME_FLOATING_SEARCH_SIZE + WallHubSpacing.xs
        } else {
            WallHubSpacing.xs
        }
    val adaptiveLayout = LocalWallHubAdaptiveLayout.current
    val homeDataSourceLabel = state.homeDataSourceLabel()
    val homeLoadingSummary = state.homeLoadingSummary()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gridEndpointColumns =
            effectiveHomeGridColumns(
                availableWidth = maxWidth - HOME_GRID_HORIZONTAL_PADDING * 2,
                configuredColumns = state.columns,
                windowLayout = adaptiveLayout.width,
                spacing = HOME_GRID_ITEM_SPACING,
            )
        val gridEndpointWidth =
            (
                maxWidth -
                    HOME_GRID_HORIZONTAL_PADDING * 2 -
                    HOME_GRID_ITEM_SPACING * (gridEndpointColumns - 1).toFloat()
            ).coerceAtLeast(WallHubSpacing.none) / gridEndpointColumns.toFloat()
        val coverRequestSizePx =
            with(LocalDensity.current) {
                maxOf(LIST_CARD_MEDIA_SIZE, gridEndpointWidth).roundToPx()
            }
        val showScrollingPadToolbar =
            adaptiveLayout.width == WallHubWindowLayout.EXPANDED &&
                onBack == null &&
                state.items.isNotEmpty()
        val toolbarActions: @Composable RowScope.() -> Unit = {
            HomeViewModeToggle(
                selected = state.viewMode,
                onViewModeSelected = { targetMode ->
                    onAction(HomeAction.SelectViewMode(targetMode))
                },
                gridContentDescription = stringResource(R.string.home_grid_view),
                listContentDescription = stringResource(R.string.home_list_view),
            )
        }
        WallHubPageScaffold(
            title = stringResource(R.string.app_name),
            titleContent = {
                if (adaptiveLayout.width != WallHubWindowLayout.EXPANDED) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            modifier =
                                Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = stringResource(R.string.home_reset),
                                    ) {
                                        focusManager.clearFocus(force = true)
                                        onAction(HomeAction.ResetAndRefresh)
                                    },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                        )
                        Spacer(modifier = Modifier.width(WallHubSpacing.xs))
                        Text(
                            text = homeDataSourceLabel,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.showHomeTopInfo) {
                        Text(
                            text = homeLoadingSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    }
                }
            },
            showAppBar = onBack == null && !showScrollingPadToolbar,
            actions = toolbarActions,
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            ) {
                if (!useFloatingSearch) {
                    HomePersistentSearchBar(
                        state = state,
                        onQueryChanged = { onAction(HomeAction.QueryChanged(it)) },
                        onSubmitSearch = { onAction(HomeAction.SubmitSearch) },
                        onSearchBoundsChanged = onSearchBoundsChanged,
                        onClear = { onAction(HomeAction.QueryChanged("")) },
                        onBack = onBack,
                        showFilters = filtersOpen,
                        onToggleFilters = onOpenFilters,
                    )
                }
                HomeResults(
                    state = state,
                    scrollingHeader =
                        if (showScrollingPadToolbar) {
                            {
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(64.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (state.showHomeTopInfo) {
                                        Column {
                                            Text(
                                                text = homeDataSourceLabel,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = homeLoadingSummary,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                    toolbarActions()
                                }
                            }
                        } else {
                            null
                        },
                    onRetry = { onAction(HomeAction.Refresh) },
                    onLoadNextPage = { onAction(HomeAction.LoadNextPage) },
                    onPageSelected = { page ->
                        onAction(HomeAction.SelectPage(page))
                        coroutineScope.launch { gridState.animateScrollToItem(0) }
                    },
                    onOpenDetail = { onAction(HomeAction.OpenDetail(it)) },
                    onPrimaryAction = { item ->
                        when (state.cardAction) {
                            HomeCardAction.DOWNLOAD -> onAction(HomeAction.RequestDownload(item))
                            HomeCardAction.PLAY_VIDEO ->
                                if (item.type == WorkshopType.VIDEO) {
                                    onAction(HomeAction.OpenDetail(item.id))
                                } else {
                                    onAction(HomeAction.RequestDownload(item))
                                }
                            HomeCardAction.OPEN_STEAM -> onAction(HomeAction.OpenSteam(item.id))
                        }
                    },
                    onDownload = { onAction(HomeAction.RequestDownload(it)) },
                    onSearchAuthor = { onAction(HomeAction.SearchAuthor(it)) },
                    onCopyText = { text, message -> onAction(HomeAction.CopyText(text, message)) },
                    onOpenSteam = { onAction(HomeAction.OpenSteam(it)) },
                    onAuthorNameRequested = { onAction(HomeAction.RequestAuthorDisplayName(it)) },
                    gridState = gridState,
                    coverRequestSizePx = coverRequestSizePx,
                    cardScene = cardScene,
                    pullToRefreshState = pullToRefreshState,
                    showPullToRefresh = showPullToRefresh,
                    onContentTopChanged = onContentTopChanged,
                    bottomContentPadding = maxOf(navigationClearance, searchClearance),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HomeUiState.homeDataSourceLabel(): String =
    when (steamWorkshopDataSource) {
        SteamWorkshopDataSource.COMMUNITY_HTML -> stringResource(R.string.home_source_community)
        SteamWorkshopDataSource.WEB_API -> stringResource(R.string.home_source_web_api)
        SteamWorkshopDataSource.CM_WEBSOCKET -> stringResource(R.string.home_source_steam_cm)
    }

@Composable
private fun HomeUiState.homeLoadingSummary(): String {
    return stringResource(
        R.string.home_loading_summary,
        items.size,
        totalCount ?: items.size,
        currentPage,
        totalPages,
    )
}

@Composable
private fun HomeFloatingSearch(
    state: HomeUiState,
    showFilters: Boolean,
    onQueryChanged: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onClear: () -> Unit,
    onToggleFilters: () -> Unit,
    onSearchBoundsChanged: (IntRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val expandedWidth = maxWidth.coerceAtMost(HOME_FLOATING_SEARCH_MAX_WIDTH)
        val width by animateDpAsState(
            targetValue = if (expanded) expandedWidth else HOME_FLOATING_SEARCH_SIZE,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "HomeFloatingSearchWidth",
        )
        val filterSlotWidth by animateDpAsState(
            targetValue = if (expanded) HOME_FLOATING_FILTER_SLOT_WIDTH else 0.dp,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "HomeFloatingFilterSlotWidth",
        )
        val filterButtonAlpha by animateFloatAsState(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = tween(durationMillis = HOME_FLOATING_FILTER_FADE_DURATION_MS),
            label = "HomeFloatingFilterButtonAlpha",
        )
        Column(
            modifier = Modifier.width(width).align(Alignment.BottomEnd),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(filterSlotWidth)
                            .clipToBounds(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    HomeFilterToggleButton(
                        state = state,
                        showFilters = showFilters,
                        onClick = onToggleFilters,
                        alpha = filterButtonAlpha,
                        enabled = expanded,
                    )
                }
                Surface(
                    onClick = { expanded = true },
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(HOME_FLOATING_SEARCH_SIZE)
                            .onGloballyPositioned { coordinates ->
                                val topLeft = coordinates.positionInRoot()
                                onSearchBoundsChanged(
                                    IntRect(
                                        left = topLeft.x.roundToInt(),
                                        top = topLeft.y.roundToInt(),
                                        right = topLeft.x.roundToInt() + coordinates.size.width,
                                        bottom = topLeft.y.roundToInt() + coordinates.size.height,
                                    ),
                                )
                            },
                    shape =
                        if (wallHubStyle().capabilities.bettboxSurfaces) {
                            RoundedCornerShape(if (expanded) 28.dp else 100.dp)
                        } else {
                            RoundedCornerShape(20.dp)
                        },
                    color = wallHubStyle().surfaces.searchFab,
                    contentColor = wallHubStyle().surfaces.searchFabContent,
                    shadowElevation = 6.dp,
                ) {
                    if (!expanded) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.home_search))
                        }
                    } else {
                        BasicTextField(
                            value = state.query,
                            onValueChange = onQueryChanged,
                            modifier = Modifier.fillMaxSize(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = wallHubStyle().surfaces.searchFabContent),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmitSearch() }),
                            decorationBox = { innerTextField ->
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(start = WallHubSpacing.md, end = WallHubSpacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Outlined.Search, contentDescription = null)
                                    Spacer(Modifier.width(WallHubSpacing.sm))
                                    Box(Modifier.weight(1f)) {
                                        if (state.query.isBlank()) {
                                            Text(
                                                stringResource(R.string.home_search_workshop),
                                                color = wallHubStyle().surfaces.searchFabContent.copy(alpha = 0.7f),
                                            )
                                        }
                                        innerTextField()
                                    }
                                    IconButton(
                                        onClick = {
                                            if (state.query.isNotBlank()) {
                                                onClear()
                                            } else {
                                                if (showFilters) onToggleFilters()
                                                focusManager.clearFocus(force = true)
                                                expanded = false
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Outlined.Cancel, contentDescription = stringResource(R.string.home_filter_clear))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomePersistentSearchBar(
    state: HomeUiState,
    onQueryChanged: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onSearchBoundsChanged: (IntRect) -> Unit,
    onClear: () -> Unit,
    onBack: (() -> Unit)? = null,
    showFilters: Boolean = false,
    onToggleFilters: () -> Unit = {},
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(wallHubStyle().surfaces.page)
                .padding(start = WallHubSpacing.md, end = WallHubSpacing.xs)
                .padding(bottom = WallHubSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
    ) {
        onBack?.let { back ->
            SettingsToolbarActionButton(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.home_back),
                onClick = back,
                buttonSize = 56.dp,
                containerSize = 56.dp,
            )
        }
        Surface(
            modifier =
                Modifier
                    .weight(1f)
                    .height(56.dp)
                .onGloballyPositioned { coordinates ->
                    val topLeft = coordinates.positionInRoot()
                    onSearchBoundsChanged(
                        IntRect(
                            left = topLeft.x.roundToInt(),
                            top = topLeft.y.roundToInt(),
                            right = topLeft.x.roundToInt() + coordinates.size.width,
                            bottom = topLeft.y.roundToInt() + coordinates.size.height,
                        ),
                    )
                },
            shape = RoundedCornerShape(100.dp),
            color = wallHubStyle().field.container,
        ) {
            BasicTextField(
                value = state.query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxSize(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmitSearch() }),
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier.padding(start = WallHubSpacing.md, end = WallHubSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(WallHubSpacing.sm))
                        Box(modifier = Modifier.weight(1f)) {
                            if (state.query.isBlank()) {
                                Text(
                                    text = stringResource(R.string.home_search_workshop),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                        if (state.query.isNotBlank()) {
                            IconButton(onClick = onClear) {
                                Icon(Icons.Outlined.Cancel, contentDescription = stringResource(R.string.home_search))
                            }
                        }
                    }
                },
            )
        }
        HomeFilterToggleButton(
            state = state,
            showFilters = showFilters,
            onClick = onToggleFilters,
        )
    }
}

@Composable
private fun HomeFilterToggleButton(
    state: HomeUiState,
    showFilters: Boolean,
    onClick: () -> Unit,
    alpha: Float = 1f,
    enabled: Boolean = true,
) {
    val hasActiveFilters = state.activeFilterCount > 0 || state.exactPhrase
    val style = wallHubStyle()
    Surface(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.size(56.dp).graphicsLayer { this.alpha = alpha },
        shape =
            if (style.capabilities.bettboxSurfaces) {
                RoundedCornerShape(if (showFilters) 16.dp else 100.dp)
            } else {
                RoundedCornerShape(20.dp)
            },
        color =
            if (hasActiveFilters) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                style.surfaces.controlStrong
            },
        contentColor =
            if (hasActiveFilters) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.FilterAlt,
                contentDescription = stringResource(R.string.home_open_all_filters),
            )
        }
    }
}

@Composable
internal fun HomeFlatCard(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    color: Color = Color.Unspecified,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    clipContent: Boolean = true,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val style = wallHubStyle()
    val resolvedShape = shape ?: style.shapes.card
    val resolvedColor = if (color == Color.Unspecified) style.surfaces.content else color
    val clickableModifier =
        if (onClick == null) {
            Modifier
        } else {
            Modifier.clickable(enabled = enabled, onClick = onClick)
        }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier =
                modifier
                    .then(
                        if (style.elevations.card > 0.dp) {
                            Modifier.shadow(style.elevations.card, resolvedShape, clip = false)
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (clipContent) {
                            Modifier.clip(resolvedShape).background(resolvedColor)
                        } else {
                            Modifier.background(resolvedColor, resolvedShape)
                        },
                    )
                    .then(if (onClick != null) Modifier.clip(resolvedShape) else Modifier)
                    .then(clickableModifier),
            content = content,
        )
    }
}

@Composable
private fun HomeViewModeToggle(
    selected: HomeViewMode,
    onViewModeSelected: (HomeViewMode) -> Unit,
    gridContentDescription: String,
    listContentDescription: String,
) {
    val borderless = BorderStroke(0.dp, Color.Transparent)
    val toggleColors =
        SegmentedButtonDefaults.colors(
            activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            inactiveContainerColor = wallHubStyle().surfaces.control,
            inactiveContentColor = MaterialTheme.colorScheme.onSurface,
        )
    SingleChoiceSegmentedButtonRow {
        SegmentedButton(
            selected = selected == HomeViewMode.GRID,
            onClick = { onViewModeSelected(HomeViewMode.GRID) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.size(40.dp),
            contentPadding = PaddingValues(0.dp),
            border = borderless,
            colors = toggleColors,
            icon = {},
            label = {
                Icon(
                    imageVector = Icons.Outlined.GridView,
                    contentDescription = gridContentDescription,
                    modifier = Modifier.size(20.dp),
                )
            },
        )
        SegmentedButton(
            selected = selected == HomeViewMode.LIST,
            onClick = { onViewModeSelected(HomeViewMode.LIST) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.size(40.dp),
            contentPadding = PaddingValues(0.dp),
            border = borderless,
            colors = toggleColors,
            icon = {},
            label = {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ViewList,
                    contentDescription = listContentDescription,
                    modifier = Modifier.size(20.dp),
                )
            },
        )
    }
}

private enum class HomeResultsPresentation {
    Loading,
    Error,
    Empty,
    Content,
}

@Composable
internal fun HomeResults(
    state: HomeUiState,
    scrollingHeader: (@Composable () -> Unit)? = null,
    onRetry: () -> Unit,
    onLoadNextPage: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onPrimaryAction: (WorkshopSummary) -> Unit,
    onDownload: (WorkshopSummary) -> Unit,
    onSearchAuthor: (String) -> Unit,
    onCopyText: (String, Int) -> Unit,
    onOpenSteam: (Long) -> Unit,
    onAuthorNameRequested: (WorkshopSummary) -> Unit,
    gridState: LazyGridState,
    coverRequestSizePx: Int,
    cardScene: HomeCardSceneState,
    pullToRefreshState: PullToRefreshState,
    showPullToRefresh: Boolean,
    onContentTopChanged: (Int) -> Unit,
    bottomContentPadding: Dp = WallHubSpacing.xs,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val leadingGridItemCount = if (scrollingHeader != null) 1 else 0
    val prefetchBudget by rememberHomeCoverPrefetchBudget(context)
    val shouldAutoLoadMore by remember(gridState, state, leadingGridItemCount) {
        derivedStateOf {
            if (
                state.paginationMode != HomePaginationMode.INFINITE_SCROLL ||
                state.isInitialLoading ||
                state.isLoadingMore ||
                !state.hasNextPage
            ) {
                false
            } else {
                val lastVisibleIndex =
                    gridState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index
                        ?.minus(leadingGridItemCount) ?: -1
                lastVisibleIndex >= (state.items.lastIndex - HOME_AUTO_LOAD_MORE_THRESHOLD).coerceAtLeast(0)
            }
        }
    }
    LaunchedEffect(shouldAutoLoadMore, state.nextPage, state.paginationMode) {
        if (shouldAutoLoadMore) onLoadNextPage()
    }
    PullToRefreshBox(
        isRefreshing = showPullToRefresh,
        onRefresh = onRetry,
        state = pullToRefreshState,
        indicator = {},
        modifier =
            modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    onContentTopChanged(coordinates.positionInRoot().y.roundToInt())
                },
    ) {
        val presentation =
            when {
                state.isInitialLoading -> HomeResultsPresentation.Loading
                (state.error != null || state.errorRes != null) && state.items.isEmpty() -> HomeResultsPresentation.Error
                state.items.isEmpty() -> HomeResultsPresentation.Empty
                else -> HomeResultsPresentation.Content
            }
        Crossfade(
            targetState = presentation,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            modifier = Modifier.fillMaxSize(),
            label = "HomeResultsPresentation",
        ) { targetPresentation ->
            when (targetPresentation) {
                HomeResultsPresentation.Loading -> {
                WallHubPageLoading(
                    layout = WallHubLoadingLayout.HOME_GRID,
                    contentDescription = stringResource(R.string.home_loading),
                )
            }

                HomeResultsPresentation.Error -> {
                WallHubEmptyState(
                    icon = Icons.Outlined.Refresh,
                    title = state.error ?: stringResource(requireNotNull(state.errorRes)),
                    actionLabel = stringResource(R.string.home_retry),
                    onAction = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            }

                HomeResultsPresentation.Empty -> {
                WallHubEmptyState(
                    icon = Icons.Outlined.Search,
                    title = stringResource(R.string.home_no_matching_wallpapers),
                    actionLabel = stringResource(R.string.home_reload),
                    onAction = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            }

                HomeResultsPresentation.Content -> {
                val contentWidthModifier =
                    if (state.viewMode == HomeViewMode.LIST) {
                        Modifier.widthIn(max = WallHubSizeTokens.readableContentMaxWidth)
                    } else {
                        Modifier
                    }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    BoxWithConstraints(
                        modifier =
                            contentWidthModifier
                                .fillMaxSize(),
                    ) {
                    val effectiveColumns =
                        effectiveHomeGridColumns(
                            availableWidth = maxWidth - HOME_GRID_HORIZONTAL_PADDING * 2,
                            configuredColumns = state.columns,
                            windowLayout = LocalWallHubAdaptiveLayout.current.width,
                            spacing = HOME_GRID_ITEM_SPACING,
                        )
                    val layoutKey = HomeCardLayoutKey.resolve(state.viewMode, effectiveColumns)
                    LaunchedEffect(layoutKey) {
                        if (cardScene.activeItemId != null) cardScene.finishClose()
                    }
                    val contentWidth = (maxWidth - HOME_GRID_HORIZONTAL_PADDING * 2).coerceAtLeast(WallHubSpacing.none)
                    val gridCardWidth =
                        (
                            contentWidth -
                                HOME_GRID_ITEM_SPACING * (effectiveColumns - 1).toFloat()
                        ).coerceAtLeast(WallHubSpacing.none) / effectiveColumns.toFloat()
                    val gridStatisticsAvailableWidth =
                        (gridCardWidth - GRID_CARD_COPY_HORIZONTAL_PADDING).coerceAtLeast(WallHubSpacing.none)
                    val listStatisticsAvailableWidth =
                        (
                            contentWidth -
                                LIST_CARD_MEDIA_SIZE -
                                LIST_CARD_ACTION_SIZE -
                                LIST_CARD_ACTION_END_PADDING -
                                LIST_CARD_COPY_HORIZONTAL_PADDING
                        ).coerceAtLeast(WallHubSpacing.none)
                    val cardMetricsDensity = LocalDensity.current
                    val cardMetrics =
                        remember(layoutKey, gridCardWidth, contentWidth, cardMetricsDensity) {
                            homeCardMetrics(
                                layoutKey = layoutKey,
                                cardWidth = gridCardWidth,
                                contentWidth = contentWidth,
                                density = cardMetricsDensity,
                            )
                        }
                    val coverSizePx = coverRequestSizePx
                    val prefetchBatchKey = state.successfulSearchToken
                    SideEffect {
                        cardScene.updateHostContent(
                            HomeCardHostContent(
                                items = state.items,
                                authorDisplayNames = state.authorDisplayNames,
                                layoutKey = layoutKey,
                                metrics = cardMetrics,
                                coverSizePx = coverSizePx,
                                action = state.cardAction,
                                onOpenDetail = onOpenDetail,
                                onPrimaryAction = onPrimaryAction,
                                onDownload = onDownload,
                                onSearchAuthor = onSearchAuthor,
                                onCopyText = onCopyText,
                                onOpenSteam = onOpenSteam,
                                onAuthorNameRequested = onAuthorNameRequested,
                            ),
                        )
                    }
                    DisposableEffect(cardScene) {
                        onDispose { cardScene.clearHostContent() }
                    }
                    HomeCoverPrefetchEffect(
                        items = state.items,
                        gridState = gridState,
                        columns = layoutKey.effectiveColumns,
                        listMode = layoutKey.listMode,
                        budget = prefetchBudget,
                        coverSizePx = coverSizePx,
                        batchKey = prefetchBatchKey,
                        leadingItemCount = leadingGridItemCount,
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(layoutKey.effectiveColumns),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding =
                            PaddingValues(
                                start = HOME_GRID_HORIZONTAL_PADDING,
                                top = WallHubSpacing.xs,
                                end = HOME_GRID_HORIZONTAL_PADDING,
                                bottom = bottomContentPadding,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(HOME_GRID_ITEM_SPACING),
                        verticalArrangement = Arrangement.spacedBy(HOME_GRID_ITEM_SPACING),
                    ) {
                        scrollingHeader?.let { header ->
                            item(
                                key = "home-scrolling-toolbar",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                header()
                            }
                        }
                        itemsIndexed(
                            items = state.items,
                            key = { _, item -> item.id },
                            contentType = { _, item -> item.type },
                        ) { _, item ->
                            HomeWorkshopCard(
                                item = item,
                                action = state.cardAction,
                                coverSizePx = coverSizePx,
                                onClick = { onOpenDetail(item.id) },
                                onLongPress = {
                                    onAuthorNameRequested(item)
                                    cardScene.open(item.id)
                                },
                                onPrimaryAction = { onPrimaryAction(item) },
                                listMode = layoutKey.listMode,
                                modifier =
                                    Modifier.animateItem(
                                        placementSpec =
                                            spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                    ),
                            )
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            when {
                                state.paginationMode == HomePaginationMode.INFINITE_SCROLL && state.isLoadingMore ->
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(WallHubSpacing.sm),
                                        contentAlignment = Alignment.Center,
                                    ) { CircularProgressIndicator() }

                                state.paginationMode == HomePaginationMode.PAGED ->
                                    HomePagination(
                                        currentPage = state.currentPage,
                                        totalPages = state.totalPages,
                                        isLoading = state.isPageLoading,
                                        onPageSelected = onPageSelected,
                                    )

                                else -> Spacer(modifier = Modifier.height(WallHubSpacing.sm))
                            }
                        }
                    }
                }
                }
            }
        }
    }
}
}

@Composable
private fun HomePagination(
    currentPage: Int,
    totalPages: Int,
    isLoading: Boolean,
    onPageSelected: (Int) -> Unit,
) {
    WallHubPaginationControl(
        currentPage = currentPage,
        totalPages = totalPages,
        isLoading = isLoading,
        currentContentDescription = stringResource(R.string.home_pagination_description, currentPage, totalPages),
        onPageSelected = onPageSelected,
        modifier = Modifier.padding(vertical = WallHubSpacing.xs),
    )
}
