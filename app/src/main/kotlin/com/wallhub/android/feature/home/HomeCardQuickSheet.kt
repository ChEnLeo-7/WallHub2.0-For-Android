package com.wallhub.android.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.format.formatByteSize
import com.wallhub.android.core.format.formatCompactCount
import com.wallhub.android.core.model.WorkshopSummary
import kotlinx.coroutines.launch

/**
 * Material 3 replacement for the former absolute-geometry long-press card morph.
 * The sheet owns presentation, motion, scrim and dismiss behavior.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeCardQuickSheet(
    content: HomeCardHostContent,
    itemId: Long,
    onDismiss: () -> Unit,
) {
    val item = content.items.firstOrNull { it.id == itemId } ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismissSheet: () -> Unit = {
        scope.launch {
            runCatching { sheetState.hide() }
            onDismiss()
        }
    }
    ModalBottomSheet(
        onDismissRequest = dismissSheet,
        sheetState = sheetState,
    ) {
        HomeCardQuickSheetContent(
            item = item,
            content = content,
            onDismiss = dismissSheet,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun HomeCardQuickSheetContent(
    item: WorkshopSummary,
    content: HomeCardHostContent,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val primaryActionLabel = content.action.label()
    Column(
        modifier = modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AsyncImage(
            model = homeWorkshopCoverRequest(context, item.previewUrl.orEmpty(), content.coverSizePx),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .aspectRatio(1f)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(24.dp)),
        )
        Text(
            text = item.localizedTitle(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.home_author_prefixed, item.author),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable {
                        if (item.author.isNotBlank()) content.onSearchAuthor(item.author)
                    },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeQuickStat(
                icon = { Icon(Icons.Outlined.PeopleOutline, null, Modifier.size(18.dp)) },
                value = item.subscriptions?.let(::formatCompactCount) ?: "—",
            )
            HomeQuickStat(
                icon = { Icon(Icons.Outlined.StarBorder, null, Modifier.size(18.dp)) },
                value = item.favorites?.let(::formatCompactCount) ?: "—",
            )
            HomeQuickStat(
                icon = null,
                value = item.fileSizeBytes?.let(::formatByteSize) ?: "—",
            )
        }
        HorizontalDivider()
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.home_project_id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = item.id.toString(),
                style = MaterialTheme.typography.bodySmall,
                modifier =
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable {
                            content.onCopyText(item.id.toString(), R.string.home_project_id_copied)
                        },
            )
        }
        OutlinedButton(
            onClick = {
                content.onOpenSteam(item.id)
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.home_open_workshop))
        }
        Button(
            onClick = {
                content.onPrimaryAction(item)
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Icon(content.action.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(primaryActionLabel)
        }
    }
}

@Composable
private fun HomeQuickStat(
    icon: (@Composable () -> Unit)?,
    value: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke()
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
