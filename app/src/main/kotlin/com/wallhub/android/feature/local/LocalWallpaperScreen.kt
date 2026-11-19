@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.local

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wallhub.android.NAVIGATION_EASING
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubHorizontalDivider
import com.wallhub.android.core.designsystem.WallHubVerticalDivider
import com.wallhub.android.core.designsystem.label
import com.wallhub.android.core.designsystem.WallHubSingleChoiceSegmentedControl
import com.wallhub.android.core.designsystem.LocalWallHubAdaptiveLayout
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubStyle
import com.wallhub.android.core.designsystem.WallHubWindowLayout
import com.wallhub.android.core.designsystem.WallHubFabActiveElevation
import com.wallhub.android.core.designsystem.WallHubFabDefaultElevation
import com.wallhub.android.core.designsystem.LocalWallHubOverlayBottomClearance
import com.wallhub.android.core.designsystem.WallHubAlertDialog
import com.wallhub.android.core.designsystem.wallHubStyle
import com.wallhub.android.core.designsystem.WallHubDropdownMenu
import com.wallhub.android.core.designsystem.WallHubDropdownMenuItem
import com.wallhub.android.core.designsystem.WallHubToolbarSearchTitle
import com.wallhub.android.data.downloads.WorkshopExportMimeTypes
import com.wallhub.android.data.downloads.WorkshopThumbnail
import com.wallhub.android.core.format.formatByteSize
import com.wallhub.android.core.designsystem.rememberWallHubDirectionalCollapseConnection
import com.wallhub.android.core.model.LocalWallpaperFormat
import com.wallhub.android.core.model.LocalWallpaperImportState
import com.wallhub.android.core.model.LocalWallpaperResource
import com.wallhub.android.core.model.LocalWallpaperSource
import com.wallhub.android.core.model.LocalWallpaperViewMode
import com.wallhub.android.feature.home.FilterSection
import com.wallhub.android.feature.home.TwoColumnFilterOptions
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.uwuaosp.compose.settingslib.SettingsToolbarActionButton
import org.uwuaosp.compose.settingslib.SettingsAppBarScaffold
import java.io.File
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LocalWallpaperRoute(
    onOpenSettings: () -> Unit = {},
    onOpenWorkshopDetail: (Long) -> Unit = {},
    onBack: () -> Unit = {},
    onScrollChromeCollapsedChanged: (Boolean) -> Unit = {},
    isPageActive: Boolean = true,
    viewModel: LocalWallpaperViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LocalWallpaperEffectHandler(viewModel, onOpenWorkshopDetail)

    LaunchedEffect(isPageActive) {
        if (isPageActive) viewModel.onAction(LocalWallpaperAction.EnterPage)
    }
    DisposableEffect(Unit) {
        onDispose { onScrollChromeCollapsedChanged(false) }
    }

    LocalWallpaperScreen(
        state = state,
        isPageActive = isPageActive,
        onAction = viewModel::onAction,
        onScrollChromeCollapsedChanged = onScrollChromeCollapsedChanged,
        onOpenSettings = onOpenSettings,
        onBack = onBack,
    )
}

@Composable
private fun LocalWallpaperEffectHandler(
    viewModel: LocalWallpaperViewModel,
    onOpenWorkshopDetail: (Long) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val selectedDirectoryLabel = stringResource(R.string.local_selected_directory)
    val authorizeDirectoryFailed = stringResource(R.string.local_authorize_directory_failed)
    val openResourceFailed = stringResource(R.string.local_open_resource_failed)
    val shareResourcesFailed = stringResource(R.string.local_share_resources_failed)
    val locationCopied = stringResource(R.string.local_location_copied)
    val workshopIdCopied = stringResource(R.string.local_workshop_id_copied)
    val importFailed = stringResource(R.string.local_import_failed)
    val readResourceFailed = stringResource(R.string.local_read_resource_failed)
    val wallpaperEngineNotInstalled = stringResource(R.string.local_wallpaper_engine_not_installed)
    val directoryLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { treeUri ->
            if (treeUri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
                    .onSuccess {
                        viewModel.onAction(
                            LocalWallpaperAction.DirectorySelected(
                                treeUri = treeUri.toString(),
                                label =
                                    treeUri.lastPathSegment
                                        ?.substringAfterLast(':')
                                        ?.ifBlank { null }
                                        ?: selectedDirectoryLabel,
                            ),
                        )
                    }.onFailure {
                        viewModel.onAction(
                            LocalWallpaperAction.SystemActionFailed(
                                authorizeDirectoryFailed,
                            ),
                        )
                    }
            }
        }
    LaunchedEffect(
        viewModel,
        context,
        openResourceFailed,
        shareResourcesFailed,
        locationCopied,
        workshopIdCopied,
        importFailed,
        onOpenWorkshopDetail,
        readResourceFailed,
        wallpaperEngineNotInstalled,
    ) {
        viewModel.effects.collect { effect ->
            when (effect) {
                LocalWallpaperEffect.ChooseDirectory -> directoryLauncher.launch(null)
                is LocalWallpaperEffect.OpenResource -> {
                    openLocalResource(context, effect.resource).onFailure {
                        viewModel.onAction(
                            LocalWallpaperAction.SystemActionFailed(
                                openResourceFailed,
                            ),
                        )
                    }
                }
                is LocalWallpaperEffect.ShareResources -> {
                    shareLocalResources(context, effect.resources).onFailure {
                        viewModel.onAction(
                            LocalWallpaperAction.SystemActionFailed(
                                shareResourcesFailed,
                            ),
                        )
                    }
                }
                is LocalWallpaperEffect.CopyLocation -> {
                    copyLocalResourceLocation(context, effect.resource)
                    Toast.makeText(context.applicationContext, locationCopied, Toast.LENGTH_SHORT).show()
                }
                is LocalWallpaperEffect.CopyWorkshopId -> {
                    copyWorkshopId(context, effect.workshopId)
                    Toast.makeText(context.applicationContext, workshopIdCopied, Toast.LENGTH_SHORT).show()
                }
                is LocalWallpaperEffect.OpenWorkshopDetail -> onOpenWorkshopDetail(effect.workshopId)
                is LocalWallpaperEffect.ImportToWallpaperEngine -> {
                    launchWallpaperEngineImport(
                        context = context,
                        resource = effect.resource,
                        readResourceFailed = readResourceFailed,
                        notInstalledMessage = wallpaperEngineNotInstalled,
                    ).onSuccess {
                        viewModel.onAction(
                            LocalWallpaperAction.ImportLaunched(effect.resource.id),
                        )
                    }.onFailure {
                        viewModel.onAction(
                            LocalWallpaperAction.SystemActionFailed(
                                importFailed,
                            ),
                        )
                    }
                }
                is LocalWallpaperEffect.ShowMessage ->
                    Toast.makeText(
                        context.applicationContext,
                        effect.message
                            ?: resources.getString(requireNotNull(effect.messageRes)),
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun LocalWallpaperScreen(
    state: LocalWallpaperUiState,
    isPageActive: Boolean,
    onAction: (LocalWallpaperAction) -> Unit,
    onScrollChromeCollapsedChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    initialFiltersOpen: Boolean = false,
) {
    val style = wallHubStyle()
    val onChooseDirectory: () -> Unit = { onAction(LocalWallpaperAction.ChooseDirectory) }
    val onResetDirectory: () -> Unit = { onAction(LocalWallpaperAction.ResetDirectory) }
    val onRefresh: () -> Unit = { onAction(LocalWallpaperAction.Refresh) }
    val onCancelScan: () -> Unit = { onAction(LocalWallpaperAction.CancelScan) }
    val onSearchQueryChanged: (String) -> Unit = { onAction(LocalWallpaperAction.SearchQueryChanged(it)) }
    val onViewModeSelected: (LocalWallpaperViewMode) -> Unit = {
        onAction(LocalWallpaperAction.SelectViewMode(it))
    }
    val onFormatFilterSelected: (LocalWallpaperFormatFilter) -> Unit = {
        onAction(LocalWallpaperAction.SelectFormatFilter(it))
    }
    val expanded = LocalWallHubAdaptiveLayout.current.width == WallHubWindowLayout.EXPANDED
    val filterDrawerState = rememberDrawerState(if (initialFiltersOpen) DrawerValue.Open else DrawerValue.Closed)
    val filterDrawerScope = rememberCoroutineScope()
    val filterDrawerModalActive =
        filterDrawerState.currentValue == DrawerValue.Open ||
            filterDrawerState.targetValue == DrawerValue.Open
    val filterDrawerTitle = stringResource(R.string.local_filter_title)
    val openFilterDrawer: () -> Unit = {
        filterDrawerScope.launch { filterDrawerState.open() }
    }
    val closeFilterDrawer: () -> Unit = {
        filterDrawerScope.launch { filterDrawerState.close() }
    }
    val onSelectResource: (String?) -> Unit = { onAction(LocalWallpaperAction.SelectResource(it)) }
    val onStartSelection: (String) -> Unit = { onAction(LocalWallpaperAction.StartSelection(it)) }
    val onToggleSelection: (String) -> Unit = { onAction(LocalWallpaperAction.ToggleSelection(it)) }
    val onClearSelection: () -> Unit = { onAction(LocalWallpaperAction.ClearSelection) }
    val onToggleFavorite: (String) -> Unit = { onAction(LocalWallpaperAction.ToggleFavorite(it)) }
    val onAddTag: (String) -> Unit = { onAction(LocalWallpaperAction.AddTagToSelection(it)) }
    val onReplaceTags: (String, Set<String>) -> Unit = { resourceId, tags ->
        onAction(LocalWallpaperAction.ReplaceResourceTags(resourceId, tags))
    }
    val onRenameTag: (String, String) -> Unit = { oldTag, newTag ->
        onAction(LocalWallpaperAction.RenameTag(oldTag, newTag))
    }
    val onDeleteTag: (String) -> Unit = { onAction(LocalWallpaperAction.DeleteTag(it)) }
    val onDeleteResources: (Set<String>) -> Unit = { onAction(LocalWallpaperAction.DeleteResources(it)) }
    val onSystemAction: (LocalWallpaperAction) -> Unit = onAction
    BackHandler(enabled = filterDrawerModalActive) {
        closeFilterDrawer()
    }
    LaunchedEffect(state.viewMode, state.selectionMode) {
        if (state.viewMode == LocalWallpaperViewMode.DETAIL || state.selectionMode) {
            closeFilterDrawer()
        }
    }
    var tagDialogVisible by remember { mutableStateOf(false) }
    var tagManagerVisible by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<String?>(null) }
    var editedTag by remember { mutableStateOf("") }
    var tagInput by remember { mutableStateOf("") }
    var deleteConfirmationVisible by remember { mutableStateOf(false) }
    var selectionMenuExpanded by remember { mutableStateOf(false) }
    var searchToolbarExpanded by remember { mutableStateOf(false) }
    var secondaryChromeCollapsed by remember { mutableStateOf(false) }
    val updateSecondaryChromeCollapsed: (Boolean) -> Unit = { collapsed ->
        if (collapsed != secondaryChromeCollapsed) {
            secondaryChromeCollapsed = collapsed
            onScrollChromeCollapsedChanged(collapsed)
        }
    }
    val chromeScrollConnection =
        rememberWallHubDirectionalCollapseConnection(
            collapsed = secondaryChromeCollapsed,
            onCollapsedChanged = updateSecondaryChromeCollapsed,
            collapseDistance = LOCAL_HEADER_COLLAPSE_DISTANCE,
            expandDistance = LOCAL_HEADER_EXPAND_DISTANCE,
        )
    val selected = state.scan.resources.firstOrNull { it.id == state.selectedResourceId }
    val selectedForDelete =
        state.selectedResourceIds.ifEmpty {
            selected?.id?.let(::setOf).orEmpty()
        }
    val headerMode =
        when {
            state.selectionMode -> LocalHeaderMode.SELECTION
            state.viewMode == LocalWallpaperViewMode.DETAIL -> LocalHeaderMode.HIDDEN
            else -> LocalHeaderMode.WORKSPACE
        }
    LaunchedEffect(state.selectionMode) {
        if (!state.selectionMode) selectionMenuExpanded = false
    }
    PredictiveBackHandler(
        enabled =
            isPageActive &&
                state.selectionMode,
    ) {
        it.collect()
        if (state.selectionMode) onClearSelection()
    }
    val pageContent: @Composable (PaddingValues) -> Unit = { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .nestedScroll(chromeScrollConnection),
        ) {
            if (state.scan.issues.isNotEmpty()) {
                LocalScanIssueSummary(state = state)
            }
            if (state.viewMode != LocalWallpaperViewMode.DETAIL) {
                LocalLibraryHeader(
                    state = state,
                    expanded = expanded,
                    onViewModeSelected = onViewModeSelected,
                    onFormatFilterSelected = onFormatFilterSelected,
                    onOpenFilters = openFilterDrawer,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = LOCAL_PAGE_MAX_WIDTH)
                            .padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.xs)
                            .align(Alignment.CenterHorizontally),
                )
            }
            LocalWallpaperContent(
                state = state,
                selected = selected,
                expanded = expanded,
                onSelectResource = onSelectResource,
                onStartSelection = onStartSelection,
                onToggleSelection = onToggleSelection,
                onToggleFavorite = onToggleFavorite,
                onDeleteResource = { resource ->
                    onSelectResource(resource.id)
                    deleteConfirmationVisible = true
                },
                onReplaceTags = onReplaceTags,
                onAddTag = { tagDialogVisible = true },
                onSystemAction = onSystemAction,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .widthIn(max = LOCAL_PAGE_MAX_WIDTH)
                        .align(Alignment.CenterHorizontally),
            )
        }
    }
    ModalNavigationDrawer(
        drawerState = filterDrawerState,
        gesturesEnabled = filterDrawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxHeight(),
            ) {
                LocalFilterDrawer(
                    state = state,
                    isOpen = filterDrawerModalActive,
                    onApply = { draft ->
                        onAction(
                            LocalWallpaperAction.ApplyFilters(
                                formatFilter = draft.formatFilter,
                                importFilter = draft.importFilter,
                                sourceId = draft.sourceId,
                                favoriteOnly = draft.favoriteOnly,
                                selectedTag = draft.selectedTag,
                                sort = draft.sort,
                            ),
                        )
                        closeFilterDrawer()
                    },
                    onDismiss = closeFilterDrawer,
                )
            }
        },
    ) {
        AnimatedContent(
        targetState = headerMode,
        transitionSpec = {
            if (
                targetState == LocalHeaderMode.HIDDEN ||
                initialState == LocalHeaderMode.HIDDEN
            ) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(tween(LOCAL_HEADER_MODE_ENTER_DURATION_MS)) togetherWith
                    fadeOut(tween(LOCAL_HEADER_MODE_EXIT_DURATION_MS))
            }
        },
        label = "LocalHeaderMode",
    ) { displayedHeaderMode ->
        when (displayedHeaderMode) {
        LocalHeaderMode.WORKSPACE ->
            SettingsAppBarScaffold(
                title = stringResource(R.string.navigation_local),
                showBackButton = true,
                onNavigateUp = onBack,
                statusBarInsets = WindowInsets(0, 0, 0, 0),
                containerColor = if (style.capabilities.bettboxSurfaces) style.surfaces.page else MaterialTheme.colorScheme.surfaceContainerLowest,
                titleContent = {
                    WallHubToolbarSearchTitle(
                        title = stringResource(R.string.navigation_local),
                        query = state.searchQuery,
                        expanded = searchToolbarExpanded,
                        placeholder = stringResource(R.string.local_search_placeholder),
                        onQueryChanged = onSearchQueryChanged,
                        onSubmit = onRefresh,
                        onExpand = { searchToolbarExpanded = true },
                        onCollapse = { searchToolbarExpanded = false },
                    )
                },
                actions = {
                    IconButton(onClick = if (state.scan.isScanning) onCancelScan else onRefresh) {
                        Icon(
                            imageVector = if (state.scan.isScanning) Icons.Outlined.Cancel else Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.local_refresh_scan),
                        )
                    }
                    LocalWorkspaceMenu(
                        hasCustomSource = state.scan.sources.any { source -> !source.isDownloadDirectory },
                        onChooseDirectory = onChooseDirectory,
                        onResetDirectory = onResetDirectory,
                        onManageTags = { tagManagerVisible = true },
                    )
                },
                content = pageContent,
            )

        LocalHeaderMode.SELECTION ->
            SettingsAppBarScaffold(
                title =
                    pluralStringResource(
                        R.plurals.local_selected_count,
                        state.selectedResourceIds.size,
                        state.selectedResourceIds.size,
                    ),
                showBackButton = true,
                onNavigateUp = onClearSelection,
                statusBarInsets = WindowInsets(0, 0, 0, 0),
                containerColor = if (style.capabilities.bettboxSurfaces) style.surfaces.page else MaterialTheme.colorScheme.surfaceContainerLowest,
                actions = {
                    IconButton(
                        onClick = {
                            onSystemAction(
                                LocalWallpaperAction.ShareResources(
                                    state.scan.resources.filter { resource ->
                                        resource.id in state.selectedResourceIds
                                    },
                                ),
                            )
                            onClearSelection()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FileUpload,
                            contentDescription = stringResource(R.string.local_share_selected),
                        )
                    }
                    IconButton(onClick = { deleteConfirmationVisible = true }) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = stringResource(R.string.local_delete_selected),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Box {
                        IconButton(onClick = { selectionMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.local_more_selection_actions),
                            )
                        }
                        WallHubDropdownMenu(
                            expanded = selectionMenuExpanded,
                            onDismissRequest = { selectionMenuExpanded = false },
                        ) {
                            WallHubDropdownMenuItem(
                                text = { Text(stringResource(R.string.local_add_tag)) },
                                leadingIcon = {
                                    Icon(imageVector = Icons.Outlined.Edit, contentDescription = null)
                                },
                                onClick = {
                                    selectionMenuExpanded = false
                                    tagDialogVisible = true
                                },
                            )
                            WallHubDropdownMenuItem(
                                text = { Text(stringResource(R.string.local_toggle_favorites)) },
                                leadingIcon = {
                                    Icon(imageVector = Icons.Outlined.FavoriteBorder, contentDescription = null)
                                },
                                onClick = {
                                    selectionMenuExpanded = false
                                    state.selectedResourceIds.forEach(onToggleFavorite)
                                    onClearSelection()
                                },
                            )
                        }
                    }
                },
                content = pageContent,
            )

        LocalHeaderMode.HIDDEN -> pageContent(PaddingValues())
        }
        }
    }

    LocalWallpaperDialogs(
        state = state,
        tagDialogVisible = tagDialogVisible,
        tagInput = tagInput,
        onTagInputChanged = { tagInput = it.take(40) },
        onDismissTagDialog = {
            tagDialogVisible = false
            tagInput = ""
        },
        onConfirmAddTag = {
            onAddTag(tagInput.trim())
            tagDialogVisible = false
            tagInput = ""
        },
        tagManagerVisible = tagManagerVisible,
        editingTag = editingTag,
        editedTag = editedTag,
        onEditedTagChanged = { editedTag = it.take(40) },
        onStartTagEdit = { tag ->
            editingTag = tag
            editedTag = tag
        },
        onDeleteTag = onDeleteTag,
        onConfirmTagManager = {
            editingTag?.let { onRenameTag(it, editedTag) }
            tagManagerVisible = false
            editingTag = null
            editedTag = ""
        },
        onDismissTagManager = {
            tagManagerVisible = false
            editingTag = null
            editedTag = ""
        },
        onCancelTagEdit = {
            editingTag = null
            editedTag = ""
        },
        deleteConfirmationVisible = deleteConfirmationVisible,
        onDismissDeleteConfirmation = { deleteConfirmationVisible = false },
        onConfirmDelete = {
            onDeleteResources(selectedForDelete)
            deleteConfirmationVisible = false
        },
    )
}

@Composable
private fun LocalWallpaperDialogs(
    state: LocalWallpaperUiState,
    tagDialogVisible: Boolean,
    tagInput: String,
    onTagInputChanged: (String) -> Unit,
    onDismissTagDialog: () -> Unit,
    onConfirmAddTag: () -> Unit,
    tagManagerVisible: Boolean,
    editingTag: String?,
    editedTag: String,
    onEditedTagChanged: (String) -> Unit,
    onStartTagEdit: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onConfirmTagManager: () -> Unit,
    onDismissTagManager: () -> Unit,
    onCancelTagEdit: () -> Unit,
    deleteConfirmationVisible: Boolean,
    onDismissDeleteConfirmation: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    if (tagDialogVisible) {
        WallHubAlertDialog(
            onDismissRequest = onDismissTagDialog,
            title = { Text(stringResource(R.string.local_add_tag)) },
            text = {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = onTagInputChanged,
                    singleLine = true,
                    label = { Text(stringResource(R.string.local_tag_name)) },
                )
            },
            confirmButton = {
                TextButton(enabled = tagInput.isNotBlank(), onClick = onConfirmAddTag) {
                    Text(stringResource(R.string.local_add))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissTagDialog) {
                    Text(stringResource(R.string.local_cancel))
                }
            },
        )
    }
    if (tagManagerVisible) {
        LocalTagManagerDialog(
            state = state,
            editingTag = editingTag,
            editedTag = editedTag,
            onEditedTagChanged = onEditedTagChanged,
            onStartTagEdit = onStartTagEdit,
            onDeleteTag = onDeleteTag,
            onConfirm = onConfirmTagManager,
            onDismiss = onDismissTagManager,
            onCancelEdit = onCancelTagEdit,
        )
    }
    if (deleteConfirmationVisible) {
        WallHubAlertDialog(
            onDismissRequest = onDismissDeleteConfirmation,
            title = { Text(stringResource(R.string.local_delete_resources_title)) },
            text = {
                Text(
                    stringResource(R.string.local_delete_resources_message),
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) { Text(stringResource(R.string.local_delete)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeleteConfirmation) {
                    Text(stringResource(R.string.local_cancel))
                }
            },
        )
    }
}

@Composable
private fun LocalTagManagerDialog(
    state: LocalWallpaperUiState,
    editingTag: String?,
    editedTag: String,
    onEditedTagChanged: (String) -> Unit,
    onStartTagEdit: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    WallHubAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_manage_tags)) },
        text = {
            if (editingTag != null) {
                OutlinedTextField(
                    value = editedTag,
                    onValueChange = onEditedTagChanged,
                    singleLine = true,
                    label = { Text(stringResource(R.string.local_new_name)) },
                )
            } else if (state.allTags.isEmpty()) {
                Text(stringResource(R.string.local_no_custom_tags))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(state.allTags, key = { tag -> tag }) { tag ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(tag, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onStartTagEdit(tag) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = stringResource(R.string.local_rename_tag),
                                )
                            }
                            IconButton(onClick = { onDeleteTag(tag) }) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteSweep,
                                    contentDescription = stringResource(R.string.local_delete_tag),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = editingTag == null || editedTag.isNotBlank(),
                onClick = onConfirm,
            ) {
                Text(
                    if (editingTag == null) {
                        stringResource(R.string.local_done)
                    } else {
                        stringResource(R.string.local_save)
                    },
                )
            }
        },
        dismissButton =
            if (editingTag != null) {
                {
                    TextButton(onClick = onCancelEdit) {
                        Text(stringResource(R.string.local_cancel))
                    }
                }
            } else {
                null
            },
    )
}

@Composable
private fun LocalWorkspaceMenu(
    hasCustomSource: Boolean,
    onChooseDirectory: () -> Unit,
    onResetDirectory: () -> Unit,
    onManageTags: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.local_more_actions),
            )
        }
        WallHubDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            WallHubDropdownMenuItem(
                text = { Text(stringResource(R.string.local_choose_scan_directory)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.FolderOpen, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onChooseDirectory()
                },
            )
            if (hasCustomSource) {
                WallHubDropdownMenuItem(
                    text = { Text(stringResource(R.string.local_remove_custom_directory)) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.Cancel, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onResetDirectory()
                    },
                )
            }
            WallHubDropdownMenuItem(
                text = { Text(stringResource(R.string.local_manage_tags)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Edit, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onManageTags()
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private data class LocalFilterDraft(
    val formatFilter: LocalWallpaperFormatFilter = LocalWallpaperFormatFilter.ALL,
    val importFilter: LocalWallpaperImportFilter = LocalWallpaperImportFilter.ALL,
    val sourceId: String? = null,
    val favoriteOnly: Boolean = false,
    val selectedTag: String? = null,
    val sort: LocalWallpaperSort = LocalWallpaperSort.RECENT,
) {
    val activeCount: Int
        get() =
            listOf(
                formatFilter != LocalWallpaperFormatFilter.ALL,
                importFilter != LocalWallpaperImportFilter.ALL,
                sourceId != null,
                favoriteOnly,
                selectedTag != null,
                sort != LocalWallpaperSort.RECENT,
            ).count { it }
}

private fun LocalWallpaperUiState.toFilterDraft(): LocalFilterDraft =
    LocalFilterDraft(
        formatFilter = formatFilter,
        importFilter = importFilter,
        sourceId = sourceId,
        favoriteOnly = favoriteOnly,
        selectedTag = selectedTag,
        sort = sort,
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalFilterDrawer(
    state: LocalWallpaperUiState,
    isOpen: Boolean,
    onApply: (LocalFilterDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(state.toFilterDraft()) }
    LaunchedEffect(isOpen) {
        if (isOpen) draft = state.toFilterDraft()
    }
    Column(modifier = Modifier.fillMaxHeight()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = WallHubSpacing.md, top = WallHubSpacing.sm, end = WallHubSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.FilterAlt, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(horizontal = WallHubSpacing.sm)) {
                Text(
                    text = stringResource(R.string.local_filter_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.local_filter_active_summary, draft.activeCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Cancel, stringResource(R.string.local_filter_close))
            }
        }
        WallHubHorizontalDivider(modifier = Modifier.padding(top = WallHubSpacing.sm))
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = WallHubSpacing.xs),
        ) {
            item {
                FilterSection(
                    title = stringResource(R.string.local_filter_format),
                    summary = draft.formatFilter.label(),
                    icon = Icons.Outlined.GridView,
                    initiallyExpanded = true,
                ) {
                    TwoColumnFilterOptions(
                        options = LocalWallpaperFormatFilter.entries.toList(),
                        selected = { it == draft.formatFilter },
                        label = { it.label() },
                        onClick = { draft = draft.copy(formatFilter = it) },
                    )
                }
            }
            item {
                FilterSection(
                    title = stringResource(R.string.local_filter_import),
                    summary = draft.importFilter.label(),
                    icon = Icons.Outlined.FileUpload,
                ) {
                    TwoColumnFilterOptions(
                        options = LocalWallpaperImportFilter.entries.toList(),
                        selected = { it == draft.importFilter },
                        label = { it.label() },
                        onClick = { draft = draft.copy(importFilter = it) },
                    )
                }
            }
            item {
                val sourceOptions = listOf<String?>(null) + state.scan.sources.map(LocalWallpaperSource::id)
                FilterSection(
                    title = stringResource(R.string.local_filter_source),
                    summary =
                        sourceOptions
                            .firstOrNull { it == draft.sourceId }
                            ?.let { id -> state.scan.sources.firstOrNull { it.id == id }?.label }
                            ?: stringResource(R.string.local_filter_all_sources),
                    icon = Icons.Outlined.FolderOpen,
                ) {
                    TwoColumnFilterOptions(
                        options = sourceOptions,
                        selected = { it == draft.sourceId },
                        label = { id ->
                            id?.let { sourceId -> state.scan.sources.firstOrNull { it.id == sourceId }?.label } ?: stringResource(R.string.local_filter_all_sources)
                        },
                        onClick = { draft = draft.copy(sourceId = it) },
                    )
                }
            }
            item {
                FilterSection(
                    title = stringResource(R.string.local_filter_sort),
                    summary = draft.sort.label(),
                    icon = Icons.Outlined.Sort,
                ) {
                    TwoColumnFilterOptions(
                        options = LocalWallpaperSort.entries.toList(),
                        selected = { it == draft.sort },
                        label = { it.label() },
                        onClick = { draft = draft.copy(sort = it) },
                    )
                }
            }
            item {
                FilterSection(
                    title = stringResource(R.string.local_filter_tags),
                    summary = draft.selectedTag ?: stringResource(R.string.local_filter_all_tags),
                    icon = Icons.Outlined.Edit,
                ) {
                    if (state.allTags.isEmpty()) {
                        Text(
                            text = stringResource(R.string.local_filter_no_tags),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        TwoColumnFilterOptions(
                            options = listOf<String?>(null) + state.allTags,
                            selected = { it == draft.selectedTag },
                            label = { it ?: stringResource(R.string.local_filter_all_tags) },
                            onClick = { draft = draft.copy(selectedTag = it) },
                        )
                    }
                }
            }
            item {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.local_filter_favorites_only),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = draft.favoriteOnly,
                        onClick = { draft = draft.copy(favoriteOnly = !draft.favoriteOnly) },
                        label = { Text(stringResource(R.string.local_filter_favorites)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(WallHubSizeTokens.compactIcon),
                            )
                        },
                    )
                }
            }
        }
        Surface(
            color = wallHubStyle().surfaces.browseCard,
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = WallHubSpacing.sm)
                        .padding(top = WallHubSpacing.xxxs, bottom = WallHubSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { draft = LocalFilterDraft() }) {
                    Text(stringResource(R.string.local_filter_reset))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onApply(draft) }) {
                    Text(stringResource(R.string.local_filter_apply))
                }
            }
        }
    }
}

@Composable
private fun LocalLibraryHeader(
    state: LocalWallpaperUiState,
    expanded: Boolean,
    onViewModeSelected: (LocalWallpaperViewMode) -> Unit,
    onFormatFilterSelected: (LocalWallpaperFormatFilter) -> Unit,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = wallHubStyle()
    val totalBytes = state.resources.sumOf(LocalWallpaperResource::sizeBytes)
    val summary =
        if (expanded) {
            stringResource(
                if (state.activeFilterCount > 0) {
                    R.string.local_library_summary_filtered
                } else {
                    R.string.local_library_summary
                },
                state.resources.size,
                formatLocalSize(totalBytes),
            )
        } else {
            stringResource(
                R.string.local_library_summary_compact,
                state.resources.size,
            )
        }
    val sourceLabel =
        state.scan.currentSourceLabel
            ?: state.scan.sources.firstOrNull()?.label
            ?: stringResource(R.string.local_source)
    val sourceLine =
        if (expanded) {
            sourceLabel
        } else {
            "$sourceLabel · ${formatLocalSize(totalBytes)}"
        }
    Surface(
        modifier = modifier,
        shape = style.shapes.card,
        color = style.surfaces.content,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(WallHubSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(
                    modifier = Modifier.weight(if (expanded) 0.9f else 1f),
                    verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
                ) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = sourceLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (expanded) {
                    LocalQuickFilterChips(
                        state = state,
                        onFormatFilterSelected = onFormatFilterSelected,
                        onOpenFilters = onOpenFilters,
                        modifier = Modifier.weight(2.2f),
                    )
                }
                ViewModeButtons(
                    selected = state.viewMode,
                    onSelected = onViewModeSelected,
                    modifier = Modifier.width(if (expanded) 152.dp else 112.dp),
                )
            }
            if (!expanded) {
                LocalQuickFilterChips(
                    state = state,
                    onFormatFilterSelected = onFormatFilterSelected,
                    onOpenFilters = onOpenFilters,
                )
            }
            if (state.scan.isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalQuickFilterChips(
    state: LocalWallpaperUiState,
    onFormatFilterSelected: (LocalWallpaperFormatFilter) -> Unit,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatFilters =
        listOf(
            LocalWallpaperFormatFilter.ALL,
            LocalWallpaperFormatFilter.MPKG,
            LocalWallpaperFormatFilter.VIDEO,
            LocalWallpaperFormatFilter.HTML,
            LocalWallpaperFormatFilter.PKG,
        )
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
    ) {
        formatFilters.forEach { filter ->
            FilterChip(
                selected = state.formatFilter == filter,
                onClick = { onFormatFilterSelected(filter) },
                label = { Text(filter.label()) },
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
        FilterChip(
            selected = state.activeFilterCount > 0,
            onClick = onOpenFilters,
            label = {
                Text(
                    if (state.activeFilterCount > 0) {
                        stringResource(R.string.local_filter_button_count, state.activeFilterCount)
                    } else {
                        stringResource(R.string.local_filter_button)
                    },
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.FilterAlt,
                    contentDescription = null,
                    modifier = Modifier.size(WallHubSizeTokens.compactIcon),
                )
            },
            modifier = Modifier.heightIn(min = 40.dp),
        )
    }
}

@Composable
private fun LocalScanIssueSummary(state: LocalWallpaperUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = stringResource(R.string.local_read_resource_failed),
            modifier =
                Modifier
                    .widthIn(max = WallHubSizeTokens.readableContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.xs),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ViewModeButtons(
    selected: LocalWallpaperViewMode,
    onSelected: (LocalWallpaperViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    WallHubSingleChoiceSegmentedControl(
        options = listOf(LocalWallpaperViewMode.LIST, LocalWallpaperViewMode.GRID),
        selected = selected,
        onSelected = onSelected,
        modifier = modifier,
        label = { mode ->
            Icon(
                imageVector = mode.icon(),
                contentDescription = mode.label(),
                modifier = Modifier.size(WallHubSizeTokens.smallIcon),
            )
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalWallpaperContent(
    state: LocalWallpaperUiState,
    selected: LocalWallpaperResource?,
    expanded: Boolean,
    onSelectResource: (String?) -> Unit,
    onStartSelection: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDeleteResource: (LocalWallpaperResource) -> Unit,
    onReplaceTags: (String, Set<String>) -> Unit,
    onAddTag: (LocalWallpaperResource) -> Unit,
    onSystemAction: (LocalWallpaperAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources =
        if (
            state.viewMode == LocalWallpaperViewMode.DETAIL &&
            selected != null &&
            state.resources.none { resource -> resource.id == selected.id }
        ) {
            listOf(selected) + state.resources
        } else {
            state.resources
        }
    if (
        resources.isEmpty() &&
        !state.scan.isScanning &&
        state.viewMode != LocalWallpaperViewMode.DETAIL
    ) {
        WallHubEmptyState(
            icon = Icons.Outlined.FolderOpen,
            title = stringResource(R.string.local_empty),
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    AnimatedContent(
        targetState = state.viewMode,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (
                fadeIn(tween(LOCAL_CONTENT_ENTER_DURATION_MS, easing = NAVIGATION_EASING)) +
                    slideInHorizontally(
                        animationSpec = tween(LOCAL_CONTENT_ENTER_DURATION_MS, easing = NAVIGATION_EASING),
                        initialOffsetX = { width -> direction * width / LOCAL_CONTENT_SLIDE_DIVISOR },
                    )
            ) togetherWith
                (
                    fadeOut(tween(LOCAL_CONTENT_EXIT_DURATION_MS, easing = NAVIGATION_EASING)) +
                        slideOutHorizontally(
                            animationSpec = tween(LOCAL_CONTENT_EXIT_DURATION_MS, easing = NAVIGATION_EASING),
                            targetOffsetX = { width -> -direction * width / LOCAL_CONTENT_SLIDE_DIVISOR },
                        )
                )
        },
        contentAlignment = Alignment.TopStart,
        label = "LocalWallpaperContent",
    ) { displayedMode ->
        when (displayedMode) {
            LocalWallpaperViewMode.DETAIL -> {
                LocalWallpaperDetailPage(
                    resource = selected,
                    onBack = { onSelectResource(null) },
                    onToggleFavorite = onToggleFavorite,
                    onDeleteResource = onDeleteResource,
                    onReplaceTags = onReplaceTags,
                    onAddTag = onAddTag,
                    onSystemAction = onSystemAction,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            LocalWallpaperViewMode.GRID -> {
                LocalWallpaperGrid(
                    resources = resources,
                    state = state,
                    expanded = expanded,
                    onSelectResource = onSelectResource,
                    onStartSelection = onStartSelection,
                    onToggleSelection = onToggleSelection,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            LocalWallpaperViewMode.LIST -> {
                LocalWallpaperList(
                    resources = resources,
                    state = state,
                    expanded = expanded,
                    onSelectResource = onSelectResource,
                    onStartSelection = onStartSelection,
                    onToggleSelection = onToggleSelection,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LocalScrollToTopButton(
    listState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val visible by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.88f),
        exit = fadeOut() + scaleOut(targetScale = 0.88f),
    ) {
        FloatingActionButton(
            onClick = { coroutineScope.launch { listState.animateScrollToItem(0) } },
            elevation =
                FloatingActionButtonDefaults.elevation(
                    defaultElevation = WallHubFabDefaultElevation,
                    pressedElevation = WallHubFabActiveElevation,
                    focusedElevation = WallHubFabDefaultElevation,
                    hoveredElevation = WallHubFabActiveElevation,
                ),
        ) {
            Icon(
                imageVector = Icons.Outlined.VerticalAlignTop,
                contentDescription = stringResource(R.string.local_back_to_top),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LocalWallpaperList(
    resources: List<LocalWallpaperResource>,
    state: LocalWallpaperUiState,
    expanded: Boolean,
    onSelectResource: (String?) -> Unit,
    onStartSelection: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyGridState()
    Box(modifier = modifier) {
        LazyVerticalGrid(
            columns =
                if (expanded) {
                    GridCells.Adaptive(minSize = 420.dp)
                } else {
                    GridCells.Fixed(1)
                },
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = WallHubSpacing.md,
                    top = WallHubSpacing.xs,
                    end = WallHubSpacing.md,
                    bottom = LocalWallHubOverlayBottomClearance.current,
                ),
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
            verticalArrangement =
                Arrangement.spacedBy(
                    if (wallHubStyle().capabilities.bettboxSurfaces) WallHubSpacing.sm else WallHubSpacing.xs,
                ),
        ) {
            items(resources, key = LocalWallpaperResource::id) { resource ->
                LocalWallpaperListItem(
                    resource = resource,
                    selected = resource.id == state.selectedResourceId,
                    checked = resource.id in state.selectedResourceIds,
                    selectionMode = state.selectionMode,
                    onClick = {
                        if (state.selectionMode) onToggleSelection(resource.id) else onSelectResource(resource.id)
                    },
                    onLongClick = { onStartSelection(resource.id) },
                    modifier =
                        Modifier.animateItem(
                            fadeInSpec = tween(LOCAL_ITEM_FADE_IN_DURATION_MS),
                            placementSpec =
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            fadeOutSpec = tween(LOCAL_ITEM_FADE_OUT_DURATION_MS),
                        ),
                )
            }
        }
        LocalScrollToTopButton(
            listState = listState,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = WallHubSpacing.md,
                        bottom = LocalWallHubOverlayBottomClearance.current + WallHubSpacing.md,
                    ),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private data class LocalCardSurface(
    val shape: Shape,
    val color: Color,
    val padding: Dp,
)

@Composable
private fun localCardSurface(
    style: WallHubStyle,
    selected: Boolean,
    checked: Boolean,
): LocalCardSurface {
    val bettbox = style.capabilities.bettboxSurfaces
    val shape = if (bettbox) style.shapes.card else MaterialTheme.shapes.small
    val color =
        when {
            selected || checked ->
                if (bettbox) style.selection.container else MaterialTheme.colorScheme.secondaryContainer
            bettbox -> style.surfaces.content
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        }
    return LocalCardSurface(
        shape = shape,
        color = color,
        padding = if (bettbox) WallHubSpacing.sm else WallHubSpacing.xs,
    )
}

@Composable
private fun LocalWallpaperListItem(
    resource: LocalWallpaperResource,
    selected: Boolean,
    checked: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = wallHubStyle()
    val surface = localCardSurface(style, selected, checked)
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("local-wallpaper-${resource.id}")
                .clip(surface.shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = surface.shape,
        color = surface.color,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 96.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(96.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = style.shapes.cardRadius,
                                bottomStart = style.shapes.cardRadius,
                            ),
                        ).background(style.surfaces.browseCard),
            ) {
                LocalWallpaperPreview(
                    resource = resource,
                    shape = RectangleShape,
                    modifier = Modifier.fillMaxSize(),
                )
                if (selectionMode) {
                    SelectionIndicator(
                        checked = checked,
                        modifier = Modifier.align(Alignment.TopEnd).padding(WallHubSpacing.dense),
                    )
                }
            }
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(
                            start = WallHubSpacing.sm,
                            top = WallHubSpacing.xs,
                            end = WallHubSpacing.xxs,
                            bottom = WallHubSpacing.xs,
                        ),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = resource.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        buildString {
                            append(resource.format.label())
                            append(' ')
                            append(formatLocalSize(resource.sizeBytes))
                            append(' ')
                            append(resource.sourceLabel)
                            if (resource.importState == LocalWallpaperImportState.IMPORT_REQUESTED) {
                                append(' ')
                                append(stringResource(R.string.local_import_requested))
                            }
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                resource.isFavorite ->
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(R.string.local_favorite),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = WallHubSpacing.sm),
                    )

                resource.format == LocalWallpaperFormat.MPKG ->
                    Icon(
                        imageVector = Icons.Outlined.PhoneAndroid,
                        contentDescription = stringResource(R.string.local_can_import),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = WallHubSpacing.sm),
                    )

                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalWallpaperGrid(
    resources: List<LocalWallpaperResource>,
    state: LocalWallpaperUiState,
    expanded: Boolean,
    onSelectResource: (String?) -> Unit,
    onStartSelection: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = wallHubStyle()
    val listState = rememberLazyGridState()
    Box(modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (expanded) 270.dp else 160.dp),
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = WallHubSpacing.md,
                    top = WallHubSpacing.xs,
                    end = WallHubSpacing.md,
                    bottom = LocalWallHubOverlayBottomClearance.current,
                ),
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
        items(resources, key = LocalWallpaperResource::id) { resource ->
            Surface(
                modifier =
                    Modifier
                        .animateItem(
                            fadeInSpec = tween(LOCAL_ITEM_FADE_IN_DURATION_MS),
                            placementSpec =
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            fadeOutSpec = tween(LOCAL_ITEM_FADE_OUT_DURATION_MS),
                        )
                        .clip(if (style.capabilities.bettboxSurfaces) style.shapes.card else MaterialTheme.shapes.small)
                        .combinedClickable(
                            onClick = {
                                if (state.selectionMode) {
                                    onToggleSelection(resource.id)
                                } else {
                                    onSelectResource(resource.id)
                                }
                            },
                            onLongClick = { onStartSelection(resource.id) },
                        ),
                shape = if (style.capabilities.bettboxSurfaces) style.shapes.card else MaterialTheme.shapes.small,
                color =
                    when {
                        resource.id in state.selectedResourceIds ->
                            if (style.capabilities.bettboxSurfaces) style.selection.container else MaterialTheme.colorScheme.secondaryContainer
                        style.capabilities.bettboxSurfaces -> style.surfaces.content
                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                    },
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
            ) {
                Column {
                    Box {
                        LocalWallpaperPreview(
                            resource = resource,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1.6f),
                        )
                        LocalFormatBadge(
                            format = resource.format,
                            modifier = Modifier.align(Alignment.TopStart).padding(WallHubSpacing.xs),
                        )
                        if (state.selectionMode) {
                            SelectionIndicator(
                                checked = resource.id in state.selectedResourceIds,
                                modifier = Modifier.align(Alignment.TopEnd).padding(WallHubSpacing.xs),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.padding(WallHubSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.dense),
                    ) {
                        Text(
                            text = resource.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            minLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.dense),
                        ) {
                            Text(
                                text = "${resource.format.label()} · ${formatLocalSize(resource.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (resource.isFavorite) {
                                Icon(
                                    imageVector = Icons.Outlined.FavoriteBorder,
                                    contentDescription = stringResource(R.string.local_favorite),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(WallHubSizeTokens.compactIcon),
                                )
                            }
                        }
                    }
                }
            }
        }
        }
        LocalScrollToTopButton(
            listState = listState,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = WallHubSpacing.md,
                        bottom = LocalWallHubOverlayBottomClearance.current + WallHubSpacing.md,
                    ),
        )
    }
}

@Composable
private fun LocalWallpaperDetailPage(
    resource: LocalWallpaperResource?,
    onBack: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDeleteResource: (LocalWallpaperResource) -> Unit,
    onReplaceTags: (String, Set<String>) -> Unit,
    onAddTag: (LocalWallpaperResource) -> Unit,
    onSystemAction: (LocalWallpaperAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (resource == null) {
        WallHubEmptyState(
            icon = Icons.Outlined.Info,
            title = stringResource(R.string.local_select_resource),
            modifier = modifier,
        )
        return
    }
    val expanded = LocalWallHubAdaptiveLayout.current.width == WallHubWindowLayout.EXPANDED
    var detailEntered by remember(resource.id) { mutableStateOf(false) }
    var detailExitRequested by remember(resource.id) { mutableStateOf(false) }
    val detailScope = rememberCoroutineScope()
    val requestDetailClose: () -> Unit = {
        if (!detailExitRequested) {
            detailExitRequested = true
            detailScope.launch {
                delay(220)
                onBack()
            }
        }
    }
    BackHandler(enabled = true) { requestDetailClose() }
    LaunchedEffect(resource.id) { detailEntered = true }
    val detailOffset by
        animateFloatAsState(
            targetValue =
                when {
                    detailExitRequested -> 1f / 18f
                    detailEntered -> 0f
                    else -> 0.1f
                },
            animationSpec = tween(durationMillis = if (detailExitRequested) 220 else 320, easing = NAVIGATION_EASING),
            label = "LocalDetailEntryOffset",
        )
    val detailAlpha by
        animateFloatAsState(
            targetValue =
                when {
                    detailExitRequested -> 0f
                    detailEntered -> 1f
                    else -> 0f
                },
            animationSpec = tween(durationMillis = if (detailExitRequested) 220 else 220, easing = NAVIGATION_EASING),
            label = "LocalDetailEntryAlpha",
        )
    Box(
        modifier =
            modifier.graphicsLayer {
                translationX = size.width * detailOffset
                alpha = detailAlpha
            },
    ) {
    SettingsAppBarScaffold(
        title = resource.title,
        modifier = modifier,
        showBackButton = true,
        onNavigateUp = requestDetailClose,
        statusBarInsets = WindowInsets(0, 0, 0, 0),
        containerColor = wallHubStyle().surfaces.page,
        actions = {
            SettingsToolbarActionButton(
                imageVector = if (resource.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = stringResource(R.string.local_favorite),
                onClick = { onToggleFavorite(resource.id) },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val scrollState = rememberScrollState()
            Crossfade(
                targetState = expanded,
                animationSpec = tween(durationMillis = 180),
                label = "LocalDetailAdaptiveLayout",
            ) { isExpanded ->
                val contentModifier =
                    Modifier
                        .widthIn(max = if (isExpanded) 1480.dp else 760.dp)
                        .fillMaxSize()
                        .align(Alignment.TopCenter)
                        .verticalScroll(scrollState)
                        .padding(
                            start = if (isExpanded) WallHubSpacing.lg else WallHubSpacing.md,
                            top = WallHubSpacing.sm,
                            end = if (isExpanded) WallHubSpacing.lg else WallHubSpacing.md,
                            bottom = LocalWallHubOverlayBottomClearance.current + WallHubSpacing.md,
                        )
                if (isExpanded) {
                    Row(
                        modifier = contentModifier,
                        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LocalDetailPreviewCard(
                            resource = resource,
                            expanded = true,
                            modifier = Modifier.weight(1.55f),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
                        ) {
                            LocalDetailInfoCard(resource = resource, onSystemAction = onSystemAction)
                            LocalDetailTagsCard(
                                resource = resource,
                                onReplaceTags = onReplaceTags,
                                onAddTag = onAddTag,
                            )
                            LocalDetailActionsCard(
                                resource = resource,
                                onDeleteResource = onDeleteResource,
                                onSystemAction = onSystemAction,
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = contentModifier,
                        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
                    ) {
                        LocalDetailPreviewCard(resource = resource, expanded = false)
                        LocalDetailActionsCard(
                            resource = resource,
                            onDeleteResource = onDeleteResource,
                            onSystemAction = onSystemAction,
                        )
                        LocalDetailInfoCard(resource = resource, onSystemAction = onSystemAction)
                        LocalDetailTagsCard(
                            resource = resource,
                            onReplaceTags = onReplaceTags,
                            onAddTag = onAddTag,
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun LocalDetailPreviewCard(
    resource: LocalWallpaperResource,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val style = wallHubStyle()
    Surface(
        modifier = modifier,
        shape = style.shapes.card,
        color = style.surfaces.content,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (expanded) 1.5f else 1.65f)
                        .background(style.surfaces.browseCard),
            ) {
                LocalWallpaperPreview(
                    resource = resource,
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    contentScale = ContentScale.Fit,
                )
                LocalFormatBadge(
                    format = resource.format,
                    modifier = Modifier.align(Alignment.TopStart).padding(WallHubSpacing.sm),
                )
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
                ) {
                    Text(
                        text = "${resource.format.label()}  ${formatLocalSize(resource.sizeBytes)}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    Text(
                        text = resource.sourceLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (resource.importState == LocalWallpaperImportState.IMPORT_REQUESTED) {
                    Text(
                        text = resource.importState.label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalDetailActionsCard(
    resource: LocalWallpaperResource,
    onDeleteResource: (LocalWallpaperResource) -> Unit,
    onSystemAction: (LocalWallpaperAction) -> Unit,
) {
    LocalDetailSectionCard(
        title = stringResource(R.string.local_detail_actions),
        icon = Icons.Outlined.Settings,
    ) {
        val onPrimaryAction = {
            if (resource.format == LocalWallpaperFormat.MPKG) {
                onSystemAction(LocalWallpaperAction.ImportToWallpaperEngine(resource))
            } else {
                onSystemAction(LocalWallpaperAction.OpenResource(resource))
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val placeSideBySide = maxWidth >= LOCAL_DETAIL_ACTION_BREAKPOINT
            Column(verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm)) {
                if (placeSideBySide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ResourcePrimaryActionButton(
                            resource = resource,
                            onClick = onPrimaryAction,
                            modifier = Modifier.weight(1f),
                        )
                        ResourceOpenDetailButton(
                            resource = resource,
                            onSystemAction = onSystemAction,
                            modifier = Modifier.weight(0.9f),
                        )
                    }
                } else {
                    ResourcePrimaryActionButton(
                        resource = resource,
                        onClick = onPrimaryAction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ResourceOpenDetailButton(
                        resource = resource,
                        onSystemAction = onSystemAction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ResourceUtilityActions(
                        resource = resource,
                        onDeleteResource = onDeleteResource,
                        onSystemAction = onSystemAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResourceOpenDetailButton(
    resource: LocalWallpaperResource,
    onSystemAction: (LocalWallpaperAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    resource.workshopId?.let { workshopId ->
        OutlinedButton(
            onClick = {
                onSystemAction(LocalWallpaperAction.OpenWorkshopDetail(workshopId))
            },
            modifier = modifier.heightIn(min = WallHubSpacing.xxl),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(WallHubSpacing.xs))
            Text(
                text = stringResource(R.string.local_open_project_detail),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LocalDetailInfoCard(
    resource: LocalWallpaperResource,
    onSystemAction: (LocalWallpaperAction) -> Unit,
) {
    LocalDetailSectionCard(
        title = stringResource(R.string.local_file_information),
        icon = Icons.Outlined.Info,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs)) {
            DetailLine(stringResource(R.string.local_format), resource.format.label())
            DetailLine(stringResource(R.string.local_detection), resource.detectionReason)
            DetailLine(stringResource(R.string.local_size), formatLocalSize(resource.sizeBytes))
            DetailLine(stringResource(R.string.local_source), resource.sourceLabel)
            DetailLine(stringResource(R.string.local_location), resource.relativePath)
            if (resource.format == LocalWallpaperFormat.MPKG) {
                DetailLine(
                    stringResource(R.string.local_conversion_preset),
                    resource.mpkgConversionPreset?.label()
                        ?: stringResource(R.string.local_conversion_preset_unknown_external),
                )
            }
            resource.workshopId?.let { id ->
                CopyableDetailLine(
                    label = stringResource(R.string.local_workshop_id),
                    value = id.toString(),
                    onClick = {
                        onSystemAction(LocalWallpaperAction.CopyWorkshopId(id))
                    },
                )
            }
            DetailLine(
                stringResource(R.string.local_import_state),
                resource.importState.label(),
            )
        }
    }
}

@Composable
private fun LocalDetailTagsCard(
    resource: LocalWallpaperResource,
    onReplaceTags: (String, Set<String>) -> Unit,
    onAddTag: (LocalWallpaperResource) -> Unit,
) {
    LocalDetailSectionCard(
        title = stringResource(R.string.local_tags),
        icon = Icons.Outlined.Edit,
        headerAction = {
            TextButton(onClick = { onAddTag(resource) }) {
                Text(stringResource(R.string.local_add))
            }
        },
    ) {
        if (resource.tags.isEmpty()) {
            Text(
                stringResource(R.string.local_no_custom_tags),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            ) {
                resource.tags.forEach { tag ->
                    AssistChip(
                        onClick = {
                            onReplaceTags(resource.id, resource.tags - tag)
                        },
                        label = { Text(tag) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Cancel,
                                contentDescription = stringResource(R.string.local_remove_tag),
                                modifier = Modifier.size(WallHubSizeTokens.compactIcon),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalDetailSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val style = wallHubStyle()
    Surface(
        modifier = modifier.animateContentSize(),
        shape = style.shapes.card,
        color = style.surfaces.content,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier.padding(
                    horizontal = WallHubSpacing.md,
                    vertical = WallHubSpacing.sm,
                ),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(WallHubSizeTokens.smallIcon),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = WallHubSpacing.xs),
                )
                headerAction?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun ResourcePrimaryActionButton(
    resource: LocalWallpaperResource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = WallHubSpacing.xxl),
    ) {
        Icon(
            imageVector =
                if (resource.format == LocalWallpaperFormat.MPKG) {
                    Icons.Outlined.PhoneAndroid
                } else {
                    Icons.AutoMirrored.Outlined.OpenInNew
                },
            contentDescription = null,
        )
        Spacer(modifier = Modifier.width(WallHubSpacing.xs))
        Text(
            if (resource.format == LocalWallpaperFormat.MPKG) {
                stringResource(R.string.local_import_wallpaper_engine)
            } else {
                stringResource(R.string.local_open_file)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ResourceUtilityActions(
    resource: LocalWallpaperResource,
    onDeleteResource: (LocalWallpaperResource) -> Unit,
    onSystemAction: (LocalWallpaperAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.heightIn(min = WallHubSpacing.xxl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    onSystemAction(LocalWallpaperAction.ShareResources(listOf(resource)))
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileUpload,
                    contentDescription = stringResource(R.string.local_share),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WallHubVerticalDivider(modifier = Modifier.height(WallHubSpacing.lg))
            IconButton(
                onClick = { onSystemAction(LocalWallpaperAction.CopyLocation(resource)) },
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.local_copy_location),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WallHubVerticalDivider(modifier = Modifier.height(WallHubSpacing.lg))
            IconButton(onClick = { onDeleteResource(resource) }) {
                Icon(
                    imageVector = Icons.Outlined.DeleteSweep,
                    contentDescription = stringResource(R.string.local_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun LocalWallpaperPreview(
    resource: LocalWallpaperResource,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = resource.format.icon(),
            contentDescription = resource.format.label(),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(WallHubSpacing.xl),
        )
        resource.workshopId?.takeIf { it > 0L }?.let { workshopId ->
            val context = LocalContext.current
            val imageRequest =
                remember(workshopId, resource.thumbnailPreviewUrl) {
                    ImageRequest
                        .Builder(context)
                        .data(WorkshopThumbnail(workshopId, resource.thumbnailPreviewUrl))
                        .memoryCacheKey("local-workshop-cover:$workshopId:${resource.thumbnailPreviewUrl.orEmpty()}")
                        .crossfade(LOCAL_THUMBNAIL_CROSSFADE_DURATION_MS)
                        .build()
                }
            AsyncImage(
                model = imageRequest,
                contentDescription = resource.title,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LocalFormatBadge(
    format: LocalWallpaperFormat,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        Text(
            text = format.label(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = WallHubSpacing.xs, vertical = WallHubSpacing.xxs),
        )
    }
}

@Composable
private fun SelectionIndicator(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(WallHubSpacing.lg),
        shape = MaterialTheme.shapes.small,
        color = if (checked) MaterialTheme.colorScheme.primary else Color.Transparent,
        border =
            if (checked) {
                null
            } else {
                androidx.compose.foundation.BorderStroke(
                    WallHubSpacing.xxxs,
                    MaterialTheme.colorScheme.outline,
                )
            },
    ) {
        if (checked) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailLine(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CopyableDetailLine(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = WallHubSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(88.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.local_copy_workshop_id),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(WallHubSizeTokens.compactIcon),
            )
        }
    }
}

private fun openLocalResource(
    context: android.content.Context,
    resource: LocalWallpaperResource,
): Result<Unit> {
    val uri =
        shareableUri(context, resource.contentUri)
            ?: return Result.failure(IllegalStateException())
    val intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resource.mimeType ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return runCatching {
        context.startActivity(Intent.createChooser(intent, resource.title))
    }
}

private fun shareLocalResources(
    context: android.content.Context,
    resources: List<LocalWallpaperResource>,
): Result<Unit> {
    if (resources.isEmpty()) {
        return Result.failure(IllegalStateException())
    }
    val uris = resources.mapNotNull { resource -> shareableUri(context, resource.contentUri) }
    if (uris.isEmpty()) {
        return Result.failure(IllegalStateException())
    }
    val mimeTypes = resources.mapNotNull(LocalWallpaperResource::mimeType).distinct()
    val intent =
        if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeTypes.singleOrNull() ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uris.single())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeTypes.singleOrNull() ?: "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    return runCatching {
        context.startActivity(Intent.createChooser(intent, resources.singleOrNull()?.title))
    }
}

private fun copyLocalResourceLocation(
    context: android.content.Context,
    resource: LocalWallpaperResource,
) {
    val location = "${resource.sourceLabel}/${resource.relativePath}".replace("//", "/")
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(resource.title, location))
}

private fun copyWorkshopId(
    context: android.content.Context,
    workshopId: Long,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("Workshop ID", workshopId.toString()))
}

private fun launchWallpaperEngineImport(
    context: android.content.Context,
    resource: LocalWallpaperResource,
    readResourceFailed: String,
    notInstalledMessage: String,
): Result<Unit> {
    val uri =
        shareableUri(context, resource.contentUri)
            ?: return Result.failure(IllegalStateException(readResourceFailed))
    val component =
        ComponentName(
            WALLPAPER_ENGINE_PACKAGE,
            WALLPAPER_ENGINE_IMPORT_ACTIVITY,
        )
    val installed =
        runCatching {
            context.packageManager.getActivityInfo(component, 0)
        }.isSuccess
    if (!installed) {
        return Result.failure(
            IllegalStateException(
                notInstalledMessage,
            ),
        )
    }
    val intent =
        Intent(Intent.ACTION_VIEW).apply {
            this.component = component
            setDataAndType(uri, WorkshopExportMimeTypes.MPKG)
            clipData = ClipData.newRawUri(resource.displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return runCatching {
        context.grantUriPermission(
            WALLPAPER_ENGINE_PACKAGE,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        context.startActivity(intent)
    }
}

private fun shareableUri(
    context: android.content.Context,
    rawUri: String,
): Uri? {
    val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return null
    if (uri.scheme != "file") return uri
    val file = uri.path?.let(::File) ?: return null
    return runCatching {
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file,
        )
    }.getOrNull()
}

private fun LocalWallpaperViewMode.icon(): androidx.compose.ui.graphics.vector.ImageVector =
    when (this) {
        LocalWallpaperViewMode.LIST -> Icons.AutoMirrored.Outlined.ViewList
        LocalWallpaperViewMode.GRID -> Icons.Outlined.GridView
        LocalWallpaperViewMode.DETAIL -> Icons.Outlined.Info
    }

@Composable
private fun LocalWallpaperViewMode.label(): String =
    when (this) {
        LocalWallpaperViewMode.LIST -> stringResource(R.string.local_view_list)
        LocalWallpaperViewMode.GRID -> stringResource(R.string.local_view_grid)
        LocalWallpaperViewMode.DETAIL -> stringResource(R.string.local_view_detail)
    }

@Composable
private fun LocalWallpaperFormat.label(): String =
    when (this) {
        LocalWallpaperFormat.MPKG -> "MPKG"
        LocalWallpaperFormat.PKG -> "PKG"
        LocalWallpaperFormat.VIDEO -> stringResource(R.string.local_format_video)
        LocalWallpaperFormat.HTML -> "HTML"
        LocalWallpaperFormat.UNKNOWN -> stringResource(R.string.local_format_unknown)
    }

@Composable
private fun LocalWallpaperFormatFilter.label(): String =
    when (this) {
        LocalWallpaperFormatFilter.ALL -> stringResource(R.string.local_filter_all)
        LocalWallpaperFormatFilter.MPKG -> "MPKG"
        LocalWallpaperFormatFilter.VIDEO -> stringResource(R.string.local_format_video)
        LocalWallpaperFormatFilter.HTML -> "HTML"
        LocalWallpaperFormatFilter.PKG -> "PKG"
        LocalWallpaperFormatFilter.UNKNOWN -> stringResource(R.string.local_format_unknown)
    }

@Composable
private fun LocalWallpaperImportFilter.label(): String =
    when (this) {
        LocalWallpaperImportFilter.ALL -> stringResource(R.string.local_filter_all_import)
        LocalWallpaperImportFilter.NOT_IMPORTED -> stringResource(R.string.local_not_imported)
        LocalWallpaperImportFilter.IMPORT_REQUESTED -> stringResource(R.string.local_import_requested)
    }

@Composable
private fun LocalWallpaperSort.label(): String =
    when (this) {
        LocalWallpaperSort.RECENT -> stringResource(R.string.local_sort_recent)
        LocalWallpaperSort.NAME -> stringResource(R.string.local_sort_name)
        LocalWallpaperSort.SIZE -> stringResource(R.string.local_sort_size)
        LocalWallpaperSort.TYPE -> stringResource(R.string.local_sort_type)
    }

private fun LocalWallpaperFormat.icon(): androidx.compose.ui.graphics.vector.ImageVector =
    when (this) {
        LocalWallpaperFormat.MPKG,
        LocalWallpaperFormat.PKG,
        -> Icons.Outlined.FolderOpen

        LocalWallpaperFormat.VIDEO -> Icons.Outlined.PlayArrow
        LocalWallpaperFormat.HTML -> Icons.AutoMirrored.Outlined.OpenInNew
        LocalWallpaperFormat.UNKNOWN -> Icons.Outlined.Info
    }

@Composable
private fun LocalWallpaperImportState.label(): String =
    when (this) {
        LocalWallpaperImportState.NOT_IMPORTED -> stringResource(R.string.local_not_imported)
        LocalWallpaperImportState.IMPORT_REQUESTED -> stringResource(R.string.local_import_requested)
    }

private fun formatLocalSize(bytes: Long): String =
    when {
        bytes <= 0L -> "—"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> formatByteSize(bytes)
    }

private val LOCAL_DETAIL_ACTION_BREAKPOINT = 460.dp
private val LOCAL_PAGE_MAX_WIDTH = 1200.dp
private val LOCAL_HEADER_COLLAPSE_DISTANCE = 44.dp
private val LOCAL_HEADER_EXPAND_DISTANCE = 20.dp
private const val LOCAL_HEADER_MODE_ENTER_DURATION_MS = 200
private const val LOCAL_HEADER_MODE_EXIT_DURATION_MS = 140
private const val LOCAL_CONTENT_ENTER_DURATION_MS = 320
private const val LOCAL_CONTENT_EXIT_DURATION_MS = 220
private const val LOCAL_CONTENT_SLIDE_DIVISOR = 9
private const val LOCAL_ITEM_FADE_IN_DURATION_MS = 220
private const val LOCAL_ITEM_FADE_OUT_DURATION_MS = 140
private const val LOCAL_THUMBNAIL_CROSSFADE_DURATION_MS = 180

private enum class LocalHeaderMode {
    HIDDEN,
    WORKSPACE,
    SELECTION,
}

private const val WALLPAPER_ENGINE_PACKAGE = "io.wallpaperengine.weclient"
private const val WALLPAPER_ENGINE_IMPORT_ACTIVITY = "io.wallpaperengine.weclient.BrowseActivity"
