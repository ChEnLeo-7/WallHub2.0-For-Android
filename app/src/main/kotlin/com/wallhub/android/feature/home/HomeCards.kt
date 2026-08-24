@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.format.formatByteSize
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.WorkshopSummary
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.StarBorder

@Composable
internal fun WorkshopAdaptiveCardContent(
    item: WorkshopSummary,
    action: HomeCardAction,
    listMode: Boolean,
    twoColumnGrid: Boolean,
    gridShowFileSize: Boolean,
    gridShowFavorites: Boolean,
    gridStatisticsAvailableWidth: Dp,
    listStatisticsAvailableWidth: Dp,
    layoutMotion: HomeViewCardLayoutMotion,
    onPrimaryAction: () -> Unit,
) {
    val density = LocalDensity.current
    val listMediaSizePx = with(density) { LIST_CARD_MEDIA_SIZE.roundToPx() }
    val listActionSlotWidthPx =
        with(density) {
            (LIST_CARD_ACTION_SIZE + LIST_CARD_ACTION_END_PADDING).roundToPx()
    }
    Layout(
        content = {
            WorkshopCoverFrame(
                item = item,
                coverShape = layoutMotion.coverShape(),
                typeTagModifier = layoutMotion.mediaContentScaleCompensationModifier(),
                modifier =
                    Modifier
                        .zIndex(HOME_CARD_PREVIEW_Z_INDEX)
                        .then(layoutMotion.mediaModifier()),
            )
            WorkshopCardCopy(
                item = item,
                compact = listMode,
                twoColumnGrid = twoColumnGrid,
                showFileSize = if (listMode) true else gridShowFileSize,
                showFavorites = if (listMode) true else gridShowFavorites,
                statisticsAvailableWidth =
                    if (listMode) {
                        listStatisticsAvailableWidth
                    } else {
                        gridStatisticsAvailableWidth
                    },
                titleModifier = layoutMotion.titleModifier(),
                metadataModifier = layoutMotion.metadataModifier(),
                modifier =
                    Modifier
                        .zIndex(layoutMotion.copyZIndex())
                        .then(
                            if (listMode) {
                                Modifier.padding(
                                    start = WallHubSpacing.sm,
                                    top = WallHubSpacing.xs,
                                    end = WallHubSpacing.xs,
                                    bottom = WallHubSpacing.xs,
                                )
                            } else {
                                Modifier.padding(
                                    start = WallHubSpacing.compact,
                                    top = if (twoColumnGrid) TWO_COLUMN_CARD_COPY_TOP_PADDING else WallHubSpacing.compact,
                                    end = WallHubSpacing.compact,
                                )
                            },
                        ),
            )
            WorkshopCardActionButton(
                action = action,
                shape = layoutMotion.actionShape(),
                contentModifier = layoutMotion.actionContentModifier(),
                labelVisibility = layoutMotion.actionLabelVisibility(),
                onPrimaryAction = onPrimaryAction,
                modifier =
                    Modifier
                        .then(
                            if (listMode) {
                                Modifier
                                    .padding(end = LIST_CARD_ACTION_END_PADDING)
                                    .size(LIST_CARD_ACTION_SIZE)
                            } else {
                                Modifier
                                    .padding(
                                        start = WallHubSpacing.compact,
                                        top = if (twoColumnGrid) TWO_COLUMN_CARD_ACTION_TOP_PADDING else LIST_CARD_ACTION_TOP_PADDING,
                                        end = WallHubSpacing.compact,
                                        bottom = WallHubSpacing.compact,
                                    ).fillMaxWidth()
                                    .height(CARD_ACTION_HEIGHT)
                            },
                        ).then(layoutMotion.actionModifier()),
            )
        },
    ) { measurables, constraints ->
        check(measurables.size == HOME_CARD_LAYOUT_SLOT_COUNT)
        val width = constraints.maxWidth
        if (listMode) {
            val cover = measurables[HOME_CARD_COVER_SLOT].measure(Constraints.fixed(listMediaSizePx, listMediaSizePx))
            val actionSlotWidth = listActionSlotWidthPx.coerceAtMost((width - cover.width).coerceAtLeast(0))
            val action =
                measurables[HOME_CARD_ACTION_SLOT].measure(
                    Constraints(
                        minWidth = 0,
                        maxWidth = actionSlotWidth,
                        minHeight = 0,
                        maxHeight = listMediaSizePx,
                    ),
                )
            val copyWidth = (width - cover.width - action.width).coerceAtLeast(0)
            val copy =
                measurables[HOME_CARD_COPY_SLOT].measure(
                    Constraints(
                        minWidth = copyWidth,
                        maxWidth = copyWidth,
                        minHeight = 0,
                        maxHeight = Constraints.Infinity,
                    ),
                )
            val height =
                maxOf(listMediaSizePx, copy.height, action.height)
                    .coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                cover.placeRelative(0, (height - cover.height) / 2)
                copy.placeRelative(cover.width, (height - copy.height) / 2)
                action.placeRelative(width - action.width, (height - action.height) / 2)
            }
        } else {
            val cover = measurables[HOME_CARD_COVER_SLOT].measure(Constraints.fixed(width, width))
            val childWidthConstraints =
                Constraints(
                    minWidth = width,
                    maxWidth = width,
                    minHeight = 0,
                    maxHeight = Constraints.Infinity,
                )
            val copy = measurables[HOME_CARD_COPY_SLOT].measure(childWidthConstraints)
            val action = measurables[HOME_CARD_ACTION_SLOT].measure(childWidthConstraints)
            val height =
                (cover.height + copy.height + action.height)
                    .coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                cover.placeRelative(0, 0)
                copy.placeRelative(0, cover.height)
                action.placeRelative(0, cover.height + copy.height)
            }
        }
    }
}

private const val HOME_CARD_COVER_SLOT = 0
private const val HOME_CARD_COPY_SLOT = 1
private const val HOME_CARD_ACTION_SLOT = 2
private const val HOME_CARD_LAYOUT_SLOT_COUNT = 3

@Composable
internal fun WorkshopCoverFrame(
    item: WorkshopSummary,
    coverShape: Shape,
    typeTagModifier: Modifier,
    modifier: Modifier,
) {
    Box(
        modifier = modifier,
    ) {
        WorkshopCover(
            item = item,
            modifier = Modifier.fillMaxSize(),
            shape = coverShape,
        )
        WorkshopCoverTypeTag(
            item = item,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .then(typeTagModifier)
                    .padding(WallHubSpacing.xs),
        )
    }
}

@Composable
internal fun WorkshopCover(
    item: WorkshopSummary,
    modifier: Modifier,
    shape: Shape,
) {
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (item.previewUrl != null) {
            AsyncImage(
                model = item.previewUrl,
                contentDescription = stringResource(R.string.home_preview_image, item.localizedTitle()),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.ImageNotSupported,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
internal fun WorkshopCoverTypeTag(
    item: WorkshopSummary,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    ) {
        Text(
            text = item.type.label(),
            style = MaterialTheme.typography.labelSmall,
            modifier =
                Modifier.padding(
                    horizontal = HOME_TYPE_TAG_HORIZONTAL_PADDING,
                    vertical = HOME_TYPE_TAG_VERTICAL_PADDING,
                ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkshopCardCopy(
    item: WorkshopSummary,
    compact: Boolean,
    twoColumnGrid: Boolean,
    showFileSize: Boolean,
    showFavorites: Boolean,
    statisticsAvailableWidth: Dp,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    metadataModifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val statisticCount =
        1 +
            (if (showFavorites) 1 else 0) +
            (if (showFileSize) 1 else 0)
    val statisticsMetrics =
        WorkshopCardStatisticsMetrics.forAvailableWidth(
            availableWidth = statisticsAvailableWidth,
            statisticCount = statisticCount,
            compact = compact,
            twoColumnGrid = twoColumnGrid,
        )
    val baseStatisticTextStyle =
        if (compact) {
            MaterialTheme.typography.labelSmall
        } else {
            MaterialTheme.typography.bodySmall
        }
    val statisticTextStyle =
        if (twoColumnGrid) {
            baseStatisticTextStyle.copy(
                fontSize = statisticsMetrics.fontSize.sp,
                lineHeight = TWO_COLUMN_CARD_STATISTICS_LINE_HEIGHT,
            )
        } else {
            baseStatisticTextStyle.copy(fontSize = statisticsMetrics.fontSize.sp)
        }
    val minimumCopyHeight =
        if (!compact) {
            with(density) {
                CARD_TITLE_HEIGHT +
                    (if (twoColumnGrid) TWO_COLUMN_CARD_TITLE_STATISTICS_SPACING else LIST_CARD_TITLE_STATISTICS_SPACING) +
                    (
                        if (twoColumnGrid) {
                            TWO_COLUMN_CARD_STATISTICS_LINE_HEIGHT.toDp()
                        } else {
                            GRID_CARD_STATISTICS_LINE_HEIGHT.toDp()
                        }
                    )
            }
        } else {
            WallHubSpacing.none
        }
    Column(
        modifier = if (compact) modifier else modifier.heightIn(min = minimumCopyHeight),
        verticalArrangement =
            Arrangement.spacedBy(
                when {
                    compact -> WallHubSpacing.xxs
                    twoColumnGrid -> TWO_COLUMN_CARD_TITLE_STATISTICS_SPACING
                    else -> LIST_CARD_TITLE_STATISTICS_SPACING
                },
            ),
    ) {
        Text(
            text = item.localizedTitle(),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = titleModifier,
        )
        Box(modifier = metadataModifier.fillMaxWidth()) {
            if (twoColumnGrid) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            space = statisticsMetrics.itemSpacing,
                            alignment = Alignment.Start,
                        ),
                    verticalArrangement = Arrangement.spacedBy(TWO_COLUMN_CARD_STATISTICS_ROW_SPACING),
                    maxItemsInEachRow = 3,
                ) {
                    WorkshopCardStatisticsItems(
                        item = item,
                        showFileSize = showFileSize,
                        showFavorites = showFavorites,
                        textStyle = statisticTextStyle,
                        metrics = statisticsMetrics,
                        overflow = TextOverflow.Clip,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            space = statisticsMetrics.itemSpacing,
                            alignment = Alignment.Start,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WorkshopCardStatisticsItems(
                        item = item,
                        showFileSize = showFileSize,
                        showFavorites = showFavorites,
                        textStyle = statisticTextStyle,
                        metrics = statisticsMetrics,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

@Composable
internal fun WorkshopCardStatisticsItems(
    item: WorkshopSummary,
    showFileSize: Boolean,
    showFavorites: Boolean,
    textStyle: androidx.compose.ui.text.TextStyle,
    metrics: WorkshopCardStatisticsMetrics,
    overflow: TextOverflow,
) {
    WorkshopCardStatistic(
        icon = Icons.Outlined.FavoriteBorder,
        value = item.subscriptions?.let(::formatCompact) ?: "—",
        contentDescription = stringResource(R.string.home_subscriptions),
        textStyle = textStyle,
        iconSize = metrics.iconSize,
        iconSpacing = metrics.iconSpacing,
        overflow = overflow,
    )
    if (showFavorites) {
        WorkshopCardStatistic(
            icon = Icons.Outlined.StarBorder,
            value = item.favorites?.let(::formatCompact) ?: "—",
            contentDescription = stringResource(R.string.home_favorites),
            textStyle = textStyle,
            iconSize = metrics.iconSize,
            iconSpacing = metrics.iconSpacing,
            overflow = overflow,
        )
    }
    if (showFileSize) {
        Text(
            text = item.fileSizeBytes?.let(::formatByteSize) ?: "— MB",
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = overflow,
        )
    }
}

@Composable
internal fun WorkshopCardActionButton(
    action: HomeCardAction,
    shape: Shape,
    contentModifier: Modifier,
    labelVisibility: Float,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionContentColor = MaterialTheme.colorScheme.onPrimary
    val label = action.label()
    val labelStyle = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelWidth =
        remember(label, labelStyle, density, textMeasurer) {
            with(density) {
                textMeasurer
                    .measure(
                        text = AnnotatedString(label),
                        style = labelStyle,
                    ).size.width
                    .toDp()
            }
        }
    val labelProgress = labelVisibility.coerceIn(0f, 1f)
    val labelContainerWidth = VIEW_MODE_TOGGLE_LABEL_INSET + labelWidth
    val iconOffsetPx =
        with(density) {
            labelContainerWidth.toPx() * (1f - labelProgress) / 2f
        }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Button(
            onClick = onPrimaryAction,
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            contentPadding = PaddingValues(WallHubSpacing.none),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .then(contentModifier),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier =
                        Modifier
                            .wrapContentWidth(unbounded = true)
                            .graphicsLayer { translationX = iconOffsetPx },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = action.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(WallHubSpacing.md),
                        tint = actionContentColor,
                    )
                    Box(
                        modifier =
                            Modifier
                                .width(labelContainerWidth)
                                .drawWithContent drawContent@{
                                    clipRect(right = size.width * labelProgress) {
                                        this@drawContent.drawContent()
                                    }
                                }.graphicsLayer { alpha = labelProgress },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = label,
                            style = labelStyle,
                            color = actionContentColor,
                            modifier = Modifier.padding(start = VIEW_MODE_TOGGLE_LABEL_INSET),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorkshopCardStatistic(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    contentDescription: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    iconSize: Dp,
    iconSpacing: Dp,
    overflow: TextOverflow,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(iconSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = overflow,
        )
    }
}

internal data class WorkshopCardStatisticsMetrics(
    val fontSize: Float,
    val iconSize: Dp,
    val iconSpacing: Dp,
    val itemSpacing: Dp,
) {
    companion object {
        fun forAvailableWidth(
            availableWidth: Dp,
            statisticCount: Int,
            compact: Boolean,
            twoColumnGrid: Boolean,
        ): WorkshopCardStatisticsMetrics {
            val slotWidth = availableWidth.value / statisticCount.coerceAtLeast(1)
            if (twoColumnGrid) {
                return WorkshopCardStatisticsMetrics(
                    fontSize =
                        (slotWidth / TWO_COLUMN_CARD_STATISTICS_FONT_WIDTH_DIVISOR)
                            .coerceIn(
                                TWO_COLUMN_CARD_STATISTICS_MIN_FONT_SIZE,
                                TWO_COLUMN_CARD_STATISTICS_MAX_FONT_SIZE,
                            ),
                    iconSize = TWO_COLUMN_CARD_STATISTICS_ICON_SIZE,
                    iconSpacing = TWO_COLUMN_CARD_STATISTICS_ICON_SPACING,
                    itemSpacing = TWO_COLUMN_CARD_STATISTICS_ITEM_SPACING,
                )
            }
            val maximumFontSize = if (compact) 12f else 13f
            val minimumFontSize = if (compact) 9f else 8.5f
            // Keep the metadata in its natural left-to-right reading order while
            // allowing it to grow slightly on wider cards.
            val fontSize = (slotWidth / 5.5f).coerceIn(minimumFontSize, maximumFontSize)
            return WorkshopCardStatisticsMetrics(
                fontSize = fontSize,
                iconSize =
                    when {
                        fontSize <= 9.5f -> 11.dp
                        fontSize <= 10.5f -> WallHubSpacing.sm
                        compact -> 13.dp
                        else -> 15.dp
                    },
                iconSpacing = if (fontSize <= 9.5f) WallHubSpacing.xxxs else 3.dp,
                itemSpacing =
                    if (fontSize <= 9.5f) {
                        WallHubSpacing.xxxs
                    } else if (compact) {
                        WallHubSpacing.xxs
                    } else {
                        WallHubSpacing.dense
                    },
            )
        }
    }
}
