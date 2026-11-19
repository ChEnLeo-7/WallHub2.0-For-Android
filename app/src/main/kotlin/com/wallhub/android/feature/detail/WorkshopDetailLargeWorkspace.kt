package com.wallhub.android.feature.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.localizedAuthor
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.designsystem.wallHubStyle
import com.wallhub.android.core.format.formatByteSize
import com.wallhub.android.core.format.formatCompactCount
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopInteraction

internal val LARGE_DETAIL_WORKSPACE_MIN_WIDTH = 1280.dp
internal val LARGE_DETAIL_WORKSPACE_MIN_HEIGHT = 720.dp
private val LARGE_DETAIL_WORKSPACE_MAX_WIDTH = 1480.dp
private val LARGE_DETAIL_MEDIA_MAX_HEIGHT = 460.dp
private val LARGE_DETAIL_COMMENTS_WIDTH = 460.dp

internal fun useLargeMonetDetailWorkspace(
    bettboxSurfaces: Boolean,
    availableWidth: Dp,
    availableHeight: Dp,
): Boolean =
    bettboxSurfaces &&
        availableWidth >= LARGE_DETAIL_WORKSPACE_MIN_WIDTH &&
        availableHeight >= LARGE_DETAIL_WORKSPACE_MIN_HEIGHT

@Suppress("LongParameterList")
@Composable
internal fun WorkshopDetailLargeWorkspace(
    detail: WorkshopDetail,
    interaction: WorkshopInteraction,
    isLoadingInteraction: Boolean,
    isUpdatingInteraction: Boolean,
    isEnqueuingDownload: Boolean,
    canSubmitUnknownInteraction: Boolean,
    inlineVideoPlayback: SteamChunkPlayback?,
    inlineVideoPlaybackEnabled: Boolean,
    isLoadingInlineVideo: Boolean,
    inlineVideoError: DetailUiText?,
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
    onToggleSubscription: () -> Unit,
    onToggleFavorite: () -> Unit,
    onStartInlineVideo: () -> Unit,
    onRetryInlineVideo: () -> Unit,
    onInlineFullscreenChange: (Boolean) -> Unit,
    onShowDownloadChoices: () -> Unit,
    onCommentsVisible: () -> Unit,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onCommentComposerHeightChanged: (Int) -> Unit,
    onActionPaneHeightChanged: (Int) -> Unit,
    onCopyWorkshopId: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
    onSearchTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(detail.summary.id) { onCommentsVisible() }

    Row(
        modifier =
            modifier
                .widthIn(max = LARGE_DETAIL_WORKSPACE_MAX_WIDTH)
                .fillMaxSize()
                .padding(horizontal = WallHubSpacing.xl, vertical = WallHubSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.lg),
    ) {
        LargeDetailMediaPane(
            detail = detail,
            inlineVideoPlayback = inlineVideoPlayback,
            inlineVideoPlaybackEnabled = inlineVideoPlaybackEnabled,
            isLoadingInlineVideo = isLoadingInlineVideo,
            inlineVideoError = inlineVideoError,
            onStartInlineVideo = onStartInlineVideo,
            onRetryInlineVideo = onRetryInlineVideo,
            onInlineFullscreenChange = onInlineFullscreenChange,
            onCopyWorkshopId = onCopyWorkshopId,
            onSearchAuthor = onSearchAuthor,
            onSearchTag = onSearchTag,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        LargeDetailCommentsPane(
            interaction = interaction,
            isLoadingInteraction = isLoadingInteraction,
            isUpdatingInteraction = isUpdatingInteraction,
            isEnqueuingDownload = isEnqueuingDownload,
            canSubmitUnknownInteraction = canSubmitUnknownInteraction,
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
            onShowDownloadChoices = onShowDownloadChoices,
            onRetryComments = onRetryComments,
            onLoadMoreComments = onLoadMoreComments,
            onCommentDraftChanged = onCommentDraftChanged,
            onSubmitComment = onSubmitComment,
            onCommentComposerHeightChanged = onCommentComposerHeightChanged,
            onActionPaneHeightChanged = onActionPaneHeightChanged,
            modifier = Modifier.width(LARGE_DETAIL_COMMENTS_WIDTH).fillMaxHeight(),
        )
    }
}

@Composable
private fun LargeDetailMediaPane(
    detail: WorkshopDetail,
    inlineVideoPlayback: SteamChunkPlayback?,
    inlineVideoPlaybackEnabled: Boolean,
    isLoadingInlineVideo: Boolean,
    inlineVideoError: DetailUiText?,
    onStartInlineVideo: () -> Unit,
    onRetryInlineVideo: () -> Unit,
    onInlineFullscreenChange: (Boolean) -> Unit,
    onCopyWorkshopId: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
    onSearchTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
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
            maxHeight = LARGE_DETAIL_MEDIA_MAX_HEIGHT,
            contentScale = ContentScale.Fit,
        )
        LargeDetailInformationPanel(
            detail = detail,
            onCopyWorkshopId = onCopyWorkshopId,
            onSearchAuthor = onSearchAuthor,
            onSearchTag = onSearchTag,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
private fun LargeDetailInformationPanel(
    detail: WorkshopDetail,
    onCopyWorkshopId: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
    onSearchTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = detail.summary
    val author = summary.localizedAuthor()
    val description = detail.description.ifBlank { stringResource(R.string.detail_no_description) }
    var descriptionExpanded by rememberSaveable(summary.id, description) { mutableStateOf(false) }
    var descriptionCanExpand by rememberSaveable(summary.id, description) { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = wallHubStyle().surfaces.content,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(WallHubSpacing.md),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
        ) {
            item {
                ListItem(
                    headlineContent = {
                        Text(
                            text = author,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    overlineContent = { Text(stringResource(R.string.detail_author)) },
                    leadingContent = {
                        Surface(
                            modifier = Modifier.size(52.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = author.firstOrNull()?.uppercase() ?: "?",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                summary.authorAvatarUrl?.let { avatarUrl ->
                                    AsyncImage(
                                        model = avatarUrl,
                                        contentDescription = stringResource(R.string.detail_author_avatar, author),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable(role = Role.Button) {
                                onSearchAuthor(detail.creatorId ?: summary.author)
                            },
                )
            }
            item {
                Text(
                    text = summary.localizedTitle(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                DetailCompactMetrics(
                    metrics =
                        listOf(
                            DetailMetricValue(
                                Icons.Outlined.ContentCopy,
                                stringResource(R.string.detail_project_id),
                                summary.id.toString(),
                                onClick = { onCopyWorkshopId(summary.id) },
                            ),
                            DetailMetricValue(
                                Icons.Outlined.Download,
                                stringResource(R.string.detail_subscriptions),
                                formatCompactCount(detail.subscriptions ?: summary.subscriptions),
                            ),
                            DetailMetricValue(
                                Icons.Outlined.StarBorder,
                                stringResource(R.string.detail_favorites),
                                formatCompactCount(summary.favorites),
                            ),
                            DetailMetricValue(
                                Icons.Outlined.Visibility,
                                stringResource(R.string.detail_views),
                                formatCompactCount(summary.views),
                            ),
                            DetailMetricValue(
                                Icons.Outlined.Download,
                                stringResource(R.string.detail_file_size),
                                detail.fileSizeBytes?.let(::formatByteSize)
                                    ?: stringResource(R.string.detail_unknown),
                            ),
                            DetailMetricValue(
                                Icons.Outlined.Schedule,
                                stringResource(R.string.detail_last_updated),
                                formatWorkshopDate(detail.updatedAt),
                            ),
                            DetailMetricValue(
                                Icons.Outlined.Info,
                                stringResource(R.string.detail_type),
                                summary.type.label(),
                            ),
                        ),
                )
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs)) {
                    Text(
                        text = stringResource(R.string.detail_description),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().animateContentSize(),
                        maxLines = if (descriptionExpanded) Int.MAX_VALUE else DESCRIPTION_COLLAPSED_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            if (!descriptionExpanded) descriptionCanExpand = result.hasVisualOverflow
                        },
                    )
                    if (descriptionCanExpand || descriptionExpanded) {
                        TextButton(onClick = { descriptionExpanded = !descriptionExpanded }) {
                            Text(
                                text =
                                    stringResource(
                                        if (descriptionExpanded) {
                                            R.string.detail_show_less
                                        } else {
                                            R.string.detail_show_more
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
            if (summary.tags.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs)) {
                        Text(
                            text = stringResource(R.string.detail_tags),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                        ) {
                            summary.tags.forEach { tag ->
                                AssistChip(
                                    onClick = { onSearchTag(tag) },
                                    label = { Text(tag) },
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
private fun LargeDetailCommentsPane(
    interaction: WorkshopInteraction,
    isLoadingInteraction: Boolean,
    isUpdatingInteraction: Boolean,
    isEnqueuingDownload: Boolean,
    canSubmitUnknownInteraction: Boolean,
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
    onToggleSubscription: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShowDownloadChoices: () -> Unit,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onCommentComposerHeightChanged: (Int) -> Unit,
    onActionPaneHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = wallHubStyle().surfaces.content,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = WallHubSpacing.lg, top = WallHubSpacing.sm, end = WallHubSpacing.sm, bottom = WallHubSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        commentsTotal?.let { stringResource(R.string.detail_comments_count, it) }
                            ?: stringResource(R.string.detail_comments),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onRetryComments) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.detail_retry),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
                    isWallpaperHeaderCollapsed = false,
                    onReturnToWallpaperTop = {},
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DetailPaneActions(
                interaction = interaction,
                isUpdatingInteraction = isUpdatingInteraction,
                isEnqueuingDownload = isEnqueuingDownload,
                canSubmitUnknownInteraction = canSubmitUnknownInteraction,
                onToggleSubscription = onToggleSubscription,
                onToggleFavorite = onToggleFavorite,
                onDownload = onShowDownloadChoices,
                modifier =
                    Modifier
                        .padding(WallHubSpacing.md)
                        .onSizeChanged { size -> onActionPaneHeightChanged(size.height) },
            )
        }
    }
}
