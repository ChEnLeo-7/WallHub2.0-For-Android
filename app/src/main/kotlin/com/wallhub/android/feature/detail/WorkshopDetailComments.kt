@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wallhub.android.NAVIGATION_EASING
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubFabActiveElevation
import com.wallhub.android.core.designsystem.WallHubFabDefaultElevation
import com.wallhub.android.core.designsystem.WallHubSecondaryButton
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.localizedAuthor
import com.wallhub.android.core.designsystem.wallHubStyle
import com.wallhub.android.core.model.WorkshopComment
import androidx.compose.material.icons.Icons
import org.uwuaosp.compose.widget.preference.CustomPreferenceRow
import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerticalAlignTop

private enum class CommentsPresentation {
    Loading,
    Error,
    Empty,
    Content,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Suppress("CyclomaticComplexMethod")
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
    val style = wallHubStyle()
    // CustomPreferenceRow supplies the SDK's connected-group spacing between comments.
    var commentsBoundsInWindow by remember { mutableStateOf(Rect.Zero) }
    var composerBoundsInWindow by remember { mutableStateOf(Rect.Zero) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val composerHeight = with(LocalDensity.current) { composerHeightPx.toDp() }
    val bottomContentPadding = maxOf(88.dp, composerHeight + 80.dp)
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
            val presentation =
                when {
                    isLoading && comments.isEmpty() -> CommentsPresentation.Loading
                    error != null && comments.isEmpty() -> CommentsPresentation.Error
                    comments.isEmpty() -> CommentsPresentation.Empty
                    else -> CommentsPresentation.Content
                }
            Crossfade(
                targetState = presentation,
                animationSpec = tween(durationMillis = 220, easing = NAVIGATION_EASING),
                modifier = Modifier.fillMaxSize(),
                label = "CommentsPresentation",
            ) { targetPresentation ->
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
                verticalArrangement = Arrangement.Top,
            ) {
                when (targetPresentation) {
                    CommentsPresentation.Loading ->
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

                    CommentsPresentation.Error ->
                        item {
                            WallHubEmptyState(
                                icon = Icons.Outlined.Refresh,
                                title = requireNotNull(error).resolve(),
                                actionLabel = stringResource(R.string.detail_retry),
                                onAction = onRetry,
                            )
                        }

                    CommentsPresentation.Empty ->
                        item {
                            WallHubEmptyState(
                                icon = Icons.Outlined.ChatBubbleOutline,
                                title = stringResource(R.string.detail_no_comments),
                            )
                        }

                    CommentsPresentation.Content -> {
                        itemsIndexed(
                            items = comments,
                            key = { _, comment ->
                                listOf(comment.author, comment.timestamp, comment.text).joinToString("|")
                            },
                        ) { index, comment ->
                            WorkshopCommentItem(
                                comment = comment,
                                index = index,
                                itemCount = comments.size,
                                modifier =
                                    Modifier
                                        .testTag("comment-$index")
                                        .animateItem(
                                            fadeInSpec = tween(220, easing = NAVIGATION_EASING),
                                            placementSpec = tween(240, easing = NAVIGATION_EASING),
                                            fadeOutSpec = tween(160, easing = NAVIGATION_EASING),
                                        ),
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
                color = wallHubStyle().surfaces.page,
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
    val style = wallHubStyle()
    var composerFocused by remember { mutableStateOf(false) }
    val borderColor by
        animateColorAsState(
            targetValue =
                if (composerFocused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            animationSpec = tween(durationMillis = 160),
            label = "CommentComposerBorder",
        )
    Column(modifier = modifier.fillMaxWidth().animateContentSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = style.shapes.card,
            color = style.surfaces.settingsRow,
            border = BorderStroke(1.dp, borderColor),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = WallHubSpacing.xs, end = WallHubSpacing.xs, bottom = WallHubSpacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
                verticalAlignment = Alignment.Bottom,
            ) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier =
                        Modifier
                            .weight(1f)
                            .onFocusChanged { composerFocused = it.isFocused },
                    placeholder = { Text(stringResource(R.string.detail_write_comment)) },
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
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            errorIndicatorColor = Color.Transparent,
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
                    modifier = Modifier.padding(bottom = WallHubSpacing.xxs).size(44.dp),
                    shape = CircleShape,
                ) {
                    Crossfade(
                        targetState = isPosting,
                        animationSpec = tween(durationMillis = 140),
                        label = "CommentPostingState",
                    ) { posting ->
                        if (posting) {
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
        }
        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(tween(140)) + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut(tween(120)) + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            if (error != null) {
                Text(
                    text = error.resolve(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = WallHubSpacing.sm, top = WallHubSpacing.xxs),
                )
            }
        }
    }
}

@Composable
internal fun WorkshopCommentItem(
    comment: WorkshopComment,
    index: Int,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    val author = comment.localizedAuthor()
    val commentStyle = wallHubStyle()
    val commentAccentBackground =
        if (commentStyle.capabilities.bettboxSurfaces) {
            commentStyle.navigation.selectedContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
    val commentAccentContent =
        if (commentStyle.capabilities.bettboxSurfaces) {
            commentStyle.navigation.selectedContent
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        }
    // The SDK reads surfaceBright; adapt it to the same row role used by WallHub settings.
    val rowScheme = MaterialTheme.colorScheme.copy(surfaceBright = commentStyle.surfaces.settingsRow)
    MaterialTheme(colorScheme = rowScheme) {
        CustomPreferenceRow(
            modifier = modifier.fillMaxWidth(),
            index = index,
            itemCount = itemCount,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = WallHubSpacing.controlInset),
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    modifier = Modifier.size(WallHubSizeTokens.compactIconButton),
                    shape = CircleShape,
                    color = commentAccentBackground,
                    contentColor = commentAccentContent,
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
                                    color = commentAccentBackground,
                                    contentColor = commentAccentContent,
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
}
