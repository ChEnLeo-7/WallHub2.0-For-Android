@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.detail

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.wallhub.android.core.designsystem.text
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTaskRepository
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WORKSHOP_COMMENT_MAX_LENGTH
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.core.model.WorkshopVideoStreamRepository
import com.wallhub.android.core.model.WorkshopVideoStreamSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkshopDetailViewModel
    @Inject
    constructor(
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
        private val effectChannel = Channel<WorkshopDetailEffect>(capacity = Channel.BUFFERED)
        private var inlineVideoLoadJob: Job? = null
        private var commentsLoadJob: Job? = null

        val uiState: StateFlow<WorkshopDetailUiState> = mutableState.asStateFlow()
        val effects: Flow<WorkshopDetailEffect> = effectChannel.receiveAsFlow()

        fun onAction(action: WorkshopDetailAction) {
            action.immediateEffect()?.let(::emitEffect) ?: when (action) {
                is WorkshopDetailAction.LegacyStoragePermissionResult -> {
                    if (action.granted) {
                        executePendingOperation(action.operation)
                    } else {
                        emitEffect(
                            WorkshopDetailEffect.ShowMessage(
                                "未授予存储权限，无法导出到 Download/WallHub",
                            ),
                        )
                    }
                }
                else -> Unit
            }
        }

        private fun executePendingOperation(operation: WorkshopDetailPendingOperation) {
            when (operation) {
                WorkshopDetailPendingOperation.Download -> enqueueDownload()
                WorkshopDetailPendingOperation.LocalVideoPlayback -> requestLocalVideoPlayback()
                is WorkshopDetailPendingOperation.ConvertExisting -> convertExistingDownload(operation.format)
            }
        }

        private fun emitEffect(effect: WorkshopDetailEffect) {
            effectChannel.trySend(effect)
        }

        init {
            viewModelScope.launch {
                steamSessionRepository.session.collect { session ->
                    mutableState.value =
                        mutableState.value.copy(
                            steamSession = session,
                            isPostingComment =
                                if (session.phase == SteamSessionPhase.SIGNED_IN) {
                                    mutableState.value.isPostingComment
                                } else {
                                    false
                                },
                        )
                }
            }
            viewModelScope.launch {
                settingsRepository.preferences.collect { preferences ->
                    mutableState.value =
                        mutableState.value.copy(
                            outputTreeUri = preferences.outputTreeUri,
                            outputDirectoryLabel = preferences.outputDirectoryLabel,
                            onlineChunkPlaybackEnabled = preferences.onlineChunkPlaybackEnabled,
                        )
                }
            }
            viewModelScope.launch {
                downloadTaskRepository.tasks.collect { tasks ->
                    val localVideoTask =
                        tasks.firstOrNull { task ->
                            task.workshopId == workshopId &&
                                task.type == WorkshopType.VIDEO &&
                                task.status == DownloadStatus.COMPLETED &&
                                !task.stagingDirectory.isNullOrBlank()
                        }
                    val stagedTask =
                        tasks.firstOrNull { task ->
                            task.workshopId == workshopId &&
                                task.status == DownloadStatus.COMPLETED &&
                                !task.stagingDirectory.isNullOrBlank() &&
                                task.outputUri == null
                        }
                    val activeVideoTask =
                        tasks.firstOrNull { task ->
                            task.workshopId == workshopId &&
                                task.type == WorkshopType.VIDEO &&
                                task.status !in
                                setOf(
                                    DownloadStatus.COMPLETED,
                                    DownloadStatus.FAILED,
                                    DownloadStatus.CANCELLED,
                                )
                        }
                    val current = mutableState.value
                    val shouldOpenLocalVideo =
                        current.waitingForLocalVideoPlayback && localVideoTask != null
                    mutableState.value =
                        current.copy(
                            localVideoTaskId = localVideoTask?.id,
                            activeVideoTaskId = activeVideoTask?.id,
                            stagedTaskId = stagedTask?.id,
                            waitingForLocalVideoPlayback =
                                if (shouldOpenLocalVideo) {
                                    false
                                } else {
                                    current.waitingForLocalVideoPlayback
                                },
                        )
                    if (shouldOpenLocalVideo) {
                        effectChannel.send(WorkshopDetailEffect.OpenLocalVideo(localVideoTask.id))
                    }
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
                mutableState.value =
                    mutableState.value.copy(
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
                        mutableState.value =
                            current.copy(
                                detail = detail,
                                isLoading = false,
                                error = null,
                                exportFormat = detail.summary.type.defaultExportFormat(),
                            )
                        refreshInteraction()
                        loadComments(refresh = true)
                    }.onFailure { error ->
                        mutableState.value =
                            mutableState.value.copy(
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
            inlineVideoLoadJob =
                viewModelScope.launch {
                    var openedStream: WorkshopVideoStreamSession? = null
                    var openedPlayer: ExoPlayer? = null
                    mutableState.value =
                        mutableState.value.copy(
                            isLoadingInlineVideo = true,
                            inlineVideoError = null,
                        )
                    try {
                        openedStream = videoStreamRepository.open(workshopId)
                        coroutineContext.ensureActive()
                        openedPlayer =
                            createSteamChunkPlayer(
                                context = applicationContext,
                                stream = openedStream,
                            )
                        mutableState.value =
                            mutableState.value.copy(
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
                        mutableState.value =
                            mutableState.value.copy(
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
            mutableState.value =
                current.copy(
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
            mutableState.value =
                mutableState.value.copy(
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
                mutableState.value =
                    mutableState.value.copy(
                        isPostingComment = true,
                        commentPostError = null,
                    )
                runCatching {
                    accountWorkshopRepository.postComment(workshopId, ownerId, comment)
                }.onSuccess {
                    mutableState.value =
                        mutableState.value.copy(
                            commentDraft = "",
                            isPostingComment = false,
                            commentPostError = null,
                        )
                    loadComments(refresh = true)
                }.onFailure { error ->
                    mutableState.value =
                        mutableState.value.copy(
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
            commentsLoadJob =
                viewModelScope.launch {
                    mutableState.value =
                        mutableState.value.copy(
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
                        val comments =
                            if (refresh) {
                                page.comments
                            } else {
                                (latest.comments + page.comments).distinctBy { comment ->
                                    listOf(comment.author, comment.timestamp, comment.text).joinToString("\u001f")
                                }
                            }
                        mutableState.value =
                            latest.copy(
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
                        mutableState.value =
                            mutableState.value.copy(
                                isLoadingComments = false,
                                isLoadingMoreComments = false,
                                commentsError = error.message ?: "无法读取 Steam 评论",
                            )
                    }
                }
        }

        fun toggleSubscription() {
            val currentlySubscribed =
                mutableState.value.interaction.subscriptionState ==
                    SubscriptionState.SUBSCRIBED
            updateInteraction(
                successMessage =
                    if (currentlySubscribed) {
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
                successMessage =
                    if (currentlyFavorited) {
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
                mutableState.value =
                    mutableState.value.copy(
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
                    mutableState.value =
                        mutableState.value.copy(
                            isEnqueuingDownload = false,
                            waitingForLocalVideoPlayback = playWhenReady,
                            downloadMessage =
                                if (playWhenReady) {
                                    "视频已加入下载队列，完成后将自动播放"
                                } else {
                                    "已加入下载队列：${task.title}"
                                },
                        )
                }.onFailure { error ->
                    mutableState.value =
                        mutableState.value.copy(
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
                    emitEffect(WorkshopDetailEffect.OpenLocalVideo(localVideoTaskId))
                }

                current.activeVideoTaskId != null -> {
                    mutableState.value =
                        current.copy(
                            waitingForLocalVideoPlayback = true,
                            downloadMessage = "视频正在下载，完成后将自动播放",
                        )
                }

                else -> enqueueDownload(playWhenReady = true)
            }
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
                }.onSuccess {
                    mutableState.value =
                        mutableState.value.copy(
                            downloadMessage = "已开始转换并导出已有暂存文件",
                        )
                }.onFailure { error ->
                    mutableState.value =
                        mutableState.value.copy(
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
                        mutableState.value =
                            mutableState.value.copy(
                                interaction = interaction,
                                isLoadingInteraction = false,
                            )
                    }.onFailure {
                        mutableState.value = mutableState.value.copy(isLoadingInteraction = false)
                    }
            }
        }

        private fun updateInteraction(
            successMessage: String,
            operation: suspend () -> WorkshopInteraction,
        ) {
            viewModelScope.launch {
                mutableState.value =
                    mutableState.value.copy(
                        isUpdatingInteraction = true,
                        interactionMessage = null,
                    )
                runCatching { operation() }
                    .onSuccess { interaction ->
                        mutableState.value =
                            mutableState.value.copy(
                                interaction = interaction,
                                isUpdatingInteraction = false,
                                interactionMessage = successMessage,
                            )
                    }.onFailure { error ->
                        mutableState.value =
                            mutableState.value.copy(
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

internal fun WorkshopDetailAction.immediateEffect(): WorkshopDetailEffect? =
    when (this) {
        is WorkshopDetailAction.RequestOperation ->
            WorkshopDetailEffect.ResolveLegacyStoragePermission(operation)
        WorkshopDetailAction.Back -> WorkshopDetailEffect.Back
        is WorkshopDetailAction.SearchAuthor -> WorkshopDetailEffect.SearchAuthor(author)
        is WorkshopDetailAction.CopyText -> WorkshopDetailEffect.CopyText(text, message)
        is WorkshopDetailAction.OpenSteam -> WorkshopDetailEffect.OpenSteam(workshopId)
        is WorkshopDetailAction.ShowMessage -> WorkshopDetailEffect.ShowMessage(message)
        is WorkshopDetailAction.LegacyStoragePermissionResult -> null
    }
