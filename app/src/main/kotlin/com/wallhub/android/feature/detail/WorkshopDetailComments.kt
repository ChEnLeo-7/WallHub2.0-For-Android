@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubFabActiveElevation
import com.wallhub.android.core.designsystem.WallHubFabDefaultElevation
import com.wallhub.android.core.designsystem.WallHubSecondaryButton
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.text
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.WorkshopComment
import kotlinx.coroutines.launch
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailCommentsPage(
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
                        bottom = 88.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.compact),
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
                            modifier =
                                Modifier.onGloballyPositioned {
                                    composerBoundsInWindow = it.boundsInWindow()
                                },
                        )
                    }
                }
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
                                title = error,
                                actionLabel = language.text("重试", "Retry"),
                                onAction = onRetry,
                            )
                        }

                    comments.isEmpty() ->
                        item {
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
        AnimatedVisibility(
            visible = showScrollToTop,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(WallHubSpacing.md),
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
                    contentDescription =
                        language.text(
                            "回到 Wallpaper 顶部",
                            "Back to wallpaper top",
                        ),
                )
            }
        }
    }
}

@Composable
internal fun CommentComposer(
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
                        modifier = Modifier.size(WallHubSizeTokens.smallIcon),
                        strokeWidth = WallHubSpacing.xxxs,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Send,
                        contentDescription = language.text("发表评论", "Post comment"),
                    )
                }
            }
        },
        supportingText =
            error?.let { message ->
                { Text(message) }
            },
        isError = error != null,
        enabled = !isPosting,
        minLines = 1,
        maxLines = 4,
    )
}

@Composable
internal fun WorkshopCommentItem(
    comment: WorkshopComment,
    language: AppLanguage,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(WallHubSpacing.controlInset),
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
                        text = comment.author.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    comment.avatarUrl?.let { avatarUrl ->
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription =
                                language.text(
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
                            text = comment.author,
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
                                    text = language.text("作者", "Creator"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = WallHubSpacing.dense, vertical = WallHubSpacing.xxxs),
                                )
                            }
                        }
                    }
                    Text(
                        text = formatCommentDate(comment, language),
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
