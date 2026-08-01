@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.home

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubContextMenuDefaults
import com.wallhub.android.core.designsystem.WallHubContextMenuSurface
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubToolbarSearchTitle
import com.wallhub.android.core.designsystem.WallHubPaginationControl
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.uwuaosp.compose.settingslib.SettingsToolbarActionButton
import kotlin.math.roundToInt
import com.wallhub.android.core.designsystem.WallHubContextMenuAction as HomeContextMenuItem
import com.wallhub.android.core.designsystem.WallHubContextMenuMetadataItem as HomeContextMenuMetadataItem
import com.wallhub.android.core.designsystem.WallHubContextMenuPositionProvider as HomeContextMenuPositionProvider
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

@Composable
fun HomeScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    scrollToTopRequest: Int = 0,
    onContextMenuActiveChanged: (Boolean) -> Unit = {},
) {
    var filterSheetInitialPage by rememberSaveable { mutableStateOf<HomeFilterPage?>(null) }
    val focusManager = LocalFocusManager.current
    val gridState = rememberLazyGridState()
    var handledScrollToTopRequest by rememberSaveable { mutableIntStateOf(scrollToTopRequest) }
    var handledSearchToken by remember { mutableLongStateOf(state.successfulSearchToken) }
    var searchBoundsInRoot by remember { mutableStateOf<IntRect?>(null) }
    val contextMenuGeometry = remember { HomeContextMenuGeometry() }
    var activeContextMenuTarget by remember { mutableStateOf<HomeContextMenuTarget?>(null) }
    var renderedContextMenuTarget by remember { mutableStateOf<HomeContextMenuTarget?>(null) }
    val contextMenuActive = activeContextMenuTarget != null
    val contextMenuBackdropProgress by animateFloatAsState(
        targetValue = if (contextMenuActive) 1f else 0f,
        animationSpec =
            tween(
                durationMillis =
                    if (contextMenuActive) {
                        WallHubContextMenuDefaults.EnterDurationMillis
                    } else {
                        WallHubContextMenuDefaults.ExitDurationMillis
                    },
                easing = WallHubContextMenuDefaults.Easing,
            ),
        label = "HomeContextMenuBackdrop",
        finishedListener = { completedProgress ->
            if (completedProgress == 0f && activeContextMenuTarget == null) {
                renderedContextMenuTarget = null
            }
        },
    )
    val openContextMenu: (HomeContextMenuTarget) -> Unit = { target ->
        activeContextMenuTarget = target
        renderedContextMenuTarget = target
        onContextMenuActiveChanged(true)
    }
    val dismissContextMenu: (Long) -> Unit = { itemId ->
        if (activeContextMenuTarget?.itemId == itemId) {
            activeContextMenuTarget = null
            onContextMenuActiveChanged(false)
        }
    }
    DisposableEffect(Unit) {
        onDispose { onContextMenuActiveChanged(false) }
    }
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
    HomeScreenFrame(
        state = state,
        onAction = onAction,
        onOpenSettings = onOpenSettings,
        onBack = onBack,
        gridState = gridState,
        filtersCollapsed = filtersCollapsed,
        searchBoundsInRoot = searchBoundsInRoot,
        onSearchBoundsChanged = { searchBoundsInRoot = it },
        focusManager = focusManager,
        contextMenuGeometry = contextMenuGeometry,
        backdropProgress = contextMenuBackdropProgress,
        renderedContextMenuTarget = renderedContextMenuTarget,
        onContextMenuOpen = openContextMenu,
        onContextMenuDismiss = dismissContextMenu,
        onOpenFilters = { filterSheetInitialPage = it },
    )

    filterSheetInitialPage?.let { initialPage ->
        HomeFiltersSheet(
            applied = state.filterSelection(),
            config =
                HomeFilterUiConfig(
                    multiSelect = state.multiSelect,
                    matureContentEnabled = state.matureContentEnabled,
                ),
            initialPage = initialPage,
            exactPhrase = state.exactPhrase,
            onToggleExactPhrase = { onAction(HomeAction.ToggleExactPhrase) },
            onDismiss = { selection ->
                filterSheetInitialPage = null
                if (selection != state.filterSelection()) {
                    onAction(HomeAction.ApplyFilters(selection))
                }
            },
        )
    }
}

@Composable
internal fun HomeScreenFrame(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    onOpenSettings: (() -> Unit)?,
    onBack: (() -> Unit)?,
    gridState: LazyGridState,
    filtersCollapsed: Boolean,
    searchBoundsInRoot: IntRect?,
    onSearchBoundsChanged: (IntRect) -> Unit,
    focusManager: androidx.compose.ui.focus.FocusManager,
    contextMenuGeometry: HomeContextMenuGeometry,
    backdropProgress: Float,
    renderedContextMenuTarget: HomeContextMenuTarget?,
    onContextMenuOpen: (HomeContextMenuTarget) -> Unit,
    onContextMenuDismiss: (Long) -> Unit,
    onOpenFilters: (HomeFilterPage) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { contextMenuGeometry.rootCoordinates = it }
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
            onSearchBoundsChanged = onSearchBoundsChanged,
            contextMenuGeometry = contextMenuGeometry,
            backdropProgress = backdropProgress,
            contextMenuPreviewItemId = renderedContextMenuTarget?.itemId,
            onContextMenuOpen = onContextMenuOpen,
            onContextMenuDismiss = onContextMenuDismiss,
            onOpenFilters = onOpenFilters,
        )
        HomeContextMenuOverlay(
            target = renderedContextMenuTarget,
            backdropProgress = backdropProgress,
        )
    }
}

@Composable
internal fun HomeScreenBody(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    onOpenSettings: (() -> Unit)?,
    onBack: (() -> Unit)?,
    gridState: LazyGridState,
    filtersCollapsed: Boolean,
    onSearchBoundsChanged: (IntRect) -> Unit,
    contextMenuGeometry: HomeContextMenuGeometry,
    backdropProgress: Float,
    contextMenuPreviewItemId: Long?,
    onContextMenuOpen: (HomeContextMenuTarget) -> Unit,
    onContextMenuDismiss: (Long) -> Unit,
    onOpenFilters: (HomeFilterPage) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .then(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && backdropProgress > 0f) {
                        Modifier.blur(WallHubContextMenuDefaults.BackgroundBlurRadius * backdropProgress)
                    } else {
                        Modifier
                    },
                ).then(
                    if (contextMenuPreviewItemId != null) {
                        Modifier.semantics { invisibleToUser() }
                    } else {
                        Modifier
                    },
                ),
    ) {
        WallHubPageScaffold(
            title = stringResource(R.string.app_name),
            useUwuToolbar = onBack == null,
            topBarContent =
                onBack?.let { back ->
                    {
                        HomeSearchTopBar(
                            state = state,
                            onQueryChanged = { onAction(HomeAction.QueryChanged(it)) },
                            onSubmitSearch = { onAction(HomeAction.SubmitSearch) },
                            onToggleExactPhrase = { onAction(HomeAction.ToggleExactPhrase) },
                            onSearchBoundsChanged = onSearchBoundsChanged,
                            onBack = back,
                            onOpenSettings = onOpenSettings,
                            onResetAndRefresh = {
                                onAction(HomeAction.Refresh)
                                coroutineScope.launch { gridState.scrollToItem(0) }
                            },
                        )
                    }
                },
            actions = {
                HomeViewModeToggle(
                    selected = state.viewMode,
                    onViewModeSelected = { onAction(HomeAction.SelectViewMode(it)) },
                    gridContentDescription = stringResource(R.string.home_grid_view),
                    listContentDescription = stringResource(R.string.home_list_view),
                )
                onOpenSettings?.let { openSettings ->
                    SettingsToolbarActionButton(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.home_settings),
                        onClick = openSettings,
                        buttonSize = 64.dp,
                        containerSize = 48.dp,
                    )
                }
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            ) {
                HomePersistentSearchBar(
                    state = state,
                    onQueryChanged = { onAction(HomeAction.QueryChanged(it)) },
                    onSubmitSearch = { onAction(HomeAction.SubmitSearch) },
                    onSearchBoundsChanged = onSearchBoundsChanged,
                    onClear = { onAction(HomeAction.QueryChanged("")) },
                    onOpenFilters = { onOpenFilters(HomeFilterPage.BROWSE) },
                    filtersActive = state.activeFilterCount > 0 || state.exactPhrase,
                )
                HomeResults(
                    state = state,
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
                            HomeCardAction.PLAY_VIDEO -> onAction(HomeAction.OpenDetail(item.id))
                            HomeCardAction.OPEN_STEAM -> onAction(HomeAction.OpenSteam(item.id))
                        }
                    },
                    onDownload = { onAction(HomeAction.RequestDownload(it)) },
                    onSearchAuthor = { onAction(HomeAction.SearchAuthor(it)) },
                    onCopyText = { text, message -> onAction(HomeAction.CopyText(text, message)) },
                    onOpenSteam = { onAction(HomeAction.OpenSteam(it)) },
                    onAuthorNameRequested = { onAction(HomeAction.RequestAuthorDisplayName(it)) },
                    gridState = gridState,
                    contextMenuPreviewItemId = contextMenuPreviewItemId,
                    contextMenuGeometry = contextMenuGeometry,
                    onContextMenuOpen = onContextMenuOpen,
                    onContextMenuDismiss = onContextMenuDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun HomeContextMenuOverlay(
    target: HomeContextMenuTarget?,
    backdropProgress: Float,
) {
    if (backdropProgress > 0f) {
        val scrimAlpha =
            if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                WallHubContextMenuDefaults.DarkScrimAlpha
            } else {
                WallHubContextMenuDefaults.LightScrimAlpha
            }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha * backdropProgress),
                    ),
        )
    }
    target?.let {
        HomeContextMenuCardPreview(
            target = it,
            elevationProgress = backdropProgress,
        )
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
    onOpenFilters: () -> Unit,
    filtersActive: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(start = WallHubSpacing.md, end = WallHubSpacing.xs)
                .padding(bottom = WallHubSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
    ) {
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
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
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
        Surface(
            onClick = onOpenFilters,
            modifier = Modifier.size(56.dp),
            shape = if (filtersActive) RoundedCornerShape(16.dp) else RoundedCornerShape(100.dp),
            color =
                if (filtersActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            contentColor =
                if (filtersActive) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = stringResource(R.string.home_open_all_filters),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeSearchTopBar(
    state: HomeUiState,
    onQueryChanged: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onToggleExactPhrase: () -> Unit,
    onSearchBoundsChanged: (IntRect) -> Unit,
    onBack: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
    onResetAndRefresh: () -> Unit,
) {
    var exactPhraseMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var searchFieldFocused by remember { mutableStateOf(false) }
    var imeWasVisibleForSearch by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(searchFieldFocused, imeVisible) {
        when {
            !searchFieldFocused -> imeWasVisibleForSearch = false
            imeVisible -> imeWasVisibleForSearch = true
            imeWasVisibleForSearch -> focusManager.clearFocus(force = true)
        }
    }
    Surface(
        modifier = Modifier.statusBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = WallHubSpacing.xxl),
                    contentPadding = PaddingValues(horizontal = WallHubSpacing.xxs),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(WallHubSizeTokens.smallIcon),
                    )
                    Spacer(modifier = Modifier.width(WallHubSpacing.xxs))
                    Text(
                        text = stringResource(R.string.home_back),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(onClick = onResetAndRefresh)
                            .padding(vertical = WallHubSpacing.compact, horizontal = WallHubSpacing.xxs),
                )
            }
            Spacer(modifier = Modifier.width(WallHubSpacing.compact))
            Box(modifier = Modifier.weight(1f)) {
                HomeFlatCard(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(HOME_SEARCH_FIELD_HEIGHT)
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
                    shape = HOME_WALLPAPER_CARD_SHAPE,
                ) {
                    BasicTextField(
                        value = state.query,
                        onValueChange = { query ->
                            onQueryChanged(query)
                            // Keep the exact-phrase chooser visible while the user
                            // continues typing, without recreating a focus-owning popup.
                            exactPhraseMenuExpanded = searchFieldFocused && query.isNotBlank()
                        },
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .onFocusChanged { focusState ->
                                    searchFieldFocused = focusState.isFocused
                                    exactPhraseMenuExpanded = focusState.isFocused && state.query.isNotBlank()
                                }.padding(start = WallHubSpacing.sm, end = WallHubSpacing.xxs),
                        textStyle =
                            MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        keyboardOptions =
                            androidx.compose.foundation.text
                                .KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions =
                            androidx.compose.foundation.text.KeyboardActions(
                                onSearch = { onSubmitSearch() },
                            ),
                        decorationBox = { innerTextField ->
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (state.query.isBlank()) {
                                        Text(
                                            text = stringResource(R.string.home_search_workshop),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    innerTextField()
                                }
                                IconButton(
                                    onClick = onSubmitSearch,
                                    modifier = Modifier.size(WallHubSizeTokens.compactActionHeight),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Search,
                                        contentDescription = stringResource(R.string.home_search),
                                    )
                                }
                            }
                        },
                    )
                }
                DropdownMenu(
                    expanded = exactPhraseMenuExpanded && state.query.isNotBlank(),
                    onDismissRequest = { exactPhraseMenuExpanded = false },
                    offset = DpOffset(WallHubSpacing.none, WallHubSpacing.xxs),
                    shape = MaterialTheme.shapes.medium,
                    containerColor =
                        if (state.exactPhrase) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    tonalElevation = WallHubSpacing.none,
                    shadowElevation = WallHubSpacing.dense,
                    // The search field must retain IME focus while this optional
                    // checkbox is shown. A focusable dropdown steals that focus on
                    // every edit on some Android devices.
                    properties = PopupProperties(focusable = false),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    onToggleExactPhrase()
                                    exactPhraseMenuExpanded = false
                                }.padding(horizontal = WallHubSpacing.compact, vertical = WallHubSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(WallHubSpacing.md)
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(
                                        if (state.exactPhrase) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerLowest
                                        },
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.exactPhrase) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(WallHubSpacing.sm),
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.home_exact_phrase),
                            style = MaterialTheme.typography.labelMedium,
                            color =
                                if (state.exactPhrase) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }
            if (onOpenSettings != null) {
                SettingsToolbarActionButton(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.home_settings),
                    onClick = onOpenSettings,
                    buttonSize = 64.dp,
                    containerSize = 48.dp,
                )
            }
        }
    }
}

@Composable
internal fun HomeFilterPanel(
    state: HomeUiState,
    onOpenFilters: (HomeFilterPage) -> Unit,
    onToggleExactPhrase: () -> Unit,
) {
    val selection = state.filterSelection()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = WallHubSpacing.xxs),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = WallHubSpacing.content, end = WallHubSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_browse_settings),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text =
                        if (state.activeFilterCount == 0) {
                            stringResource(R.string.home_using_defaults)
                        } else {
                            pluralStringResource(
                                R.plurals.home_filters_active,
                                state.activeFilterCount,
                                state.activeFilterCount,
                            )
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BadgedBox(
                badge = {
                    if (state.activeFilterCount > 0) {
                        Badge { Text(state.activeFilterCount.toString()) }
                    }
                },
            ) {
                FilledTonalIconButton(
                    onClick = { onOpenFilters(HomeFilterPage.BROWSE) },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = stringResource(R.string.home_open_all_filters),
                    )
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = WallHubSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
        ) {
            items(HomeFilterPage.entries, key = { it }) { page ->
                HomeConditionChip(
                    label = page.label(),
                    value = page.summary(selection, state),
                    active = page.activeSectionCount(selection) > 0,
                    onClick = { onOpenFilters(page) },
                )
            }
        }
    }
}

@Composable
internal fun HomeConditionChip(
    label: String,
    value: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color =
            if (active) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        contentColor =
            if (active) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        tonalElevation = WallHubSpacing.none,
    ) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = WallHubSpacing.xxl)
                    .clickable(
                        role = Role.Button,
                        onClick = onClick,
                    ).padding(horizontal = WallHubSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
        ) {
            Text(
                text = "$label · $value",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(WallHubSizeTokens.compactIcon),
            )
        }
    }
}

@Composable
internal fun HomeFlatCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
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
                    .clip(shape)
                    .background(color)
                    .then(clickableModifier),
            content = content,
        )
    }
}

@Composable
internal fun HomeViewModeToggle(
    selected: HomeViewMode,
    onViewModeSelected: (HomeViewMode) -> Unit,
    gridContentDescription: String,
    listContentDescription: String,
) {
    val indicatorOffset by animateDpAsState(
        targetValue =
            if (selected == HomeViewMode.GRID) {
                HOME_VIEW_MODE_TOGGLE_INSET
            } else {
                HOME_VIEW_MODE_TOGGLE_INSET + HOME_VIEW_MODE_TOGGLE_BUTTON_SIZE
            },
        animationSpec =
            tween(
                durationMillis = HOME_VIEW_MODE_TOGGLE_DURATION_MS,
                easing = HOME_CONTEXT_MENU_EASING,
            ),
        label = "HomeViewModeIndicator",
    )
    Box(
        modifier = Modifier.height(64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(100.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(
                modifier =
                    Modifier
                        .width(HOME_VIEW_MODE_TOGGLE_WIDTH)
                        .height(HOME_VIEW_MODE_TOGGLE_HEIGHT),
            ) {
                Surface(
                    modifier =
                        Modifier
                            .offset(x = indicatorOffset, y = HOME_VIEW_MODE_TOGGLE_INSET)
                            .size(HOME_VIEW_MODE_TOGGLE_BUTTON_SIZE),
                    shape = RoundedCornerShape(100.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {}
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(HOME_VIEW_MODE_TOGGLE_INSET),
                ) {
                    ViewModeButton(
                        selected = selected == HomeViewMode.GRID,
                        icon = Icons.Outlined.GridView,
                        contentDescription = gridContentDescription,
                        onClick = { onViewModeSelected(HomeViewMode.GRID) },
                    )
                    ViewModeButton(
                        selected = selected == HomeViewMode.LIST,
                        icon = Icons.Outlined.ViewList,
                        contentDescription = listContentDescription,
                        onClick = { onViewModeSelected(HomeViewMode.LIST) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ViewModeButton(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        targetValue =
            if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        animationSpec =
            tween(
                durationMillis = HOME_VIEW_MODE_TOGGLE_DURATION_MS,
                easing = HOME_CONTEXT_MENU_EASING,
            ),
        label = "HomeViewModeIcon",
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(HOME_VIEW_MODE_TOGGLE_BUTTON_SIZE),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(26.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun HomeResults(
    state: HomeUiState,
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
    contextMenuPreviewItemId: Long?,
    contextMenuGeometry: HomeContextMenuGeometry,
    onContextMenuOpen: (HomeContextMenuTarget) -> Unit,
    onContextMenuDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shouldAutoLoadMore by remember(gridState, state) {
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
                        ?.index ?: -1
                lastVisibleIndex >= (state.items.lastIndex - HOME_AUTO_LOAD_MORE_THRESHOLD).coerceAtLeast(0)
            }
        }
    }
    LaunchedEffect(shouldAutoLoadMore, state.nextPage, state.paginationMode) {
        if (shouldAutoLoadMore) onLoadNextPage()
    }
    val loadingIndicators = state.loadingIndicatorVisibility()
    val pullToRefreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = loadingIndicators.showPullToRefresh,
        onRefresh = onRetry,
        state = pullToRefreshState,
        indicator = {
            if (!state.isSteamIpPrewarming) {
                PullToRefreshDefaults.Indicator(
                    state = pullToRefreshState,
                    isRefreshing = loadingIndicators.showPullToRefresh,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .size(48.dp),
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        when {
            state.isInitialLoading -> {
                if (loadingIndicators.showSteamIpPrewarm) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = WallHubSpacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm, Alignment.CenterVertically),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.home_warming_steam_ip),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }

            (state.error != null || state.errorRes != null) && state.items.isEmpty() -> {
                WallHubEmptyState(
                    icon = Icons.Outlined.Refresh,
                    title = state.error ?: stringResource(requireNotNull(state.errorRes)),
                    actionLabel = stringResource(R.string.home_retry),
                    onAction = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            state.items.isEmpty() -> {
                WallHubEmptyState(
                    icon = Icons.Outlined.Search,
                    title = stringResource(R.string.home_no_matching_wallpapers),
                    actionLabel = stringResource(R.string.home_reload),
                    onAction = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val layoutKey = HomeCardLayoutKey.resolve(state.viewMode, state.columns)
                    val contentWidth = (maxWidth - HOME_GRID_HORIZONTAL_PADDING * 2).coerceAtLeast(WallHubSpacing.none)
                    val gridCardWidth =
                        (
                            contentWidth -
                                HOME_GRID_ITEM_SPACING * (state.columns - 1).toFloat()
                        ).coerceAtLeast(WallHubSpacing.none) / state.columns.toFloat()
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
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(layoutKey.effectiveColumns),
                        state = gridState,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { coordinates ->
                                    contextMenuGeometry.gridCoordinates = coordinates
                                },
                        contentPadding =
                            PaddingValues(
                                horizontal = HOME_GRID_HORIZONTAL_PADDING,
                                vertical = WallHubSpacing.xs,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(HOME_GRID_ITEM_SPACING),
                        verticalArrangement = Arrangement.spacedBy(HOME_GRID_ITEM_SPACING),
                    ) {
                        items(
                            items = state.items,
                            key = WorkshopSummary::id,
                            contentType = { item -> item.type },
                        ) { item ->
                            WorkshopCard(
                                modifier = Modifier,
                                item = item,
                                authorDisplayName = state.authorDisplayNames[item.id],
                                layoutKey = layoutKey,
                                gridShowFileSize = state.columns < 3,
                                gridShowFavorites = state.columns < 4,
                                gridStatisticsAvailableWidth = gridStatisticsAvailableWidth,
                                listStatisticsAvailableWidth = listStatisticsAvailableWidth,
                                isContextMenuPreviewTarget = contextMenuPreviewItemId == item.id,
                                action = state.cardAction,
                                onOpen = { onOpenDetail(item.id) },
                                onPrimaryAction = { onPrimaryAction(item) },
                                onDownload = { onDownload(item) },
                                onSearchAuthor = { onSearchAuthor(item.creatorId ?: item.author) },
                                onCopyText = onCopyText,
                                onOpenSteam = { onOpenSteam(item.id) },
                                onAuthorNameRequested = { onAuthorNameRequested(item) },
                                contextMenuGeometry = contextMenuGeometry,
                                onContextMenuOpen = onContextMenuOpen,
                                onContextMenuDismiss = onContextMenuDismiss,
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

@Composable
internal fun HomePagination(
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorkshopCard(
    modifier: Modifier = Modifier,
    item: WorkshopSummary,
    authorDisplayName: String?,
    layoutKey: HomeCardLayoutKey,
    gridShowFileSize: Boolean,
    gridShowFavorites: Boolean,
    gridStatisticsAvailableWidth: Dp,
    listStatisticsAvailableWidth: Dp,
    isContextMenuPreviewTarget: Boolean,
    action: HomeCardAction,
    onOpen: () -> Unit,
    onPrimaryAction: () -> Unit,
    onDownload: () -> Unit,
    onSearchAuthor: () -> Unit,
    onCopyText: (String, Int) -> Unit,
    onOpenSteam: () -> Unit,
    onAuthorNameRequested: () -> Unit,
    contextMenuGeometry: HomeContextMenuGeometry,
    onContextMenuOpen: (HomeContextMenuTarget) -> Unit,
    onContextMenuDismiss: (Long) -> Unit,
) {
    val listMode = layoutKey.listMode
    val twoColumnGrid = !listMode && layoutKey.effectiveColumns == 2
    val layoutMotion =
        rememberHomeViewCardLayoutMotion(
            layoutKey = layoutKey,
        )
    val contextMenuPreviewLayer = rememberGraphicsLayer()
    val cardPosition = remember { HomeCardPositionHolder() }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var contextMenuVisible by remember { mutableStateOf(false) }
    var contextMenuMounted by remember { mutableStateOf(false) }
    var contextMenuRequested by remember { mutableStateOf(false) }
    var contextMenuEntranceRequest by remember { mutableIntStateOf(0) }
    var contextMenuTarget by remember(item.id) { mutableStateOf<HomeContextMenuTarget?>(null) }
    var contextMenuPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    val viewDetailsLabel = stringResource(R.string.home_view_details)
    val openActionsMenuLabel = stringResource(R.string.home_open_actions_menu)
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val contextMenuPositionProvider =
        remember(contextMenuPositionInWindow, density) {
            HomeContextMenuPositionProvider(
                touchPosition = contextMenuPositionInWindow,
                touchOffsetPx = with(density) { WallHubContextMenuDefaults.TouchOffset.roundToPx() },
            )
        }
    val contextMenuAlpha by animateFloatAsState(
        targetValue = if (contextMenuVisible) 1f else 0f,
        animationSpec =
            tween(
                durationMillis =
                    if (contextMenuVisible) {
                        WallHubContextMenuDefaults.EnterDurationMillis
                    } else {
                        WallHubContextMenuDefaults.ExitDurationMillis
                    },
                easing = WallHubContextMenuDefaults.Easing,
            ),
        label = "HomeContextMenuFade",
    )
    LaunchedEffect(contextMenuVisible) {
        if (!contextMenuVisible && contextMenuMounted) {
            delay(WallHubContextMenuDefaults.ExitDurationMillis.toLong())
            if (!contextMenuRequested) contextMenuMounted = false
        }
    }
    LaunchedEffect(contextMenuMounted, contextMenuEntranceRequest) {
        if (contextMenuMounted) {
            withFrameNanos { }
            if (contextMenuRequested) contextMenuVisible = true
        }
    }
    DisposableEffect(item.id) {
        onDispose { onContextMenuDismiss(item.id) }
    }

    fun dismissContextMenu() {
        contextMenuRequested = false
        contextMenuVisible = false
        onContextMenuDismiss(item.id)
    }

    fun openContextMenuAt(position: Offset) {
        val target =
            contextMenuGeometry.captureTarget(
                itemId = item.id,
                graphicsLayer = contextMenuPreviewLayer,
                cardCoordinates = cardPosition.cardCoordinates,
                touchCoordinates = cardPosition.touchCoordinates,
                touchPosition = position,
                shape = layoutMotion.cardShape(),
            ) ?: return
        contextMenuTarget = target
        contextMenuPositionInWindow = target.touchPositionInWindow
        onAuthorNameRequested()
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        onContextMenuOpen(target)
        contextMenuRequested = true
        contextMenuVisible = false
        contextMenuMounted = true
        contextMenuEntranceRequest += 1
    }
    val interactionModifier =
        Modifier
            .testTag("home-workshop-${item.id}")
            .pointerInput(item.id) {
                detectTapGestures(
                    onPress = { position ->
                        val press = PressInteraction.Press(position)
                        interactionSource.emit(press)
                        interactionSource.emit(
                            if (tryAwaitRelease()) {
                                PressInteraction.Release(press)
                            } else {
                                PressInteraction.Cancel(press)
                            },
                        )
                    },
                    onTap = { onOpen() },
                    onLongPress = ::openContextMenuAt,
                )
            }.semantics {
                role = Role.Button
                onClick(label = viewDetailsLabel) {
                    onOpen()
                    true
                }
                onLongClick(label = openActionsMenuLabel) {
                    val size = cardPosition.touchCoordinates?.size
                    if (size == null || size.width <= 0 || size.height <= 0) {
                        false
                    } else {
                        openContextMenuAt(Offset(size.width / 2f, size.height / 2f))
                        true
                    }
                }
            }
    val pressActive = isPressed && !isContextMenuPreviewTarget
    val recordContextMenuPreview = isPressed || contextMenuMounted || isContextMenuPreviewTarget
    val pressedScale by animateFloatAsState(
        targetValue =
            when {
                !pressActive -> 1f
                listMode -> HOME_CONTEXT_MENU_LIST_PRESS_SCALE
                else -> HOME_CONTEXT_MENU_GRID_PRESS_SCALE
            },
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = HOME_CONTEXT_MENU_PRESS_STIFFNESS,
            ),
        label = "WorkshopCardPressScale",
    )
    val pressedTranslationY by animateDpAsState(
        targetValue = if (pressActive) HOME_CONTEXT_MENU_PRESS_TRANSLATION_Y else WallHubSpacing.none,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = HOME_CONTEXT_MENU_PRESS_STIFFNESS,
            ),
        label = "WorkshopCardPressTranslation",
    )
    Box(
        modifier =
            modifier
                .fillMaxWidth(),
    ) {
        HomeFlatCard(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { cardPosition.cardCoordinates = it }
                    .drawWithContent recordCard@{
                        if (recordContextMenuPreview) {
                            contextMenuPreviewLayer.record {
                                this@recordCard.drawContent()
                            }
                            if (!isContextMenuPreviewTarget) drawLayer(contextMenuPreviewLayer)
                        } else {
                            drawContent()
                        }
                    }.graphicsLayer {
                        transformOrigin = TransformOrigin.Center
                        scaleX = pressedScale
                        scaleY = pressedScale
                        translationY = pressedTranslationY.toPx()
                    }.then(layoutMotion.cardModifier())
                    .onGloballyPositioned { cardPosition.touchCoordinates = it }
                    .then(interactionModifier),
            shape = layoutMotion.cardShape(),
        ) {
            if (listMode) {
                WorkshopListCardContent(
                    item = item,
                    action = action,
                    showFileSize = true,
                    showFavorites = true,
                    statisticsAvailableWidth = listStatisticsAvailableWidth,
                    layoutMotion = layoutMotion,
                    onPrimaryAction = onPrimaryAction,
                )
            } else {
                Column {
                    WorkshopCoverFrame(
                        item = item,
                        coverShape = layoutMotion.coverShape(),
                        typeTagModifier = layoutMotion.mediaContentScaleCompensationModifier(),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .zIndex(HOME_CARD_PREVIEW_Z_INDEX)
                                .then(layoutMotion.mediaModifier()),
                    )
                    WorkshopCardCopy(
                        item = item,
                        compact = false,
                        twoColumnGrid = twoColumnGrid,
                        showFileSize = gridShowFileSize,
                        showFavorites = gridShowFavorites,
                        statisticsAvailableWidth = gridStatisticsAvailableWidth,
                        titleModifier = layoutMotion.titleModifier(),
                        metadataModifier = layoutMotion.metadataModifier(),
                        modifier =
                            Modifier
                                .padding(
                                    start = WallHubSpacing.compact,
                                    top = if (twoColumnGrid) TWO_COLUMN_CARD_COPY_TOP_PADDING else WallHubSpacing.compact,
                                    end = WallHubSpacing.compact,
                                ),
                    )
                    WorkshopGridCardAction(
                        action = action,
                        layoutMotion = layoutMotion,
                        onPrimaryAction = onPrimaryAction,
                        modifier =
                            Modifier.padding(
                                start = WallHubSpacing.compact,
                                top = if (twoColumnGrid) TWO_COLUMN_CARD_ACTION_TOP_PADDING else LIST_CARD_ACTION_TOP_PADDING,
                                end = WallHubSpacing.compact,
                                bottom = WallHubSpacing.compact,
                            ),
                    )
                }
            }
        }
        if (contextMenuMounted) {
            Popup(
                popupPositionProvider = contextMenuPositionProvider,
                onDismissRequest = { dismissContextMenu() },
                properties = PopupProperties(focusable = true),
            ) {
                val menuWidth =
                    WallHubContextMenuDefaults.menuWidth(
                        cardWidth =
                            contextMenuTarget?.let { target ->
                                with(density) { target.cardBounds.width.toDp() }
                            },
                    )
                WallHubContextMenuSurface(
                    width = menuWidth,
                    modifier =
                        Modifier
                            .graphicsLayer { alpha = contextMenuAlpha }
                            .then(
                                if (contextMenuVisible) {
                                    Modifier
                                } else {
                                    Modifier
                                        .pointerInput(Unit) {
                                            awaitEachGesture {
                                                do {
                                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                                    event.changes.forEach { it.consume() }
                                                } while (event.changes.any { it.pressed })
                                            }
                                        }.clearAndSetSemantics {}
                                },
                            ),
                ) {
                    val title = item.localizedTitle()
                    HomeContextMenuMetadataItem(
                        label = stringResource(R.string.home_wallpaper_title),
                        value = title,
                        icon = Icons.Outlined.ContentCopy,
                        onClick = {
                            onCopyText(
                                title,
                                R.string.home_wallpaper_title_copied,
                            )
                            dismissContextMenu()
                        },
                    )
                    HomeContextMenuMetadataItem(
                        label = stringResource(R.string.home_author),
                        value =
                            authorDisplayName
                                ?: item.author.takeIf { item.authorPlaceholder == WorkshopAuthorPlaceholder.NONE }
                                ?: stringResource(R.string.home_loading_steam_username),
                        icon = Icons.Outlined.PersonOutline,
                        onClick = {
                            dismissContextMenu()
                            onSearchAuthor()
                        },
                    )
                    HomeContextMenuMetadataItem(
                        label = stringResource(R.string.home_project_id),
                        value = item.id.toString(),
                        icon = Icons.Outlined.ContentCopy,
                        onClick = {
                            onCopyText(
                                item.id.toString(),
                                R.string.home_project_id_copied,
                            )
                            dismissContextMenu()
                        },
                    )
                    Spacer(modifier = Modifier.height(WallHubSpacing.xxxs))
                    HomeContextMenuItem(
                        text = stringResource(R.string.home_download),
                        icon = Icons.Outlined.Download,
                        onClick = {
                            dismissContextMenu()
                            onDownload()
                        },
                    )
                    if (item.type == WorkshopType.VIDEO) {
                        HomeContextMenuItem(
                            text = stringResource(R.string.home_open_video_details),
                            icon = Icons.Outlined.PlayArrow,
                            onClick = {
                                dismissContextMenu()
                                onOpen()
                            },
                        )
                    }
                    HomeContextMenuItem(
                        text = stringResource(R.string.home_open_in_steam),
                        icon = Icons.Outlined.OpenInNew,
                        onClick = {
                            dismissContextMenu()
                            onOpenSteam()
                        },
                    )
                }
            }
        }
    }
}
