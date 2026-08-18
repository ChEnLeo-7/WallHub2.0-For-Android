@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.detail

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallhub.android.core.designsystem.requiresLegacyPublicDownloadPermission
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun WorkshopDetailRoute(
    onBack: () -> Unit,
    onSearchAuthor: (String) -> Unit = {},
    onSearchTag: (String) -> Unit = {},
    onOpenLocalVideo: (String) -> Unit = {},
    viewModel: WorkshopDetailViewModel =
        androidx.hilt.navigation.compose
            .hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
    WorkshopDetailEffectHandler(
        viewModel = viewModel,
        onBack = { leaveDetail(onBack) },
        onSearchAuthor = onSearchAuthor,
        onOpenLocalVideo = onOpenLocalVideo,
    )
    PredictiveBackHandler(enabled = !isLeavingDetail) {
        it.collect()
        viewModel.onAction(WorkshopDetailAction.Back)
    }
    WorkshopDetailScreen(
        state =
            if (isLeavingDetail) {
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
        onBack = { viewModel.onAction(WorkshopDetailAction.Back) },
        onRetry = viewModel::reload,
        onToggleSubscription = viewModel::toggleSubscription,
        onToggleFavorite = viewModel::toggleFavorite,
        onReconnectSteam = viewModel::reconnectSteamSession,
        onStartInlineVideo = viewModel::startInlineVideoPlayback,
        onRetryInlineVideo = viewModel::retryInlineVideoPlayback,
        onExportFormatSelected = viewModel::selectExportFormat,
        onDownload = {
            viewModel.onAction(
                WorkshopDetailAction.RequestOperation(WorkshopDetailPendingOperation.Download),
            )
        },
        onRetryComments = viewModel::retryComments,
        onLoadMoreComments = viewModel::loadMoreComments,
        onCommentDraftChanged = viewModel::updateCommentDraft,
        onSubmitComment = viewModel::submitComment,
        onInlineFullscreenChange = viewModel::setInlineVideoFullscreen,
        shouldShowInlinePlaybackStartNotice = viewModel::consumeInlinePlaybackStartNotice,
        onSearchAuthor = { author ->
            viewModel.onAction(WorkshopDetailAction.SearchAuthor(author))
        },
        onSearchTag = onSearchTag,
        onCopyText = { text, message ->
            viewModel.onAction(WorkshopDetailAction.CopyText(text, message))
        },
        onOpenSteam = { workshopId ->
            viewModel.onAction(WorkshopDetailAction.OpenSteam(workshopId))
        },
    )
}

@Composable
fun WorkshopDetailEffectHandler(
    viewModel: WorkshopDetailViewModel,
    onBack: () -> Unit,
    onSearchAuthor: (String) -> Unit = {},
    onOpenLocalVideo: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var effectMessage by remember { mutableStateOf<DetailUiText?>(null) }
    val resolvedEffectMessage = effectMessage?.resolve()
    LaunchedEffect(resolvedEffectMessage) {
        resolvedEffectMessage?.let { message ->
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
        effectMessage = null
    }
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnSearchAuthor by rememberUpdatedState(onSearchAuthor)
    val currentOnOpenLocalVideo by rememberUpdatedState(onOpenLocalVideo)
    var pendingOperation by remember { mutableStateOf<WorkshopDetailPendingOperation?>(null) }
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            pendingOperation?.let { operation ->
                viewModel.onAction(
                    WorkshopDetailAction.LegacyStoragePermissionResult(operation, granted),
                )
            }
            pendingOperation = null
        }
    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is WorkshopDetailEffect.ResolveLegacyStoragePermission -> {
                    if (context.requiresLegacyPublicDownloadPermission()) {
                        pendingOperation = effect.operation
                        permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        viewModel.onAction(
                            WorkshopDetailAction.LegacyStoragePermissionResult(
                                effect.operation,
                                granted = true,
                            ),
                        )
                    }
                }
                WorkshopDetailEffect.Back -> currentOnBack()
                is WorkshopDetailEffect.SearchAuthor -> {
                    viewModel.stopInlineVideoPlayback()
                    currentOnSearchAuthor(effect.author)
                }
                is WorkshopDetailEffect.CopyText -> {
                    clipboard.setText(AnnotatedString(effect.text))
                    Toast.makeText(context.applicationContext, effect.message, Toast.LENGTH_SHORT).show()
                }
                is WorkshopDetailEffect.OpenSteam -> {
                    val intent =
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://steamcommunity.com/sharedfiles/filedetails/?id=${effect.workshopId}"),
                        )
                    runCatching { context.startActivity(intent) }
                }
                is WorkshopDetailEffect.OpenLocalVideo -> {
                    viewModel.stopInlineVideoPlayback()
                    currentOnOpenLocalVideo(effect.taskId)
                }
                is WorkshopDetailEffect.ShowMessage -> effectMessage = effect.message
            }
        }
    }
}
