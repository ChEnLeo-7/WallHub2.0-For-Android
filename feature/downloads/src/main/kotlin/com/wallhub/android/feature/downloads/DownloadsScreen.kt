package com.wallhub.android.feature.downloads

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.LocalWallHubLanguage
import com.wallhub.android.core.designsystem.WallHubIcons as Icons
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubSingleChoiceSegmentedControl
import com.wallhub.android.core.designsystem.WallHubSurfaceCard
import com.wallhub.android.core.designsystem.formatMegabytes
import com.wallhub.android.core.designsystem.wallHubText
import com.wallhub.android.core.designsystem.text
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.DownloadTaskRepository
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.requiresLegacyPublicDownloadPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DownloadFilter {
    ALL,
    COMPLETED,
    DOWNLOADING,
    QUEUED,
    FAILED,
}

enum class DownloadTypeFilter(
    val type: com.wallhub.android.core.model.WorkshopType?,
) {
    ALL(null),
    VIDEO(com.wallhub.android.core.model.WorkshopType.VIDEO),
    SCENE(com.wallhub.android.core.model.WorkshopType.SCENE),
    WEB(com.wallhub.android.core.model.WorkshopType.WEB),
}

data class DownloadsUiState(
    val filter: DownloadFilter = DownloadFilter.ALL,
    val typeFilter: DownloadTypeFilter = DownloadTypeFilter.ALL,
    val tasks: List<DownloadTask> = emptyList(),
    val actionMessage: String? = null,
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val taskRepository: DownloadTaskRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val stateSource = DownloadsStateSource(taskRepository)

    val uiState: StateFlow<DownloadsUiState> = stateSource.states.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DownloadsUiState(),
    )

    fun setFilter(filter: DownloadFilter) {
        stateSource.setFilter(filter)
    }

    fun setTypeFilter(filter: DownloadTypeFilter) {
        stateSource.setTypeFilter(filter)
    }

    fun requestAction(taskId: String, action: DownloadAction) {
        viewModelScope.launch { stateSource.requestAction(taskId, action) }
    }

    fun clearFinishedHistory() {
        viewModelScope.launch { taskRepository.clearFinishedHistory() }
    }

    fun reorderTasks(taskIds: List<String>) {
        viewModelScope.launch { taskRepository.reorder(taskIds) }
    }

    fun reportLegacyStoragePermissionDenied() {
        stateSource.reportActionMessage("未授予存储权限，无法导出到 Download/WallHub")
    }

    fun enqueueWorkshop(item: WorkshopSummary) {
        viewModelScope.launch {
            runCatching {
                val outputTreeUri = settingsRepository.preferences.first().outputTreeUri
                taskRepository.enqueue(
                    DownloadRequest(
                        workshopId = item.id,
                        title = item.title,
                        type = item.type,
                        previewUrl = item.previewUrl,
                        expectedTotalBytes = item.fileSizeBytes ?: 0L,
                        outputTreeUri = outputTreeUri,
                        exportFormat = ExportFormat.AUTO,
                    ),
                )
            }.onSuccess { task ->
                stateSource.reportActionMessage("已加入下载队列：${task.title}")
            }.onFailure { error ->
                stateSource.reportActionMessage(error.message ?: "无法加入下载队列")
            }
        }
    }
}

internal class DownloadsStateSource(
    private val taskRepository: DownloadTaskRepository,
) {
    private val selectedFilter = MutableStateFlow(DownloadFilter.ALL)
    private val selectedTypeFilter = MutableStateFlow(DownloadTypeFilter.ALL)
    private val actionMessage = MutableStateFlow<String?>(null)

    val states = combine(
        taskRepository.tasks,
        selectedFilter,
        selectedTypeFilter,
        actionMessage,
    ) { tasks, filter, typeFilter, message ->
        DownloadsUiState(
            filter = filter,
            typeFilter = typeFilter,
            tasks = filterTasks(tasks, filter, typeFilter),
            actionMessage = message,
        )
    }

    fun setFilter(filter: DownloadFilter) {
        selectedFilter.value = filter
    }

    fun setTypeFilter(filter: DownloadTypeFilter) {
        selectedTypeFilter.value = filter
    }

    fun reportActionMessage(message: String) {
        actionMessage.value = message
    }

    suspend fun requestAction(taskId: String, action: DownloadAction) {
        runCatching { taskRepository.requestAction(taskId, action) }
            .onSuccess {
                actionMessage.value = when (action) {
                    DownloadAction.EXPORT -> "已加入转换和导出任务"
                    else -> null
                }
            }
            .onFailure { error ->
                actionMessage.value = error.message ?: "操作失败，请稍后重试"
            }
    }
}

@Composable
fun DownloadsRoute(
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var pendingLegacyStorageAction by remember { mutableStateOf<Pair<String, DownloadAction>?>(null) }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pendingAction = pendingLegacyStorageAction
        pendingLegacyStorageAction = null
        if (granted && pendingAction != null) {
            viewModel.requestAction(pendingAction.first, pendingAction.second)
        } else if (!granted) {
            viewModel.reportLegacyStoragePermissionDenied()
        }
    }
    DownloadsScreen(
        state = state,
        onFilterSelected = viewModel::setFilter,
        onTypeFilterSelected = viewModel::setTypeFilter,
        onAction = { taskId, action ->
            val task = state.tasks.firstOrNull { it.id == taskId }
            val requiresPermission = action == DownloadAction.EXPORT ||
                (action == DownloadAction.RETRY && !task?.stagingDirectory.isNullOrBlank())
            if (requiresPermission && context.requiresLegacyPublicDownloadPermission()) {
                pendingLegacyStorageAction = taskId to action
                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                viewModel.requestAction(taskId, action)
            }
        },
        onReorder = viewModel::reorderTasks,
    )
}

@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    onFilterSelected: (DownloadFilter) -> Unit,
    onTypeFilterSelected: (DownloadTypeFilter) -> Unit,
    onAction: (String, DownloadAction) -> Unit,
    onReorder: (List<String>) -> Unit,
    onPlayVideo: (String) -> Unit = {},
) {
    WallHubPageScaffold(
        title = wallHubText("下载", "Downloads"),
    ) { padding ->
        DownloadsContent(
            state = state,
            onAction = onAction,
            showFilters = true,
            onFilterSelected = onFilterSelected,
            onTypeFilterSelected = onTypeFilterSelected,
            onReorder = onReorder,
            onPlayVideo = onPlayVideo,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
fun DownloadsContent(
    state: DownloadsUiState,
    onAction: (String, DownloadAction) -> Unit,
    showFilters: Boolean,
    onFilterSelected: (DownloadFilter) -> Unit = {},
    onTypeFilterSelected: (DownloadTypeFilter) -> Unit = {},
    onReorder: (List<String>) -> Unit = {},
    onPlayVideo: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val language = LocalWallHubLanguage.current
    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        if (showFilters) {
            WallHubSingleChoiceSegmentedControl(
                options = DownloadFilter.entries,
                selected = state.filter,
                onSelected = onFilterSelected,
                label = { filter -> Text(filter.label(language)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            WallHubSingleChoiceSegmentedControl(
                options = DownloadTypeFilter.entries,
                selected = state.typeFilter,
                onSelected = onTypeFilterSelected,
                label = { filter -> Text(filter.label(language)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 0.dp),
            )
        }
        state.actionMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (state.tasks.isEmpty()) {
            WallHubEmptyState(
                icon = Icons.Outlined.Download,
                title = wallHubText("暂无下载任务", "No download tasks"),
                modifier = Modifier.weight(1f),
            )
        } else {
            ReorderableDownloadList(
                tasks = state.tasks,
                language = language,
                onAction = onAction,
                onPlayVideo = onPlayVideo,
                onReorder = onReorder,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ReorderableDownloadList(
    tasks: List<DownloadTask>,
    language: AppLanguage,
    onAction: (String, DownloadAction) -> Unit,
    onPlayVideo: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val taskIds = tasks.map(DownloadTask::id)
    val tasksById = tasks.associateBy(DownloadTask::id)
    var orderedIds by remember { mutableStateOf(taskIds) }
    var draggedTaskId by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val itemExtentPx = with(LocalDensity.current) {
        (DOWNLOAD_CARD_HEIGHT + DOWNLOAD_CARD_SPACING).toPx()
    }
    LaunchedEffect(taskIds, draggedTaskId) {
        if (draggedTaskId == null) orderedIds = taskIds
    }
    val visibleIds = orderedIds.filter(tasksById::containsKey) + taskIds.filterNot(orderedIds::contains)

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = 80.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(DOWNLOAD_CARD_SPACING),
    ) {
        items(items = visibleIds, key = { taskId -> taskId }) { taskId ->
            val task = tasksById.getValue(taskId)
            val isDragging = draggedTaskId == taskId
            val canReorder = task.status in REORDERABLE_DOWNLOAD_STATUSES
            val dragModifier = Modifier
                .then(if (isDragging) Modifier else Modifier.animateItem())
                .zIndex(if (isDragging) 1f else 0f)
                .graphicsLayer {
                    if (isDragging) {
                        translationY = dragOffsetPx
                        scaleX = DOWNLOAD_DRAG_SCALE
                        scaleY = DOWNLOAD_DRAG_SCALE
                        shadowElevation = DOWNLOAD_DRAG_ELEVATION.toPx()
                    }
                }
                .pointerInput(taskId, canReorder) {
                    if (!canReorder) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            draggedTaskId = taskId
                            dragOffsetPx = 0f
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragCancel = {
                            draggedTaskId = null
                            dragOffsetPx = 0f
                        },
                        onDragEnd = {
                            draggedTaskId = null
                            dragOffsetPx = 0f
                            onReorder(orderedIds)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetPx += dragAmount.y
                            var currentIndex = orderedIds.indexOf(taskId)
                            while (dragOffsetPx > itemExtentPx / 2f && currentIndex < orderedIds.lastIndex) {
                                val next = currentIndex + 1
                                orderedIds = orderedIds.toMutableList().apply {
                                    this[currentIndex] = this[next]
                                    this[next] = taskId
                                }
                                dragOffsetPx -= itemExtentPx
                                currentIndex = next
                            }
                            while (dragOffsetPx < -itemExtentPx / 2f && currentIndex > 0) {
                                val previous = currentIndex - 1
                                orderedIds = orderedIds.toMutableList().apply {
                                    this[currentIndex] = this[previous]
                                    this[previous] = taskId
                                }
                                dragOffsetPx += itemExtentPx
                                currentIndex = previous
                            }
                            val itemInfo = listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { item -> item.key == taskId }
                            if (itemInfo != null) {
                                val translatedTop = itemInfo.offset + dragOffsetPx
                                val translatedBottom = translatedTop + itemInfo.size
                                val viewportStart = listState.layoutInfo.viewportStartOffset + DOWNLOAD_AUTO_SCROLL_EDGE_PX
                                val viewportEnd = listState.layoutInfo.viewportEndOffset - DOWNLOAD_AUTO_SCROLL_EDGE_PX
                                val scrollDelta = when {
                                    translatedTop < viewportStart -> -DOWNLOAD_AUTO_SCROLL_STEP_PX
                                    translatedBottom > viewportEnd -> DOWNLOAD_AUTO_SCROLL_STEP_PX
                                    else -> 0f
                                }
                                if (scrollDelta != 0f) {
                                    coroutineScope.launch { listState.scrollBy(scrollDelta) }
                                }
                            }
                        },
                    )
                }
            DownloadTaskCard(
                task = task,
                language = language,
                onAction = { action -> onAction(task.id, action) },
                onPlayVideo = { onPlayVideo(task.id) },
                modifier = dragModifier,
            )
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    language: AppLanguage,
    onAction: (DownloadAction) -> Unit,
    onPlayVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WallHubSurfaceCard(
        modifier = modifier
            .fillMaxWidth()
            .height(DOWNLOAD_CARD_HEIGHT),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (!task.previewUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = task.previewUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.ImageNotSupported,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = task.projectSizeLabel(language),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.weight(1f))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DOWNLOAD_ACTION_ROW_HEIGHT),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when {
                            task.status == DownloadStatus.DOWNLOADING && task.bytesPerSecond > 0L ->
                                "${formatMegabytes(task.bytesPerSecond)}/s"

                            task.status == DownloadStatus.DOWNLOADING ->
                                language.text("正在测速", "Measuring")

                            task.status == DownloadStatus.FAILED && !task.message.isNullOrBlank() ->
                                task.message.orEmpty()

                            else -> task.status.label(language)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (
                        task.type == com.wallhub.android.core.model.WorkshopType.VIDEO &&
                        task.status == DownloadStatus.COMPLETED &&
                        !task.stagingDirectory.isNullOrBlank()
                    ) {
                        DownloadIconButton(
                            icon = Icons.Outlined.PlayArrow,
                            label = language.text("播放视频", "Play video"),
                            onClick = onPlayVideo,
                        )
                    }
                    task.availableActions.forEach { action ->
                        DownloadIconButton(
                            icon = action.icon(),
                            label = action.label(language),
                            onClick = { onAction(action) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(DOWNLOAD_ICON_BUTTON_SIZE),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
        )
    }
}

internal fun filterTasks(
    tasks: List<DownloadTask>,
    filter: DownloadFilter,
    typeFilter: DownloadTypeFilter = DownloadTypeFilter.ALL,
): List<DownloadTask> = tasks.asSequence()
    .filter { task -> typeFilter.type == null || task.type == typeFilter.type }
    .filter { task ->
        when (filter) {
            DownloadFilter.ALL -> true
            DownloadFilter.DOWNLOADING -> task.status in setOf(
            DownloadStatus.QUEUED,
            DownloadStatus.RESOLVING,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.PAUSED,
            DownloadStatus.CONVERTING,
            DownloadStatus.EXPORTING,
            )

            DownloadFilter.QUEUED -> task.status == DownloadStatus.QUEUED
            DownloadFilter.COMPLETED -> task.status == DownloadStatus.COMPLETED
            DownloadFilter.FAILED -> task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELLED
        }
    }
    .toList()

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

private fun com.wallhub.android.core.model.WorkshopType.label(language: AppLanguage): String = when (this) {
    com.wallhub.android.core.model.WorkshopType.VIDEO -> language.text("视频", "Video")
    com.wallhub.android.core.model.WorkshopType.SCENE -> language.text("场景", "Scene")
    com.wallhub.android.core.model.WorkshopType.WEB -> language.text("网站", "Web")
    com.wallhub.android.core.model.WorkshopType.UNKNOWN -> language.text("壁纸", "Wallpaper")
}

private fun DownloadStatus.label(language: AppLanguage): String = when (this) {
    DownloadStatus.QUEUED -> language.text("等待中", "Queued")
    DownloadStatus.RESOLVING -> language.text("解析中", "Resolving")
    DownloadStatus.DOWNLOADING -> language.text("下载中", "Downloading")
    DownloadStatus.PAUSED -> language.text("已暂停", "Paused")
    DownloadStatus.CONVERTING -> language.text("转换中", "Converting")
    DownloadStatus.EXPORTING -> language.text("导出中", "Exporting")
    DownloadStatus.COMPLETED -> language.text("已完成", "Completed")
    DownloadStatus.FAILED -> language.text("失败", "Failed")
    DownloadStatus.CANCELLED -> language.text("已取消", "Cancelled")
}

private fun DownloadAction.label(language: AppLanguage): String = when (this) {
    DownloadAction.PAUSE -> language.text("暂停", "Pause")
    DownloadAction.RESUME -> language.text("继续", "Resume")
    DownloadAction.RETRY -> language.text("重试", "Retry")
    DownloadAction.EXPORT -> language.text("导出", "Export")
    DownloadAction.CANCEL -> language.text("取消", "Cancel")
    DownloadAction.DELETE -> language.text("删除", "Delete")
}

private fun DownloadAction.icon() = when (this) {
    DownloadAction.PAUSE -> Icons.Outlined.Pause
    DownloadAction.RESUME -> Icons.Outlined.PlayArrow
    DownloadAction.RETRY -> Icons.Outlined.Refresh
    DownloadAction.EXPORT -> Icons.Outlined.FileUpload
    DownloadAction.CANCEL -> Icons.Outlined.Cancel
    DownloadAction.DELETE -> Icons.Outlined.DeleteSweep
}

private fun DownloadTask.projectSizeLabel(language: AppLanguage): String = totalBytes
    .takeIf { it > 0L }
    ?.let(::formatMegabytes)
    ?: language.text("正在读取大小", "Reading size")

private val REORDERABLE_DOWNLOAD_STATUSES = setOf(
    DownloadStatus.QUEUED,
    DownloadStatus.RESOLVING,
    DownloadStatus.DOWNLOADING,
    DownloadStatus.PAUSED,
)
private val DOWNLOAD_CARD_HEIGHT = 132.dp
private val DOWNLOAD_CARD_SPACING = 8.dp
private val DOWNLOAD_ACTION_ROW_HEIGHT = 40.dp
private val DOWNLOAD_ICON_BUTTON_SIZE = 36.dp
private val DOWNLOAD_DRAG_ELEVATION = 8.dp
private const val DOWNLOAD_DRAG_SCALE = 1.015f
private const val DOWNLOAD_AUTO_SCROLL_EDGE_PX = 96
private const val DOWNLOAD_AUTO_SCROLL_STEP_PX = 24f
