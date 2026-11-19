@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.wallhub.android.feature.downloads

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.FilterableWorkshopTypes
import com.wallhub.android.core.designsystem.LocalWallHubAdaptiveLayout
import com.wallhub.android.core.designsystem.LocalWallHubOverlayBottomClearance
import com.wallhub.android.core.designsystem.WallHubAlertDialog
import com.wallhub.android.core.designsystem.WallHubColorTokens
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubModalBottomSheet
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubDropdownMenu
import com.wallhub.android.core.designsystem.WallHubDropdownMenuItem
import com.wallhub.android.core.designsystem.WALLHUB_DROPDOWN_ITEM_HEIGHT
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubWindowLayout
import com.wallhub.android.core.designsystem.label
import com.wallhub.android.core.designsystem.wallHubStyle
import com.wallhub.android.core.format.formatByteSize
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.WorkshopType
import org.json.JSONObject

internal enum class DownloadViewMode { SQUARE, WIDE }

@Composable
internal fun BettboxDownloadViewModeToggle(
    viewMode: DownloadViewMode,
    onClick: () -> Unit,
) {
    val style = wallHubStyle()
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = style.selection.container,
        contentColor = style.selection.content,
    ) {
        Text(
            text =
                stringResource(
                    if (viewMode == DownloadViewMode.SQUARE) {
                        R.string.downloads_view_square
                    } else {
                        R.string.downloads_view_wide
                    },
                ),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = WallHubSpacing.sm, vertical = WallHubSpacing.xs),
        )
    }
}

internal fun useExpandedDownloadGrid(
    windowLayout: WallHubWindowLayout,
    viewMode: DownloadViewMode,
): Boolean = windowLayout == WallHubWindowLayout.EXPANDED && viewMode == DownloadViewMode.WIDE

/** Visual list variant used by the single DownloadsScreen tree. Business state stays in the caller. */
@Composable
internal fun BettboxDownloadList(
    tasks: List<DownloadTask>,
    viewMode: DownloadViewMode,
    onDownloadsAction: (DownloadsAction) -> Unit,
    onOpenDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onPlayVideo: (String) -> Unit = {},
) {
    val contentPadding =
        PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = LocalWallHubOverlayBottomClearance.current + 80.dp,
        )
    if (useExpandedDownloadGrid(LocalWallHubAdaptiveLayout.current.width, viewMode)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 360.dp),
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            gridItems(tasks, key = { it.id }) { task ->
                BettboxDownloadItem(task, viewMode, onDownloadsAction, onOpenDetail, onPlayVideo)
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tasks, key = { it.id }) { task ->
                BettboxDownloadItem(task, viewMode, onDownloadsAction, onOpenDetail, onPlayVideo)
            }
        }
    }
}

@Composable
private fun BettboxDownloadItem(
    task: DownloadTask,
    viewMode: DownloadViewMode,
    onDownloadsAction: (DownloadsAction) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onPlayVideo: (String) -> Unit,
) {
    var showFailureDetails by rememberSaveable(task.id) { mutableStateOf(false) }
    val failureDetails = remember(task) { task.failureDetails() }
    AnimatedContent(
        targetState = viewMode,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        transitionSpec = {
            val direction = if (targetState == DownloadViewMode.WIDE) 1 else -1
            (
                slideInHorizontally(
                    animationSpec = tween(360, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
                    initialOffsetX = { fullWidth -> fullWidth / 6 * direction },
                ) + fadeIn(tween(220))
            ).togetherWith(
                slideOutHorizontally(
                    animationSpec = tween(260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
                    targetOffsetX = { fullWidth -> -fullWidth / 10 * direction },
                ) + fadeOut(tween(160)),
            ).using(SizeTransform(clip = true))
        },
        label = "DownloadViewModeTransition",
    ) { mode ->
        if (mode == DownloadViewMode.SQUARE) {
            DownloadSquareCard(
                task = task,
                onAction = { action -> onDownloadsAction(DownloadsAction.RequestTaskAction(task.id, action)) },
                onOpenDetail = onOpenDetail,
                onPlay = { onPlayVideo(task.id) },
                onFailureDetails = { showFailureDetails = true },
            )
        } else {
            DownloadWideCard(
                task = task,
                onAction = { action -> onDownloadsAction(DownloadsAction.RequestTaskAction(task.id, action)) },
                onOpenDetail = onOpenDetail,
                onPlay = { onPlayVideo(task.id) },
                onFailureDetails = { showFailureDetails = true },
            )
        }
    }
    if (showFailureDetails && failureDetails != null) {
        DownloadFailureDetailsSheet(
            details = failureDetails,
            onDismiss = { showFailureDetails = false },
        )
    }
}

@Composable
internal fun BettboxDownloadTabs(selectedTab: Int, onSelected: (Int) -> Unit) {
    val style = wallHubStyle()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = CircleShape,
        color = style.surfaces.content,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(WallHubSizeTokens.minimumTouchTarget).padding(4.dp)) {
            val slotWidth = maxWidth / 2
            val indicator by androidx.compose.animation.core.animateDpAsState(
                targetValue = slotWidth * selectedTab.coerceIn(0, 1),
                animationSpec = tween(320, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
                label = "BettboxDownloadTabIndicator",
            )
            Box(
                Modifier
                    .offset { IntOffset(indicator.roundToPx(), 0) }
                    .width(slotWidth)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(style.selection.container),
            )
            Row(Modifier.fillMaxSize().selectableGroup()) {
                listOf(R.string.downloads_tab_active, R.string.downloads_tab_completed).forEachIndexed { index, label ->
                    val selected = selectedTab == index
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .selectable(
                                selected = selected,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelected(index) },
                                role = Role.Tab,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(label),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) style.selection.content else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Square-card cover size, and the card's minimum height. The cover spans the card's full start
 * edge: a fixed 96.dp thumbnail centred in a 104.dp card left white strips above and below.
 */
private val DownloadSquareCoverSize = 104.dp

@Composable
private fun DownloadSquareCard(
    task: DownloadTask,
    onAction: (DownloadAction) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onPlay: () -> Unit,
    onFailureDetails: () -> Unit,
) {
    val style = wallHubStyle()
    Card(
        onClick = { onOpenDetail(task.workshopId) },
        modifier = Modifier.fillMaxWidth().heightIn(min = DownloadSquareCoverSize),
        shape = style.shapes.card,
        colors = CardDefaults.cardColors(containerColor = style.surfaces.content),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = DownloadSquareCoverSize),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(DownloadSquareCoverSize)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = style.shapes.cardRadius, bottomStart = style.shapes.cardRadius))
                    .background(style.surfaces.browseCard),
            ) {
                if (!task.previewUrl.isNullOrBlank()) {
                    AsyncImage(task.previewUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = WallHubSpacing.sm, top = WallHubSpacing.xs, bottom = WallHubSpacing.xs, end = WallHubSpacing.xxs),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
            ) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    downloadQualityLabel(task),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (task.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED, DownloadStatus.QUEUED, DownloadStatus.RESOLVING)) {
                    DownloadProgressBar(downloadProgressFraction(task) ?: 0f)
                }
                Text(
                    downloadBytesLabel(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELLED) {
                    Text(
                        failureSummary(task),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DownloadActionRail(
                task = task,
                onAction = onAction,
                onPlay = onPlay,
                onFailureDetails = onFailureDetails,
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .width(if (task.failureDetails() != null) 96.dp else WallHubSizeTokens.minimumTouchTarget)
                        .padding(vertical = WallHubSpacing.xxs),
            )
        }
    }
}

@Composable
private fun DownloadWideCard(
    task: DownloadTask,
    onAction: (DownloadAction) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onPlay: () -> Unit,
    onFailureDetails: () -> Unit,
) {
    val style = wallHubStyle()
    Card(
        onClick = { onOpenDetail(task.workshopId) },
        modifier = Modifier.fillMaxWidth(),
        shape = style.shapes.card,
        colors = CardDefaults.cardColors(containerColor = style.surfaces.content),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(topStart = style.shapes.cardRadius, topEnd = style.shapes.cardRadius))) {
                if (!task.previewUrl.isNullOrBlank()) {
                    AsyncImage(task.previewUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, WallHubColorTokens.mediaOverlayScrim))))
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = WallHubColorTokens.mediaOverlayContent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    downloadQualityLabel(task),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (task.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED, DownloadStatus.QUEUED, DownloadStatus.RESOLVING)) {
                    DownloadProgressBar(downloadProgressFraction(task) ?: 0f, height = 6.dp)
                }
                if (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELLED) {
                    Text(
                        failureSummary(task),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        downloadBytesLabel(task),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    DownloadActionRow(
                        task = task,
                        onAction = onAction,
                        onPlay = onPlay,
                        onFailureDetails = onFailureDetails,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressBar(
    progress: Float,
    height: androidx.compose.ui.unit.Dp = 4.dp,
) {
    val style = wallHubStyle()
    val fraction by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(220),
        label = "BettboxDownloadProgress",
    )
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(CircleShape)
                .background(style.surfaces.progressTrack)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * Visual size of an in-card action. It is intentionally smaller than the 48dp rail so the circular
 * state layer stays inside the card instead of being clipped by the card edge.
 */
private val WallHubActionButtonSize = 40.dp

/** Wide cards use a slightly larger action target to match their larger cover and type scale. */
private val WallHubWideActionButtonSize = 44.dp

@Composable
private fun DownloadActionRail(
    task: DownloadTask,
    onAction: (DownloadAction) -> Unit,
    onPlay: () -> Unit,
    onFailureDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = downloadRailActions(task)
    if (actions.primary == null && actions.secondary == null && !actions.playable) return
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (actions.primary != null) {
                AnimatedContent(
                    targetState = actions.primary,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                    label = "downloadPrimaryAction",
                ) { action ->
                    IconButton(onClick = { onAction(action) }, modifier = Modifier.size(WallHubActionButtonSize)) {
                        Icon(
                            imageVector =
                                when (action) {
                                    DownloadAction.PAUSE -> Icons.Outlined.Pause
                                    DownloadAction.RESUME -> Icons.Outlined.PlayArrow
                                    DownloadAction.RETRY -> Icons.Outlined.Refresh
                                    else -> Icons.Outlined.Check
                                },
                            contentDescription = action.label(),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            } else if (actions.playable) {
                IconButton(onClick = onPlay, modifier = Modifier.size(WallHubActionButtonSize)) {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = stringResource(R.string.downloads_play_video),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = actions.secondary != null || task.failureDetails() != null,
                enter = fadeIn(tween(180)) + expandVertically(tween(200)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.width(WallHubSpacing.xs))
                    if (task.failureDetails() != null) {
                        IconButton(onClick = onFailureDetails, modifier = Modifier.size(WallHubActionButtonSize)) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = stringResource(R.string.downloads_action_error_details),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    actions.secondary?.let { secondary ->
                        IconButton(onClick = { onAction(secondary) }, modifier = Modifier.size(WallHubActionButtonSize)) {
                            Icon(
                                imageVector = if (secondary == DownloadAction.DELETE) Icons.Outlined.DeleteSweep else Icons.Outlined.Close,
                                contentDescription = secondary.label(),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class DownloadRailActions(
    val primary: DownloadAction?,
    val secondary: DownloadAction?,
    val playable: Boolean,
)

private fun downloadRailActions(task: DownloadTask): DownloadRailActions =
    DownloadRailActions(
        primary =
            task.availableActions.firstOrNull {
                it == DownloadAction.PAUSE || it == DownloadAction.RESUME || it == DownloadAction.RETRY
            },
        secondary =
            task.availableActions.firstOrNull {
                it == DownloadAction.CANCEL || it == DownloadAction.DELETE
            },
        playable =
            task.type == WorkshopType.VIDEO &&
                task.status == DownloadStatus.COMPLETED &&
                !task.stagingDirectory.isNullOrBlank(),
    )

/** Horizontal action group anchored to the bottom-right of the wide card's information area. */
@Composable
private fun DownloadActionRow(
    task: DownloadTask,
    onAction: (DownloadAction) -> Unit,
    onPlay: () -> Unit,
    onFailureDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = downloadRailActions(task)
    if (actions.primary == null && actions.secondary == null && !actions.playable) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (actions.primary != null) {
            IconButton(onClick = { onAction(actions.primary) }, modifier = Modifier.size(WallHubWideActionButtonSize)) {
                Icon(
                    imageVector =
                        when (actions.primary) {
                            DownloadAction.PAUSE -> Icons.Outlined.Pause
                            DownloadAction.RESUME -> Icons.Outlined.PlayArrow
                            DownloadAction.RETRY -> Icons.Outlined.Refresh
                            else -> Icons.Outlined.Check
                        },
                    contentDescription = actions.primary.label(),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else if (actions.playable) {
            IconButton(onClick = onPlay, modifier = Modifier.size(WallHubWideActionButtonSize)) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(R.string.downloads_play_video),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (task.failureDetails() != null) {
            IconButton(onClick = onFailureDetails, modifier = Modifier.size(WallHubWideActionButtonSize)) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.downloads_action_error_details),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        actions.secondary?.let { secondary ->
            IconButton(onClick = { onAction(secondary) }, modifier = Modifier.size(WallHubWideActionButtonSize)) {
                Icon(
                    imageVector = if (secondary == DownloadAction.DELETE) Icons.Outlined.DeleteSweep else Icons.Outlined.Close,
                    contentDescription = secondary.label(),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun failureSummary(task: DownloadTask): String =
    stringResource(
        if (task.status == DownloadStatus.CANCELLED) {
            R.string.downloads_cancelled_summary
        } else {
            R.string.downloads_failure_summary
        },
    )

internal data class DownloadFailureDetails(
    val stage: String,
    val type: String,
    val detail: String,
)

internal fun DownloadTask.failureDetails(): DownloadFailureDetails? {
    if (status != DownloadStatus.FAILED) return null
    val report =
        conversionReportJson?.let { payload ->
            runCatching { JSONObject(payload).takeIf { it.optString("result") == "failed" } }.getOrNull()
        }
    val detail =
        failureDetail?.takeIf(String::isNotBlank)
            ?: report?.optString("detail")?.takeIf(String::isNotBlank)
            ?: message?.takeIf(String::isNotBlank)
    if (detail == null) return null
    return DownloadFailureDetails(
        stage = failureStage?.takeIf(String::isNotBlank)
            ?: if (report != null) "CONVERSION" else "DOWNLOAD",
        type = failureType?.takeIf(String::isNotBlank)
            ?: report?.optString("reasonCode")?.takeIf(String::isNotBlank)
            ?: "Unknown",
        detail = detail,
    )
}

@Composable
private fun DownloadFailureDetailsSheet(
    details: DownloadFailureDetails,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val stageLabel =
        stringResource(
            when (details.stage) {
                "QUEUE" -> R.string.downloads_failure_stage_queue
                "RESOLUTION" -> R.string.downloads_failure_stage_resolution
                "TRANSFER_OR_VERIFY" -> R.string.downloads_failure_stage_transfer
                "CONVERSION" -> R.string.downloads_failure_stage_conversion
                "CONVERSION_OR_EXPORT" -> R.string.downloads_failure_stage_conversion
                "EXPORT" -> R.string.downloads_failure_stage_export
                else -> R.string.downloads_failure_stage_download
            },
        )
    val copyText =
        stringResource(R.string.downloads_failure_details_stage, stageLabel) + "\n" +
            stringResource(R.string.downloads_failure_details_type, details.type) + "\n" + details.detail
    WallHubModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            Text(stringResource(R.string.downloads_failure_details_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.downloads_failure_details_stage, stageLabel),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.downloads_failure_details_type, details.type),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                details.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { clipboard.setText(AnnotatedString(copyText)) },
            ) {
                Text(stringResource(R.string.downloads_failure_details_copy))
            }
        }
    }
}

@Composable
private fun downloadQualityLabel(task: DownloadTask): String =
    if (task.shouldShowConversionPresetLabel()) task.mpkgConversionPreset.label() else task.type.downloadTypeLabel()

@Composable
private fun downloadBytesLabel(task: DownloadTask): String {
    val total = task.totalBytes
    return if (total > 0L) "${formatByteSize(task.downloadedBytes)} / ${formatByteSize(total)}" else task.status.label()
}

@Composable
internal fun BettboxDownloadFab(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    globalLabel: String?,
    isResume: Boolean,
    filterLabel: String,
    retryLabel: String,
    clearLabel: String,
    failedCount: Int,
    completedCount: Int,
    onGlobalAction: () -> Unit,
    onFilter: () -> Unit,
    onRetry: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    filterMenuExpanded: Boolean = false,
    onFilterMenuDismiss: () -> Unit = {},
    selectedTypes: Set<WorkshopType> = FilterableWorkshopTypes,
    onSelectTypes: (Set<WorkshopType>) -> Unit = {},
) {
    val expressive = wallHubStyle().capabilities.bettboxSurfaces
    val fabContainerColor = MaterialTheme.colorScheme.primary
    val fabContentColor = MaterialTheme.colorScheme.onPrimary
    Box(modifier = modifier, contentAlignment = Alignment.BottomEnd) {
        val fab: @Composable () -> Unit = {
            if (expressive) {
                FloatingActionButtonMenu(
                    expanded = expanded,
                    button = {
                        ToggleFloatingActionButton(
                            checked = expanded,
                            onCheckedChange = onExpandedChange,
                            containerColor = { fabContainerColor },
                        ) {
                            Icon(
                                imageVector = if (expanded) Icons.Outlined.Close else Icons.Outlined.Add,
                                contentDescription =
                                    if (expanded) {
                                        stringResource(R.string.downloads_collapse_actions)
                                    } else {
                                        stringResource(R.string.downloads_expand_actions)
                                    },
                                tint = fabContentColor,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    },
                    horizontalAlignment = Alignment.End,
                ) {
                    globalLabel?.let { label ->
                        FloatingActionButtonMenuItem(
                            onClick = onGlobalAction,
                            text = { Text(label) },
                            icon = {
                                Icon(
                                    imageVector = if (isResume) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                                    contentDescription = null,
                                )
                            },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    if (failedCount > 0) {
                        FloatingActionButtonMenuItem(
                            onClick = onRetry,
                            text = { Text(retryLabel) },
                            icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    if (completedCount > 0) {
                        FloatingActionButtonMenuItem(
                            onClick = onClear,
                            text = { Text(clearLabel) },
                            icon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    FloatingActionButtonMenuItem(
                        onClick = onFilter,
                        text = { Text(filterLabel) },
                        icon = { Icon(Icons.Outlined.FilterAlt, contentDescription = null) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (expanded) {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            globalLabel?.let { label ->
                                BulkDownloadActionButton(label = label, isResume = isResume, onClick = onGlobalAction)
                            }
                            if (failedCount > 0) FabAction(retryLabel, Icons.Outlined.Refresh, onRetry)
                            if (completedCount > 0) FabAction(clearLabel, Icons.Outlined.DeleteSweep, onClear)
                            FabAction(filterLabel, Icons.Outlined.FilterAlt, onFilter)
                        }
                    }
                    FloatingActionButton(
                        onClick = { onExpandedChange(!expanded) },
                        modifier = Modifier.size(56.dp),
                        containerColor = fabContainerColor,
                        contentColor = fabContentColor,
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Outlined.Close else Icons.Outlined.Add,
                            contentDescription =
                                if (expanded) {
                                    stringResource(R.string.downloads_collapse_actions)
                                } else {
                                    stringResource(R.string.downloads_expand_actions)
                                },
                            tint = fabContentColor,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
        if (filterMenuExpanded) {
            DownloadsTypeFilterMenu(
                expanded = true,
                onDismiss = onFilterMenuDismiss,
                selectedTypes = selectedTypes,
                onSelect = onSelectTypes,
                anchor = {
                    FloatingActionButton(
                        onClick = onFilterMenuDismiss,
                        modifier = Modifier.size(56.dp),
                        containerColor = fabContainerColor,
                        contentColor = fabContentColor,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.downloads_expand_actions),
                            tint = fabContentColor,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
            )
        } else {
            fab()
        }
    }
}

@Composable
private fun BulkDownloadActionButton(
    label: String,
    isResume: Boolean,
    onClick: () -> Unit,
) {
    val style = wallHubStyle()
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        shape = style.selection.shape,
        colors =
            androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
    ) {
        Icon(
            imageVector = if (isResume) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FabAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val style = wallHubStyle()
    Surface(
        onClick = onClick,
        shape = style.selection.shape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DownloadsTypeFilterMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    selectedTypes: Set<WorkshopType>,
    onSelect: (Set<WorkshopType>) -> Unit,
    anchor: @Composable () -> Unit,
) {
    Box {
        anchor()
        WallHubDropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            offset = DpOffset(0.dp, -WALLHUB_DROPDOWN_ITEM_HEIGHT * 4 - 8.dp),
        ) {
            DownloadsTypeFilterMenuItems(
                selectedTypes = selectedTypes,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun DownloadsTypeFilterMenuItems(
    selectedTypes: Set<WorkshopType>,
    onSelect: (Set<WorkshopType>) -> Unit,
) {
    val selectedType = selectedTypes.singleOrNull()
    WallHubDropdownMenuItem(
        text = { Text(stringResource(R.string.downloads_type_all)) },
        onClick = { onSelect(FilterableWorkshopTypes) },
        trailingIcon = if (selectedType == null || selectedTypes.containsAll(FilterableWorkshopTypes)) {
            { Icon(Icons.Outlined.Check, contentDescription = null) }
        } else {
            null
        },
    )
    FilterableWorkshopTypes.forEach { type ->
        WallHubDropdownMenuItem(
            text = { Text(type.downloadTypeLabel()) },
            onClick = { onSelect(setOf(type)) },
            trailingIcon = if (selectedType == type) {
                { Icon(Icons.Outlined.Check, contentDescription = null) }
            } else {
                null
            },
        )
    }
}

@Composable
internal fun DownloadsClearConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    WallHubAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.downloads_clear_finished)) },
        text = {
            Text(
                text = stringResource(R.string.downloads_clear_completed_description),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.downloads_clear_finished))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
