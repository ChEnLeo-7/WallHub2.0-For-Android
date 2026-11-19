package com.wallhub.android.feature.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.WorkshopSummary

/** Holds the visible quick-sheet target and the data the sheet needs from the grid. */
@Stable
internal class HomeCardSceneState {
    var activeItemId by mutableStateOf<Long?>(null)
        private set

    var closing by mutableStateOf(false)
        private set

    var hostContent by mutableStateOf<HomeCardHostContent?>(null)
        private set

    fun open(itemId: Long) {
        if (activeItemId != null) return
        closing = false
        activeItemId = itemId
    }

    fun requestClose() {
        if (activeItemId != null) closing = true
    }

    fun finishClose() {
        activeItemId = null
        closing = false
    }

    fun updateHostContent(content: HomeCardHostContent?) {
        if (hostContent !== content) hostContent = content
    }

    fun clearHostContent() {
        hostContent = null
    }
}

internal data class HomeCardMetrics(
    val height: Dp,
    val gridStatisticsAvailableWidth: Dp,
    val listStatisticsAvailableWidth: Dp,
)

/** Everything the quick sheet needs from [HomeResults]. */
internal data class HomeCardHostContent(
    val items: List<WorkshopSummary>,
    val authorDisplayNames: Map<Long, String>,
    val layoutKey: HomeCardLayoutKey,
    val metrics: HomeCardMetrics,
    val coverSizePx: Int,
    val action: HomeCardAction,
    val onOpenDetail: (Long) -> Unit,
    val onPrimaryAction: (WorkshopSummary) -> Unit,
    val onDownload: (WorkshopSummary) -> Unit,
    val onSearchAuthor: (String) -> Unit,
    val onCopyText: (String, Int) -> Unit,
    val onOpenSteam: (Long) -> Unit,
    val onAuthorNameRequested: (WorkshopSummary) -> Unit,
)

internal fun homeCardMetrics(
    layoutKey: HomeCardLayoutKey,
    cardWidth: Dp,
    contentWidth: Dp,
    density: androidx.compose.ui.unit.Density,
): HomeCardMetrics {
    if (layoutKey.listMode) {
        return HomeCardMetrics(
            height = LIST_CARD_MEDIA_SIZE,
            gridStatisticsAvailableWidth = cardWidth,
            listStatisticsAvailableWidth =
                (
                    contentWidth -
                        LIST_CARD_MEDIA_SIZE -
                        LIST_CARD_ACTION_SIZE -
                        LIST_CARD_ACTION_END_PADDING -
                        LIST_CARD_COPY_HORIZONTAL_PADDING
                ).coerceAtLeast(0.dp),
        )
    }

    val twoColumn = layoutKey.effectiveColumns == 2
    val copyTop = if (twoColumn) TWO_COLUMN_CARD_COPY_TOP_PADDING else WallHubSpacing.compact
    val copySpacing = if (twoColumn) TWO_COLUMN_CARD_TITLE_STATISTICS_SPACING else LIST_CARD_TITLE_STATISTICS_SPACING
    val statsLine =
        with(density) {
            (if (twoColumn) TWO_COLUMN_CARD_STATISTICS_LINE_HEIGHT else GRID_CARD_STATISTICS_LINE_HEIGHT)
                .toDp()
        }
    val actionTop = if (twoColumn) TWO_COLUMN_CARD_ACTION_TOP_PADDING else LIST_CARD_ACTION_TOP_PADDING
    val height =
        cardWidth +
            copyTop +
            CARD_TITLE_HEIGHT +
            copySpacing +
            statsLine +
            actionTop +
            CARD_ACTION_HEIGHT +
            WallHubSpacing.compact
    return HomeCardMetrics(
        height = height,
        gridStatisticsAvailableWidth =
            (cardWidth - GRID_CARD_COPY_HORIZONTAL_PADDING).coerceAtLeast(0.dp),
        listStatisticsAvailableWidth = 0.dp,
    )
}
