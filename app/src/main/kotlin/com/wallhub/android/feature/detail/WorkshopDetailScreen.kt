@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.wallhub.android.feature.detail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.widget.Toast
import coil.compose.AsyncImage
import com.wallhub.android.NAVIGATION_EASING
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.LocalWallHubAdaptiveLayout
import com.wallhub.android.core.designsystem.WallHubColorTokens
import com.wallhub.android.core.designsystem.WallHubDividerVariant
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubHorizontalDivider
import com.wallhub.android.core.designsystem.WallHubShapeTokens
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.wallHubStyle
import com.wallhub.android.core.designsystem.localizedAuthor
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopType
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.uwuaosp.compose.settingslib.SettingsCollapsingAppBarScaffold
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

private const val DETAIL_PAGER_POSITIONAL_THRESHOLD = 0.65f

internal fun useExpandedDetailPane(layout: com.wallhub.android.core.designsystem.WallHubAdaptiveLayout): Boolean =
    layout.width == com.wallhub.android.core.designsystem.WallHubWindowLayout.EXPANDED

internal fun useShortHeightDetailLayout(layout: com.wallhub.android.core.designsystem.WallHubAdaptiveLayout): Boolean =
    layout.width == com.wallhub.android.core.designsystem.WallHubWindowLayout.EXPANDED &&
        layout.height != com.wallhub.android.core.designsystem.WallHubWindowHeight.EXPANDED

internal fun workshopDetailCoverMaxHeight(shortHeight: Boolean): Dp? =
    WallHubSizeTokens.shortHeightDetailCoverHeight.takeIf { shortHeight }

internal fun workshopDetailContentMaxWidth(
    bettboxSurfaces: Boolean,
    shortHeight: Boolean,
) = when {
    bettboxSurfaces -> WallHubSizeTokens.detailReadableMaxWidth
    shortHeight -> WallHubSizeTokens.shortHeightDetailMaxWidth
    else -> WallHubSizeTokens.readableContentMaxWidth
}

private enum class WorkshopDetailPresentation {
    Loading,
    Error,
    Content,
}

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
    onRetryInlineVideo: () -> Unit,
    onExportFormatSelected: (ExportFormat) -> Unit,
    onMpkgConversionPresetSelected: (MpkgConversionPreset) -> Unit,
    onPreserveAudioChanged: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCommentsVisible: () -> Unit,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onInlineFullscreenChange: (Boolean) -> Unit,
    shouldShowInlinePlaybackStartNotice: () -> Boolean = { true },
    onFullscreenPlaybackChanged: (Boolean) -> Unit = {},
    onSearchAuthor: (String) -> Unit,
    onSearchTag: (String) -> Unit,
    onCopyText: (String, String) -> Unit,
    onOpenSteam: (Long) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val selectedSummary = state.detail?.summary
    val unknown = stringResource(R.string.detail_unknown)
    val streamingStartedMessage = stringResource(R.string.detail_streaming_started)
    val steamCdnMessageTemplate = stringResource(R.string.detail_steam_cdn, "%s")
    val projectIdCopiedMessage = stringResource(R.string.detail_project_id_copied, selectedSummary?.id ?: 0L)
    val titleCopiedMessage = stringResource(R.string.detail_wallpaper_title_copied)
    val context = LocalContext.current
    val inlineVideoStream = state.inlineVideoStream
    val inlineFullscreen = state.isInlineVideoFullscreen
    val inlineRecoveryBudget =
        rememberSaveable(selectedSummary?.id, saver = StreamRecoveryBudgetSaver) {
            StreamRecoveryBudget()
        }
    val manualRetryInlineVideo = {
        inlineRecoveryBudget.resetForManualRetry()
        onRetryInlineVideo()
    }
    val inlinePlayback =
        inlineVideoStream?.let { stream ->
            state.inlineVideoPlayer?.let { player ->
                rememberRetainedSteamChunkPlayback(
                    stream = stream,
                    player = player,
                    onFirstFrameRendered = {
                        inlineRecoveryBudget.onFirstFrameRendered()
                        if (shouldShowInlinePlaybackStartNotice()) {
                            Toast.makeText(
                                context.applicationContext,
                                stream.currentCdnHost?.let { host -> steamCdnMessageTemplate.format(host) }
                                    ?: streamingStartedMessage,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }
    LaunchedEffect(inlineVideoStream, inlinePlayback?.rawError) {
        val playerError = inlinePlayback?.rawError ?: return@LaunchedEffect
        if (!inlineRecoveryBudget.tryAcquire(StreamPlaybackPath.INLINE, playerError)) return@LaunchedEffect
        onRetryInlineVideo()
    }
    BackHandler(enabled = inlineFullscreen) { onInlineFullscreenChange(false) }
    FullscreenSystemBarsEffect(enabled = inlineFullscreen)
    LaunchedEffect(inlineFullscreen) { onFullscreenPlaybackChanged(inlineFullscreen) }
    DisposableEffect(Unit) {
        onDispose { onFullscreenPlaybackChanged(false) }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (inlineFullscreen && inlinePlayback != null) {
            FullscreenWallpaperVideoPlayer(
                playback = inlinePlayback,
                onFullscreenChange = onInlineFullscreenChange,
                onRetry = manualRetryInlineVideo,
            )
        } else {
            val title = selectedSummary?.localizedTitle() ?: stringResource(R.string.detail_wallpaper_details)
            val expandedDetailPane = useExpandedDetailPane(LocalWallHubAdaptiveLayout.current)
            val style = wallHubStyle()
            val configuration = LocalConfiguration.current
            val largeMonetWorkspace =
                useLargeMonetDetailWorkspace(
                    bettboxSurfaces = style.capabilities.bettboxSurfaces,
                    availableWidth = configuration.screenWidthDp.dp,
                    availableHeight = configuration.screenHeightDp.dp,
                )
            MaterialTheme {
                val toolbarActions: @Composable RowScope.() -> Unit = {
                    selectedSummary?.let { workshop ->
                        IconButton(onClick = { onCopyText(title, titleCopiedMessage) }) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.detail_copy_title),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = { onOpenSteam(workshop.id) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = stringResource(R.string.detail_open_steam_page),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                val detailBody: @Composable (PaddingValues) -> Unit = { contentPadding ->
                    val presentation =
                        when {
                            state.isLoading -> WorkshopDetailPresentation.Loading
                            state.error != null -> WorkshopDetailPresentation.Error
                            else -> WorkshopDetailPresentation.Content
                        }
                    Crossfade(
                        targetState = presentation,
                        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        modifier = Modifier.fillMaxSize(),
                        label = "WorkshopDetailPresentation",
                    ) { targetPresentation ->
                        when (targetPresentation) {
                            WorkshopDetailPresentation.Loading -> {
                                Box(
                                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    androidx.compose.material3.CircularProgressIndicator()
                                }
                            }
                            WorkshopDetailPresentation.Error -> {
                                val resolvedError = requireNotNull(state.error).resolve()
                                val errorDetailsCopied = stringResource(R.string.detail_error_details_copied)
                                WallHubEmptyState(
                                    icon = Icons.Outlined.Refresh,
                                    title = resolvedError,
                                    actionLabel = stringResource(R.string.detail_retry),
                                    onAction = onRetry,
                                    secondaryActionLabel = stringResource(R.string.detail_copy_error_details),
                                    onSecondaryAction = { onCopyText(resolvedError, errorDetailsCopied) },
                                    tertiaryActionLabel = stringResource(R.string.detail_open_data_source_settings),
                                    onTertiaryAction = onOpenSettings,
                                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                                )
                            }
                            WorkshopDetailPresentation.Content ->
                                state.detail?.let { detail ->
                                    WorkshopDetailPagerContent(
                                        detail = detail,
                                        interaction = state.interaction,
                                        isLoadingInteraction = state.isLoadingInteraction,
                                        isUpdatingInteraction = state.isUpdatingInteraction,
                                        canSubmitUnknownInteraction = state.steamSession.phase == SteamSessionPhase.SIGNED_IN && state.steamSession.isConnected,
                                        interactionMessage = state.interactionMessage,
                                        steamSession = state.steamSession,
                                        onReconnectSteam = onReconnectSteam,
                                        onToggleSubscription = onToggleSubscription,
                                        onToggleFavorite = onToggleFavorite,
                                        inlineVideoPlayback = inlinePlayback,
                                        inlineVideoPlaybackEnabled = state.onlineChunkPlaybackEnabled,
                                        isLoadingInlineVideo = state.isLoadingInlineVideo,
                                        inlineVideoError = state.inlineVideoError,
                                        onStartInlineVideo = onStartInlineVideo,
                                        onRetryInlineVideo = manualRetryInlineVideo,
                                        onInlineFullscreenChange = onInlineFullscreenChange,
                                        onExportFormatSelected = onExportFormatSelected,
                                        mpkgConversionPreset = state.mpkgConversionPreset,
                                        onMpkgConversionPresetSelected = onMpkgConversionPresetSelected,
                                        preserveAudio = state.preserveAudio,
                                        onPreserveAudioChanged = onPreserveAudioChanged,
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
                                        onCommentsVisible = onCommentsVisible,
                                        onRetryComments = onRetryComments,
                                        onLoadMoreComments = onLoadMoreComments,
                                        onCommentDraftChanged = onCommentDraftChanged,
                                        onSubmitComment = onSubmitComment,
                                        onCopyWorkshopId = { id -> onCopyText(id.toString(), projectIdCopiedMessage) },
                                        onSearchAuthor = onSearchAuthor,
                                        onSearchTag = onSearchTag,
                                        onOpenDownloads = onOpenDownloads,
                                        modifier = Modifier.fillMaxSize().padding(contentPadding),
                                    )
                                }
                        }
                    }
                }
                if (expandedDetailPane) {
                    Column(modifier = Modifier.fillMaxSize().background(style.surfaces.page)) {
                        DetailExpandedToolbar(
                            title = title,
                            onNavigateUp = onBack,
                            containerColor = style.surfaces.page,
                            actions = toolbarActions,
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            detailBody(PaddingValues())
                        }
                    }
                } else {
                    SettingsCollapsingAppBarScaffold(
                        title = title,
                        showBackButton = true,
                        onNavigateUp = onBack,
                        expandedToolbarHeight = if (largeMonetWorkspace) 64.dp else 200.dp,
                        containerColor = style.surfaces.page,
                        actions = toolbarActions,
                        content = { padding -> detailBody(padding) },
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
    onRetry: () -> Unit,
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
            onRetry = onRetry,
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
    interaction: WorkshopInteraction,
    isLoadingInteraction: Boolean,
    isUpdatingInteraction: Boolean,
    canSubmitUnknownInteraction: Boolean,
    interactionMessage: DetailUiText?,
    steamSession: SteamSessionState,
    onReconnectSteam: () -> Unit,
    onToggleSubscription: () -> Unit,
    onToggleFavorite: () -> Unit,
    inlineVideoPlayback: SteamChunkPlayback?,
    inlineVideoPlaybackEnabled: Boolean,
    isLoadingInlineVideo: Boolean,
    inlineVideoError: DetailUiText?,
    onStartInlineVideo: () -> Unit,
    onRetryInlineVideo: () -> Unit,
    onInlineFullscreenChange: (Boolean) -> Unit,
    onExportFormatSelected: (ExportFormat) -> Unit,
    mpkgConversionPreset: MpkgConversionPreset,
    onMpkgConversionPresetSelected: (MpkgConversionPreset) -> Unit,
    preserveAudio: Boolean,
    onPreserveAudioChanged: (Boolean) -> Unit,
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
    onCommentsVisible: () -> Unit,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onCopyWorkshopId: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
    onSearchTag: (String) -> Unit,
    onOpenDownloads: () -> Unit,
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
    LaunchedEffect(pagerState.currentPage, summary.id) {
        if (pagerState.currentPage == DETAIL_COMMENTS_PAGE) {
            onCommentsVisible()
        } else {
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
        inlineVideoPlaybackEnabled = inlineVideoPlaybackEnabled,
        isLoadingInlineVideo = isLoadingInlineVideo,
        inlineVideoError = inlineVideoError,
        onStartInlineVideo = onStartInlineVideo,
        onRetryInlineVideo = onRetryInlineVideo,
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
        onCommentsVisible = onCommentsVisible,
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
        canSubmitUnknownInteraction = canSubmitUnknownInteraction,
        downloadMessage = downloadMessage,
        onToggleSubscription = onToggleSubscription,
        onToggleFavorite = onToggleFavorite,
        onShowDownloadChoices = { showDownloadChoices = true },
        onCopyWorkshopId = onCopyWorkshopId,
        onSearchAuthor = onSearchAuthor,
        onSearchTag = onSearchTag,
        onOpenDownloads = onOpenDownloads,
        modifier = modifier,
    )
    if (showDownloadChoices) {
        DownloadChoiceSheet(
            type = summary.type,
            exportFormats = exportFormats,
            mpkgConversionPreset = mpkgConversionPreset,
            onMpkgConversionPresetSelected = onMpkgConversionPresetSelected,
            preserveAudio = preserveAudio,
            onPreserveAudioChanged = onPreserveAudioChanged,
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
private fun WorkshopDetailPane(
    detail: WorkshopDetail,
    pagerState: androidx.compose.foundation.pager.PagerState,
    nestedScrollConnection: NestedScrollConnection,
    headerOffsetPx: Float,
    onHeaderOffsetChange: (Float) -> Unit,
    onHeaderHeightChanged: (Int) -> Unit,
    onCoverHeightChanged: (Int) -> Unit,
    inlineVideoPlayback: SteamChunkPlayback?,
    inlineVideoPlaybackEnabled: Boolean,
    isLoadingInlineVideo: Boolean,
    inlineVideoError: DetailUiText?,
    onStartInlineVideo: () -> Unit,
    onRetryInlineVideo: () -> Unit,
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
    onCommentsVisible: () -> Unit,
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
    canSubmitUnknownInteraction: Boolean,
    downloadMessage: DetailUiText?,
    onOpenDownloads: () -> Unit,
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
    val viewDownloadsActionLabel = stringResource(R.string.downloads_view_queue)
    val currentOnOpenDownloads by rememberUpdatedState(onOpenDownloads)
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
    val canOpenDownloads =
        resolvedDownloadMessage != null &&
            !isLoadingInteraction &&
            !isUpdatingInteraction &&
            !canReconnect &&
            resolvedInteractionMessage == null &&
            downloadMessage is DetailUiText.Resource &&
            downloadMessage.resourceId in
                setOf(
                    R.string.detail_added_to_download_queue,
                    R.string.detail_video_queued_for_playback,
                )
    val snackbarDuration =
        if (isLoadingInteraction || isUpdatingInteraction || canReconnect) {
            SnackbarDuration.Indefinite
        } else if (canOpenDownloads) {
            SnackbarDuration.Long
        } else {
            SnackbarDuration.Short
        }
    val isDownloadNotice =
        resolvedDownloadMessage != null &&
            !isLoadingInteraction &&
            !isUpdatingInteraction &&
            !canReconnect &&
            resolvedInteractionMessage == null
    LaunchedEffect(snackbarMessage, snackbarDuration, canReconnect, canOpenDownloads, isDownloadNotice) {
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarMessage?.let { message ->
            val showNotice: suspend () -> SnackbarResult = {
                snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel =
                        when {
                            canReconnect -> reconnectActionLabel
                            canOpenDownloads -> viewDownloadsActionLabel
                            else -> null
                        },
                    duration = if (isDownloadNotice) SnackbarDuration.Indefinite else snackbarDuration,
                )
            }
            val result =
                if (isDownloadNotice) {
                    withTimeoutOrNull(2_000L) { showNotice() }
                } else {
                    showNotice()
                }
            if (result == SnackbarResult.ActionPerformed) {
                when {
                    canReconnect -> onReconnectSteam()
                    canOpenDownloads -> currentOnOpenDownloads()
                }
            }
        }
    }
    var actionBarHeightPx by remember { mutableIntStateOf(0) }
    var commentComposerHeightPx by remember { mutableIntStateOf(0) }
    val actionBarHeight = with(LocalDensity.current) { actionBarHeightPx.toDp() }
    val commentComposerHeight = with(LocalDensity.current) { commentComposerHeightPx.toDp() }
    val adaptiveLayout = LocalWallHubAdaptiveLayout.current
    val shortHeightDetail = useShortHeightDetailLayout(adaptiveLayout)
    val coverMaxHeight = workshopDetailCoverMaxHeight(shortHeightDetail)
    val contentMaxWidth =
        workshopDetailContentMaxWidth(
            bettboxSurfaces = wallHubStyle().capabilities.bettboxSurfaces,
            shortHeight = shortHeightDetail,
        )

    val expandedDetailPane = useExpandedDetailPane(adaptiveLayout)

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .then(if (expandedDetailPane) Modifier else Modifier.nestedScroll(nestedScrollConnection)),
    ) {
        val largeMonetWorkspace =
            useLargeMonetDetailWorkspace(
                bettboxSurfaces = wallHubStyle().capabilities.bettboxSurfaces,
                availableWidth = maxWidth,
                availableHeight = maxHeight,
            )
        if (largeMonetWorkspace) {
            WorkshopDetailLargeWorkspace(
                detail = detail,
                interaction = interaction,
                isLoadingInteraction = isLoadingInteraction,
                isUpdatingInteraction = isUpdatingInteraction,
                isEnqueuingDownload = isEnqueuingDownload,
                canSubmitUnknownInteraction = canSubmitUnknownInteraction,
                inlineVideoPlayback = inlineVideoPlayback,
                inlineVideoPlaybackEnabled = inlineVideoPlaybackEnabled,
                isLoadingInlineVideo = isLoadingInlineVideo,
                inlineVideoError = inlineVideoError,
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
                onToggleSubscription = onToggleSubscription,
                onToggleFavorite = onToggleFavorite,
                onStartInlineVideo = onStartInlineVideo,
                onRetryInlineVideo = onRetryInlineVideo,
                onInlineFullscreenChange = onInlineFullscreenChange,
                onShowDownloadChoices = onShowDownloadChoices,
                onCommentsVisible = onCommentsVisible,
                onRetryComments = onRetryComments,
                onLoadMoreComments = onLoadMoreComments,
                onCommentDraftChanged = onCommentDraftChanged,
                onSubmitComment = onSubmitComment,
                onCommentComposerHeightChanged = { commentComposerHeightPx = it },
                onActionPaneHeightChanged = { actionBarHeightPx = it },
                onCopyWorkshopId = onCopyWorkshopId,
                onSearchAuthor = onSearchAuthor,
                onSearchTag = onSearchTag,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        } else if (expandedDetailPane) {
            Row(
                modifier =
                    Modifier
                        .widthIn(max = 1480.dp)
                        .fillMaxSize()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = WallHubSpacing.xl, vertical = WallHubSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.lg),
            ) {
                Column(
                    modifier = Modifier.weight(0.58f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Top,
                ) {
                    DetailCover(
                        title = detail.summary.localizedTitle(),
                        previewUrl = detail.summary.previewUrl,
                        type = detail.summary.type,
                        playback = inlineVideoPlayback,
                        playbackEnabled = inlineVideoPlaybackEnabled,
                        isLoadingInlineVideo = isLoadingInlineVideo,
                        inlineVideoError = inlineVideoError,
                        onStartInlineVideo = onStartInlineVideo,
                        onRetryInlineVideo = onRetryInlineVideo,
                        onFullscreenChange = onInlineFullscreenChange,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = wallHubStyle().dividers.subtle,
                )
                Column(
                    modifier = Modifier.weight(0.42f).fillMaxHeight(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.xs),
                        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                    ) {
                        Text(
                            text = detail.summary.localizedTitle(),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = detail.summary.localizedAuthor(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                        isWallpaperHeaderCollapsed = false,
                        onReturnToWallpaperTop = {},
                        onCommentsVisible = onCommentsVisible,
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
                        isUpdatingInteraction = isUpdatingInteraction,
                        isEnqueuingDownload = isEnqueuingDownload,
                        canSubmitUnknownInteraction = canSubmitUnknownInteraction,
                        onToggleSubscription = onToggleSubscription,
                        onToggleFavorite = onToggleFavorite,
                        onDownload = onShowDownloadChoices,
                        modifier = Modifier.onSizeChanged { size -> actionBarHeightPx = size.height },
                    )
                }
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = contentMaxWidth)
                        .fillMaxSize()
                        .align(Alignment.TopCenter),
            ) {
                WorkshopDetailCollapsibleHeader(
                    detail = detail,
                    offsetPx = headerOffsetPx,
                    onHeaderHeightChanged = onHeaderHeightChanged,
                    onCoverHeightChanged = onCoverHeightChanged,
                    coverMaxHeight = coverMaxHeight,
                    inlineVideoPlayback = inlineVideoPlayback,
                    inlineVideoPlaybackEnabled = inlineVideoPlaybackEnabled,
                    isLoadingInlineVideo = isLoadingInlineVideo,
                    inlineVideoError = inlineVideoError,
                    onStartInlineVideo = onStartInlineVideo,
                    onRetryInlineVideo = onRetryInlineVideo,
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
                    onCommentsVisible = onCommentsVisible,
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
                    isUpdatingInteraction = isUpdatingInteraction,
                    isEnqueuingDownload = isEnqueuingDownload,
                    canSubmitUnknownInteraction = canSubmitUnknownInteraction,
                    onToggleSubscription = onToggleSubscription,
                    onToggleFavorite = onToggleFavorite,
                    onDownload = onShowDownloadChoices,
                    modifier = Modifier.onSizeChanged { size -> actionBarHeightPx = size.height },
                )
            }
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
private fun WorkshopDetailCollapsibleHeader(
    detail: WorkshopDetail,
    offsetPx: Float,
    onHeaderHeightChanged: (Int) -> Unit,
    onCoverHeightChanged: (Int) -> Unit,
    coverMaxHeight: Dp?,
    inlineVideoPlayback: SteamChunkPlayback?,
    inlineVideoPlaybackEnabled: Boolean,
    isLoadingInlineVideo: Boolean,
    inlineVideoError: DetailUiText?,
    onStartInlineVideo: () -> Unit,
    onRetryInlineVideo: () -> Unit,
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
            playbackEnabled = inlineVideoPlaybackEnabled,
            isLoadingInlineVideo = isLoadingInlineVideo,
            inlineVideoError = inlineVideoError,
            onStartInlineVideo = onStartInlineVideo,
            onRetryInlineVideo = onRetryInlineVideo,
            onFullscreenChange = onInlineFullscreenChange,
            maxHeight = coverMaxHeight,
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
    onCommentsVisible: () -> Unit,
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
    val detailStyle = wallHubStyle()
    PrimaryTabRow(
        selectedTabIndex = pagerState.currentPage,
        containerColor = detailStyle.surfaces.page,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = { if (detailStyle.dividers.showTabDivider) DetailDivider() },
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
        flingBehavior =
            PagerDefaults.flingBehavior(
                state = pagerState,
                snapPositionalThreshold = DETAIL_PAGER_POSITIONAL_THRESHOLD,
            ),
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
private fun CollapsibleDetailHeader(
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
private fun DetailExpandedToolbar(
    title: String,
    onNavigateUp: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(72.dp).background(containerColor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onNavigateUp) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.detail_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(horizontal = WallHubSpacing.md),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
internal fun DetailCover(
    title: String,
    previewUrl: String?,
    type: WorkshopType,
    playback: SteamChunkPlayback?,
    playbackEnabled: Boolean,
    isLoadingInlineVideo: Boolean,
    inlineVideoError: DetailUiText?,
    onStartInlineVideo: () -> Unit,
    onRetryInlineVideo: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    maxHeight: Dp? = null,
    contentScale: ContentScale = ContentScale.Crop,
    modifier: Modifier = Modifier,
) {
    val coverStyle = wallHubStyle()
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    maxHeight?.let { Modifier.height(it) }
                        ?: Modifier.aspectRatio(16f / 9f),
                )
                .clip(coverStyle.shapes.detailHero)
                .background(coverStyle.surfaces.mediaPlaceholder),
        contentAlignment = Alignment.Center,
    ) {
        if (playback != null) {
            SteamChunkVideoPlayer(
                playback = playback,
                fullscreen = false,
                onFullscreenChange = onFullscreenChange,
                onRetry = onRetryInlineVideo,
                modifier = Modifier.matchParentSize(),
                inlineCornerRadius = coverStyle.shapes.detailHeroRadius,
            )
            if (!playback.renderedFirstFrame && playback.error == null && previewUrl != null) {
                AsyncImage(
                    model = previewUrl,
                    contentDescription = null,
                    contentScale = contentScale,
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
                    contentScale = contentScale,
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
        if (playbackEnabled && type == WorkshopType.VIDEO && playback == null) {
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
private fun DetailDivider() {
    WallHubHorizontalDivider(variant = WallHubDividerVariant.Subtle)
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
