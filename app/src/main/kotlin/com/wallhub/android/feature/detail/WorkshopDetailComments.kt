@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubFabActiveElevation
import com.wallhub.android.core.designsystem.WallHubFabDefaultElevation
import com.wallhub.android.core.designsystem.WallHubSecondaryButton
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.localizedAuthor
import com.wallhub.android.core.model.WorkshopComment
import kotlinx.coroutines.launch
import org.uwuaosp.compose.settingslib.CustomPreferenceRow
import org.uwuaosp.compose.settingslib.PreferencePosition
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerticalAlignTop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailCommentsPage(
    comments: List<WorkshopComment>,
    commentsHasMore: Boolean,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    error: DetailUiText?,
    canPostComment: Boolean,
    commentDraft: String,
    isPostingComment: Boolean,
    commentPostError: DetailUiText?,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onCommentDraftChanged: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onComposerHeightChanged: (Int) -> Unit,
    isWallpaperHeaderCollapsed: Boolean,
    onReturnToWallpaperTop: () -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var commentsBoundsInWindow by remember { mutableStateOf(Rect.Zero) }
    var composerBoundsInWindow by remember { mutableStateOf(Rect.Zero) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val composerHeight = with(LocalDensity.current) { composerHeightPx.toDp() }
    val bottomContentPadding = maxOf(88.dp, composerHeight + WallHubSpacing.md)
    DisposableEffect(Unit) {
        onDispose { onComposerHeightChanged(0) }
    }
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
        modifier =
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { commentsBoundsInWindow = it.boundsInWindow() }
                .pointerInput(commentsBoundsInWindow, composerBoundsInWindow) {
                    awaitEachGesture {
                        val down =
                            awaitFirstDown(
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
                contentPadding =
                    androidx.compose.foundation.layout.PaddingValues(
                        start = WallHubSpacing.md,
                        top = WallHubSpacing.md,
                        end = WallHubSpacing.md,
                        bottom = bottomContentPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxxs),
            ) {
                when {
                    isLoading && comments.isEmpty() ->
                        item {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 56.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.CircularProgressIndicator()
                            }
                        }

                    error != null && comments.isEmpty() ->
                        item {
                            WallHubEmptyState(
                                icon = Icons.Outlined.Refresh,
                                title = error.resolve(),
                                actionLabel = stringResource(R.string.detail_retry),
                                onAction = onRetry,
                            )
                        }

                    comments.isEmpty() ->
                        item {
                            WallHubEmptyState(
                                icon = Icons.Outlined.ChatBubbleOutline,
                                title = stringResource(R.string.detail_no_comments),
                            )
                        }

                    else -> {
                        itemsIndexed(
                            items = comments,
                            key = { _, comment ->
                                listOf(comment.author, comment.timestamp, comment.text).joinToString("|")
                            },
                        ) { index, comment ->
                            WorkshopCommentItem(
                                comment = comment,
                                position =
                                    when {
                                        comments.size == 1 -> PreferencePosition.Single
                                        index == 0 -> PreferencePosition.Top
                                        index == comments.lastIndex -> PreferencePosition.Bottom
                                        else -> PreferencePosition.Middle
                                    },
                            )
                        }
                        if (error != null) {
                            item {
                                WallHubSecondaryButton(
                                    onClick = onLoadMore,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null)
                                    Text(
                                        text = stringResource(R.string.detail_retry_loading_more_comments),
                                        modifier = Modifier.padding(start = WallHubSpacing.xs),
                                    )
                                }
                            }
                        } else if (commentsHasMore) {
                            item {
                                LaunchedEffect(comments.size, commentsHasMore) {
                                    onLoadMore()
                                }
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(52.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isLoadingMore) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(WallHubSpacing.lg),
                                            strokeWidth = WallHubSpacing.xxxs,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (canPostComment) {
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onSizeChanged { size ->
                            composerHeightPx = size.height
                            onComposerHeightChanged(size.height)
                        }
                        .onGloballyPositioned { composerBoundsInWindow = it.boundsInWindow() },
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                CommentComposer(
                    value = commentDraft,
                    isPosting = isPostingComment,
                    error = commentPostError,
                    onValueChange = onCommentDraftChanged,
                    onSubmit = onSubmitComment,
                    modifier =
                        Modifier.padding(
                            start = WallHubSpacing.md,
                            top = WallHubSpacing.xs,
                            end = WallHubSpacing.md,
                            bottom = WallHubSpacing.xs,
                        ),
                )
            }
        }
        AnimatedVisibility(
            visible = showScrollToTop,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = WallHubSpacing.md, bottom = composerHeight + WallHubSpacing.md),
            enter = fadeIn() + scaleIn(initialScale = 0.88f),
            exit = fadeOut() + scaleOut(targetScale = 0.88f),
        ) {
            FloatingActionButton(
                onClick = {
                    focusManager.clearFocus(force = true)
                    onReturnToWallpaperTop()
                    coroutineScope.launch { listState.animateScrollToItem(0) }
                },
                elevation =
                    FloatingActionButtonDefaults.elevation(
                        defaultElevation = WallHubFabDefaultElevation,
                        pressedElevation = WallHubFabActiveElevation,
                        focusedElevation = WallHubFabDefaultElevation,
                        hoveredElevation = WallHubFabActiveElevation,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.VerticalAlignTop,
                    contentDescription = stringResource(R.string.detail_back_to_wallpaper_top),
                )
            }
        }
    }
}

@Composable
internal fun CommentComposer(
    value: String,
    isPosting: Boolean,
    error: DetailUiText?,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.detail_write_comment)) },
            supportingText =
                error?.let { message ->
                    { Text(message.resolve()) }
                },
            isError = error != null,
            enabled = !isPosting,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions =
                KeyboardActions(
                    onSend = {
                        if (value.isNotBlank() && !isPosting) {
                            focusManager.clearFocus(force = true)
                            onSubmit()
                        }
                    },
                ),
            minLines = 1,
            maxLines = 4,
        )
        FilledIconButton(
            onClick = {
                focusManager.clearFocus(force = true)
                onSubmit()
            },
            enabled = value.isNotBlank() && !isPosting,
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
        ) {
            if (isPosting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(WallHubSizeTokens.smallIcon),
                    strokeWidth = WallHubSpacing.xxxs,
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.detail_post_comment),
                )
            }
        }
    }
}

@Composable
internal fun WorkshopCommentItem(
    comment: WorkshopComment,
    position: PreferencePosition,
) {
    val author = comment.localizedAuthor()
    CustomPreferenceRow(
        modifier = Modifier.fillMaxWidth(),
        position = position,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = WallHubSpacing.controlInset),
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(WallHubSizeTokens.compactIconButton),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = author.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    comment.avatarUrl?.let { avatarUrl ->
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription =
                                stringResource(R.string.detail_author_avatar, author),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.dense),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.dense),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = author,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (comment.isCreator) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) {
                                Text(
                                    text = stringResource(R.string.detail_creator),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = WallHubSpacing.dense, vertical = WallHubSpacing.xxxs),
                                )
                            }
                        }
                    }
                    Text(
                        text = formatCommentDate(comment),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = WallHubSpacing.xs),
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
