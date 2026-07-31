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
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wallhub.android.core.designsystem.LocalWallHubLanguage
import com.wallhub.android.core.designsystem.WallHubColorTokens
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubShapeTokens
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubToastHost
import com.wallhub.android.core.designsystem.text
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

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
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onInlineFullscreenChange: (Boolean) -> Unit,
    onSearchAuthor: (String) -> Unit,
    onCopyText: (String, String) -> Unit,
    onOpenSteam: (Long) -> Unit,
) {
    val language = LocalWallHubLanguage.current
    val selectedSummary = state.detail?.summary
    var toastMessage by remember { mutableStateOf<String?>(null) }
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
                    WorkshopDetailTopBar(
                        summary = selectedSummary,
                        language = language,
                        onBack = onBack,
                        onCopyText = onCopyText,
                        onOpenSteam = onOpenSteam,
                    )
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
                            title = state.error,
                            actionLabel = language.text("重试", "Retry"),
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
                                    language.text("已复制项目 ID：$id", "Project ID copied: $id"),
                                )
                            },
                            onSearchAuthor = onSearchAuthor,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkshopDetailTopBar(
    summary: WorkshopSummary?,
    language: AppLanguage,
    onBack: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onOpenSteam: (Long) -> Unit,
) {
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
                    text = summary?.title ?: language.text("壁纸详情", "Wallpaper details"),
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                summary?.let { workshop ->
                    IconButton(
                        onClick = {
                            onCopyText(
                                workshop.title,
                                language.text("已复制壁纸标题", "Wallpaper title copied"),
                            )
                        },
                        modifier = Modifier.size(WallHubSizeTokens.compactActionHeight),
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
            summary?.let { workshop ->
                IconButton(onClick = { onOpenSteam(workshop.id) }) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = language.text("打开 Steam 页面", "Open Steam page"),
                    )
                }
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )
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
    ExperimentalMaterial3AdaptiveApi::class,
)
@Composable
internal fun WorkshopDetailPagerContent(
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
    onExportFormatSelected: (ExportFormat) -> Unit,
    isEnqueuingDownload: Boolean,
    downloadMessage: String?,
    onDownload: () -> Unit,
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
    val pagerState =
        rememberPagerState(initialPage = DETAIL_OVERVIEW_PAGE) {
            DETAIL_PAGE_COUNT
        }
    // Start on Detail for compact screens while retaining List history for wider layouts.
    // This avoids a transient list-only frame before the navigation coroutine runs.
    val paneNavigator =
        rememberListDetailPaneScaffoldNavigator(
            initialDestinationHistory =
                listOf(
                    ThreePaneScaffoldDestinationItem<Int>(ListDetailPaneScaffoldRole.List),
                    ThreePaneScaffoldDestinationItem(
                        ListDetailPaneScaffoldRole.Detail,
                        DETAIL_OVERVIEW_PAGE,
                    ),
                ),
        )
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
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
    LaunchedEffect(summary.id) {
        if (paneNavigator.currentDestination?.contentKey != DETAIL_OVERVIEW_PAGE) {
            paneNavigator.navigateTo(
                ListDetailPaneScaffoldRole.Detail,
                DETAIL_OVERVIEW_PAGE,
            )
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        paneNavigator.navigateTo(
            ListDetailPaneScaffoldRole.Detail,
            pagerState.currentPage,
        )
    }
    LaunchedEffect(paneNavigator.currentDestination?.contentKey) {
        paneNavigator.currentDestination?.contentKey?.let { destinationPage ->
            if (destinationPage != pagerState.currentPage) {
                pagerState.animateScrollToPage(destinationPage)
            }
        }
    }
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
    NavigableListDetailPaneScaffold(
        navigator = paneNavigator,
        modifier = modifier,
        listPane = {
            AnimatedPane {
                WorkshopDetailSectionList(
                    selectedPage = pagerState.currentPage,
                    commentsTotal = commentsTotal,
                    language = language,
                    onPageSelected = { page ->
                        coroutineScope.launch {
                            paneNavigator.navigateTo(ListDetailPaneScaffoldRole.Detail, page)
                        }
                    },
                )
            }
        },
        detailPane = {
            AnimatedPane {
                WorkshopDetailPane(
                    detail = detail,
                    language = language,
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
                    isEnqueuingDownload = isEnqueuingDownload,
                    downloadMessage = downloadMessage,
                    onToggleSubscription = onToggleSubscription,
                    onToggleFavorite = onToggleFavorite,
                    onShowDownloadChoices = { showDownloadChoices = true },
                    onCopyWorkshopId = onCopyWorkshopId,
                    onSearchAuthor = onSearchAuthor,
                )
            }
        },
    )
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

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
internal fun WorkshopDetailPane(
    detail: WorkshopDetail,
    language: AppLanguage,
    pagerState: androidx.compose.foundation.pager.PagerState,
    nestedScrollConnection: NestedScrollConnection,
    headerOffsetPx: Float,
    onHeaderOffsetChange: (Float) -> Unit,
    onHeaderHeightChanged: (Int) -> Unit,
    onCoverHeightChanged: (Int) -> Unit,
    inlineVideoPlayback: SteamChunkPlayback?,
    isLoadingInlineVideo: Boolean,
    inlineVideoError: String?,
    onStartInlineVideo: () -> Unit,
    onInlineFullscreenChange: (Boolean) -> Unit,
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
    interaction: WorkshopInteraction,
    isLoadingInteraction: Boolean,
    isUpdatingInteraction: Boolean,
    interactionMessage: String?,
    isEnqueuingDownload: Boolean,
    downloadMessage: String?,
    onToggleSubscription: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShowDownloadChoices: () -> Unit,
    onCopyWorkshopId: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().nestedScroll(nestedScrollConnection)) {
        WorkshopDetailCollapsibleHeader(
            detail = detail,
            language = language,
            offsetPx = headerOffsetPx,
            onHeaderHeightChanged = onHeaderHeightChanged,
            onCoverHeightChanged = onCoverHeightChanged,
            inlineVideoPlayback = inlineVideoPlayback,
            isLoadingInlineVideo = isLoadingInlineVideo,
            inlineVideoError = inlineVideoError,
            onStartInlineVideo = onStartInlineVideo,
            onInlineFullscreenChange = onInlineFullscreenChange,
            onCopyWorkshopId = onCopyWorkshopId,
            onSearchAuthor = onSearchAuthor,
        )
        WorkshopDetailTabPager(
            detail = detail,
            language = language,
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
        )
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
            onDownload = onShowDownloadChoices,
        )
    }
}

@Composable
internal fun WorkshopDetailCollapsibleHeader(
    detail: WorkshopDetail,
    language: AppLanguage,
    offsetPx: Float,
    onHeaderHeightChanged: (Int) -> Unit,
    onCoverHeightChanged: (Int) -> Unit,
    inlineVideoPlayback: SteamChunkPlayback?,
    isLoadingInlineVideo: Boolean,
    inlineVideoError: String?,
    onStartInlineVideo: () -> Unit,
    onInlineFullscreenChange: (Boolean) -> Unit,
    onCopyWorkshopId: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
) {
    val summary = detail.summary
    CollapsibleDetailHeader(
        offsetPx = offsetPx,
        onHeightChanged = onHeaderHeightChanged,
    ) {
        FlowRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = WallHubSpacing.md, end = WallHubSpacing.md, bottom = WallHubSpacing.compact),
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
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
                onClick = { onSearchAuthor(detail.creatorId ?: summary.author) },
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
    language: AppLanguage,
    pagerState: androidx.compose.foundation.pager.PagerState,
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
    isWallpaperHeaderCollapsed: Boolean,
    onReturnToWallpaperTop: () -> Unit,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    PrimaryTabRow(
        selectedTabIndex = pagerState.currentPage,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = { DetailDivider() },
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
        val commentsLabel =
            commentsTotal?.takeIf { it > 0 }?.let { total ->
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
                    language = language,
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
                    language = language,
                    onRetry = onRetryComments,
                    onLoadMore = onLoadMoreComments,
                    onCommentDraftChanged = onCommentDraftChanged,
                    onSubmitComment = onSubmitComment,
                    isWallpaperHeaderCollapsed = isWallpaperHeaderCollapsed,
                    onReturnToWallpaperTop = onReturnToWallpaperTop,
                )
        }
    }
}

@Composable
internal fun WorkshopDetailSectionList(
    selectedPage: Int,
    commentsTotal: Int?,
    language: AppLanguage,
    onPageSelected: (Int) -> Unit,
) {
    val commentsLabel =
        commentsTotal?.takeIf { it > 0 }?.let { total ->
            language.text("评论 ($total)", "Comments ($total)")
        } ?: language.text("评论", "Comments")
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(WallHubSpacing.md),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
    ) {
        Text(
            text = language.text("详情导航", "Details navigation"),
            style = MaterialTheme.typography.titleMedium,
        )
        DetailSectionListItem(
            label = language.text("详情", "Details"),
            selected = selectedPage == DETAIL_OVERVIEW_PAGE,
            onClick = { onPageSelected(DETAIL_OVERVIEW_PAGE) },
        )
        DetailSectionListItem(
            label = commentsLabel,
            selected = selectedPage == DETAIL_COMMENTS_PAGE,
            onClick = { onPageSelected(DETAIL_COMMENTS_PAGE) },
        )
    }
}

@Composable
internal fun DetailSectionListItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color =
            if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        contentColor =
            if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier =
                Modifier.padding(
                    horizontal = WallHubSpacing.sm,
                    vertical = WallHubSpacing.controlInset,
                ),
        )
    }
}

@Composable
internal fun DetailIdentityChip(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = WallHubSpacing.compact, vertical = WallHubSpacing.dense),
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.dense),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(WallHubSpacing.controlInset),
            )
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            content = content,
        )
    } else {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            content = content,
        )
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
    language: AppLanguage,
    playback: SteamChunkPlayback?,
    isLoadingInlineVideo: Boolean,
    inlineVideoError: String?,
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
                    contentDescription = language.text(title + " 预览图", title + " preview"),
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
                            onClickLabel = language.text("播放视频", "Play video"),
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
                                text = inlineVideoError,
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
                                contentDescription = language.text("播放视频", "Play video"),
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
