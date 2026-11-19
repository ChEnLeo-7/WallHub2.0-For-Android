package com.wallhub.android.core.designsystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Host state for the Material 3 modal action sheet that replaced the former custom
 * shared-transition context-menu overlay.
 */
@Stable
class WallHubActionSheetState<T> {
    var activeItem by mutableStateOf<T?>(null)
        private set

    val isOpen: Boolean
        get() = activeItem != null

    fun open(item: T) {
        activeItem = item
    }

    fun dismiss() {
        activeItem = null
    }
}

@Composable
fun <T> rememberWallHubActionSheetState(): WallHubActionSheetState<T> = remember { WallHubActionSheetState() }

/** Official M3 modal sheet presentation for the currently selected item. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> WallHubActionSheetHost(
    state: WallHubActionSheetState<T>,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    val item = state.activeItem ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    WallHubModalBottomSheet(
        onDismissRequest = state::dismiss,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        content(item)
    }
}

/** Long-press source for a card that opens [state]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> WallHubActionSheetTrigger(
    state: WallHubActionSheetState<T>,
    item: T,
    onClick: () -> Unit,
    clickLabel: String,
    longClickLabel: String,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier =
            modifier.combinedClickable(
                onClickLabel = clickLabel,
                onLongClickLabel = longClickLabel,
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                    state.open(item)
                },
            ),
    ) {
        content()
    }
}

@Composable
fun WallHubActionSheetHeader(
    title: String,
    subtitle: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    previewModel: Any? = null,
    badge: String? = null,
    stats: List<WallHubActionSheetStat> = emptyList(),
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (previewModel != null) {
                AsyncImage(
                    model = previewModel,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.ImageNotSupported,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            badge?.let { label ->
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(WallHubSpacing.sm),
                    shape = WallHubShapeTokens.badge,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = WallHubSpacing.sm, vertical = WallHubSpacing.xs),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(top = WallHubSpacing.md),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (stats.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = WallHubSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    stats.forEach { stat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
                        ) {
                            stat.icon?.let { icon ->
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(WallHubSizeTokens.compactIcon),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = stat.value,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = WallHubSpacing.md),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
fun WallHubActionSheetMetadataItem(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        overlineContent = { Text(text = label, maxLines = 1) },
        content = {
            Text(
                text = value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

enum class WallHubActionSheetActionEmphasis {
    Primary,
    Secondary,
    Outlined,
    Tertiary,
}

@Composable
fun WallHubActionSheetAction(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    emphasis: WallHubActionSheetActionEmphasis = WallHubActionSheetActionEmphasis.Tertiary,
    shape: Shape = WallHubShapeTokens.cta,
    modifier: Modifier = Modifier,
) {
    val actionModifier =
        modifier
            .fillMaxWidth()
            .padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.xxs)
            .heightIn(min = 48.dp)
    when (emphasis) {
        WallHubActionSheetActionEmphasis.Primary ->
            Button(
                onClick = onClick,
                modifier = actionModifier,
                shape = shape,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(WallHubSizeTokens.smallIcon))
                Spacer(Modifier.size(WallHubSpacing.xs))
                Text(text)
            }

        WallHubActionSheetActionEmphasis.Secondary ->
            FilledTonalButton(
                onClick = onClick,
                modifier = actionModifier,
                shape = shape,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(WallHubSizeTokens.smallIcon))
                Spacer(Modifier.size(WallHubSpacing.xs))
                Text(text)
            }

        WallHubActionSheetActionEmphasis.Outlined ->
            OutlinedButton(
                onClick = onClick,
                modifier = actionModifier,
                shape = shape,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(WallHubSizeTokens.smallIcon))
                Spacer(Modifier.size(WallHubSpacing.xs))
                Text(text)
            }

        WallHubActionSheetActionEmphasis.Tertiary ->
            TextButton(
                onClick = onClick,
                modifier = actionModifier,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(WallHubSizeTokens.smallIcon),
                )
                Spacer(Modifier.size(WallHubSpacing.sm))
                Text(text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
    }
}

data class WallHubActionSheetStat(
    val value: String,
    val icon: ImageVector? = null,
)
