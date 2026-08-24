@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.model.WorkshopFilterCatalog
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopType

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeFilterDrawer(
    state: HomeUiState,
    isOpen: Boolean,
    onAction: (HomeAction) -> Unit,
    onDismiss: () -> Unit,
    showSortAndTime: Boolean = true,
    showExactPhrase: Boolean = true,
    title: String? = null,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf(state.filterSelection()) }
    var exactPhrase by remember { mutableStateOf(state.exactPhrase) }

    LaunchedEffect(isOpen) {
        if (isOpen) {
            draft = state.filterSelection()
            exactPhrase = state.exactPhrase
        }
    }

    val activeCount = draft.activeSectionCount() + if (exactPhrase) 1 else 0
    Column(modifier = modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = WallHubSpacing.md, top = WallHubSpacing.sm, end = WallHubSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.FilterAlt, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(horizontal = WallHubSpacing.sm)) {
                Text(
                    text = title ?: stringResource(R.string.home_filter_drawer_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.home_filter_active_summary, activeCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, stringResource(R.string.home_filter_close))
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = WallHubSpacing.sm))
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = WallHubSpacing.xs),
        ) {
            if (showSortAndTime) {
                item {
                    FilterSection(
                        title = stringResource(R.string.home_filter_sort),
                        summary = draft.sort.label(),
                        icon = Icons.Outlined.FilterList,
                        initiallyExpanded = true,
                    ) {
                        TwoColumnFilterOptions(
                            options = WorkshopSort.entries.filterNot {
                                it == WorkshopSort.FRIENDS_FAVORITES || it == WorkshopSort.FRIENDS_CREATED
                            },
                            selected = { it == draft.sort },
                            label = { it.label() },
                            onClick = { sort ->
                                draft = draft.copy(sort = sort, days = if (sort == WorkshopSort.TRENDING) draft.days else 0)
                            },
                        )
                    }
                }
                if (draft.sort == WorkshopSort.TRENDING) {
                    item {
                        FilterSection(
                            title = stringResource(R.string.home_filter_time),
                            summary = draft.days.label(),
                            icon = Icons.Outlined.Schedule,
                            initiallyExpanded = true,
                        ) {
                            TwoColumnFilterOptions(
                                options = timeRangeOptions(draft.days),
                                selected = { it.first == draft.days },
                                label = { it.second },
                                onClick = { draft = draft.copy(days = it.first) },
                            )
                        }
                    }
                }
            }
            item {
                FilterSection(
                    title = stringResource(R.string.home_filter_type),
                    summary = selectionSummary(draft.types.size, typesForFilter.size),
                    icon = Icons.Outlined.GridView,
                ) {
                    TwoColumnFilterOptions(
                        options = typesForFilter.toList(),
                        selected = { draft.types.isFilterOptionSelected(it, typesForFilter) },
                        label = { it.label() },
                        onClick = { type ->
                            val updated = draft.types.toggleBounded(type, typesForFilter)
                            draft = draft.copy(types = updated.takeUnless { it == typesForFilter }.orEmpty())
                        },
                    )
                }
            }
            item {
                val visibleRatings = WorkshopRating.entries.filter {
                    it != WorkshopRating.ALL && (it != WorkshopRating.MATURE || state.matureContentEnabled)
                }
                FilterSection(
                    title = stringResource(R.string.home_filter_rating),
                    summary = selectionSummary(draft.ratings.size, visibleRatings.size),
                    icon = Icons.Outlined.StarBorder,
                ) {
                    TwoColumnFilterOptions(
                        options = visibleRatings,
                        selected = { it in draft.ratings.normalizedRatings(state.matureContentEnabled) },
                        label = { it.label() },
                        onClick = { rating ->
                            draft =
                                draft.copy(
                                    ratings = draft.ratings.toggleRating(rating, multiSelect = true, state.matureContentEnabled),
                                )
                        },
                    )
                }
            }
            item {
                FilterSection(
                    title = stringResource(R.string.home_filter_genre),
                    summary = selectionSummary(draft.genres.size, DEFAULT_HOME_GENRE_SELECTION.size),
                    icon = Icons.Outlined.Palette,
                ) {
                    FilterSelectionShortcuts(
                        onSelectAll = { draft = draft.copy(genres = DEFAULT_HOME_GENRE_SELECTION) },
                        onInvert = { draft = draft.copy(genres = DEFAULT_HOME_GENRE_SELECTION - draft.genres) },
                    )
                    TwoColumnFilterOptions(
                        options = WorkshopFilterCatalog.genres,
                        selected = { draft.genres.isFilterOptionSelected(it, DEFAULT_HOME_GENRE_SELECTION) },
                        label = { it.localizedGenre() },
                        onClick = { genre -> draft = draft.copy(genres = draft.genres.toggleBounded(genre, DEFAULT_HOME_GENRE_SELECTION)) },
                    )
                }
            }
            item {
                FilterSection(
                    title = stringResource(R.string.home_filter_resolution),
                    summary = selectionSummary(draft.resolutions.size, DEFAULT_HOME_RESOLUTION_SELECTION.size),
                    icon = Icons.Outlined.PhoneAndroid,
                ) {
                    FilterSelectionShortcuts(
                        onSelectAll = { draft = draft.copy(resolutions = DEFAULT_HOME_RESOLUTION_SELECTION) },
                        onInvert = { draft = draft.copy(resolutions = DEFAULT_HOME_RESOLUTION_SELECTION - draft.resolutions) },
                    )
                    TwoColumnFilterOptions(
                        options = WorkshopFilterCatalog.resolutions,
                        selected = { draft.resolutions.isFilterOptionSelected(it, DEFAULT_HOME_RESOLUTION_SELECTION) },
                        label = { it.localizedResolution() },
                        onClick = { resolution ->
                            draft = draft.copy(resolutions = draft.resolutions.toggleBounded(resolution, DEFAULT_HOME_RESOLUTION_SELECTION))
                        },
                    )
                }
            }
            item {
                FilterSection(
                    title = stringResource(R.string.home_filter_official_tags),
                    summary = stringResource(
                        R.string.home_filter_official_summary,
                        draft.officialTags.size,
                        draft.excludedOfficialTags.size,
                    ),
                    icon = Icons.Outlined.Tune,
                ) {
                    Text(
                        text = stringResource(R.string.home_filter_official_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = WallHubSpacing.xs),
                    )
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val itemWidth = (maxWidth - FILTER_OPTION_GAP) / 2
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(FILTER_OPTION_GAP),
                            verticalArrangement = Arrangement.spacedBy(FILTER_OPTION_GAP),
                            maxItemsInEachRow = 2,
                        ) {
                            WorkshopFilterCatalog.officialTags.forEach { tag ->
                                OfficialTagChip(
                                    tag = tag,
                                    included = tag in draft.officialTags,
                                    excluded = tag in draft.excludedOfficialTags,
                                    onClick = {
                                        draft =
                                            when {
                                                tag in draft.officialTags ->
                                                    draft.copy(
                                                        officialTags = draft.officialTags - tag,
                                                        excludedOfficialTags = draft.excludedOfficialTags + tag,
                                                    )
                                                tag in draft.excludedOfficialTags ->
                                                    draft.copy(excludedOfficialTags = draft.excludedOfficialTags - tag)
                                                else -> draft.copy(officialTags = draft.officialTags + tag)
                                            }
                                    },
                                    modifier = Modifier.width(itemWidth),
                                )
                            }
                        }
                    }
                }
            }
            if (showExactPhrase) {
                item {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = exactPhrase,
                                    role = Role.Switch,
                                    onValueChange = { exactPhrase = it },
                                ).padding(horizontal = FILTER_DRAWER_HORIZONTAL_PADDING, vertical = WallHubSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = stringResource(R.string.home_exact_phrase),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(horizontal = WallHubSpacing.sm),
                        )
                        Switch(checked = exactPhrase, onCheckedChange = null)
                    }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = WallHubSpacing.sm, vertical = WallHubSpacing.xxxs),
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        draft = HomeFilterSelection.defaults()
                        exactPhrase = false
                    },
                    modifier = Modifier.heightIn(min = FILTER_ACTION_MIN_HEIGHT),
                ) {
                    Text(stringResource(R.string.home_reset))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        onAction(HomeAction.ApplyFilters(draft, exactPhrase))
                        onDismiss()
                    },
                    modifier = Modifier.heightIn(min = FILTER_ACTION_MIN_HEIGHT),
                ) {
                    Text(stringResource(R.string.home_filter_apply))
                }
            }
        }
    }
}

@Composable
private fun FilterSection(
    title: String,
    summary: String,
    icon: ImageVector,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val motionDuration = if (expanded) FILTER_EXPAND_DURATION_MS else FILTER_COLLAPSE_DURATION_MS
    val motionEasing = if (expanded) FILTER_EXPAND_EASING else FILTER_COLLAPSE_EASING
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = motionDuration, easing = motionEasing),
        label = "FilterSectionArrow",
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = FILTER_DRAWER_HORIZONTAL_PADDING, vertical = WallHubSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f).padding(horizontal = WallHubSpacing.sm)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.graphicsLayer { rotationZ = rotation },
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter =
                expandVertically(
                    animationSpec = tween(FILTER_EXPAND_DURATION_MS, easing = FILTER_EXPAND_EASING),
                    expandFrom = Alignment.Top,
                ) + fadeIn(tween(FILTER_CONTENT_FADE_IN_MS, easing = FILTER_EXPAND_EASING)),
            exit =
                shrinkVertically(
                    animationSpec = tween(FILTER_COLLAPSE_DURATION_MS, easing = FILTER_COLLAPSE_EASING),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(tween(FILTER_CONTENT_FADE_OUT_MS, easing = FILTER_COLLAPSE_EASING)),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = FILTER_DRAWER_HORIZONTAL_PADDING, end = FILTER_DRAWER_HORIZONTAL_PADDING, bottom = WallHubSpacing.md),
            ) {
                content()
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = FILTER_DRAWER_HORIZONTAL_PADDING))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> TwoColumnFilterOptions(
    options: List<T>,
    selected: (T) -> Boolean,
    label: @Composable (T) -> String,
    onClick: (T) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val itemWidth = (maxWidth - FILTER_OPTION_GAP) / 2
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FILTER_OPTION_GAP),
            verticalArrangement = Arrangement.spacedBy(FILTER_OPTION_GAP),
            maxItemsInEachRow = 2,
        ) {
            options.forEach { option ->
                val isSelected = selected(option)
                FilterChip(
                    selected = isSelected,
                    onClick = { onClick(option) },
                    label = {
                        Text(
                            text = label(option),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon =
                        if (isSelected) {
                            { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    modifier = Modifier.width(itemWidth).heightIn(min = WallHubSizeTokens.minimumTouchTarget),
                )
            }
        }
    }
}

@Composable
private fun OfficialTagChip(
    tag: String,
    included: Boolean,
    excluded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = included || excluded
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(tag, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(
                imageVector = when {
                    included -> Icons.Outlined.Add
                    excluded -> Icons.Outlined.Remove
                    else -> Icons.Outlined.Cancel
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        modifier = modifier.heightIn(min = WallHubSizeTokens.minimumTouchTarget),
    )
}

@Composable
private fun FilterSelectionShortcuts(
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = WallHubSpacing.xs),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onSelectAll) { Text(stringResource(R.string.home_filter_select_all)) }
        TextButton(onClick = onInvert) { Text(stringResource(R.string.home_filter_invert)) }
    }
}

@Composable
private fun selectionSummary(
    selectedCount: Int,
    totalCount: Int,
): String =
    if (selectedCount == 0 || selectedCount >= totalCount) {
        stringResource(R.string.home_filter_all)
    } else {
        stringResource(R.string.home_filter_count, selectedCount)
    }

private val typesForFilter = linkedSetOf(WorkshopType.SCENE, WorkshopType.VIDEO, WorkshopType.WEB)
private val FILTER_DRAWER_HORIZONTAL_PADDING = WallHubSpacing.md
private val FILTER_OPTION_GAP = WallHubSpacing.xs
private val FILTER_ACTION_MIN_HEIGHT = 40.dp
private const val FILTER_EXPAND_DURATION_MS = 280
private const val FILTER_COLLAPSE_DURATION_MS = 220
private const val FILTER_CONTENT_FADE_IN_MS = 220
private const val FILTER_CONTENT_FADE_OUT_MS = 160
private val FILTER_EXPAND_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val FILTER_COLLAPSE_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
