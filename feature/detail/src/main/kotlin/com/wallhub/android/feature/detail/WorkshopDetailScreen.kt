package com.wallhub.android.feature.detail

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubFabActiveElevation
import com.wallhub.android.core.designsystem.WallHubFabDefaultElevation
import com.wallhub.android.core.designsystem.LocalWallHubLanguage
import com.wallhub.android.core.designsystem.WallHubIcons as Icons
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubPrimaryAction
import com.wallhub.android.core.designsystem.WallHubSecondaryButton
import com.wallhub.android.core.designsystem.WallHubSurfaceCard
import com.wallhub.android.core.designsystem.WallHubToastHost
import com.wallhub.android.core.designsystem.formatMegabytes
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTaskRepository
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.WORKSHOP_COMMENT_MAX_LENGTH
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.core.model.WorkshopVideoStreamRepository
import com.wallhub.android.core.model.WorkshopVideoStreamSession
import com.wallhub.android.core.model.requiresLegacyPublicDownloadPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

data class WorkshopDetailUiState(
    val detail: WorkshopDetail? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val interaction: WorkshopInteraction = WorkshopInteraction(),
    val isLoadingInteraction: Boolean = false,
    val isUpdatingInteraction: Boolean = false,
    val interactionMessage: String? = null,
    val outputTreeUri: String? = null,
    val outputDirectoryLabel: String? = null,
    val exportFormat: ExportFormat = ExportFormat.AUTO,
    val onlineChunkPlaybackEnabled: Boolean = false,
    val isEnqueuingDownload: Boolean = false,
    val downloadMessage: String? = null,
    val localVideoTaskId: String? = null,
    val activeVideoTaskId: String? = null,
    val waitingForLocalVideoPlayback: Boolean = false,
    val pendingLocalVideoPlaybackTaskId: String? = null,
    val stagedTaskId: String? = null,
    val comments: List<WorkshopComment> = emptyList(),
    val commentsTotal: Int? = null,
    val commentsNextStart: Int = 0,
    val commentsHasMore: Boolean = false,
    val commentsOwnerId: String? = null,
    val isLoadingComments: Boolean = false,
    val isLoadingMoreComments: Boolean = false,
    val commentsError: String? = null,
    val steamSession: SteamSessionState = SteamSessionState(),
    val commentDraft: String = "",
    val isPostingComment: Boolean = false,
    val commentPostError: String? = null,
    val inlineVideoStream: WorkshopVideoStreamSession? = null,
    val inlineVideoPlayer: ExoPlayer? = null,
    val isInlineVideoFullscreen: Boolean = false,
    val isLoadingInlineVideo: Boolean = false,
    val inlineVideoError: String? = null,
)

@HiltViewModel
class WorkshopDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val applicationContext: Context,
    private val workshopRepository: WorkshopRepository,
    private val accountWorkshopRepository: AccountWorkshopRepository,
    private val steamSessionRepository: SteamSessionRepository,
    private val downloadTaskRepository: DownloadTaskRepository,
    private val settingsRepository: SettingsRepository,
    private val videoStreamRepository: WorkshopVideoStreamRepository,
) : ViewModel() {
    private val workshopId = checkNotNull(savedStateHandle.get<Long>("workshopId"))
    private val mutableState = MutableStateFlow(WorkshopDetailUiState())
    private var inlineVideoLoadJob: Job? = null
    private var commentsLoadJob: Job? = null

    val uiState: StateFlow<WorkshopDetailUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            steamSessionRepository.session.collect { session ->
                mutableState.value = mutableState.value.copy(
                    steamSession = session,
                    isPostingComment = if (session.phase == SteamSessionPhase.SIGNED_IN) {
                        mutableState.value.isPostingComment
                    } else {
                        false
                    },
                )
            }
        }
        viewModelScope.launch {
            settingsRepository.preferences.collect { preferences ->
                mutableState.value = mutableState.value.copy(
                    outputTreeUri = preferences.outputTreeUri,
                    outputDirectoryLabel = preferences.outputDirectoryLabel,
                    onlineChunkPlaybackEnabled = preferences.onlineChunkPlaybackEnabled,
                )
            }
        }
        viewModelScope.launch {
            downloadTaskRepository.tasks.collect { tasks ->
                val localVideoTask = tasks.firstOrNull { task ->
                    task.workshopId == workshopId &&
                        task.type == WorkshopType.VIDEO &&
                        task.status == DownloadStatus.COMPLETED &&
                        !task.stagingDirectory.isNullOrBlank()
                }
                val stagedTask = tasks.firstOrNull { task ->
                    task.workshopId == workshopId &&
                        task.status == DownloadStatus.COMPLETED &&
                        !task.stagingDirectory.isNullOrBlank() &&
                        task.outputUri == null
                }
                val activeVideoTask = tasks.firstOrNull { task ->
                    task.workshopId == workshopId &&
                        task.type == WorkshopType.VIDEO &&
                        task.status !in setOf(
                            DownloadStatus.COMPLETED,
                            DownloadStatus.FAILED,
                            DownloadStatus.CANCELLED,
                        )
                }
                val current = mutableState.value
                val shouldOpenLocalVideo =
                    current.waitingForLocalVideoPlayback && localVideoTask != null
                mutableState.value = current.copy(
                    localVideoTaskId = localVideoTask?.id,
                    activeVideoTaskId = activeVideoTask?.id,
                    stagedTaskId = stagedTask?.id,
                    waitingForLocalVideoPlayback = if (shouldOpenLocalVideo) false else {
                        current.waitingForLocalVideoPlayback
                    },
                    pendingLocalVideoPlaybackTaskId = if (shouldOpenLocalVideo) {
                        localVideoTask.id
                    } else {
                        current.pendingLocalVideoPlaybackTaskId
                    },
                )
            }
        }
        reload()
    }

    fun reload() {
        inlineVideoLoadJob?.cancel()
        inlineVideoLoadJob = null
        viewModelScope.launch {
            mutableState.value.inlineVideoPlayer?.release()
            mutableState.value.inlineVideoStream?.close()
            mutableState.value = mutableState.value.copy(
                isLoading = true,
                error = null,
                comments = emptyList(),
                commentsTotal = null,
                commentsNextStart = 0,
                commentsHasMore = false,
                commentsOwnerId = null,
                commentsError = null,
                inlineVideoStream = null,
                inlineVideoPlayer = null,
                isInlineVideoFullscreen = false,
                isLoadingInlineVideo = false,
                inlineVideoError = null,
            )
            runCatching { workshopRepository.getDetail(workshopId) }
                .onSuccess { detail ->
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        detail = detail,
                        isLoading = false,
                        error = null,
                        exportFormat = detail.summary.type.defaultExportFormat(),
                    )
                    refreshInteraction()
                    loadComments(refresh = true)
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        isLoading = false,
                        error = error.message ?: "无法读取该创意工坊项目",
                    )
                }
        }
    }

    fun startInlineVideoPlayback() {
        val current = mutableState.value
        if (
            current.detail?.summary?.type != WorkshopType.VIDEO ||
            current.inlineVideoStream != null ||
            current.isLoadingInlineVideo
        ) {
            return
        }
        inlineVideoLoadJob = viewModelScope.launch {
            var openedStream: WorkshopVideoStreamSession? = null
            var openedPlayer: ExoPlayer? = null
            mutableState.value = mutableState.value.copy(
                isLoadingInlineVideo = true,
                inlineVideoError = null,
            )
            try {
                openedStream = videoStreamRepository.open(workshopId)
                coroutineContext.ensureActive()
                openedPlayer = createSteamChunkPlayer(
                    context = applicationContext,
                    stream = openedStream,
                )
                mutableState.value = mutableState.value.copy(
                    inlineVideoStream = openedStream,
                    inlineVideoPlayer = openedPlayer,
                    isInlineVideoFullscreen = false,
                    isLoadingInlineVideo = false,
                    inlineVideoError = null,
                )
                openedStream = null
                openedPlayer = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                openedPlayer?.release()
                openedPlayer = null
                mutableState.value = mutableState.value.copy(
                    inlineVideoStream = null,
                    inlineVideoPlayer = null,
                    isInlineVideoFullscreen = false,
                    isLoadingInlineVideo = false,
                    inlineVideoError = error.message ?: "无法初始化视频在线播放",
                )
            } finally {
                openedStream?.close()
            }
        }
    }

    fun stopInlineVideoPlayback() {
        inlineVideoLoadJob?.cancel()
        inlineVideoLoadJob = null
        val current = mutableState.value
        current.inlineVideoPlayer?.release()
        current.inlineVideoStream?.close()
        mutableState.value = current.copy(
            inlineVideoStream = null,
            inlineVideoPlayer = null,
            isInlineVideoFullscreen = false,
            isLoadingInlineVideo = false,
            inlineVideoError = null,
        )
    }

    fun setInlineVideoFullscreen(fullscreen: Boolean) {
        val current = mutableState.value
        if (current.isInlineVideoFullscreen == fullscreen) return
        if (fullscreen && (current.inlineVideoStream == null || current.inlineVideoPlayer == null)) return
        mutableState.value = current.copy(isInlineVideoFullscreen = fullscreen)
    }

    fun retryComments() {
        loadComments(refresh = true)
    }

    fun loadMoreComments() {
        loadComments(refresh = false)
    }

    fun updateCommentDraft(value: String) {
        mutableState.value = mutableState.value.copy(
            commentDraft = value.take(WORKSHOP_COMMENT_MAX_LENGTH),
            commentPostError = null,
        )
    }

    fun submitComment() {
        val current = mutableState.value
        if (current.steamSession.phase != SteamSessionPhase.SIGNED_IN || current.isPostingComment) return
        val detail = current.detail ?: return
        val ownerId = current.commentsOwnerId ?: detail.creatorId ?: detail.summary.creatorId
        if (ownerId.isNullOrBlank()) {
            mutableState.value = current.copy(commentPostError = "无法确定该项目的作者")
            return
        }
        val comment = current.commentDraft.trim()
        if (comment.isEmpty()) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                isPostingComment = true,
                commentPostError = null,
            )
            runCatching {
                accountWorkshopRepository.postComment(workshopId, ownerId, comment)
            }.onSuccess {
                mutableState.value = mutableState.value.copy(
                    commentDraft = "",
                    isPostingComment = false,
                    commentPostError = null,
                )
                loadComments(refresh = true)
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    isPostingComment = false,
                    commentPostError = error.message ?: "无法发表评论，请稍后重试",
                )
            }
        }
    }

    private fun loadComments(refresh: Boolean) {
        val current = mutableState.value
        val detail = current.detail ?: return
        if (refresh) {
            if (current.isLoadingComments) return
            commentsLoadJob?.cancel()
        } else if (
            current.isLoadingComments || current.isLoadingMoreComments || !current.commentsHasMore
        ) {
            return
        }
        val start = if (refresh) 0 else current.commentsNextStart
        commentsLoadJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                isLoadingComments = refresh,
                isLoadingMoreComments = !refresh,
                commentsError = null,
            )
            runCatching {
                workshopRepository.getComments(
                    workshopId = workshopId,
                    start = start,
                    count = COMMENTS_PAGE_SIZE,
                    ownerId = current.commentsOwnerId ?: detail.creatorId,
                )
            }.onSuccess { page ->
                val latest = mutableState.value
                val comments = if (refresh) {
                    page.comments
                } else {
                    (latest.comments + page.comments).distinctBy { comment ->
                        listOf(comment.author, comment.timestamp, comment.text).joinToString("\u001f")
                    }
                }
                mutableState.value = latest.copy(
                    comments = comments,
                    commentsTotal = page.total,
                    commentsNextStart = page.nextStart,
                    commentsHasMore = page.hasMore,
                    commentsOwnerId = page.ownerId ?: latest.commentsOwnerId ?: detail.creatorId,
                    isLoadingComments = false,
                    isLoadingMoreComments = false,
                    commentsError = null,
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    isLoadingComments = false,
                    isLoadingMoreComments = false,
                    commentsError = error.message ?: "无法读取 Steam 评论",
                )
            }
        }
    }

    fun toggleSubscription() {
        val currentlySubscribed = mutableState.value.interaction.subscriptionState ==
            SubscriptionState.SUBSCRIBED
        updateInteraction(
            successMessage = if (currentlySubscribed) {
                "已向 Steam 提交取消订阅请求"
            } else {
                "已向 Steam 提交订阅请求"
            },
        ) {
            accountWorkshopRepository.setSubscribed(workshopId, !currentlySubscribed)
        }
    }

    fun toggleFavorite() {
        val currentlyFavorited = mutableState.value.interaction.favoriteState == FavoriteState.FAVORITED
        updateInteraction(
            successMessage = if (currentlyFavorited) {
                "已向 Steam 提交取消收藏请求"
            } else {
                "已向 Steam 提交收藏请求"
            },
        ) {
            accountWorkshopRepository.setFavorited(workshopId, !currentlyFavorited)
        }
    }

    fun enqueueDownload(playWhenReady: Boolean = false) {
        val detail = mutableState.value.detail ?: return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                isEnqueuingDownload = true,
                downloadMessage = null,
            )
            runCatching {
                downloadTaskRepository.enqueue(
                    DownloadRequest(
                        workshopId = detail.summary.id,
                        title = detail.summary.title,
                        type = detail.summary.type,
                        previewUrl = detail.summary.previewUrl,
                        expectedTotalBytes = detail.summary.fileSizeBytes ?: 0L,
                        outputTreeUri = mutableState.value.outputTreeUri,
                        exportFormat = mutableState.value.exportFormat,
                    ),
                )
            }.onSuccess { task ->
                mutableState.value = mutableState.value.copy(
                    isEnqueuingDownload = false,
                    waitingForLocalVideoPlayback = playWhenReady,
                    downloadMessage = if (playWhenReady) {
                        "视频已加入下载队列，完成后将自动播放"
                    } else {
                        "已加入下载队列：${task.title}"
                    },
                )
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    isEnqueuingDownload = false,
                    downloadMessage = error.message ?: "无法加入下载队列",
                )
            }
        }
    }

    fun requestLocalVideoPlayback() {
        val current = mutableState.value
        val localVideoTaskId = current.localVideoTaskId
        when {
            localVideoTaskId != null -> {
                mutableState.value = current.copy(
                    pendingLocalVideoPlaybackTaskId = localVideoTaskId,
                )
            }

            current.activeVideoTaskId != null -> {
                mutableState.value = current.copy(
                    waitingForLocalVideoPlayback = true,
                    downloadMessage = "视频正在下载，完成后将自动播放",
                )
            }

            else -> enqueueDownload(playWhenReady = true)
        }
    }

    fun consumePendingLocalVideoPlayback(taskId: String) {
        if (mutableState.value.pendingLocalVideoPlaybackTaskId == taskId) {
            mutableState.value = mutableState.value.copy(pendingLocalVideoPlaybackTaskId = null)
        }
    }

    fun reportLegacyStoragePermissionDenied() {
        mutableState.value = mutableState.value.copy(
            downloadMessage = "未授予存储权限，无法导出到 Download/WallHub",
        )
    }

    fun selectExportFormat(format: ExportFormat) {
        val detail = mutableState.value.detail ?: return
        if (format !in detail.summary.type.availableExportFormats()) return
        mutableState.value = mutableState.value.copy(exportFormat = format)
    }

    fun convertExistingDownload(format: ExportFormat) {
        val taskId = mutableState.value.stagedTaskId ?: return
        viewModelScope.launch {
            runCatching {
                val task = downloadTaskRepository.find(taskId) ?: error("下载任务不存在")
                downloadTaskRepository.upsert(task.copy(exportFormat = format))
                downloadTaskRepository.requestAction(taskId, DownloadAction.EXPORT)
            }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(
                        downloadMessage = "已开始转换并导出已有暂存文件",
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        downloadMessage = error.message ?: "无法转换已有暂存文件",
                    )
                }
        }
    }

    private fun refreshInteraction() {
        viewModelScope.launch {
            val current = mutableState.value
            if (current.detail == null) return@launch
            mutableState.value = current.copy(isLoadingInteraction = true, interactionMessage = null)
            runCatching { accountWorkshopRepository.getInteraction(workshopId) }
                .onSuccess { interaction ->
                    mutableState.value = mutableState.value.copy(
                        interaction = interaction,
                        isLoadingInteraction = false,
                    )
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(isLoadingInteraction = false)
                }
        }
    }

    private fun updateInteraction(
        successMessage: String,
        operation: suspend () -> WorkshopInteraction,
    ) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                isUpdatingInteraction = true,
                interactionMessage = null,
            )
            runCatching { operation() }
                .onSuccess { interaction ->
                    mutableState.value = mutableState.value.copy(
                        interaction = interaction,
                        isUpdatingInteraction = false,
                        interactionMessage = successMessage,
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        isUpdatingInteraction = false,
                        interactionMessage = error.message ?: "Steam 请求失败，请稍后重试",
                    )
                }
        }
    }

    override fun onCleared() {
        stopInlineVideoPlayback()
        super.onCleared()
    }

    private companion object {
        const val COMMENTS_PAGE_SIZE = 20
    }
}

@Composable
fun WorkshopDetailRoute(
    onBack: () -> Unit,
    onSearchAuthor: (String) -> Unit = {},
    onOpenLocalVideo: (String) -> Unit = {},
    onOpenOnlineVideo: (Long) -> Unit = {},
    viewModel: WorkshopDetailViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isLeavingDetail by remember { mutableStateOf(false) }
    val leaveDetail: (() -> Unit) -> Unit = { navigate ->
        if (!isLeavingDetail) {
            isLeavingDetail = true
            viewModel.stopInlineVideoPlayback()
            coroutineScope.launch {
                withFrameNanos { }
                navigate()
            }
        }
    }
    BackHandler { leaveDetail(onBack) }
    var pendingLegacyStorageDownload by remember { mutableStateOf(false) }
    var pendingLegacyStorageLocalPlayback by remember { mutableStateOf(false) }
    var pendingLegacyStorageConversion by remember { mutableStateOf<ExportFormat?>(null) }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (pendingLegacyStorageDownload) {
                viewModel.enqueueDownload()
            }
            if (pendingLegacyStorageLocalPlayback) {
                viewModel.requestLocalVideoPlayback()
            }
            pendingLegacyStorageConversion?.let(viewModel::convertExistingDownload)
        } else if (!granted) {
            viewModel.reportLegacyStoragePermissionDenied()
        }
        pendingLegacyStorageDownload = false
        pendingLegacyStorageLocalPlayback = false
        pendingLegacyStorageConversion = null
    }
    LaunchedEffect(state.pendingLocalVideoPlaybackTaskId) {
        val taskId = state.pendingLocalVideoPlaybackTaskId ?: return@LaunchedEffect
        viewModel.consumePendingLocalVideoPlayback(taskId)
        viewModel.stopInlineVideoPlayback()
        onOpenLocalVideo(taskId)
    }
    WorkshopDetailScreen(
        state = if (isLeavingDetail) {
            state.copy(
                inlineVideoStream = null,
                inlineVideoPlayer = null,
                isInlineVideoFullscreen = false,
                isLoadingInlineVideo = false,
                inlineVideoError = null,
            )
        } else {
            state
        },
        onBack = { leaveDetail(onBack) },
        onRetry = viewModel::reload,
        onToggleSubscription = viewModel::toggleSubscription,
        onToggleFavorite = viewModel::toggleFavorite,
        onStartInlineVideo = viewModel::startInlineVideoPlayback,
        onExportFormatSelected = viewModel::selectExportFormat,
        onDownload = {
            if (context.requiresLegacyPublicDownloadPermission()) {
                pendingLegacyStorageDownload = true
                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                viewModel.enqueueDownload()
            }
        },
        onConvertExisting = { format ->
            if (context.requiresLegacyPublicDownloadPermission()) {
                pendingLegacyStorageConversion = format
                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                viewModel.convertExistingDownload(format)
            }
        },
        onRetryComments = viewModel::retryComments,
        onLoadMoreComments = viewModel::loadMoreComments,
        onCommentDraftChanged = viewModel::updateCommentDraft,
        onSubmitComment = viewModel::submitComment,
        onInlineFullscreenChange = viewModel::setInlineVideoFullscreen,
        onSearchAuthor = { author ->
            viewModel.stopInlineVideoPlayback()
            onSearchAuthor(author)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkshopDetailScreen(
    state: WorkshopDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleSubscription: () -> Unit,
    onToggleFavorite: () -> Unit,
    onStartInlineVideo: () -> Unit,
    onExportFormatSelected: (ExportFormat) -> Unit,
    onDownload: () -> Unit,
    onConvertExisting: (ExportFormat) -> Unit,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onInlineFullscreenChange: (Boolean) -> Unit,
    onSearchAuthor: (String) -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val language = LocalWallHubLanguage.current
    val selectedSummary = state.detail?.summary
    var toastMessage by remember { mutableStateOf<String?>(null) }
    val inlineVideoStream = state.inlineVideoStream
    val inlineFullscreen = state.isInlineVideoFullscreen
    var cdnToastDelivered by remember(inlineVideoStream) { mutableStateOf(false) }
    val inlinePlayback = inlineVideoStream?.let { stream ->
        state.inlineVideoPlayer?.let { player ->
            rememberRetainedSteamChunkPlayback(
                stream = stream,
                player = player,
                onFirstFrameRendered = {
                    if (!cdnToastDelivered) {
                        cdnToastDelivered = true
                        toastMessage = "Steam CDN: ${stream.currentCdnHost ?: "Unknown"}"
                    }
                },
            )
        }
    }
    BackHandler(enabled = inlineFullscreen) { onInlineFullscreenChange(false) }
    FullscreenSystemBarsEffect(enabled = inlineFullscreen)
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(3_000L)
            toastMessage = null
        }
    }
    WallHubToastHost(
        message = toastMessage,
        onDismiss = { toastMessage = null },
        modifier = Modifier.fillMaxSize(),
    ) {
        if (inlineFullscreen && inlinePlayback != null) {
            FullscreenWallpaperVideoPlayer(
                playback = inlinePlayback,
                onFullscreenChange = onInlineFullscreenChange,
            )
        } else {
            WallHubPageScaffold(
            title = selectedSummary?.title ?: language.text("壁纸详情", "Wallpaper details"),
            topBarContent = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = language.text("返回", "Back"),
                            )
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = selectedSummary?.title
                                    ?: language.text("壁纸详情", "Wallpaper details"),
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            selectedSummary?.let { summary ->
                                IconButton(
                                    onClick = {
                                        toastMessage = language.text("已复制壁纸标题", "Wallpaper title copied")
                                        runCatching {
                                            clipboardManager.setText(AnnotatedString(summary.title))
                                        }
                                    },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentCopy,
                                        contentDescription = language.text("复制标题", "Copy title"),
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        selectedSummary?.let { summary ->
                            IconButton(onClick = {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://steamcommunity.com/sharedfiles/filedetails/?id=${summary.id}"),
                                )
                                runCatching { context.startActivity(intent) }
                            }) {
                                Icon(
                                    imageVector = Icons.Outlined.OpenInNew,
                                    contentDescription = language.text("打开 Steam 页面", "Open Steam page"),
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            },
        ) { padding ->
            when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }

            state.error != null -> {
                WallHubEmptyState(
                    icon = Icons.Outlined.Refresh,
                    title = state.error,
                    actionLabel = language.text("重试", "Retry"),
                    onAction = onRetry,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }

            state.detail != null -> WorkshopDetailPagerContent(
                detail = state.detail,
                language = language,
                interaction = state.interaction,
                isLoadingInteraction = state.isLoadingInteraction,
                isUpdatingInteraction = state.isUpdatingInteraction,
                interactionMessage = state.interactionMessage,
                onToggleSubscription = onToggleSubscription,
                onToggleFavorite = onToggleFavorite,
                inlineVideoPlayback = inlinePlayback,
                isLoadingInlineVideo = state.isLoadingInlineVideo,
                inlineVideoError = state.inlineVideoError,
                onStartInlineVideo = onStartInlineVideo,
                onInlineFullscreenChange = onInlineFullscreenChange,
                exportFormat = state.exportFormat,
                onExportFormatSelected = onExportFormatSelected,
                isEnqueuingDownload = state.isEnqueuingDownload,
                downloadMessage = state.downloadMessage,
                onDownload = onDownload,
                onConvertExisting = onConvertExisting,
                stagedTaskId = state.stagedTaskId,
                comments = state.comments,
                commentsTotal = state.commentsTotal,
                commentsHasMore = state.commentsHasMore,
                isLoadingComments = state.isLoadingComments,
                isLoadingMoreComments = state.isLoadingMoreComments,
                commentsError = state.commentsError,
                canPostComment = state.steamSession.phase == SteamSessionPhase.SIGNED_IN,
                commentDraft = state.commentDraft,
                isPostingComment = state.isPostingComment,
                commentPostError = state.commentPostError,
                onRetryComments = onRetryComments,
                onLoadMoreComments = onLoadMoreComments,
                onCommentDraftChanged = onCommentDraftChanged,
                onSubmitComment = onSubmitComment,
                onCopyWorkshopId = { id ->
                    toastMessage = language.text("已复制项目 ID：$id", "Project ID copied: $id")
                    runCatching {
                        clipboardManager.setText(AnnotatedString(id.toString()))
                    }
                },
                onSearchAuthor = onSearchAuthor,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            }
        }
        }
    }
}

@Composable
private fun FullscreenWallpaperVideoPlayer(
    playback: SteamChunkPlayback,
    onFullscreenChange: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        SteamChunkVideoPlayer(
            playback = playback,
            fullscreen = true,
            onFullscreenChange = onFullscreenChange,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
private fun WorkshopDetailPagerContent(
    detail: WorkshopDetail,
    language: AppLanguage,
    interaction: WorkshopInteraction,
    isLoadingInteraction: Boolean,
    isUpdatingInteraction: Boolean,
    interactionMessage: String?,
    onToggleSubscription: () -> Unit,
    onToggleFavorite: () -> Unit,
    inlineVideoPlayback: SteamChunkPlayback?,
    isLoadingInlineVideo: Boolean,
    inlineVideoError: String?,
    onStartInlineVideo: () -> Unit,
    onInlineFullscreenChange: (Boolean) -> Unit,
    exportFormat: ExportFormat,
    onExportFormatSelected: (ExportFormat) -> Unit,
    isEnqueuingDownload: Boolean,
    downloadMessage: String?,
    onDownload: () -> Unit,
    onConvertExisting: (ExportFormat) -> Unit,
    stagedTaskId: String?,
    comments: List<WorkshopComment>,
    commentsTotal: Int?,
    commentsHasMore: Boolean,
    isLoadingComments: Boolean,
    isLoadingMoreComments: Boolean,
    commentsError: String?,
    canPostComment: Boolean,
    commentDraft: String,
    isPostingComment: Boolean,
    commentPostError: String?,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onCopyWorkshopId: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = detail.summary
    val exportFormats = summary.type.availableExportFormats()
    val pagerState = rememberPagerState(initialPage = DETAIL_OVERVIEW_PAGE) {
        DETAIL_PAGE_COUNT
    }
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    var showDownloadChoices by remember { mutableStateOf(false) }
    var headerHeightPx by remember(summary.id) { mutableIntStateOf(0) }
    var coverHeightPx by remember(summary.id) { mutableIntStateOf(0) }
    var headerOffsetPx by rememberSaveable(summary.id) { mutableStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inlineVideoActive = summary.type == WorkshopType.VIDEO &&
        (inlineVideoPlayback != null || isLoadingInlineVideo)
    val pinnedSpacingPx = with(density) { 8.dp.roundToPx() }
    val pinnedHeaderHeightPx = if (inlineVideoActive) coverHeightPx + pinnedSpacingPx else 0
    val maxHeaderCollapsePx = (headerHeightPx - pinnedHeaderHeightPx).coerceAtLeast(0).toFloat()
    val maxHeaderCollapseState = rememberUpdatedState(maxHeaderCollapsePx)
    LaunchedEffect(maxHeaderCollapsePx) {
        headerOffsetPx = headerOffsetPx.coerceIn(-maxHeaderCollapsePx, 0f)
    }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != DETAIL_COMMENTS_PAGE) {
            focusManager.clearFocus(force = true)
        }
    }
    val nestedScrollConnection = remember(summary.id) {
        object : NestedScrollConnection {
            private fun consumeHeaderDelta(deltaY: Float): Offset {
                val previousOffset = headerOffsetPx
                val nextOffset = (previousOffset + deltaY)
                    .coerceIn(-maxHeaderCollapseState.value, 0f)
                headerOffsetPx = nextOffset
                return Offset(x = 0f, y = nextOffset - previousOffset)
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                return if (available.y != 0f) consumeHeaderDelta(available.y) else Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                return if (available.y > 0f) consumeHeaderDelta(available.y) else Offset.Zero
            }
        }
    }
    Column(modifier = modifier.nestedScroll(nestedScrollConnection)) {
        CollapsibleDetailHeader(
            offsetPx = headerOffsetPx,
            onHeightChanged = { headerHeightPx = it },
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DetailIdentityChip(
                    label = language.text("项目 ID", "Project ID"),
                    value = summary.id.toString(),
                    icon = Icons.Outlined.ContentCopy,
                    onClick = { onCopyWorkshopId(summary.id) },
                )
                DetailIdentityChip(
                    label = language.text("作者", "Author"),
                    value = summary.author,
                    icon = Icons.Outlined.Search,
                    onClick = {
                        onSearchAuthor(detail.creatorId ?: summary.author)
                    },
                )
            }
            DetailCover(
                title = summary.title,
                previewUrl = summary.previewUrl,
                type = summary.type,
                language = language,
                playback = inlineVideoPlayback,
                isLoadingInlineVideo = isLoadingInlineVideo,
                inlineVideoError = inlineVideoError,
                onStartInlineVideo = onStartInlineVideo,
                onFullscreenChange = onInlineFullscreenChange,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .onSizeChanged { size -> coverHeightPx = size.height },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        PrimaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = {
                DetailDivider()
            },
        ) {
            DetailTab(
                selected = pagerState.currentPage == DETAIL_OVERVIEW_PAGE,
                text = language.text("详情", "Details"),
                onClick = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(DETAIL_OVERVIEW_PAGE)
                    }
                },
            )
            val commentsLabel = commentsTotal?.takeIf { it > 0 }?.let { total ->
                language.text("评论 ($total)", "Comments ($total)")
            } ?: language.text("评论", "Comments")
            DetailTab(
                selected = pagerState.currentPage == DETAIL_COMMENTS_PAGE,
                text = commentsLabel,
                onClick = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(DETAIL_COMMENTS_PAGE)
                    }
                },
            )
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalAlignment = Alignment.Top,
        ) { page ->
            when (page) {
                DETAIL_OVERVIEW_PAGE -> DetailOverviewPage(
                    detail = detail,
                    language = language,
                )

                else -> DetailCommentsPage(
                    comments = comments,
                    commentsHasMore = commentsHasMore,
                    isLoading = isLoadingComments,
                    isLoadingMore = isLoadingMoreComments,
                    error = commentsError,
                    canPostComment = canPostComment,
                    commentDraft = commentDraft,
                    isPostingComment = isPostingComment,
                    commentPostError = commentPostError,
                    language = language,
                    onRetry = onRetryComments,
                    onLoadMore = onLoadMoreComments,
                    onCommentDraftChanged = onCommentDraftChanged,
                    onSubmitComment = onSubmitComment,
                    isWallpaperHeaderCollapsed = headerOffsetPx < -1f,
                    onReturnToWallpaperTop = { headerOffsetPx = 0f },
                )
            }
        }
        DetailActionBar(
            language = language,
            interaction = interaction,
            isLoadingInteraction = isLoadingInteraction,
            isUpdatingInteraction = isUpdatingInteraction,
            interactionMessage = interactionMessage,
            isEnqueuingDownload = isEnqueuingDownload,
            downloadMessage = downloadMessage,
            onToggleSubscription = onToggleSubscription,
            onToggleFavorite = onToggleFavorite,
            onDownload = { showDownloadChoices = true },
        )
    }
    if (showDownloadChoices) {
        DownloadChoiceSheet(
            type = summary.type,
            language = language,
            exportFormats = exportFormats,
            onDismiss = { showDownloadChoices = false },
            onDownload = { format ->
                onExportFormatSelected(format)
                showDownloadChoices = false
                onDownload()
            },
        )
    }
}

@Composable
private fun DetailIdentityChip(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            content = content,
        )
    } else {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            content = content,
        )
    }
}

@Composable
private fun CollapsibleDetailHeader(
    offsetPx: Float,
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                val offset = offsetPx.roundToInt().coerceAtMost(0)
                val visibleHeight = (placeable.height + offset).coerceIn(0, placeable.height)
                layout(placeable.width, visibleHeight) {
                    placeable.placeRelative(x = 0, y = offset)
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size -> onHeightChanged(size.height) },
            content = content,
        )
    }
}

@Composable
private fun DetailCover(
    title: String,
    previewUrl: String?,
    type: WorkshopType,
    language: AppLanguage,
    playback: SteamChunkPlayback?,
    isLoadingInlineVideo: Boolean,
    inlineVideoError: String?,
    onStartInlineVideo: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (playback != null) {
            SteamChunkVideoPlayer(
                playback = playback,
                fullscreen = false,
                onFullscreenChange = onFullscreenChange,
                modifier = Modifier.matchParentSize(),
            )
            if (!playback.renderedFirstFrame && playback.error == null && previewUrl != null) {
                AsyncImage(
                    model = previewUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.38f)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(42.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                }
            }
        } else {
            if (previewUrl != null) {
                AsyncImage(
                    model = previewUrl,
                    contentDescription = language.text(title + " 预览图", title + " preview"),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.ImageNotSupported,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (type == WorkshopType.VIDEO && playback == null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.38f))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = language.text("播放视频", "Play video"),
                        onClick = onStartInlineVideo,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isLoadingInlineVideo -> {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(42.dp),
                            color = Color.White,
                            strokeWidth = 3.dp,
                        )
                    }

                    inlineVideoError != null -> {
                        Column(
                            modifier = Modifier.padding(horizontal = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(34.dp),
                            )
                            Text(
                                text = inlineVideoError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    else -> {
                        Surface(
                            shape = RoundedCornerShape(32.dp),
                            color = Color.Black.copy(alpha = 0.58f),
                            contentColor = Color.White,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PlayArrow,
                                contentDescription = language.text("播放视频", "Play video"),
                                modifier = Modifier.padding(14.dp).size(34.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = DETAIL_DIVIDER_ALPHA),
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DetailTab(
    selected: Boolean,
    text: String,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        Tab(
            selected = selected,
            onClick = onClick,
            text = {
                Text(
                    text = text,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailOverviewPage(
    detail: WorkshopDetail,
    language: AppLanguage,
) {
    val summary = detail.summary
    val description = detail.description.ifBlank {
        language.text("该壁纸没有提供简介。", "No description was provided.")
    }
    var descriptionExpanded by rememberSaveable(summary.id, description) { mutableStateOf(false) }
    var descriptionCanExpand by remember(description) { mutableStateOf(false) }
    var descriptionTogglePending by remember(summary.id, description) { mutableStateOf(false) }
    val expandedDescriptionInteractionSource = remember { MutableInteractionSource() }
    val descriptionToggleInteractionSource = remember { MutableInteractionSource() }
    val descriptionToggleScope = rememberCoroutineScope()
    val requestDescriptionExpansion: (Boolean) -> Unit = { expanded ->
        if (!descriptionTogglePending && descriptionExpanded != expanded) {
            descriptionTogglePending = true
            descriptionToggleScope.launch {
                delay(DESCRIPTION_RIPPLE_SETTLE_DURATION_MS)
                descriptionExpanded = expanded
                descriptionTogglePending = false
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            WallHubSurfaceCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = language.text("壁纸信息", "Wallpaper information"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    DetailMetricRow(
                        first = DetailMetricValue(
                            Icons.Heart,
                            language.text("订阅数", "Subscriptions"),
                            formatCompactCount(detail.subscriptions ?: summary.subscriptions),
                        ),
                        second = DetailMetricValue(
                            Icons.Star,
                            language.text("收藏数", "Favorites"),
                            formatCompactCount(summary.favorites),
                        ),
                    )
                    DetailDivider()
                    DetailMetricRow(
                        first = DetailMetricValue(
                            Icons.Visibility,
                            language.text("浏览量", "Views"),
                            formatCompactCount(summary.views),
                        ),
                        second = DetailMetricValue(
                            Icons.Download,
                            language.text("文件大小", "File size"),
                            detail.fileSizeBytes?.let(::formatMegabytes)
                                ?: language.text("未知", "Unknown"),
                        ),
                    )
                    DetailDivider()
                    DetailMetricRow(
                        first = DetailMetricValue(
                            Icons.Schedule,
                            language.text("最后更新", "Last updated"),
                            formatWorkshopDate(detail.updatedAt, language),
                        ),
                        second = DetailMetricValue(
                            Icons.Info,
                            language.text("类型", "Type"),
                            summary.type.label(language),
                        ),
                    )
                    if (summary.tags.isNotEmpty()) {
                        DetailDivider()
                        Text(
                            text = language.text("标签", "Tags"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            summary.tags.forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ) {
                                    Text(
                                        text = tag,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                    DetailDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = language.text("简介", "Description"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (!descriptionExpanded && descriptionCanExpand) {
                                        Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(
                                                enabled = !descriptionTogglePending,
                                                role = Role.Button,
                                                onClickLabel = language.text(
                                                    "展开简介",
                                                    "Expand description",
                                                ),
                                            ) {
                                                requestDescriptionExpansion(true)
                                            }
                                    } else if (descriptionExpanded) {
                                        Modifier.clickable(
                                            interactionSource = expandedDescriptionInteractionSource,
                                            indication = null,
                                            enabled = !descriptionTogglePending,
                                            role = Role.Button,
                                            onClickLabel = language.text(
                                                "收起简介",
                                                "Collapse description",
                                            ),
                                        ) {
                                            requestDescriptionExpansion(false)
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                            maxLines = if (descriptionExpanded) {
                                Int.MAX_VALUE
                            } else {
                                DESCRIPTION_COLLAPSED_MAX_LINES
                            },
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { result ->
                                if (!descriptionExpanded) {
                                    descriptionCanExpand = result.hasVisualOverflow
                                }
                            },
                        )
                        if (descriptionCanExpand || descriptionExpanded) {
                            Surface(
                                onClick = {
                                    requestDescriptionExpansion(!descriptionExpanded)
                                },
                                enabled = !descriptionTogglePending,
                                interactionSource = descriptionToggleInteractionSource,
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.primary,
                            ) {
                                Text(
                                    text = if (descriptionExpanded) {
                                        language.text("收起", "Show less")
                                    } else {
                                        language.text("展开查看更多", "Show more")
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DetailMetricValue(
    val icon: ImageVector,
    val label: String,
    val value: String,
)

@Composable
private fun DetailMetricRow(
    first: DetailMetricValue,
    second: DetailMetricValue,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DetailMetric(first, Modifier.weight(1f))
        DetailMetric(second, Modifier.weight(1f))
    }
}

@Composable
private fun DetailMetric(
    metric: DetailMetricValue,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = metric.icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = metric.value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailCommentsPage(
    comments: List<WorkshopComment>,
    commentsHasMore: Boolean,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    error: String?,
    canPostComment: Boolean,
    commentDraft: String,
    isPostingComment: Boolean,
    commentPostError: String?,
    language: AppLanguage,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
    isWallpaperHeaderCollapsed: Boolean,
    onReturnToWallpaperTop: () -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var commentsBoundsInWindow by remember { mutableStateOf(Rect.Zero) }
    var composerBoundsInWindow by remember { mutableStateOf(Rect.Zero) }
    val showScrollToTop by remember(isWallpaperHeaderCollapsed) {
        derivedStateOf {
            isWallpaperHeaderCollapsed ||
                listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 0
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) focusManager.clearFocus(force = true)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { commentsBoundsInWindow = it.boundsInWindow() }
            .pointerInput(commentsBoundsInWindow, composerBoundsInWindow) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    val positionInWindow = down.position + commentsBoundsInWindow.topLeft
                    if (!composerBoundsInWindow.contains(positionInWindow)) {
                        focusManager.clearFocus(force = true)
                    }
                }
            },
    ) {
        PullToRefreshBox(
            isRefreshing = isLoading && comments.isNotEmpty(),
            onRefresh = {
                focusManager.clearFocus(force = true)
                onRetry()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            if (canPostComment) {
                item(key = "comment-composer") {
                    CommentComposer(
                        value = commentDraft,
                        isPosting = isPostingComment,
                        error = commentPostError,
                        language = language,
                        onValueChange = onCommentDraftChanged,
                        onSubmit = onSubmitComment,
                        modifier = Modifier.onGloballyPositioned {
                            composerBoundsInWindow = it.boundsInWindow()
                        },
                    )
                }
            }
            when {
                isLoading && comments.isEmpty() -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                }

                error != null && comments.isEmpty() -> item {
                    WallHubEmptyState(
                        icon = Icons.Outlined.Refresh,
                        title = error,
                        actionLabel = language.text("重试", "Retry"),
                        onAction = onRetry,
                    )
                }

                comments.isEmpty() -> item {
                    WallHubEmptyState(
                        icon = Icons.Outlined.ChatBubbleOutline,
                        title = language.text("暂时没有评论", "No comments yet"),
                    )
                }

                else -> {
                    items(
                        items = comments,
                        key = { comment ->
                            listOf(comment.author, comment.timestamp, comment.text).joinToString("|")
                        },
                    ) { comment ->
                        WorkshopCommentItem(comment = comment, language = language)
                    }
                    if (error != null) {
                        item {
                            WallHubSecondaryButton(
                                onClick = onLoadMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null)
                                Text(
                                    text = language.text("重试加载更多评论", "Retry loading more"),
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    } else if (commentsHasMore) {
                        item {
                            LaunchedEffect(comments.size, commentsHasMore) {
                                onLoadMore()
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isLoadingMore) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }
        AnimatedVisibility(
            visible = showScrollToTop,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.88f),
            exit = fadeOut() + scaleOut(targetScale = 0.88f),
        ) {
            FloatingActionButton(
                onClick = {
                    focusManager.clearFocus(force = true)
                    onReturnToWallpaperTop()
                    coroutineScope.launch { listState.animateScrollToItem(0) }
                },
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = WallHubFabDefaultElevation,
                    pressedElevation = WallHubFabActiveElevation,
                    focusedElevation = WallHubFabDefaultElevation,
                    hoveredElevation = WallHubFabActiveElevation,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.VerticalAlignTop,
                    contentDescription = language.text(
                        "回到 Wallpaper 顶部",
                        "Back to wallpaper top",
                    ),
                )
            }
        }
    }
}

@Composable
private fun CommentComposer(
    value: String,
    isPosting: Boolean,
    error: String?,
    language: AppLanguage,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        placeholder = { Text(language.text("发表评论", "Write a comment")) },
        trailingIcon = {
            IconButton(
                onClick = {
                    focusManager.clearFocus(force = true)
                    onSubmit()
                },
                enabled = value.isNotBlank() && !isPosting,
            ) {
                if (isPosting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Send,
                        contentDescription = language.text("发表评论", "Post comment"),
                    )
                }
            }
        },
        supportingText = error?.let { message ->
            { Text(message) }
        },
        isError = error != null,
        enabled = !isPosting,
        minLines = 1,
        maxLines = 4,
    )
}

@Composable
private fun WorkshopCommentItem(
    comment: WorkshopComment,
    language: AppLanguage,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = comment.author.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    comment.avatarUrl?.let { avatarUrl ->
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = language.text(
                                "${comment.author} 的头像",
                                "${comment.author}'s avatar",
                            ),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = comment.author,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (comment.isCreator) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) {
                                Text(
                                    text = language.text("作者", "Creator"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = formatCommentDate(comment, language),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    text = comment.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailActionBar(
    language: AppLanguage,
    interaction: WorkshopInteraction,
    isLoadingInteraction: Boolean,
    isUpdatingInteraction: Boolean,
    interactionMessage: String?,
    isEnqueuingDownload: Boolean,
    downloadMessage: String?,
    onToggleSubscription: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
) {
    val interactionEnabled = !isLoadingInteraction && !isUpdatingInteraction
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WallHubSecondaryButton(
                onClick = onToggleSubscription,
                modifier = Modifier.weight(1f),
                enabled = interactionEnabled,
            ) {
                Icon(imageVector = Icons.Bell, contentDescription = null)
                Text(
                    text = if (interaction.subscriptionState == SubscriptionState.SUBSCRIBED) {
                        language.text("取消订阅", "Unsubscribe")
                    } else {
                        language.text("订阅", "Subscribe")
                    },
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            WallHubSecondaryButton(
                onClick = onToggleFavorite,
                modifier = Modifier.weight(1f),
                enabled = interactionEnabled,
            ) {
                Icon(imageVector = Icons.Star, contentDescription = null)
                Text(
                    text = if (interaction.favoriteState == FavoriteState.FAVORITED) {
                        language.text("取消收藏", "Unfavorite")
                    } else {
                        language.text("收藏", "Favorite")
                    },
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        WallHubPrimaryAction(
            label = if (isEnqueuingDownload) {
                language.text("正在加入下载队列…", "Adding to download queue…")
            } else {
                language.text("下载", "Download")
            },
            onClick = onDownload,
            icon = Icons.Download,
            enabled = !isEnqueuingDownload,
        )
        val status = when {
            isLoadingInteraction -> language.text("正在读取 Steam 账户状态…", "Loading Steam account state…")
            isUpdatingInteraction -> language.text("正在向 Steam 提交请求…", "Sending request to Steam…")
            interactionMessage != null -> interactionMessage
            downloadMessage != null -> downloadMessage
            else -> ""
        }
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(16.dp),
        )
    }
}

private fun formatCompactCount(value: Long?): String {
    if (value == null) return "—"
    return when {
        value >= 1_000_000L -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000.0)
            .replace(".0M", "M")
        value >= 1_000L -> String.format(Locale.getDefault(), "%.1fK", value / 1_000.0)
            .replace(".0K", "K")
        else -> value.toString()
    }
}

private fun formatWorkshopDate(timestamp: Long?, language: AppLanguage): String {
    if (timestamp == null || timestamp <= 0L) return language.text("未知", "Unknown")
    val pattern = if (language == AppLanguage.EN) "MMM d, yyyy" else "yyyy年M月d日"
    return runCatching {
        DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            .format(Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault()))
    }.getOrDefault(language.text("未知", "Unknown"))
}

internal fun formatCommentDate(
    comment: WorkshopComment,
    language: AppLanguage,
    nowMillis: Long = System.currentTimeMillis(),
): String {
    comment.timestamp?.takeIf { it > 0L }?.let { timestamp ->
        val timestampMillis = if (timestamp > 100_000_000_000L) timestamp else timestamp * 1_000L
        val difference = nowMillis - timestampMillis
        if (difference in 0 until COMMENT_HOUR_MS) {
            val minutes = (difference / COMMENT_MINUTE_MS).coerceAtLeast(1L)
            return language.text("$minutes 分钟以前", "$minutes minutes ago")
        }
        if (difference in 0 until COMMENT_DAY_MS) {
            val hours = difference / COMMENT_HOUR_MS
            return language.text("$hours 小时以前", "$hours hours ago")
        }
        return runCatching {
            val dateTime = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())
            val currentYear = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).year
            val pattern = when (language) {
                AppLanguage.EN -> if (dateTime.year == currentYear) {
                    "MMM dd, hh:mm:ss a"
                } else {
                    "yyyy MMM dd, hh:mm:ss a"
                }

                AppLanguage.ZH -> if (dateTime.year == currentYear) {
                    "MM 月 dd 日 a hh:mm:ss"
                } else {
                    "yyyy 年 MM 月 dd 日 a hh:mm:ss"
                }
            }
            val locale = if (language == AppLanguage.EN) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
            DateTimeFormatter.ofPattern(pattern, locale).format(dateTime)
        }.getOrDefault(comment.dateLabel.orEmpty())
    }
    return comment.dateLabel.orEmpty()
}

private const val DETAIL_OVERVIEW_PAGE = 0
private const val DETAIL_COMMENTS_PAGE = 1
private const val DETAIL_PAGE_COUNT = 2
private const val DESCRIPTION_COLLAPSED_MAX_LINES = 3
private const val DESCRIPTION_RIPPLE_SETTLE_DURATION_MS = 110L
private const val DETAIL_DIVIDER_ALPHA = 0.32f
private const val COMMENT_MINUTE_MS = 60_000L
private const val COMMENT_HOUR_MS = 60L * COMMENT_MINUTE_MS
private const val COMMENT_DAY_MS = 24L * COMMENT_HOUR_MS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadChoiceSheet(
    type: WorkshopType,
    language: AppLanguage,
    exportFormats: List<ExportFormat>,
    onDismiss: () -> Unit,
    onDownload: (ExportFormat) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(language.text("下载选项", "Download options"), style = MaterialTheme.typography.titleLarge)
            Text(
                language.text(
                    "${type.label(language)} 项目会加入下载队列，可在下载页面查看进度。",
                    "${type.label(language)} tasks are added to the download queue. Track their progress on Downloads.",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            exportFormats.forEach { format ->
                Button(
                    onClick = { onDownload(format) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when (format) {
                            ExportFormat.MPKG -> language.text("下载 MPKG 文件（手机）", "Download MPKG (mobile)")
                            ExportFormat.ZIP -> language.text("下载 ZIP 压缩包", "Download ZIP archive")
                            ExportFormat.AUTO -> language.text("自动选择格式", "Automatically choose format")
                        },
                    )
                }
            }
            WallHubSecondaryButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(language.text("关闭", "Close"))
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private fun WorkshopType.label(language: AppLanguage): String = when (this) {
    WorkshopType.VIDEO -> language.text("视频", "Video")
    WorkshopType.SCENE -> language.text("场景", "Scene")
    WorkshopType.WEB -> language.text("网站", "Web")
    WorkshopType.UNKNOWN -> language.text("未知", "Unknown")
}

private fun WorkshopType.defaultExportFormat(): ExportFormat = when (this) {
    WorkshopType.WEB -> ExportFormat.ZIP
    WorkshopType.VIDEO,
    WorkshopType.SCENE,
    WorkshopType.UNKNOWN,
    -> ExportFormat.MPKG
}

private fun WorkshopType.availableExportFormats(): List<ExportFormat> = when (this) {
    WorkshopType.WEB -> listOf(ExportFormat.ZIP)
    WorkshopType.VIDEO,
    WorkshopType.SCENE,
    WorkshopType.UNKNOWN,
    -> listOf(ExportFormat.MPKG, ExportFormat.ZIP)
}

private fun ExportFormat.label(language: AppLanguage): String = when (this) {
    ExportFormat.AUTO -> language.text("自动", "Automatic")
    ExportFormat.MPKG -> language.text("MPKG（移动端）", "MPKG (mobile)")
    ExportFormat.ZIP -> language.text("ZIP 压缩包", "ZIP archive")
}

private fun AppLanguage.text(zh: String, en: String): String = if (this == AppLanguage.EN) en else zh
