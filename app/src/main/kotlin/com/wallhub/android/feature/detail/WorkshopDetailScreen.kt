@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.wallhub.android.feature.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubColorTokens
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.LocalWallHubToastState
import com.wallhub.android.core.designsystem.WallHubShapeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopType
import kotlinx.coroutines.launch
import org.uwuaosp.compose.settingslib.SettingsCollapsingAppBarScaffold
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkshopDetailScreen(
    state: WorkshopDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleSubscription: () -> Unit,
    onToggleFavorite: () -> Unit,
    onReconnectSteam: () -> Unit,
    onStartInlineVideo: () -> Unit,
    onExportFormatSelected: (ExportFormat) -> Unit,
    onDownload: () -> Unit,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onInlineFullscreenChange: (Boolean) -> Unit,
    onSearchAuthor: (String) -> Unit,
    onSearchTag: (String) -> Unit,
    onCopyText: (String, String) -> Unit,
    onOpenSteam: (Long) -> Unit,
) {
    val selectedSummary = state.detail?.summary
    val unknown = stringResource(R.string.detail_unknown)
    val steamCdnMessageTemplate = stringResource(R.string.detail_steam_cdn, "%s")
    val projectIdCopiedMessage = stringResource(R.string.detail_project_id_copied, selectedSummary?.id ?: 0L)
    val titleCopiedMessage = stringResource(R.string.detail_wallpaper_title_copied)
    val toastState = LocalWallHubToastState.current
    val inlineVideoStream = state.inlineVideoStream
    val inlineFullscreen = state.isInlineVideoFullscreen
    var cdnToastDelivered by remember(inlineVideoStream) { mutableStateOf(false) }
    val inlinePlayback =
        inlineVideoStream?.let { stream ->
            state.inlineVideoPlayer?.let { player ->
                rememberRetainedSteamChunkPlayback(
                    stream = stream,
                    player = player,
                    onFirstFrameRendered = {
                        if (!cdnToastDelivered) {
                            cdnToastDelivered = true
                            toastState.show(steamCdnMessageTemplate.format(stream.currentCdnHost ?: unknown))
                        }
                    },
                )
            }
        }
    BackHandler(enabled = inlineFullscreen) { onInlineFullscreenChange(false) }
    FullscreenSystemBarsEffect(enabled = inlineFullscreen)
    Box(modifier = Modifier.fillMaxSize()) {
        if (inlineFullscreen && inlinePlayback != null) {
            FullscreenWallpaperVideoPlayer(
                playback = inlinePlayback,
                onFullscreenChange = onInlineFullscreenChange,
            )
        } else {
            val title = selectedSummary?.localizedTitle() ?: stringResource(R.string.detail_wallpaper_details)
            val colorScheme = MaterialTheme.colorScheme
            MaterialTheme(
                colorScheme =
                    colorScheme.copy(
                        surfaceContainer = colorScheme.surfaceContainerLowest,
                        surfaceBright = colorScheme.surfaceContainerLow,
                    ),
            ) {
                SettingsCollapsingAppBarScaffold(
                    title = title,
                    showBackButton = true,
                    onNavigateUp = onBack,
                    actions = {
                        selectedSummary?.let { workshop ->
                            IconButton(onClick = { onCopyText(title, titleCopiedMessage) }) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = stringResource(R.string.detail_copy_title),
                                )
                            }
                            IconButton(onClick = { onOpenSteam(workshop.id) }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = stringResource(R.string.detail_open_steam_page),
                                )
                            }
                        }
                    },
                ) { padding ->
                    when {
                        state.isLoading -> {
                            Box(
                                modifier =
                                    Modifier
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
                                title = state.error.resolve(),
                                actionLabel = stringResource(R.string.detail_retry),
                                onAction = onRetry,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(padding),
                            )
                        }

                        state.detail != null ->
                            WorkshopDetailPagerContent(
                            detail = state.detail,
                            interaction = state.interaction,
                            isLoadingInteraction = state.isLoadingInteraction,
                            isUpdatingInteraction = state.isUpdatingInteraction,
                            interactionMessage = state.interactionMessage,
                            steamSession = state.steamSession,
                            onReconnectSteam = onReconnectSteam,
                            onToggleSubscription = onToggleSubscription,
                            onToggleFavorite = onToggleFavorite,
                            inlineVideoPlayback = inlinePlayback,
                            isLoadingInlineVideo = state.isLoadingInlineVideo,
                            inlineVideoError = state.inlineVideoError,
                            onStartInlineVideo = onStartInlineVideo,
                            onInlineFullscreenChange = onInlineFullscreenChange,
                            onExportFormatSelected = onExportFormatSelected,
                            isEnqueuingDownload = state.isEnqueuingDownload,
                            downloadMessage = state.downloadMessage,
                            onDownload = onDownload,
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
                                onCopyText(
                                    id.toString(),
                                    projectIdCopiedMessage,
                                )
                            },
                            onSearchAuthor = onSearchAuthor,
                            onSearchTag = onSearchTag,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(padding),
                            )
                    }
                }
            }
        }
    }
}

@Composable
internal fun FullscreenWallpaperVideoPlayer(
    playback: SteamChunkPlayback,
    onFullscreenChange: (Boolean) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(WallHubColorTokens.mediaCanvas),
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
internal fun WorkshopDetailPagerContent(
    detail: WorkshopDetail,
    interaction: WorkshopInteraction,
    isLoadingInteraction: Boolean,
    isUpdatingInteraction: Boolean,
    interactionMessage: DetailUiText?,
    steamSession: SteamSessionState,
    onReconnectSteam: () -> Unit,
    onToggleSubscription: () -> Unit,
    onToggleFavorite: () -> Unit,
    inlineVideoPlayback: SteamChunkPlayback?,
    isLoadingInlineVideo: Boolean,
    inlineVideoError: DetailUiText?,
    onStartInlineVideo: () -> Unit,
    onInlineFullscreenChange: (Boolean) -> Unit,
    onExportFormatSelected: (ExportFormat) -> Unit,
    isEnqueuingDownload: Boolean,
    downloadMessage: DetailUiText?,
    onDownload: () -> Unit,
    comments: List<WorkshopComment>,
    commentsTotal: Int?,
    commentsHasMore: Boolean,
    isLoadingComments: Boolean,
    isLoadingMoreComments: Boolean,
    commentsError: DetailUiText?,
    canPostComment: Boolean,
    commentDraft: String,
    isPostingComment: Boolean,
    commentPostError: DetailUiText?,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onCopyWorkshopId: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
    onSearchTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = detail.summary
    val exportFormats = summary.type.availableExportFormats()
    val pagerState =
        rememberPagerState(initialPage = DETAIL_OVERVIEW_PAGE) {
            DETAIL_PAGE_COUNT
        }
    val focusManager = LocalFocusManager.current
    var showDownloadChoices by remember { mutableStateOf(false) }
    var headerHeightPx by remember(summary.id) { mutableIntStateOf(0) }
    var coverHeightPx by remember(summary.id) { mutableIntStateOf(0) }
    var headerOffsetPx by rememberSaveable(summary.id) { mutableStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val inlineVideoActive =
        summary.type == WorkshopType.VIDEO &&
            (inlineVideoPlayback != null || isLoadingInlineVideo)
    val pinnedSpacingPx = with(density) { WallHubSpacing.xs.roundToPx() }
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
    val nestedScrollConnection =
        remember(summary.id) {
            object : NestedScrollConnection {
                private fun consumeHeaderDelta(deltaY: Float): Offset {
                    val previousOffset = headerOffsetPx
                    val nextOffset =
                        (previousOffset + deltaY)
                            .coerceIn(-maxHeaderCollapseState.value, 0f)
                    headerOffsetPx = nextOffset
                    return Offset(x = 0f, y = nextOffset - previousOffset)
                }

                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset = if (available.y != 0f) consumeHeaderDelta(available.y) else Offset.Zero

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset = if (available.y > 0f) consumeHeaderDelta(available.y) else Offset.Zero
            }
        }
    WorkshopDetailPane(
        detail = detail,
        pagerState = pagerState,
        nestedScrollConnection = nestedScrollConnection,
        headerOffsetPx = headerOffsetPx,
        onHeaderOffsetChange = { headerOffsetPx = it },
        onHeaderHeightChanged = { headerHeightPx = it },
        onCoverHeightChanged = { coverHeightPx = it },
        inlineVideoPlayback = inlineVideoPlayback,
        isLoadingInlineVideo = isLoadingInlineVideo,
        inlineVideoError = inlineVideoError,
        onStartInlineVideo = onStartInlineVideo,
        onInlineFullscreenChange = onInlineFullscreenChange,
        comments = comments,
        commentsTotal = commentsTotal,
        commentsHasMore = commentsHasMore,
        isLoadingComments = isLoadingComments,
        isLoadingMoreComments = isLoadingMoreComments,
        commentsError = commentsError,
        canPostComment = canPostComment,
        commentDraft = commentDraft,
        isPostingComment = isPostingComment,
        commentPostError = commentPostError,
        onRetryComments = onRetryComments,
        onLoadMoreComments = onLoadMoreComments,
        onCommentDraftChanged = onCommentDraftChanged,
        onSubmitComment = onSubmitComment,
        interaction = interaction,
        isLoadingInteraction = isLoadingInteraction,
        isUpdatingInteraction = isUpdatingInteraction,
        interactionMessage = interactionMessage,
        steamSession = steamSession,
        onReconnectSteam = onReconnectSteam,
        isEnqueuingDownload = isEnqueuingDownload,
        downloadMessage = downloadMessage,
        onToggleSubscription = onToggleSubscription,
        onToggleFavorite = onToggleFavorite,
        onShowDownloadChoices = { showDownloadChoices = true },
        onCopyWorkshopId = onCopyWorkshopId,
        onSearchAuthor = onSearchAuthor,
        onSearchTag = onSearchTag,
        modifier = modifier,
    )
    if (showDownloadChoices) {
        DownloadChoiceSheet(
            type = summary.type,
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

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
internal fun WorkshopDetailPane(
    detail: WorkshopDetail,
    pagerState: androidx.compose.foundation.pager.PagerState,
    nestedScrollConnection: NestedScrollConnection,
    headerOffsetPx: Float,
    onHeaderOffsetChange: (Float) -> Unit,
    onHeaderHeightChanged: (Int) -> Unit,
    onCoverHeightChanged: (Int) -> Unit,
    inlineVideoPlayback: SteamChunkPlayback?,
    isLoadingInlineVideo: Boolean,
    inlineVideoError: DetailUiText?,
    onStartInlineVideo: () -> Unit,
    onInlineFullscreenChange: (Boolean) -> Unit,
    comments: List<WorkshopComment>,
    commentsTotal: Int?,
    commentsHasMore: Boolean,
    isLoadingComments: Boolean,
    isLoadingMoreComments: Boolean,
    commentsError: DetailUiText?,
    canPostComment: Boolean,
    commentDraft: String,
    isPostingComment: Boolean,
    commentPostError: DetailUiText?,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
    interaction: WorkshopInteraction,
    isLoadingInteraction: Boolean,
    isUpdatingInteraction: Boolean,
    interactionMessage: DetailUiText?,
    steamSession: SteamSessionState,
    onReconnectSteam: () -> Unit,
    isEnqueuingDownload: Boolean,
    downloadMessage: DetailUiText?,
    onToggleSubscription: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShowDownloadChoices: () -> Unit,
    onCopyWorkshopId: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
    onSearchTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val loadingInteractionMessage = stringResource(R.string.detail_loading_steam_account)
    val updatingInteractionMessage = stringResource(R.string.detail_sending_steam_request)
    val resolvedInteractionMessage = interactionMessage?.resolve()
    val resolvedDownloadMessage = downloadMessage?.resolve()
    val disconnectedMessage = stringResource(R.string.backend_steam_disconnected)
    val reconnectActionLabel = stringResource(R.string.detail_reconnect)
    val canReconnect =
        steamSession.phase == SteamSessionPhase.RESTORABLE &&
            steamSession.hasStoredSession &&
            steamSession.message == disconnectedMessage
    val snackbarMessage =
        when {
            isLoadingInteraction -> loadingInteractionMessage
            isUpdatingInteraction -> updatingInteractionMessage
            canReconnect -> disconnectedMessage
            resolvedInteractionMessage != null -> resolvedInteractionMessage
            else -> resolvedDownloadMessage
        }
    val snackbarDuration =
        if (isLoadingInteraction || isUpdatingInteraction || canReconnect) {
            SnackbarDuration.Indefinite
        } else {
            SnackbarDuration.Short
        }
    LaunchedEffect(snackbarMessage, snackbarDuration, canReconnect) {
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarMessage?.let { message ->
            val result =
                snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = reconnectActionLabel.takeIf { canReconnect },
                    duration = snackbarDuration,
                )
            if (canReconnect && result == SnackbarResult.ActionPerformed) {
                onReconnectSteam()
            }
        }
    }
    var actionBarHeightPx by remember { mutableIntStateOf(0) }
    var commentComposerHeightPx by remember { mutableIntStateOf(0) }
    val actionBarHeight = with(LocalDensity.current) { actionBarHeightPx.toDp() }
    val commentComposerHeight = with(LocalDensity.current) { commentComposerHeightPx.toDp() }

    Box(modifier = modifier.fillMaxSize().nestedScroll(nestedScrollConnection)) {
        Column(modifier = Modifier.fillMaxSize()) {
            WorkshopDetailCollapsibleHeader(
                detail = detail,
                offsetPx = headerOffsetPx,
                onHeaderHeightChanged = onHeaderHeightChanged,
                onCoverHeightChanged = onCoverHeightChanged,
                inlineVideoPlayback = inlineVideoPlayback,
                isLoadingInlineVideo = isLoadingInlineVideo,
                inlineVideoError = inlineVideoError,
                onStartInlineVideo = onStartInlineVideo,
                onInlineFullscreenChange = onInlineFullscreenChange,
            )
            WorkshopDetailTabPager(
                detail = detail,
                pagerState = pagerState,
                comments = comments,
                commentsTotal = commentsTotal,
                commentsHasMore = commentsHasMore,
                isLoadingComments = isLoadingComments,
                isLoadingMoreComments = isLoadingMoreComments,
                commentsError = commentsError,
                canPostComment = canPostComment,
                commentDraft = commentDraft,
                isPostingComment = isPostingComment,
                commentPostError = commentPostError,
                isWallpaperHeaderCollapsed = headerOffsetPx < -1f,
                onReturnToWallpaperTop = { onHeaderOffsetChange(0f) },
                onRetryComments = onRetryComments,
                onLoadMoreComments = onLoadMoreComments,
                onCommentDraftChanged = onCommentDraftChanged,
                onSubmitComment = onSubmitComment,
                onCommentComposerHeightChanged = { commentComposerHeightPx = it },
                onCopyWorkshopId = onCopyWorkshopId,
                onSearchAuthor = onSearchAuthor,
                onSearchTag = onSearchTag,
            )
            DetailActionBar(
                interaction = interaction,
                isLoadingInteraction = isLoadingInteraction,
                isUpdatingInteraction = isUpdatingInteraction,
                isEnqueuingDownload = isEnqueuingDownload,
                onToggleSubscription = onToggleSubscription,
                onToggleFavorite = onToggleFavorite,
                onDownload = onShowDownloadChoices,
                modifier = Modifier.onSizeChanged { size -> actionBarHeightPx = size.height },
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = WallHubSpacing.md)
                    .padding(bottom = actionBarHeight + commentComposerHeight + WallHubSpacing.xs)
                    .widthIn(max = 560.dp),
        )
    }
}

@Composable
internal fun WorkshopDetailCollapsibleHeader(
    detail: WorkshopDetail,
    offsetPx: Float,
    onHeaderHeightChanged: (Int) -> Unit,
    onCoverHeightChanged: (Int) -> Unit,
    inlineVideoPlayback: SteamChunkPlayback?,
    isLoadingInlineVideo: Boolean,
    inlineVideoError: DetailUiText?,
    onStartInlineVideo: () -> Unit,
    onInlineFullscreenChange: (Boolean) -> Unit,
) {
    val summary = detail.summary
    val title = summary.localizedTitle()
    CollapsibleDetailHeader(
        offsetPx = offsetPx,
        onHeightChanged = onHeaderHeightChanged,
    ) {
        DetailCover(
            title = title,
            previewUrl = summary.previewUrl,
            type = summary.type,
            playback = inlineVideoPlayback,
            isLoadingInlineVideo = isLoadingInlineVideo,
            inlineVideoError = inlineVideoError,
            onStartInlineVideo = onStartInlineVideo,
            onFullscreenChange = onInlineFullscreenChange,
            modifier =
                Modifier
                    .padding(horizontal = WallHubSpacing.md)
                    .onSizeChanged { size -> onCoverHeightChanged(size.height) },
        )
        Spacer(modifier = Modifier.height(WallHubSpacing.xs))
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.WorkshopDetailTabPager(
    detail: WorkshopDetail,
    pagerState: androidx.compose.foundation.pager.PagerState,
    comments: List<WorkshopComment>,
    commentsTotal: Int?,
    commentsHasMore: Boolean,
    isLoadingComments: Boolean,
    isLoadingMoreComments: Boolean,
    commentsError: DetailUiText?,
    canPostComment: Boolean,
    commentDraft: String,
    isPostingComment: Boolean,
    commentPostError: DetailUiText?,
    isWallpaperHeaderCollapsed: Boolean,
    onReturnToWallpaperTop: () -> Unit,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onCommentComposerHeightChanged: (Int) -> Unit,
    onCopyWorkshopId: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
    onSearchTag: (String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    PrimaryTabRow(
        selectedTabIndex = pagerState.currentPage,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = { DetailDivider() },
    ) {
        DetailTab(
            selected = pagerState.currentPage == DETAIL_OVERVIEW_PAGE,
            text = stringResource(R.string.detail_details),
            onClick = {
                coroutineScope.launch {
                    pagerState.animateScrollToPage(DETAIL_OVERVIEW_PAGE)
                }
            },
        )
        val commentsLabel =
            commentsTotal?.takeIf { it > 0 }?.let { total ->
                stringResource(R.string.detail_comments_count, total)
            } ?: stringResource(R.string.detail_comments)
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
        modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f),
        verticalAlignment = Alignment.Top,
    ) { page ->
        when (page) {
            DETAIL_OVERVIEW_PAGE ->
                DetailOverviewPage(
                    detail = detail,
                    onCopyWorkshopId = onCopyWorkshopId,
                    onSearchAuthor = onSearchAuthor,
                    onSearchTag = onSearchTag,
                )

            else ->
                DetailCommentsPage(
                    comments = comments,
                    commentsHasMore = commentsHasMore,
                    isLoading = isLoadingComments,
                    isLoadingMore = isLoadingMoreComments,
                    error = commentsError,
                    canPostComment = canPostComment,
                    commentDraft = commentDraft,
                    isPostingComment = isPostingComment,
                    commentPostError = commentPostError,
                    onRetry = onRetryComments,
                    onLoadMore = onLoadMoreComments,
                    onCommentDraftChanged = onCommentDraftChanged,
                    onSubmitComment = onSubmitComment,
                    onComposerHeightChanged = onCommentComposerHeightChanged,
                    isWallpaperHeaderCollapsed = isWallpaperHeaderCollapsed,
                    onReturnToWallpaperTop = onReturnToWallpaperTop,
                )
        }
    }
}

@Composable
internal fun CollapsibleDetailHeader(
    offsetPx: Float,
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size -> onHeightChanged(size.height) },
            content = content,
        )
    }
}

@Composable
internal fun DetailCover(
    title: String,
    previewUrl: String?,
    type: WorkshopType,
    playback: SteamChunkPlayback?,
    isLoadingInlineVideo: Boolean,
    inlineVideoError: DetailUiText?,
    onStartInlineVideo: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.large)
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
                    modifier =
                        Modifier
                            .matchParentSize()
                            .background(WallHubColorTokens.mediaControlScrim),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(42.dp),
                        color = WallHubColorTokens.mediaOverlayContent,
                        strokeWidth = 3.dp,
                    )
                }
            }
        } else {
            if (previewUrl != null) {
                AsyncImage(
                    model = previewUrl,
                    contentDescription = stringResource(R.string.detail_preview_image, title),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.ImageNotSupported,
                    contentDescription = null,
                    modifier = Modifier.size(WallHubSpacing.xxl),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (type == WorkshopType.VIDEO && playback == null) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(WallHubColorTokens.mediaControlScrim)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.detail_play_video),
                            onClick = onStartInlineVideo,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isLoadingInlineVideo -> {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(42.dp),
                            color = WallHubColorTokens.mediaOverlayContent,
                            strokeWidth = 3.dp,
                        )
                    }

                    inlineVideoError != null -> {
                        Column(
                            modifier = Modifier.padding(horizontal = WallHubSpacing.xl),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                tint = WallHubColorTokens.mediaOverlayContent,
                                modifier = Modifier.size(34.dp),
                            )
                            Text(
                                text = inlineVideoError.resolve(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = WallHubColorTokens.mediaOverlayContent,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    else -> {
                        Surface(
                            shape = WallHubShapeTokens.mediaControl,
                            color = WallHubColorTokens.mediaOverlayScrim,
                            contentColor = WallHubColorTokens.mediaOverlayContent,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PlayArrow,
                                contentDescription = stringResource(R.string.detail_play_video),
                                modifier = Modifier.padding(WallHubSpacing.controlInset).size(34.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DetailDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = DETAIL_DIVIDER_ALPHA),
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun DetailTab(
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
