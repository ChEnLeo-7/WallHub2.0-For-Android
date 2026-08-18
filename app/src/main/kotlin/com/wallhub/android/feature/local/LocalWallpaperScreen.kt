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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubSingleChoiceSegmentedControl
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubToolbarSearchTitle
import com.wallhub.android.core.format.formatByteSize
import com.wallhub.android.core.designsystem.rememberWallHubDirectionalCollapseConnection
import com.wallhub.android.core.model.LocalWallpaperFormat
import com.wallhub.android.core.model.LocalWallpaperImportState
import com.wallhub.android.core.model.LocalWallpaperResource
import com.wallhub.android.core.model.LocalWallpaperViewMode
import kotlinx.coroutines.flow.collect
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
import androidx.compose.material.icons.outlined.FolderOpen
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
    onScrollChromeCollapsedChanged: (Boolean) -> Unit = {},
    isPageActive: Boolean = true,
    viewModel: LocalWallpaperViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LocalWallpaperEffectHandler(viewModel)

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
    )
}

@Composable
private fun LocalWallpaperEffectHandler(viewModel: LocalWallpaperViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val selectedDirectoryLabel = stringResource(R.string.local_selected_directory)
    val authorizeDirectoryFailed = stringResource(R.string.local_authorize_directory_failed)
    val openResourceFailed = stringResource(R.string.local_open_resource_failed)
    val shareResourcesFailed = stringResource(R.string.local_share_resources_failed)
    val locationCopied = stringResource(R.string.local_location_copied)
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
        importFailed,
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
private fun LocalWallpaperScreen(
    state: LocalWallpaperUiState,
    isPageActive: Boolean,
    onAction: (LocalWallpaperAction) -> Unit,
    onScrollChromeCollapsedChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val onChooseDirectory: () -> Unit = { onAction(LocalWallpaperAction.ChooseDirectory) }
    val onResetDirectory: () -> Unit = { onAction(LocalWallpaperAction.ResetDirectory) }
    val onRefresh: () -> Unit = { onAction(LocalWallpaperAction.Refresh) }
    val onCancelScan: () -> Unit = { onAction(LocalWallpaperAction.CancelScan) }
    val onSearchQueryChanged: (String) -> Unit = { onAction(LocalWallpaperAction.SearchQueryChanged(it)) }
    val onViewModeSelected: (LocalWallpaperViewMode) -> Unit = {
        onAction(LocalWallpaperAction.SelectViewMode(it))
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
                (state.selectionMode || state.viewMode == LocalWallpaperViewMode.DETAIL),
    ) {
        it.collect()
        if (state.selectionMode) onClearSelection() else onSelectResource(null)
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
            LocalWallpaperContent(
                state = state,
                selected = selected,
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
                modifier = Modifier.weight(1f),
            )
        }
    }
    when (headerMode) {
        LocalHeaderMode.WORKSPACE ->
            SettingsAppBarScaffold(
                title = stringResource(R.string.navigation_local),
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
                    SettingsToolbarActionButton(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.management_settings),
                        onClick = onOpenSettings,
                        buttonSize = 64.dp,
                        containerSize = 48.dp,
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
                        DropdownMenu(
                            expanded = selectionMenuExpanded,
                            onDismissRequest = { selectionMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.local_add_tag)) },
                                leadingIcon = {
                                    Icon(imageVector = Icons.Outlined.Edit, contentDescription = null)
                                },
                                onClick = {
                                    selectionMenuExpanded = false
                                    tagDialogVisible = true
                                },
                            )
                            DropdownMenuItem(
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
        AlertDialog(
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
        AlertDialog(
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
    AlertDialog(
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
private fun LocalWorkspaceHeader(
    state: LocalWallpaperUiState,
    onChooseDirectory: () -> Unit,
    onResetDirectory: () -> Unit,
    onRefresh: () -> Unit,
    onCancelScan: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onViewModeSelected: (LocalWallpaperViewMode) -> Unit,
    onManageTags: () -> Unit,
    secondaryChromeCollapsed: Boolean,
) {
    val customSource = state.scan.sources.firstOrNull { source -> !source.isDownloadDirectory }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = WallHubSizeTokens.readableContentMaxWidth)
                        .fillMaxWidth()
                        .align(Alignment.Center)
                        .padding(horizontal = WallHubSpacing.sm, vertical = WallHubSpacing.dense),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.dense),
            ) {
                AnimatedVisibility(
                    visible = !secondaryChromeCollapsed,
                    enter =
                        expandVertically(
                            animationSpec = tween(LOCAL_HEADER_EXPAND_DURATION_MS),
                            expandFrom = Alignment.Top,
                        ) + fadeIn(tween(LOCAL_HEADER_EXPAND_DURATION_MS)),
                    exit =
                        shrinkVertically(
                            animationSpec = tween(LOCAL_HEADER_COLLAPSE_DURATION_MS),
                            shrinkTowards = Alignment.Top,
                        ) + fadeOut(tween(LOCAL_HEADER_COLLAPSE_DURATION_MS)),
                    label = "LocalSecondaryTools",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(WallHubSpacing.dense)) {
                        LocalSearchField(
                            query = state.searchQuery,
                            onQueryChanged = onSearchQueryChanged,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                        ) {
                            ViewModeButtons(
                                selected = state.viewMode,
                                onSelected = onViewModeSelected,
                                modifier = Modifier.width(160.dp),
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = if (state.scan.isScanning) onCancelScan else onRefresh) {
                                Icon(
                                    imageVector =
                                        if (state.scan.isScanning) {
                                            Icons.Outlined.Cancel
                                        } else {
                                            Icons.Outlined.Refresh
                                        },
                                    contentDescription =
                                        if (state.scan.isScanning) {
                                            stringResource(R.string.local_cancel_scan)
                                        } else {
                                            stringResource(R.string.local_refresh_scan)
                                        },
                                )
                            }
                            LocalWorkspaceMenu(
                                hasCustomSource = customSource != null,
                                onChooseDirectory = onChooseDirectory,
                                onResetDirectory = onResetDirectory,
                                onManageTags = onManageTags,
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = state.scan.isScanning,
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(180)),
                    label = "LocalScanProgress",
                ) {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalSearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.TextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.local_search_placeholder)) },
        leadingIcon = { Icon(imageVector = Icons.Outlined.Search, contentDescription = null) },
        trailingIcon =
            if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChanged("") }) {
                        Icon(
                            imageVector = Icons.Outlined.Cancel,
                            contentDescription = stringResource(R.string.local_clear_search),
                        )
                    }
                }
            } else {
                null
            },
        shape = MaterialTheme.shapes.medium,
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
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
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
                DropdownMenuItem(
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
            DropdownMenuItem(
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
                fadeIn(tween(LOCAL_CONTENT_ENTER_DURATION_MS)) +
                    slideInHorizontally(
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        initialOffsetX = { width -> direction * width / LOCAL_CONTENT_SLIDE_DIVISOR },
                    )
            ) togetherWith
                (
                    fadeOut(tween(LOCAL_CONTENT_EXIT_DURATION_MS)) +
                        slideOutHorizontally(
                            animationSpec =
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            targetOffsetX = { width -> -direction * width / LOCAL_CONTENT_SLIDE_DIVISOR },
                        )
                )
        },
        contentAlignment = Alignment.TopStart,
        label = "LocalWallpaperContent",
    ) { displayedMode ->
        when (displayedMode) {
            LocalWallpaperViewMode.DETAIL -> {
                LocalWallpaperDetail(
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
                    onSelectResource = onSelectResource,
                    onStartSelection = onStartSelection,
                    onToggleSelection = onToggleSelection,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LocalWallpaperList(
    resources: List<LocalWallpaperResource>,
    state: LocalWallpaperUiState,
    onSelectResource: (String?) -> Unit,
    onStartSelection: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding =
            PaddingValues(
                start = WallHubSpacing.md,
                top = WallHubSpacing.xs,
                end = WallHubSpacing.md,
                bottom = WallHubSizeTokens.bottomNavigationClearance,
            ),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
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
}

@OptIn(ExperimentalFoundationApi::class)
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
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("local-wallpaper-${resource.id}")
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = MaterialTheme.shapes.small,
        color =
            if (selected || checked) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
    ) {
        Row(
            modifier = Modifier.padding(WallHubSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                LocalWallpaperPreview(
                    resource = resource,
                    modifier = Modifier.size(width = 104.dp, height = 68.dp),
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
                        .padding(horizontal = WallHubSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
            ) {
                Text(
                    text = resource.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        buildString {
                            append(resource.format.label())
                            append(" · ${formatLocalSize(resource.sizeBytes)}")
                            append(" · ${resource.sourceLabel}")
                            if (resource.importState == LocalWallpaperImportState.IMPORT_REQUESTED) {
                                append(stringResource(R.string.local_import_requested_suffix))
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
                    )

                resource.format == LocalWallpaperFormat.MPKG ->
                    Icon(
                        imageVector = Icons.Outlined.PhoneAndroid,
                        contentDescription = stringResource(R.string.local_can_import),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onSelectResource: (String?) -> Unit,
    onStartSelection: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 190.dp),
        modifier = modifier,
        contentPadding =
            PaddingValues(
                start = WallHubSpacing.md,
                top = WallHubSpacing.xs,
                end = WallHubSpacing.md,
                bottom = WallHubSizeTokens.bottomNavigationClearance,
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
                        ).combinedClickable(
                            onClick = {
                                if (state.selectionMode) {
                                    onToggleSelection(resource.id)
                                } else {
                                    onSelectResource(resource.id)
                                }
                            },
                            onLongClick = { onStartSelection(resource.id) },
                        ),
                shape = MaterialTheme.shapes.small,
                color =
                    if (resource.id in state.selectedResourceIds) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
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
}

@Composable
private fun LocalWallpaperDetail(
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
    SettingsAppBarScaffold(
        title = resource.title,
        modifier = modifier,
        showBackButton = true,
        onNavigateUp = onBack,
        actions = {
            SettingsToolbarActionButton(
                imageVector = if (resource.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = stringResource(R.string.local_favorite),
                onClick = { onToggleFavorite(resource.id) },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(
                        start = WallHubSpacing.md,
                        top = WallHubSpacing.xs,
                        end = WallHubSpacing.md,
                        bottom = WallHubSizeTokens.bottomNavigationClearance,
                    ),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
        ) {
            LocalWallpaperPreview(
                resource = resource,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.65f),
                contentScale = ContentScale.Fit,
            )
            ResourceActionRow(
                resource = resource,
                onDeleteResource = onDeleteResource,
                onSystemAction = onSystemAction,
            )
            HorizontalDivider(color = DividerDefaults.color)
            Column(verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs)) {
                Text(stringResource(R.string.local_file_information), style = MaterialTheme.typography.titleMedium)
                DetailLine(stringResource(R.string.local_format), resource.format.label())
                DetailLine(stringResource(R.string.local_detection), resource.detectionReason)
                DetailLine(stringResource(R.string.local_size), formatLocalSize(resource.sizeBytes))
                DetailLine(stringResource(R.string.local_source), resource.sourceLabel)
                DetailLine(stringResource(R.string.local_location), resource.relativePath)
                resource.workshopId?.let { id ->
                    DetailLine(stringResource(R.string.local_workshop_id), id.toString())
                }
                DetailLine(
                    stringResource(R.string.local_import_state),
                    resource.importState.label(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            ) {
                Text(stringResource(R.string.local_tags), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onAddTag(resource) }) {
                    Text(stringResource(R.string.local_add))
                }
            }
            if (resource.tags.isEmpty()) {
                Text(
                    stringResource(R.string.local_no_custom_tags),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
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
}

@Composable
private fun ResourceActionRow(
    resource: LocalWallpaperResource,
    onDeleteResource: (LocalWallpaperResource) -> Unit,
    onSystemAction: (LocalWallpaperAction) -> Unit,
) {
    val onPrimaryAction = {
        if (resource.format == LocalWallpaperFormat.MPKG) {
            onSystemAction(LocalWallpaperAction.ImportToWallpaperEngine(resource))
        } else {
            onSystemAction(LocalWallpaperAction.OpenResource(resource))
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < LOCAL_DETAIL_ACTION_BREAKPOINT) {
            Column(verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs)) {
                ResourcePrimaryActionButton(
                    resource = resource,
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth(),
                )
                ResourceUtilityActions(
                    resource = resource,
                    onDeleteResource = onDeleteResource,
                    onSystemAction = onSystemAction,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        } else {
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
                ResourceUtilityActions(
                    resource = resource,
                    onDeleteResource = onDeleteResource,
                    onSystemAction = onSystemAction,
                )
            }
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
            VerticalDivider(
                modifier = Modifier.height(WallHubSpacing.lg),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            IconButton(
                onClick = { onSystemAction(LocalWallpaperAction.CopyLocation(resource)) },
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.local_copy_location),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            VerticalDivider(
                modifier = Modifier.height(WallHubSpacing.lg),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
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
    contentScale: ContentScale = ContentScale.Crop,
) {
    val shape = MaterialTheme.shapes.small
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = resource.format.icon(),
            contentDescription = resource.format.label(),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(WallHubSpacing.xl),
        )
        resource.thumbnailUri?.let { thumbnailUri ->
            val context = LocalContext.current
            val imageRequest =
                remember(thumbnailUri) {
                    ImageRequest
                        .Builder(context)
                        .data(Uri.parse(thumbnailUri))
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
            modifier = Modifier.padding(horizontal = 7.dp, vertical = WallHubSpacing.xxs),
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
            setDataAndType(uri, WALLPAPER_ENGINE_MPKG_MIME_TYPE)
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
private val LOCAL_HEADER_COLLAPSE_DISTANCE = 44.dp
private val LOCAL_HEADER_EXPAND_DISTANCE = 20.dp
private const val LOCAL_HEADER_EXPAND_DURATION_MS = 220
private const val LOCAL_HEADER_COLLAPSE_DURATION_MS = 160
private const val LOCAL_HEADER_MODE_ENTER_DURATION_MS = 200
private const val LOCAL_HEADER_MODE_EXIT_DURATION_MS = 140
private const val LOCAL_CONTENT_ENTER_DURATION_MS = 210
private const val LOCAL_CONTENT_EXIT_DURATION_MS = 150
private const val LOCAL_CONTENT_SLIDE_DIVISOR = 8
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
private const val WALLPAPER_ENGINE_MPKG_MIME_TYPE = "application/vnd.mpkg"
