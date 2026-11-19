package com.wallhub.android.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.designsystem.wallHubStyle
import com.wallhub.android.core.format.formatByteSize
import com.wallhub.android.core.format.formatCompactCount
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType

/**
 * The visible home grid card. The Material 3 [Card] owns presentation; the grid item owns tap and
 * long-press gestures so the quick-action [HomeCardQuickSheet] remains the only long-press surface.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeWorkshopCard(
    item: WorkshopSummary,
    action: HomeCardAction,
    coverSizePx: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onPrimaryAction: () -> Unit,
    listMode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val style = wallHubStyle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by
        animateFloatAsState(
            targetValue = if (pressed) 0.985f else 1f,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "HomeCardPressScale",
        )
    val title = item.localizedTitle()
    val actionLabel = action.label()
    val typeLabel = item.type.label()
    val cardModifier =
        modifier
            .fillMaxWidth()
            .then(if (listMode) Modifier.height(96.dp) else Modifier)
            .clip(style.shapes.card)
            .scale(pressScale)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClickLabel = stringResource(R.string.home_view_details),
                onLongClickLabel = stringResource(R.string.home_open_actions_menu),
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
    Card(
        modifier = cardModifier,
        shape = style.shapes.card,
        colors = CardDefaults.cardColors(containerColor = style.surfaces.browseCard),
        elevation =
            CardDefaults.cardElevation(
                defaultElevation = style.elevations.card,
                pressedElevation = 2.dp,
            ),
    ) {
        if (listMode) {
            HomeListCardContent(
                item = item,
                action = action,
                actionLabel = actionLabel,
                typeLabel = typeLabel,
                coverSizePx = coverSizePx,
                onPrimaryAction = onPrimaryAction,
            )
        } else {
            Box {
            HomeCoverImage(
                model = homeWorkshopCoverRequest(context, item.previewUrl.orEmpty(), coverSizePx),
                contentDescription = title,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            if (typeLabel != null) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(WallHubSpacing.sm),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.type == WorkshopType.VIDEO) {
                            Icon(
                                imageVector = Icons.Outlined.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Text(
                            text = typeLabel,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(WallHubSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeCardStatistic(
                    icon = Icons.Outlined.PeopleOutline,
                    value = item.subscriptions?.let(::formatCompactCount) ?: "—",
                )
                HomeCardStatistic(
                    icon = Icons.Outlined.FavoriteBorder,
                    value = item.favorites?.let(::formatCompactCount) ?: "—",
                )
                Text(
                    text = item.fileSizeBytes?.let(::formatByteSize) ?: "— MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            val actionContent: @Composable () -> Unit = {
                Icon(
                    imageVector = action.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = actionLabel,
                    modifier = Modifier.padding(start = WallHubSpacing.xs),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (style.capabilities.bettboxSurfaces) {
                Button(
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth().padding(top = WallHubSpacing.xxxs),
                    content = { actionContent() },
                )
            } else {
                FilledTonalButton(
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth().padding(top = WallHubSpacing.xxxs),
                    content = { actionContent() },
                )
            }
        }
        }
    }
}

@Composable
private fun HomeListCardContent(
    item: WorkshopSummary,
    action: HomeCardAction,
    actionLabel: String,
    typeLabel: String?,
    coverSizePx: Int,
    onPrimaryAction: () -> Unit,
) {
    val context = LocalContext.current
    val title = item.localizedTitle()
    val supportingLine =
        listOfNotNull(
            item.author.takeIf { it.isNotBlank() },
            typeLabel,
        ).joinToString(" · ")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showActionLabel = maxWidth >= 480.dp
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeCoverImage(
                model = homeWorkshopCoverRequest(context, item.previewUrl.orEmpty(), coverSizePx),
                contentDescription = title,
                modifier = Modifier.size(96.dp),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = supportingLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HomeCardStatistic(
                        icon = Icons.Outlined.PeopleOutline,
                        value = item.subscriptions?.let(::formatCompactCount) ?: "—",
                    )
                    HomeCardStatistic(
                        icon = Icons.Outlined.FavoriteBorder,
                        value = item.favorites?.let(::formatCompactCount) ?: "—",
                    )
                    Text(
                        text = item.fileSizeBytes?.let(::formatByteSize) ?: "— MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (showActionLabel) {
                FilledTonalButton(
                    onClick = onPrimaryAction,
                    modifier = Modifier.padding(end = 8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Icon(
                        imageVector = action.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = actionLabel,
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                    )
                }
            } else {
                FilledTonalIconButton(
                    onClick = onPrimaryAction,
                    modifier = Modifier.padding(end = 8.dp).size(40.dp),
                ) {
                    Icon(
                        imageVector = action.icon(),
                        contentDescription = actionLabel,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private const val HOME_COVER_FADE_MILLIS = 240

@Composable
private fun HomeCoverImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var revealed by remember(model) { mutableStateOf(false) }
    val coverAlpha by
        animateFloatAsState(
            targetValue = if (revealed) 1f else 0f,
            animationSpec = tween(durationMillis = HOME_COVER_FADE_MILLIS),
            label = "HomeCoverFade",
        )
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        onSuccess = { revealed = true },
        onError = { revealed = true },
        modifier = modifier.graphicsLayer { alpha = coverAlpha },
    )
}

@Composable
private fun HomeCardStatistic(
    icon: ImageVector,
    value: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
