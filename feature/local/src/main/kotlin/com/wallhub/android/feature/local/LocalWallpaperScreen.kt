package com.wallhub.android.feature.local

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wallhub.android.core.designsystem.LocalWallHubLanguage
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubIcons as Icons
import com.wallhub.android.core.designsystem.WallHubSingleChoiceSegmentedControl
import com.wallhub.android.core.designsystem.formatMegabytes
import com.wallhub.android.core.designsystem.rememberWallHubDirectionalCollapseConnection
import com.wallhub.android.core.model.LocalWallpaperFormat
import com.wallhub.android.core.model.LocalWallpaperImportState
import com.wallhub.android.core.model.LocalWallpaperResource
import com.wallhub.android.core.model.LocalWallpaperViewMode
import com.wallhub.android.core.model.AppLanguage
import java.io.File
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LocalWallpaperRoute(
    onScrollChromeCollapsedChanged: (Boolean) -> Unit = {},
    isPageActive: Boolean = true,
    viewModel: LocalWallpaperViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val directoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
                .onSuccess {
                    viewModel.setCustomDirectory(
                        treeUri.toString(),
                        treeUri.lastPathSegment
                            ?.substringAfterLast(':')
                            ?.ifBlank { null }
                            ?: "已选择本地管理目录",
                    )
                }
                .onFailure { error ->
                    viewModel.setActionMessage(error.message ?: "无法授权本地目录")
                }
        }
    }

    LaunchedEffect(isPageActive) {
        if (isPageActive) viewModel.enterPage()
    }
    DisposableEffect(Unit) {
        onDispose { onScrollChromeCollapsedChanged(false) }
    }

    LocalWallpaperScreen(
        state = state,
        isPageActive = isPageActive,
        onChooseDirectory = { directoryLauncher.launch(null) },
        onResetDirectory = viewModel::clearCustomDirectory,
        onRefresh = viewModel::scan,
        onCancelScan = viewModel::cancelScan,
        onSearchQueryChanged = viewModel::setSearchQuery,
        onViewModeSelected = viewModel::setViewMode,
        onSelectResource = viewModel::selectResource,
        onStartSelection = viewModel::startSelection,
        onToggleSelection = viewModel::toggleSelection,
        onClearSelection = viewModel::clearSelection,
        onToggleFavorite = viewModel::toggleFavorite,
        onAddTag = viewModel::addTagToSelection,
        onReplaceTags = viewModel::replaceResourceTags,
        onRenameTag = viewModel::renameTag,
        onDeleteTag = viewModel::deleteTag,
        onMarkImportRequested = viewModel::markImportRequested,
        onDeleteResources = viewModel::deleteResources,
        onActionMessageDismissed = { viewModel.setActionMessage("") },
        onScrollChromeCollapsedChanged = onScrollChromeCollapsedChanged,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LocalWallpaperScreen(
    state: LocalWallpaperUiState,
    isPageActive: Boolean,
    onChooseDirectory: () -> Unit,
    onResetDirectory: () -> Unit,
    onRefresh: () -> Unit,
    onCancelScan: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onViewModeSelected: (LocalWallpaperViewMode) -> Unit,
    onSelectResource: (String?) -> Unit,
    onStartSelection: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onClearSelection: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onReplaceTags: (String, Set<String>) -> Unit,
    onRenameTag: (String, String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onMarkImportRequested: (String) -> Unit,
    onDeleteResources: (Set<String>) -> Unit,
    onActionMessageDismissed: () -> Unit,
    onScrollChromeCollapsedChanged: (Boolean) -> Unit,
) {
    val language = LocalWallHubLanguage.current
    val context = LocalContext.current
    var tagDialogVisible by remember { mutableStateOf(false) }
    var tagManagerVisible by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<String?>(null) }
    var editedTag by remember { mutableStateOf("") }
    var tagInput by remember { mutableStateOf("") }
    var deleteConfirmationVisible by remember { mutableStateOf(false) }
    var selectionMenuExpanded by remember { mutableStateOf(false) }
    var secondaryChromeCollapsed by remember { mutableStateOf(false) }
    val updateSecondaryChromeCollapsed: (Boolean) -> Unit = { collapsed ->
        if (collapsed != secondaryChromeCollapsed) {
            secondaryChromeCollapsed = collapsed
            onScrollChromeCollapsedChanged(collapsed)
        }
    }
    val chromeScrollConnection = rememberWallHubDirectionalCollapseConnection(
        collapsed = secondaryChromeCollapsed,
        onCollapsedChanged = updateSecondaryChromeCollapsed,
        collapseDistance = LOCAL_HEADER_COLLAPSE_DISTANCE,
        expandDistance = LOCAL_HEADER_EXPAND_DISTANCE,
    )
    val selected = state.scan.resources.firstOrNull { it.id == state.selectedResourceId }
    val selectedForDelete = state.selectedResourceIds.ifEmpty {
        selected?.id?.let(::setOf).orEmpty()
    }
    val headerMode = when {
        state.selectionMode -> LocalHeaderMode.SELECTION
        state.viewMode == LocalWallpaperViewMode.DETAIL -> LocalHeaderMode.HIDDEN
        else -> LocalHeaderMode.WORKSPACE
    }
    LaunchedEffect(state.selectionMode) {
        if (!state.selectionMode) selectionMenuExpanded = false
    }
    BackHandler(
        enabled = isPageActive &&
            (state.selectionMode || state.viewMode == LocalWallpaperViewMode.DETAIL),
    ) {
        if (state.selectionMode) onClearSelection() else onSelectResource(null)
    }
    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = headerMode,
                transitionSpec = {
                    (fadeIn(tween(LOCAL_HEADER_MODE_ENTER_DURATION_MS)) +
                        slideInVertically(
                            animationSpec = tween(LOCAL_HEADER_MODE_ENTER_DURATION_MS),
                            initialOffsetY = { height -> -height / 4 },
                        )) togetherWith
                        (fadeOut(tween(LOCAL_HEADER_MODE_EXIT_DURATION_MS)) +
                            slideOutVertically(
                                animationSpec = tween(LOCAL_HEADER_MODE_EXIT_DURATION_MS),
                                targetOffsetY = { height -> -height / 4 },
                            ))
                },
                label = "LocalHeaderMode",
            ) { mode ->
                when (mode) {
                    LocalHeaderMode.HIDDEN -> Spacer(modifier = Modifier.height(0.dp))
                    LocalHeaderMode.WORKSPACE -> LocalWorkspaceHeader(
                        state = state,
                        language = language,
                        onChooseDirectory = onChooseDirectory,
                        onResetDirectory = onResetDirectory,
                        onRefresh = onRefresh,
                        onCancelScan = onCancelScan,
                        onSearchQueryChanged = onSearchQueryChanged,
                        onViewModeSelected = onViewModeSelected,
                        onManageTags = { tagManagerVisible = true },
                        secondaryChromeCollapsed = secondaryChromeCollapsed,
                    )

                    LocalHeaderMode.SELECTION -> LocalSelectionBar(
                        selectedCount = state.selectedResourceIds.size,
                        language = language,
                        menuExpanded = selectionMenuExpanded,
                        onMenuExpandedChanged = { selectionMenuExpanded = it },
                        onClearSelection = onClearSelection,
                        onShare = {
                            shareLocalResources(
                                context,
                                state.scan.resources.filter { resource ->
                                    resource.id in state.selectedResourceIds
                                },
                            )
                            onClearSelection()
                        },
                        onDelete = { deleteConfirmationVisible = true },
                        onAddTag = { tagDialogVisible = true },
                        onToggleFavorites = {
                            state.selectedResourceIds.forEach(onToggleFavorite)
                            onClearSelection()
                        },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(chromeScrollConnection),
        ) {
            if (state.actionMessage?.isNotBlank() == true) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = 1080.dp)
                            .fillMaxWidth()
                            .align(Alignment.CenterHorizontally)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.actionMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onActionMessageDismissed) {
                            Text(language.text("关闭", "Dismiss"))
                        }
                    }
                }
            }
            if (state.scan.issues.isNotEmpty()) {
                LocalScanIssueSummary(state = state, language = language)
            }
            LocalWallpaperContent(
                state = state,
                language = language,
                context = context,
                selected = selected,
                onSelectResource = onSelectResource,
                onStartSelection = onStartSelection,
                onToggleSelection = onToggleSelection,
                onToggleFavorite = onToggleFavorite,
                onMarkImportRequested = onMarkImportRequested,
                onDeleteResource = { resource ->
                    onSelectResource(resource.id)
                    deleteConfirmationVisible = true
                },
                onReplaceTags = onReplaceTags,
                onAddTag = { tagDialogVisible = true },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (tagDialogVisible) {
        AlertDialog(
            onDismissRequest = {
                tagDialogVisible = false
                tagInput = ""
            },
            title = { Text(language.text("添加标签", "Add tag")) },
            text = {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it.take(40) },
                    singleLine = true,
                    label = { Text(language.text("标签名称", "Tag name")) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = tagInput.isNotBlank(),
                    onClick = {
                        onAddTag(tagInput.trim())
                        tagDialogVisible = false
                        tagInput = ""
                    },
                ) { Text(language.text("添加", "Add")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    tagDialogVisible = false
                    tagInput = ""
                }) { Text(language.text("取消", "Cancel")) }
            },
        )
    }
    if (tagManagerVisible) {
        AlertDialog(
            onDismissRequest = {
                tagManagerVisible = false
                editingTag = null
                editedTag = ""
            },
            title = { Text(language.text("管理标签", "Manage tags")) },
            text = {
                val currentEditingTag = editingTag
                if (currentEditingTag != null) {
                    OutlinedTextField(
                        value = editedTag,
                        onValueChange = { editedTag = it.take(40) },
                        singleLine = true,
                        label = { Text(language.text("新名称", "New name")) },
                    )
                } else if (state.allTags.isEmpty()) {
                    Text(language.text("暂无自定义标签", "No custom tags"))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(state.allTags, key = { tag -> tag }) { tag ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(tag, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    editingTag = tag
                                    editedTag = tag
                                }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = language.text("重命名标签", "Rename tag"),
                                    )
                                }
                                IconButton(onClick = { onDeleteTag(tag) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteSweep,
                                        contentDescription = language.text("删除标签", "Delete tag"),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val currentEditingTag = editingTag
                TextButton(
                    enabled = currentEditingTag == null || editedTag.isNotBlank(),
                    onClick = {
                        if (currentEditingTag != null) {
                            onRenameTag(currentEditingTag, editedTag)
                        }
                        tagManagerVisible = false
                        editingTag = null
                        editedTag = ""
                    },
                ) {
                    Text(
                        if (currentEditingTag == null) {
                            language.text("完成", "Done")
                        } else {
                            language.text("保存", "Save")
                        },
                    )
                }
            },
            dismissButton = if (editingTag != null) {
                {
                    TextButton(onClick = {
                        editingTag = null
                        editedTag = ""
                    }) {
                        Text(language.text("取消", "Cancel"))
                    }
                }
            } else {
                null
            },
        )
    }
    if (deleteConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { deleteConfirmationVisible = false },
            title = { Text(language.text("删除本地资源？", "Delete local resources?")) },
            text = {
                Text(
                    language.text(
                        "文件会从原目录删除，WallHub 不会移动或备份它们。此操作无法撤销。",
                        "The files will be deleted from their original directory. WallHub will not move or back them up. This cannot be undone.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteResources(selectedForDelete)
                    deleteConfirmationVisible = false
                }) { Text(language.text("删除", "Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmationVisible = false }) {
                    Text(language.text("取消", "Cancel"))
                }
            },
        )
    }
}

@Composable
private fun LocalSelectionBar(
    selectedCount: Int,
    language: AppLanguage,
    menuExpanded: Boolean,
    onMenuExpandedChanged: (Boolean) -> Unit,
    onClearSelection: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onAddTag: () -> Unit,
    onToggleFavorites: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClearSelection) {
                Icon(
                    imageVector = Icons.Outlined.Cancel,
                    contentDescription = language.text("退出选择", "Exit selection"),
                )
            }
            Text(
                language.text("已选择 $selectedCount 项", "$selectedCount selected"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Outlined.FileUpload,
                    contentDescription = language.text("分享所选", "Share selected"),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.DeleteSweep,
                    contentDescription = language.text("删除所选", "Delete selected"),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Box {
                IconButton(onClick = { onMenuExpandedChanged(true) }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = language.text("更多选择操作", "More selection actions"),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChanged(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text(language.text("添加标签", "Add tag")) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Outlined.Edit, contentDescription = null)
                        },
                        onClick = {
                            onMenuExpandedChanged(false)
                            onAddTag()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(language.text("切换收藏", "Toggle favorites")) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Outlined.FavoriteBorder, contentDescription = null)
                        },
                        onClick = {
                            onMenuExpandedChanged(false)
                            onToggleFavorites()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalWorkspaceHeader(
    state: LocalWallpaperUiState,
    language: AppLanguage,
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
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .widthIn(max = 1080.dp)
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AnimatedVisibility(
                    visible = !secondaryChromeCollapsed,
                    enter = expandVertically(
                        animationSpec = tween(LOCAL_HEADER_EXPAND_DURATION_MS),
                        expandFrom = Alignment.Top,
                    ) + fadeIn(tween(LOCAL_HEADER_EXPAND_DURATION_MS)),
                    exit = shrinkVertically(
                        animationSpec = tween(LOCAL_HEADER_COLLAPSE_DURATION_MS),
                        shrinkTowards = Alignment.Top,
                    ) + fadeOut(tween(LOCAL_HEADER_COLLAPSE_DURATION_MS)),
                    label = "LocalSecondaryTools",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LocalSearchField(
                            query = state.searchQuery,
                            language = language,
                            onQueryChanged = onSearchQueryChanged,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ViewModeButtons(
                                selected = state.viewMode,
                                language = language,
                                onSelected = onViewModeSelected,
                                modifier = Modifier.width(160.dp),
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = if (state.scan.isScanning) onCancelScan else onRefresh) {
                                Icon(
                                    imageVector = if (state.scan.isScanning) {
                                        Icons.Outlined.Cancel
                                    } else {
                                        Icons.Outlined.Refresh
                                    },
                                    contentDescription = if (state.scan.isScanning) {
                                        language.text("取消扫描", "Cancel scan")
                                    } else {
                                        language.text("刷新扫描", "Refresh scan")
                                    },
                                )
                            }
                            LocalWorkspaceMenu(
                                hasCustomSource = customSource != null,
                                language = language,
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
    language: AppLanguage,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.TextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(language.text("搜索本地壁纸", "Search local wallpapers")) },
        leadingIcon = { Icon(imageVector = Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChanged("") }) {
                    Icon(
                        imageVector = Icons.Outlined.Cancel,
                        contentDescription = language.text("清除搜索", "Clear search"),
                    )
                }
            }
        } else {
            null
        },
        shape = MaterialTheme.shapes.medium,
        colors = TextFieldDefaults.colors(
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
    language: AppLanguage,
    onChooseDirectory: () -> Unit,
    onResetDirectory: () -> Unit,
    onManageTags: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = language.text("更多本地操作", "More local actions"),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(language.text("选择扫描目录", "Choose scan directory")) },
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
                    text = { Text(language.text("移除自定义目录", "Remove custom directory")) },
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
                text = { Text(language.text("管理标签", "Manage tags")) },
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
private fun LocalScanIssueSummary(
    state: LocalWallpaperUiState,
    language: AppLanguage,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = state.scan.issues.joinToString(" · ") { issue -> issue.message },
            modifier = Modifier
                .widthIn(max = 1080.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ViewModeButtons(
    selected: LocalWallpaperViewMode,
    language: AppLanguage,
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
                contentDescription = mode.label(language),
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalWallpaperContent(
    state: LocalWallpaperUiState,
    language: AppLanguage,
    context: android.content.Context,
    selected: LocalWallpaperResource?,
    onSelectResource: (String?) -> Unit,
    onStartSelection: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onMarkImportRequested: (String) -> Unit,
    onDeleteResource: (LocalWallpaperResource) -> Unit,
    onReplaceTags: (String, Set<String>) -> Unit,
    onAddTag: (LocalWallpaperResource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = if (
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
            title = language.text("没有找到本地壁纸", "No local wallpapers found"),
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    AnimatedContent(
        targetState = state.viewMode,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
            (fadeIn(tween(LOCAL_CONTENT_ENTER_DURATION_MS)) +
                slideInHorizontally(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    initialOffsetX = { width -> direction * width / LOCAL_CONTENT_SLIDE_DIVISOR },
                )) togetherWith
                (fadeOut(tween(LOCAL_CONTENT_EXIT_DURATION_MS)) +
                    slideOutHorizontally(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        targetOffsetX = { width -> -direction * width / LOCAL_CONTENT_SLIDE_DIVISOR },
                    ))
        },
        contentAlignment = Alignment.TopStart,
        label = "LocalWallpaperContent",
    ) { displayedMode ->
        when (displayedMode) {
            LocalWallpaperViewMode.DETAIL -> {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    if (maxWidth >= LOCAL_DETAIL_SPLIT_BREAKPOINT) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            LocalWallpaperList(
                                resources = resources,
                                state = state,
                                language = language,
                                onSelectResource = onSelectResource,
                                onStartSelection = onStartSelection,
                                onToggleSelection = onToggleSelection,
                                modifier = Modifier.weight(0.32f).fillMaxHeight(),
                            )
                            VerticalDivider(
                                modifier = Modifier.fillMaxHeight().width(1.dp),
                                color = DividerDefaults.color,
                            )
                            LocalWallpaperDetail(
                                resource = selected,
                                language = language,
                                context = context,
                                onBack = { onSelectResource(null) },
                                onToggleFavorite = onToggleFavorite,
                                onMarkImportRequested = onMarkImportRequested,
                                onDeleteResource = onDeleteResource,
                                onReplaceTags = onReplaceTags,
                                onAddTag = onAddTag,
                                modifier = Modifier.weight(0.68f).fillMaxHeight(),
                            )
                        }
                    } else if (selected != null) {
                        LocalWallpaperDetail(
                            resource = selected,
                            language = language,
                            context = context,
                            onBack = { onSelectResource(null) },
                            onToggleFavorite = onToggleFavorite,
                            onMarkImportRequested = onMarkImportRequested,
                            onDeleteResource = onDeleteResource,
                            onReplaceTags = onReplaceTags,
                            onAddTag = onAddTag,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            LocalWallpaperViewMode.GRID -> {
                LocalWallpaperGrid(
                    resources = resources,
                    state = state,
                    language = language,
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
                    language = language,
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
private fun LocalWallpaperList(
    resources: List<LocalWallpaperResource>,
    state: LocalWallpaperUiState,
    language: AppLanguage,
    onSelectResource: (String?) -> Unit,
    onStartSelection: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = 80.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(resources, key = LocalWallpaperResource::id) { resource ->
            LocalWallpaperListItem(
                resource = resource,
                language = language,
                selected = resource.id == state.selectedResourceId,
                checked = resource.id in state.selectedResourceIds,
                selectionMode = state.selectionMode,
                onClick = {
                    if (state.selectionMode) onToggleSelection(resource.id) else onSelectResource(resource.id)
                },
                onLongClick = { onStartSelection(resource.id) },
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(LOCAL_ITEM_FADE_IN_DURATION_MS),
                    placementSpec = spring(
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
    language: AppLanguage,
    selected: Boolean,
    checked: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected || checked) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
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
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = resource.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(resource.format.label(language))
                        append(" · ${formatLocalSize(resource.sizeBytes)}")
                        append(" · ${resource.sourceLabel}")
                        if (resource.importState == LocalWallpaperImportState.IMPORT_REQUESTED) {
                            append(language.text(" · 已发起导入", " · Import requested"))
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                resource.isFavorite -> Icon(
                    imageVector = Icons.Outlined.FavoriteBorder,
                    contentDescription = language.text("已收藏", "Favorite"),
                    tint = MaterialTheme.colorScheme.primary,
                )

                resource.format == LocalWallpaperFormat.MPKG -> Icon(
                    imageVector = Icons.Outlined.PhoneAndroid,
                    contentDescription = language.text(
                        "可导入 Wallpaper Engine",
                        "Can import to Wallpaper Engine",
                    ),
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
    language: AppLanguage,
    onSelectResource: (String?) -> Unit,
    onStartSelection: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 190.dp),
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = 80.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(resources, key = LocalWallpaperResource::id) { resource ->
            Surface(
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = tween(LOCAL_ITEM_FADE_IN_DURATION_MS),
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        fadeOutSpec = tween(LOCAL_ITEM_FADE_OUT_DURATION_MS),
                    )
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
                shape = MaterialTheme.shapes.small,
                color = if (resource.id in state.selectedResourceIds) {
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
                            language = language,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        )
                        if (state.selectionMode) {
                            SelectionIndicator(
                                checked = resource.id in state.selectedResourceIds,
                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "${resource.format.label(language)} · ${formatLocalSize(resource.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (resource.isFavorite) {
                                Icon(
                                    imageVector = Icons.Outlined.FavoriteBorder,
                                    contentDescription = language.text("已收藏", "Favorite"),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
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
    language: AppLanguage,
    context: android.content.Context,
    onBack: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onMarkImportRequested: (String) -> Unit,
    onDeleteResource: (LocalWallpaperResource) -> Unit,
    onReplaceTags: (String, Set<String>) -> Unit,
    onAddTag: (LocalWallpaperResource) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (resource == null) {
        WallHubEmptyState(
            icon = Icons.Outlined.Info,
            title = language.text("选择资源查看详情", "Select a resource to view details"),
            modifier = modifier,
        )
        return
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = language.text("返回资源列表", "Back to resources"),
                )
            }
            Text(
                text = resource.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onToggleFavorite(resource.id) }) {
                Icon(
                    imageVector = Icons.Outlined.FavoriteBorder,
                    contentDescription = language.text("收藏", "Favorite"),
                    tint = if (resource.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LocalWallpaperPreview(
            resource = resource,
            modifier = Modifier.fillMaxWidth().aspectRatio(1.65f),
            contentScale = ContentScale.Fit,
        )
        ResourceActionRow(
            resource = resource,
            language = language,
            context = context,
            onMarkImportRequested = onMarkImportRequested,
            onDeleteResource = onDeleteResource,
        )
        HorizontalDivider(color = DividerDefaults.color)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(language.text("文件信息", "File information"), style = MaterialTheme.typography.titleMedium)
            DetailLine(language.text("格式", "Format"), resource.format.label(language))
            DetailLine(language.text("识别依据", "Detection"), resource.detectionReason)
            DetailLine(language.text("大小", "Size"), formatLocalSize(resource.sizeBytes))
            DetailLine(language.text("来源", "Source"), resource.sourceLabel)
            DetailLine(language.text("位置", "Location"), resource.relativePath)
            resource.workshopId?.let { id -> DetailLine("Workshop ID", id.toString()) }
            DetailLine(
                language.text("导入状态", "Import state"),
                resource.importState.label(language),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(language.text("标签", "Tags"), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onAddTag(resource) }) {
                Text(language.text("添加", "Add"))
            }
        }
        if (resource.tags.isEmpty()) {
            Text(
                language.text("暂无自定义标签", "No custom tags"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                contentDescription = language.text("移除标签", "Remove tag"),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResourceActionRow(
    resource: LocalWallpaperResource,
    language: AppLanguage,
    context: android.content.Context,
    onMarkImportRequested: (String) -> Unit,
    onDeleteResource: (LocalWallpaperResource) -> Unit,
) {
    val onPrimaryAction = {
        if (resource.format == LocalWallpaperFormat.MPKG) {
            launchWallpaperEngineImport(context, resource, language, onMarkImportRequested)
        } else {
            openLocalResource(context, resource)
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < LOCAL_DETAIL_ACTION_BREAKPOINT) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ResourcePrimaryActionButton(
                    resource = resource,
                    language = language,
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth(),
                )
                ResourceUtilityActions(
                    resource = resource,
                    language = language,
                    context = context,
                    onDeleteResource = onDeleteResource,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ResourcePrimaryActionButton(
                    resource = resource,
                    language = language,
                    onClick = onPrimaryAction,
                    modifier = Modifier.weight(1f),
                )
                ResourceUtilityActions(
                    resource = resource,
                    language = language,
                    context = context,
                    onDeleteResource = onDeleteResource,
                )
            }
        }
    }
}

@Composable
private fun ResourcePrimaryActionButton(
    resource: LocalWallpaperResource,
    language: AppLanguage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Icon(
            imageVector = if (resource.format == LocalWallpaperFormat.MPKG) {
                Icons.Outlined.PhoneAndroid
            } else {
                Icons.Outlined.OpenInNew
            },
            contentDescription = null,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            if (resource.format == LocalWallpaperFormat.MPKG) {
                language.text("导入 Wallpaper Engine", "Import to Wallpaper Engine")
            } else {
                language.text("打开文件", "Open file")
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ResourceUtilityActions(
    resource: LocalWallpaperResource,
    language: AppLanguage,
    context: android.content.Context,
    onDeleteResource: (LocalWallpaperResource) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { shareLocalResource(context, resource) }) {
                Icon(
                    imageVector = Icons.Outlined.FileUpload,
                    contentDescription = language.text("分享", "Share"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            VerticalDivider(
                modifier = Modifier.height(24.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            IconButton(onClick = { copyLocalResourceLocation(context, resource, language) }) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = language.text("复制位置", "Copy location"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            VerticalDivider(
                modifier = Modifier.height(24.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            IconButton(onClick = { onDeleteResource(resource) }) {
                Icon(
                    imageVector = Icons.Outlined.DeleteSweep,
                    contentDescription = language.text("删除", "Delete"),
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
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = resource.format.icon(),
            contentDescription = resource.format.label(LocalWallHubLanguage.current),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        resource.thumbnailUri?.let { thumbnailUri ->
            val context = LocalContext.current
            val imageRequest = remember(thumbnailUri) {
                ImageRequest.Builder(context)
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
    language: AppLanguage,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        Text(
            text = format.label(language),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SelectionIndicator(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(24.dp),
        shape = MaterialTheme.shapes.small,
        color = if (checked) MaterialTheme.colorScheme.primary else Color.Transparent,
        border = if (checked) null else androidx.compose.foundation.BorderStroke(
            2.dp,
            MaterialTheme.colorScheme.outline,
        ),
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
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
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

private fun openLocalResource(context: android.content.Context, resource: LocalWallpaperResource) {
    val uri = shareableUri(context, resource.contentUri) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, resource.mimeType ?: "application/octet-stream")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, resource.title)) }
}

private fun shareLocalResource(context: android.content.Context, resource: LocalWallpaperResource) {
    val uri = shareableUri(context, resource.contentUri) ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = resource.mimeType ?: "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, resource.title)) }
}

private fun shareLocalResources(
    context: android.content.Context,
    resources: List<LocalWallpaperResource>,
) {
    if (resources.isEmpty()) return
    val uris = resources.mapNotNull { resource -> shareableUri(context, resource.contentUri) }
    if (uris.isEmpty()) return
    val mimeTypes = resources.mapNotNull(LocalWallpaperResource::mimeType).distinct()
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = mimeTypes.singleOrNull() ?: "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}

private fun copyLocalResourceLocation(
    context: android.content.Context,
    resource: LocalWallpaperResource,
    language: AppLanguage,
) {
    val location = "${resource.sourceLabel}/${resource.relativePath}".replace("//", "/")
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(resource.title, location))
    Toast.makeText(
        context,
        language.text("已复制位置", "Location copied"),
        Toast.LENGTH_SHORT,
    ).show()
}

private fun launchWallpaperEngineImport(
    context: android.content.Context,
    resource: LocalWallpaperResource,
    language: AppLanguage,
    onMarkImportRequested: (String) -> Unit,
) {
    val uri = shareableUri(context, resource.contentUri) ?: return
    val component = ComponentName(
        WALLPAPER_ENGINE_PACKAGE,
        WALLPAPER_ENGINE_IMPORT_ACTIVITY,
    )
    val installed = runCatching {
        context.packageManager.getActivityInfo(component, 0)
    }.isSuccess
    if (!installed) {
        Toast.makeText(
            context,
            language.text(
                "未安装官方 Wallpaper Engine",
                "Official Wallpaper Engine is not installed",
            ),
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        this.component = component
        setDataAndType(uri, WALLPAPER_ENGINE_MPKG_MIME_TYPE)
        clipData = ClipData.newRawUri(resource.displayName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.grantUriPermission(
            WALLPAPER_ENGINE_PACKAGE,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        context.startActivity(intent)
    }.onSuccess {
        onMarkImportRequested(resource.id)
    }.onFailure { error ->
        Toast.makeText(
            context,
            error.message ?: language.text(
                "无法启动 Wallpaper Engine 导入",
                "Unable to start Wallpaper Engine import",
            ),
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun shareableUri(context: android.content.Context, rawUri: String): Uri? {
    val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return null
    if (uri.scheme != "file" || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return uri
    val file = uri.path?.let(::File) ?: return null
    return runCatching {
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file,
        )
    }.getOrNull()
}

private fun LocalWallpaperViewMode.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    LocalWallpaperViewMode.LIST -> Icons.Outlined.ViewList
    LocalWallpaperViewMode.GRID -> Icons.Outlined.GridView
    LocalWallpaperViewMode.DETAIL -> Icons.Outlined.Info
}

private fun LocalWallpaperViewMode.label(language: AppLanguage): String = when (this) {
    LocalWallpaperViewMode.LIST -> language.text("列表", "List")
    LocalWallpaperViewMode.GRID -> language.text("网格", "Grid")
    LocalWallpaperViewMode.DETAIL -> language.text("详情", "Detail")
}

private fun LocalWallpaperFormat.label(language: AppLanguage): String = when (this) {
    LocalWallpaperFormat.MPKG -> "MPKG"
    LocalWallpaperFormat.PKG -> "PKG"
    LocalWallpaperFormat.VIDEO -> language.text("视频", "Video")
    LocalWallpaperFormat.HTML -> "HTML"
    LocalWallpaperFormat.UNKNOWN -> language.text("未知", "Unknown")
}

private fun LocalWallpaperFormat.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    LocalWallpaperFormat.MPKG,
    LocalWallpaperFormat.PKG,
    -> Icons.Outlined.FolderOpen

    LocalWallpaperFormat.VIDEO -> Icons.Outlined.PlayArrow
    LocalWallpaperFormat.HTML -> Icons.Outlined.OpenInNew
    LocalWallpaperFormat.UNKNOWN -> Icons.Outlined.Info
}

private fun LocalWallpaperImportState.label(language: AppLanguage): String = when (this) {
    LocalWallpaperImportState.NOT_IMPORTED -> language.text("未导入", "Not imported")
    LocalWallpaperImportState.IMPORT_REQUESTED -> language.text("已发起导入", "Import requested")
}

private fun formatLocalSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> formatMegabytes(bytes)
}

private fun AppLanguage.text(zh: String, en: String): String = if (this == AppLanguage.EN) en else zh

private val LOCAL_DETAIL_ACTION_BREAKPOINT = 460.dp
private val LOCAL_HEADER_COLLAPSE_DISTANCE = 44.dp
private val LOCAL_HEADER_EXPAND_DISTANCE = 20.dp
private val LOCAL_DETAIL_SPLIT_BREAKPOINT = 840.dp
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
