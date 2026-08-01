@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.downloads

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.LocalWallHubToastState
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubShapeTokens
import com.wallhub.android.core.designsystem.WallHubSingleChoiceSegmentedControl
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubSurfaceCard
import com.wallhub.android.core.format.formatByteSize
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.designsystem.requiresLegacyPublicDownloadPermission
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.DownloadTaskRepository
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.WorkshopSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.uwuaosp.compose.settingslib.SettingsToolbarActionButton
import javax.inject.Inject
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

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
)

sealed interface DownloadsAction {
    data class SelectFilter(
        val filter: DownloadFilter,
    ) : DownloadsAction

    data class SelectTypeFilter(
        val filter: DownloadTypeFilter,
    ) : DownloadsAction

    data class RequestTaskAction(
        val taskId: String,
        val action: DownloadAction,
    ) : DownloadsAction

    data class ReorderTasks(
        val taskIds: List<String>,
    ) : DownloadsAction

    data object ClearFinishedHistory : DownloadsAction

    data class EnqueueWorkshop(
        val item: WorkshopSummary,
    ) : DownloadsAction

    data class PlayVideo(
        val taskId: String,
    ) : DownloadsAction

    data class LegacyStoragePermissionResult(
        val operation: DownloadsPendingOperation,
        val granted: Boolean,
    ) : DownloadsAction
}

sealed interface DownloadsPendingOperation {
    data class TaskAction(
        val taskId: String,
        val action: DownloadAction,
    ) : DownloadsPendingOperation

    data class EnqueueWorkshop(
        val item: WorkshopSummary,
    ) : DownloadsPendingOperation
}

sealed interface DownloadsEffect {
    data class ResolveLegacyStoragePermission(
        val operation: DownloadsPendingOperation,
    ) : DownloadsEffect

    data class ShowMessage(
        @StringRes val messageRes: Int,
        val formatArgs: List<Any> = emptyList(),
    ) : DownloadsEffect

    data class PlayVideo(
        val taskId: String,
    ) : DownloadsEffect
}

@HiltViewModel
class DownloadsViewModel
    @Inject
    constructor(
        @ApplicationContext private val applicationContext: Context,
        private val taskRepository: DownloadTaskRepository,
        private val settingsRepository: SettingsRepository,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) : ViewModel() {
        private val stateSource = DownloadsStateSource(taskRepository, savedStateHandle)
        private val effectChannel = Channel<DownloadsEffect>(capacity = Channel.BUFFERED)

        val uiState: StateFlow<DownloadsUiState> =
            stateSource.states.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DownloadsUiState(),
            )
        val effects: Flow<DownloadsEffect> = effectChannel.receiveAsFlow()

        fun onAction(action: DownloadsAction) {
            when (action) {
                is DownloadsAction.SelectFilter -> stateSource.setFilter(action.filter)
                is DownloadsAction.SelectTypeFilter -> stateSource.setTypeFilter(action.filter)
                is DownloadsAction.RequestTaskAction -> prepareTaskAction(action.taskId, action.action)
                is DownloadsAction.ReorderTasks ->
                    viewModelScope.launch {
                        taskRepository.reorder(action.taskIds)
                    }
                DownloadsAction.ClearFinishedHistory ->
                    viewModelScope.launch {
                        taskRepository.clearFinishedHistory()
                    }
                is DownloadsAction.EnqueueWorkshop ->
                    emitEffect(
                        DownloadsEffect.ResolveLegacyStoragePermission(
                            DownloadsPendingOperation.EnqueueWorkshop(action.item),
                        ),
                    )
                is DownloadsAction.PlayVideo -> emitEffect(DownloadsEffect.PlayVideo(action.taskId))
                is DownloadsAction.LegacyStoragePermissionResult -> {
                    if (action.granted) {
                        executePendingOperation(action.operation)
                    } else {
                        emitEffect(
                            DownloadsEffect.ShowMessage(
                                R.string.downloads_storage_permission_denied,
                            ),
                        )
                    }
                }
            }
        }

        private fun prepareTaskAction(
            taskId: String,
            action: DownloadAction,
        ) {
            viewModelScope.launch {
                val task = taskRepository.find(taskId)
                val requiresPermission =
                    action == DownloadAction.EXPORT ||
                        (action == DownloadAction.RETRY && !task?.stagingDirectory.isNullOrBlank())
                val operation = DownloadsPendingOperation.TaskAction(taskId, action)
                if (requiresPermission) {
                    effectChannel.send(DownloadsEffect.ResolveLegacyStoragePermission(operation))
                } else {
                    executeTaskAction(taskId, action)
                }
            }
        }

        private fun executePendingOperation(operation: DownloadsPendingOperation) {
            when (operation) {
                is DownloadsPendingOperation.TaskAction ->
                    executeTaskAction(
                        operation.taskId,
                        operation.action,
                    )
                is DownloadsPendingOperation.EnqueueWorkshop -> enqueueWorkshop(operation.item)
            }
        }

        private fun executeTaskAction(
            taskId: String,
            action: DownloadAction,
        ) {
            viewModelScope.launch {
                runCatching { taskRepository.requestAction(taskId, action) }
                    .onSuccess {
                        if (action == DownloadAction.EXPORT) {
                            effectChannel.send(
                                DownloadsEffect.ShowMessage(R.string.downloads_export_queued),
                            )
                        }
                    }.onFailure {
                        effectChannel.send(
                            DownloadsEffect.ShowMessage(R.string.downloads_action_failed),
                        )
                    }
            }
        }

        private fun enqueueWorkshop(item: WorkshopSummary) {
            viewModelScope.launch {
                runCatching {
                    val outputTreeUri = settingsRepository.preferences.first().outputTreeUri
                    taskRepository.enqueue(
                        DownloadRequest(
                            workshopId = item.id,
                            title = applicationContext.localizedTitle(item),
                            type = item.type,
                            previewUrl = item.previewUrl,
                            expectedTotalBytes = item.fileSizeBytes ?: 0L,
                            outputTreeUri = outputTreeUri,
                            exportFormat = ExportFormat.AUTO,
                        ),
                    )
                }.onSuccess { task ->
                    effectChannel.send(
                        DownloadsEffect.ShowMessage(
                            R.string.downloads_added_to_queue,
                            listOf(task.title),
                        ),
                    )
                }.onFailure {
                    effectChannel.send(
                        DownloadsEffect.ShowMessage(R.string.downloads_enqueue_failed),
                    )
                }
            }
        }

        private fun emitEffect(effect: DownloadsEffect) {
            effectChannel.trySend(effect)
        }
    }

internal class DownloadsStateSource(
    private val taskRepository: DownloadTaskRepository,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) {
    private val selectedFilter =
        MutableStateFlow(downloadEnumValueOrDefault(savedStateHandle[DOWNLOAD_FILTER_KEY], DownloadFilter.ALL))
    private val selectedTypeFilter =
        MutableStateFlow(
            downloadEnumValueOrDefault(savedStateHandle[DOWNLOAD_TYPE_FILTER_KEY], DownloadTypeFilter.ALL),
        )

    val states =
        combine(
            taskRepository.tasks,
            selectedFilter,
            selectedTypeFilter,
        ) { tasks, filter, typeFilter ->
            DownloadsUiState(
                filter = filter,
                typeFilter = typeFilter,
                tasks = filterTasks(tasks, filter, typeFilter),
            )
        }

    fun setFilter(filter: DownloadFilter) {
        selectedFilter.value = filter
        savedStateHandle[DOWNLOAD_FILTER_KEY] = filter.name
    }

    fun setTypeFilter(filter: DownloadTypeFilter) {
        selectedTypeFilter.value = filter
        savedStateHandle[DOWNLOAD_TYPE_FILTER_KEY] = filter.name
    }
}

private inline fun <reified T : Enum<T>> downloadEnumValueOrDefault(
    value: String?,
    default: T,
): T = value?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

private const val DOWNLOAD_FILTER_KEY = "downloads.filter"
private const val DOWNLOAD_TYPE_FILTER_KEY = "downloads.typeFilter"

@Composable
fun DownloadsRoute(
    onOpenSettings: () -> Unit = {},
    onPlayVideo: (String) -> Unit = {},
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DownloadsEffectHandler(
        viewModel = viewModel,
        onPlayVideo = onPlayVideo,
    )
    DownloadsScreen(
        state = state,
        onAction = viewModel::onAction,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun DownloadsEffectHandler(
    viewModel: DownloadsViewModel,
    onPlayVideo: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val toastState = LocalWallHubToastState.current
    val currentOnPlayVideo by rememberUpdatedState(onPlayVideo)
    var pendingOperation by remember { mutableStateOf<DownloadsPendingOperation?>(null) }
    val legacyStoragePermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            pendingOperation?.let { operation ->
                viewModel.onAction(
                    DownloadsAction.LegacyStoragePermissionResult(operation, granted),
                )
            }
            pendingOperation = null
        }
    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DownloadsEffect.ResolveLegacyStoragePermission -> {
                    if (context.requiresLegacyPublicDownloadPermission()) {
                        pendingOperation = effect.operation
                        legacyStoragePermissionLauncher.launch(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        )
                    } else {
                        viewModel.onAction(
                            DownloadsAction.LegacyStoragePermissionResult(
                                effect.operation,
                                granted = true,
                            ),
                        )
                    }
                }
                is DownloadsEffect.ShowMessage ->
                    toastState.show(context.getString(effect.messageRes, *effect.formatArgs.toTypedArray()))
                is DownloadsEffect.PlayVideo -> currentOnPlayVideo(effect.taskId)
            }
        }
    }
}

@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    onAction: (DownloadsAction) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    WallHubPageScaffold(
        title = stringResource(R.string.downloads_title),
        useUwuToolbar = true,
        actions = {
            SettingsToolbarActionButton(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.management_settings),
                onClick = onOpenSettings,
                buttonSize = 64.dp,
                containerSize = 48.dp,
            )
        },
    ) { padding ->
        DownloadsContent(
            state = state,
            onAction = onAction,
            showFilters = true,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
fun DownloadsContent(
    state: DownloadsUiState,
    onAction: (DownloadsAction) -> Unit,
    showFilters: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        if (showFilters) {
            WallHubSingleChoiceSegmentedControl(
                options = DownloadFilter.entries,
                selected = state.filter,
                onSelected = { filter -> onAction(DownloadsAction.SelectFilter(filter)) },
                label = { filter -> Text(filter.label()) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.xs),
            )
            WallHubSingleChoiceSegmentedControl(
                options = DownloadTypeFilter.entries,
                selected = state.typeFilter,
                onSelected = { filter -> onAction(DownloadsAction.SelectTypeFilter(filter)) },
                label = { filter -> Text(filter.label()) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.none),
            )
        }
        if (state.tasks.isEmpty()) {
            WallHubEmptyState(
                icon = Icons.Outlined.Download,
                title = stringResource(R.string.downloads_empty),
                modifier = Modifier.weight(1f),
            )
        } else {
            ReorderableDownloadList(
                tasks = state.tasks,
                onAction = { taskId, action ->
                    onAction(DownloadsAction.RequestTaskAction(taskId, action))
                },
                onPlayVideo = { taskId -> onAction(DownloadsAction.PlayVideo(taskId)) },
                onReorder = { taskIds -> onAction(DownloadsAction.ReorderTasks(taskIds)) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ReorderableDownloadList(
    tasks: List<DownloadTask>,
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
    val itemExtentPx =
        with(LocalDensity.current) {
            (DOWNLOAD_CARD_HEIGHT + DOWNLOAD_CARD_SPACING).toPx()
        }
    LaunchedEffect(taskIds, draggedTaskId) {
        if (draggedTaskId == null) orderedIds = taskIds
    }
    val visibleIds = orderedIds.filter(tasksById::containsKey) + taskIds.filterNot(orderedIds::contains)

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                start = WallHubSpacing.md,
                top = WallHubSpacing.xs,
                end = WallHubSpacing.md,
                bottom = WallHubSizeTokens.bottomNavigationClearance,
            ),
        verticalArrangement = Arrangement.spacedBy(DOWNLOAD_CARD_SPACING),
    ) {
        items(items = visibleIds, key = { taskId -> taskId }) { taskId ->
            val task = tasksById.getValue(taskId)
            val isDragging = draggedTaskId == taskId
            val canReorder = task.status in REORDERABLE_DOWNLOAD_STATUSES
            val dragModifier =
                Modifier
                    .then(if (isDragging) Modifier else Modifier.animateItem())
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        if (isDragging) {
                            translationY = dragOffsetPx
                            scaleX = DOWNLOAD_DRAG_SCALE
                            scaleY = DOWNLOAD_DRAG_SCALE
                            shadowElevation = DOWNLOAD_DRAG_ELEVATION.toPx()
                        }
                    }.pointerInput(taskId, canReorder) {
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
                                    orderedIds =
                                        orderedIds.toMutableList().apply {
                                            this[currentIndex] = this[next]
                                            this[next] = taskId
                                        }
                                    dragOffsetPx -= itemExtentPx
                                    currentIndex = next
                                }
                                while (dragOffsetPx < -itemExtentPx / 2f && currentIndex > 0) {
                                    val previous = currentIndex - 1
                                    orderedIds =
                                        orderedIds.toMutableList().apply {
                                            this[currentIndex] = this[previous]
                                            this[previous] = taskId
                                        }
                                    dragOffsetPx += itemExtentPx
                                    currentIndex = previous
                                }
                                val itemInfo =
                                    listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { item -> item.key == taskId }
                                if (itemInfo != null) {
                                    val translatedTop = itemInfo.offset + dragOffsetPx
                                    val translatedBottom = translatedTop + itemInfo.size
                                    val viewportStart = listState.layoutInfo.viewportStartOffset + DOWNLOAD_AUTO_SCROLL_EDGE_PX
                                    val viewportEnd = listState.layoutInfo.viewportEndOffset - DOWNLOAD_AUTO_SCROLL_EDGE_PX
                                    val scrollDelta =
                                        when {
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
    onAction: (DownloadAction) -> Unit,
    onPlayVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WallHubSurfaceCard(
        modifier =
            modifier
                .fillMaxWidth()
                .height(DOWNLOAD_CARD_HEIGHT),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = WallHubShapeTokens.small,
    ) {
        Row(
            modifier = Modifier.padding(WallHubSpacing.compact),
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .clip(WallHubShapeTokens.thumbnail)
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
                    text = task.projectSizeLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.weight(1f))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(WallHubSpacing.xxs),
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(DOWNLOAD_ACTION_ROW_HEIGHT),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text =
                            when {
                                task.status == DownloadStatus.DOWNLOADING && task.bytesPerSecond > 0L ->
                                    "${formatByteSize(task.bytesPerSecond)}/s"

                                task.status == DownloadStatus.DOWNLOADING ->
                                    stringResource(R.string.downloads_measuring)

                                else -> task.status.label()
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
                            label = stringResource(R.string.downloads_play_video),
                            onClick = onPlayVideo,
                        )
                    }
                    task.availableActions.forEach { action ->
                        DownloadIconButton(
                            icon = action.icon(),
                            label = action.label(),
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
            modifier = Modifier.size(WallHubSizeTokens.smallIcon),
        )
    }
}

internal fun filterTasks(
    tasks: List<DownloadTask>,
    filter: DownloadFilter,
    typeFilter: DownloadTypeFilter = DownloadTypeFilter.ALL,
): List<DownloadTask> =
    tasks
        .asSequence()
        .filter { task -> typeFilter.type == null || task.type == typeFilter.type }
        .filter { task ->
            when (filter) {
                DownloadFilter.ALL -> true
                DownloadFilter.DOWNLOADING ->
                    task.status in
                        setOf(
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
        }.toList()

@Composable
private fun DownloadFilter.label(): String =
    when (this) {
        DownloadFilter.ALL -> stringResource(R.string.downloads_filter_all)
        DownloadFilter.COMPLETED -> stringResource(R.string.downloads_filter_completed)
        DownloadFilter.DOWNLOADING -> stringResource(R.string.downloads_filter_active)
        DownloadFilter.QUEUED -> stringResource(R.string.downloads_filter_queued)
        DownloadFilter.FAILED -> stringResource(R.string.downloads_filter_failed)
    }

@Composable
private fun DownloadTypeFilter.label(): String =
    when (this) {
        DownloadTypeFilter.ALL -> stringResource(R.string.downloads_type_all)
        DownloadTypeFilter.VIDEO -> stringResource(R.string.downloads_type_video)
        DownloadTypeFilter.SCENE -> stringResource(R.string.downloads_type_scene)
        DownloadTypeFilter.WEB -> stringResource(R.string.downloads_type_web)
    }

@Composable
private fun DownloadStatus.label(): String =
    when (this) {
        DownloadStatus.QUEUED -> stringResource(R.string.downloads_status_queued)
        DownloadStatus.RESOLVING -> stringResource(R.string.downloads_status_resolving)
        DownloadStatus.DOWNLOADING -> stringResource(R.string.downloads_status_downloading)
        DownloadStatus.PAUSED -> stringResource(R.string.downloads_status_paused)
        DownloadStatus.CONVERTING -> stringResource(R.string.downloads_status_converting)
        DownloadStatus.EXPORTING -> stringResource(R.string.downloads_status_exporting)
        DownloadStatus.COMPLETED -> stringResource(R.string.downloads_status_completed)
        DownloadStatus.FAILED -> stringResource(R.string.downloads_status_failed)
        DownloadStatus.CANCELLED -> stringResource(R.string.downloads_status_cancelled)
    }

@Composable
private fun DownloadAction.label(): String =
    when (this) {
        DownloadAction.PAUSE -> stringResource(R.string.downloads_action_pause)
        DownloadAction.RESUME -> stringResource(R.string.downloads_action_resume)
        DownloadAction.RETRY -> stringResource(R.string.downloads_action_retry)
        DownloadAction.EXPORT -> stringResource(R.string.downloads_action_export)
        DownloadAction.CANCEL -> stringResource(R.string.downloads_action_cancel)
        DownloadAction.DELETE -> stringResource(R.string.downloads_action_delete)
    }

private fun DownloadAction.icon() =
    when (this) {
        DownloadAction.PAUSE -> Icons.Outlined.Pause
        DownloadAction.RESUME -> Icons.Outlined.PlayArrow
        DownloadAction.RETRY -> Icons.Outlined.Refresh
        DownloadAction.EXPORT -> Icons.Outlined.FileUpload
        DownloadAction.CANCEL -> Icons.Outlined.Cancel
        DownloadAction.DELETE -> Icons.Outlined.DeleteSweep
    }

@Composable
private fun DownloadTask.projectSizeLabel(): String =
    totalBytes
        .takeIf { it > 0L }
        ?.let(::formatByteSize)
        ?: stringResource(R.string.downloads_reading_size)

private val REORDERABLE_DOWNLOAD_STATUSES =
    setOf(
        DownloadStatus.QUEUED,
        DownloadStatus.RESOLVING,
        DownloadStatus.DOWNLOADING,
        DownloadStatus.PAUSED,
    )
private val DOWNLOAD_CARD_HEIGHT = 132.dp
private val DOWNLOAD_CARD_SPACING = WallHubSpacing.xs
private val DOWNLOAD_ACTION_ROW_HEIGHT = WallHubSizeTokens.compactActionHeight
private val DOWNLOAD_ICON_BUTTON_SIZE = WallHubSizeTokens.compactIconButton
private val DOWNLOAD_DRAG_ELEVATION = WallHubSpacing.xs
private const val DOWNLOAD_DRAG_SCALE = 1.015f
private const val DOWNLOAD_AUTO_SCROLL_EDGE_PX = 96
private const val DOWNLOAD_AUTO_SCROLL_STEP_PX = 24f
