@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubShapeTokens
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.format.formatByteSize
import com.wallhub.android.core.designsystem.localizedAuthor
import com.wallhub.android.core.model.WorkshopDetail
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.uwuaosp.compose.settingslib.CustomPreferenceRow
import org.uwuaosp.compose.settingslib.PreferencePosition
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailOverviewPage(
    detail: WorkshopDetail,
    onCopyWorkshopId: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
    onSearchTag: (String) -> Unit,
) {
    val summary = detail.summary
    val description =
        detail.description.ifBlank {
            stringResource(R.string.detail_no_description)
        }
    var descriptionExpanded by rememberSaveable(summary.id, description) { mutableStateOf(false) }
    var descriptionCanExpand by remember(description) { mutableStateOf(false) }
    var descriptionTogglePending by remember(summary.id, description) { mutableStateOf(false) }
    val expandedDescriptionInteractionSource = remember { MutableInteractionSource() }
    val descriptionToggleInteractionSource = remember { MutableInteractionSource() }
    val descriptionToggleScope = rememberCoroutineScope()
    val requestDescriptionExpansion: (Boolean) -> Unit = { expanded ->
        if (!descriptionTogglePending && descriptionExpanded != expanded) {
            descriptionTogglePending = true
            descriptionToggleScope.launch {
                delay(DESCRIPTION_RIPPLE_SETTLE_DURATION_MS)
                descriptionExpanded = expanded
                descriptionTogglePending = false
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(WallHubSpacing.md),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm)) {
                CustomPreferenceRow(
                    position = PreferencePosition.Single,
                ) {
                    Column(
                        modifier = Modifier.weight(1f).padding(vertical = WallHubSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                    ) {
                        Text(
                            text = stringResource(R.string.detail_description),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(
                                        animationSpec =
                                            spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                    )
                                    .then(
                                        if (!descriptionExpanded && descriptionCanExpand) {
                                            Modifier
                                                .clip(MaterialTheme.shapes.small)
                                                .clickable(
                                                    enabled = !descriptionTogglePending,
                                                    role = Role.Button,
                                                    onClickLabel = stringResource(R.string.detail_expand_description),
                                                ) {
                                                    requestDescriptionExpansion(true)
                                                }
                                        } else if (descriptionExpanded) {
                                            Modifier.clickable(
                                                interactionSource = expandedDescriptionInteractionSource,
                                                indication = null,
                                                enabled = !descriptionTogglePending,
                                                role = Role.Button,
                                                onClickLabel = stringResource(R.string.detail_collapse_description),
                                            ) {
                                                requestDescriptionExpansion(false)
                                            }
                                        } else {
                                            Modifier
                                        },
                                    ),
                            maxLines =
                                if (descriptionExpanded) {
                                    Int.MAX_VALUE
                                } else {
                                    DESCRIPTION_COLLAPSED_MAX_LINES
                                },
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { result ->
                                if (!descriptionExpanded) {
                                    descriptionCanExpand = result.hasVisualOverflow
                                }
                            },
                        )
                        if (descriptionCanExpand || descriptionExpanded) {
                            Surface(
                                onClick = {
                                    requestDescriptionExpansion(!descriptionExpanded)
                                },
                                enabled = !descriptionTogglePending,
                                interactionSource = descriptionToggleInteractionSource,
                                shape = WallHubShapeTokens.badge,
                                color = Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.primary,
                            ) {
                                Text(
                                    text =
                                        if (descriptionExpanded) {
                                            stringResource(R.string.detail_show_less)
                                        } else {
                                            stringResource(R.string.detail_show_more)
                                        },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = WallHubSpacing.xs, vertical = WallHubSpacing.dense),
                                )
                            }
                        }
                    }
                }
                CustomPreferenceRow(position = PreferencePosition.Single) {
                    Column(
                        modifier = Modifier.weight(1f).padding(vertical = WallHubSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.controlInset),
                    ) {
                        DetailMetricRow(
                            first =
                                DetailMetricValue(
                                    Icons.Outlined.PersonOutline,
                                    stringResource(R.string.detail_author),
                                    summary.localizedAuthor(),
                                    onClick = { onSearchAuthor(detail.creatorId ?: summary.author) },
                                ),
                            second =
                                DetailMetricValue(
                                    Icons.Outlined.ContentCopy,
                                    stringResource(R.string.detail_project_id),
                                    summary.id.toString(),
                                    onClick = { onCopyWorkshopId(summary.id) },
                                ),
                        )
                        DetailMetricRow(
                            first =
                                DetailMetricValue(
                                    Icons.Outlined.FavoriteBorder,
                                    stringResource(R.string.detail_subscriptions),
                                    formatCompactCount(detail.subscriptions ?: summary.subscriptions),
                                ),
                            second =
                                DetailMetricValue(
                                    Icons.Outlined.StarBorder,
                                    stringResource(R.string.detail_favorites),
                                    formatCompactCount(summary.favorites),
                                ),
                        )
                        DetailMetricRow(
                            first =
                                DetailMetricValue(
                                    Icons.Outlined.Visibility,
                                    stringResource(R.string.detail_views),
                                    formatCompactCount(summary.views),
                                ),
                            second =
                                DetailMetricValue(
                                    Icons.Outlined.Download,
                                    stringResource(R.string.detail_file_size),
                                    detail.fileSizeBytes?.let(::formatByteSize)
                                        ?: stringResource(R.string.detail_unknown),
                                ),
                        )
                        DetailMetricRow(
                            first =
                                DetailMetricValue(
                                    Icons.Outlined.Schedule,
                                    stringResource(R.string.detail_last_updated),
                                    formatWorkshopDate(detail.updatedAt),
                                ),
                            second =
                                DetailMetricValue(
                                    Icons.Outlined.Info,
                                    stringResource(R.string.detail_type),
                                    summary.type.label(),
                                ),
                        )
                    }
                }
                if (summary.tags.isNotEmpty()) {
                    CustomPreferenceRow(position = PreferencePosition.Single) {
                        Column(
                            modifier = Modifier.weight(1f).padding(vertical = WallHubSpacing.sm),
                            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                        ) {
                            Text(
                                text = stringResource(R.string.detail_tags),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                            ) {
                                summary.tags.forEach { tag ->
                                    AssistChip(
                                        onClick = { onSearchTag(tag) },
                                        label = { Text(text = tag) },
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

internal data class DetailMetricValue(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val onClick: (() -> Unit)? = null,
)

@Composable
internal fun DetailMetricRow(
    first: DetailMetricValue,
    second: DetailMetricValue? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
    ) {
        DetailMetric(first, Modifier.weight(1f))
        second?.let { DetailMetric(it, Modifier.weight(1f)) }
    }
}

@Composable
internal fun DetailMetric(
    metric: DetailMetricValue,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .then(
                    metric.onClick?.let { onClick ->
                        Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClick = onClick)
                    } ?: Modifier,
                ).padding(WallHubSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.compact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = metric.icon,
            contentDescription = null,
            modifier = Modifier.size(WallHubSizeTokens.compactIcon),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = metric.value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
