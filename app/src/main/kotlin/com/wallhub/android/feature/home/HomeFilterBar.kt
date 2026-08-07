@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.model.WorkshopFilterCatalog
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeFilterBar(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selection = state.filterSelection()
    val applyFilter: (HomeFilterSelection) -> Unit = { updated ->
        if (updated != selection) onAction(HomeAction.ApplyFilters(updated))
    }
    val filterBarShape = RoundedCornerShape(FILTER_BAR_CORNER_RADIUS)
    val filterBarBorderColor = MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = FILTER_BAR_HORIZONTAL_PADDING)
                .drawBehind {
                    val borderWidth = FILTER_BAR_BORDER_WIDTH.toPx()
                    val inset = borderWidth / 2f
                    drawRoundRect(
                        color = filterBarBorderColor,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - borderWidth, size.height - borderWidth),
                        cornerRadius = CornerRadius(FILTER_BAR_CORNER_RADIUS.toPx()),
                        style = Stroke(width = borderWidth),
                    )
                },
        shape = filterBarShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = FILTER_BAR_VERTICAL_PADDING),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val itemWidth = (maxWidth - FILTER_BAR_CONTENT_PADDING * 2 - FILTER_BAR_GAP) / 2
                FlowRow(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FILTER_BAR_CONTENT_PADDING),
                    horizontalArrangement = Arrangement.spacedBy(FILTER_BAR_GAP),
                    verticalArrangement = Arrangement.spacedBy(FILTER_BAR_GAP),
                ) {
                    HomeSortDropdown(
                        selected = selection.sort,
                        onSelected = {
                            applyFilter(
                                selection.copy(
                                    sort = it,
                                    days = if (it != WorkshopSort.TRENDING) 0 else selection.days,
                                ),
                            )
                        },
                        itemWidth = itemWidth,
                    )
                    AnimatedVisibility(
                        visible = selection.sort == WorkshopSort.TRENDING,
                        enter = expandVertically(spring()) + fadeIn(tween(120)),
                        exit = shrinkVertically(spring()) + fadeOut(tween(80)),
                    ) {
                        HomeTimeDropdown(
                            selected = selection.days,
                            onSelected = { applyFilter(selection.copy(days = it)) },
                            itemWidth = itemWidth,
                        )
                    }
                    HomeTypeDropdown(
                        selected = selection.types,
                        onSelectedSet = { applyFilter(selection.copy(types = it)) },
                        itemWidth = itemWidth,
                    )
                    HomeRatingDropdown(
                        selected = selection.ratings,
                        matureEnabled = state.matureContentEnabled,
                        onSelectedSet = { applyFilter(selection.copy(ratings = it)) },
                        itemWidth = itemWidth,
                    )
                    HomeGenreDropdown(
                        selected = selection.genres,
                        onSelectedSet = { applyFilter(selection.copy(genres = it)) },
                        itemWidth = itemWidth,
                    )
                    HomeResolutionDropdown(
                        selected = selection.resolutions,
                        onSelectedSet = { applyFilter(selection.copy(resolutions = it)) },
                        itemWidth = itemWidth,
                    )
                }
            }
            ExactPhraseRow(
                checked = state.exactPhrase,
                showReset = state.activeFilterCount > 0 || state.exactPhrase,
                onReset = { onAction(HomeAction.ResetAndRefresh) },
                onCheckedChange = { onAction(HomeAction.ToggleExactPhrase) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = FILTER_BAR_CONTENT_PADDING,
                            top = WallHubSpacing.xs,
                            end = FILTER_BAR_CONTENT_PADDING,
                        ),
            )
        }
    }
}

@Composable
private fun HomeSortDropdown(
    selected: WorkshopSort,
    onSelected: (WorkshopSort) -> Unit,
    itemWidth: Dp,
) {
    var expanded by remember { mutableStateOf(false) }
    val isDefault = selected == WorkshopSort.TRENDING
    val value = if (isDefault) null else selected.label()
    FilterPillTrigger(
        icon = Icons.Outlined.FilterList,
        label = stringResource(R.string.home_filter_sort),
        value = value,
        active = !isDefault,
        expanded = expanded,
        onClick = { expanded = true },
        onDismissRequest = { expanded = false },
        itemWidth = itemWidth,
    ) {
        WorkshopSort.entries.forEach { sort ->
            DropdownMenuItem(
                text = { Text(sort.label()) },
                leadingIcon = { if (sort == selected) Icon(Icons.Outlined.Check, contentDescription = null) },
                onClick = { onSelected(sort); expanded = false },
            )
        }
    }
}

@Composable
private fun HomeTimeDropdown(
    selected: Int,
    onSelected: (Int) -> Unit,
    itemWidth: Dp,
) {
    var expanded by remember { mutableStateOf(false) }
    val isDefault = selected == 30
    val value = if (isDefault) null else selected.label()
    FilterPillTrigger(
        icon = Icons.Outlined.Schedule,
        label = stringResource(R.string.home_filter_time),
        value = value,
        active = !isDefault,
        expanded = expanded,
        onClick = { expanded = true },
        onDismissRequest = { expanded = false },
        itemWidth = itemWidth,
        menuWidth = 140.dp,
    ) {
        timeRangeOptions(selected).forEach { (days, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { if (days == selected) Icon(Icons.Outlined.Check, contentDescription = null) },
                onClick = { onSelected(days); expanded = false },
            )
        }
    }
}

@Composable
private fun HomeTypeDropdown(
    selected: Set<WorkshopType>,
    onSelectedSet: (Set<WorkshopType>) -> Unit,
    itemWidth: Dp,
) {
    var expanded by remember { mutableStateOf(false) }
    val isDefault = selected.isEmpty()
    val value =
        when (selected.size) {
            0 -> null
            1 -> selected.first().label()
            else -> stringResource(R.string.home_filter_count, selected.size)
        }
    FilterPillTrigger(
        icon = Icons.Outlined.GridView,
        label = stringResource(R.string.home_filter_type),
        value = value,
        active = !isDefault,
        expanded = expanded,
        onClick = { expanded = true },
        onDismissRequest = { expanded = false },
        itemWidth = itemWidth,
    ) {
        typesForFilter.forEach { type ->
            val checked = type in selected
            DropdownMenuItem(
                text = { Text(type.label()) },
                leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                onClick = {
                    onSelectedSet(if (checked) selected - type else selected + type)
                },
            )
        }
    }
}

@Composable
private fun HomeRatingDropdown(
    selected: Set<WorkshopRating>,
    matureEnabled: Boolean,
    onSelectedSet: (Set<WorkshopRating>) -> Unit,
    itemWidth: Dp,
) {
    var expanded by remember { mutableStateOf(false) }
    val normalized = selected.normalizedRatings(matureEnabled)
    val isDefault = normalized == setOf(WorkshopRating.EVERYONE)
    val value =
        when (normalized.size) {
            0 -> null
            1 -> normalized.first().label()
            else -> stringResource(R.string.home_filter_count, normalized.size)
        }
    FilterPillTrigger(
        icon = Icons.Outlined.StarBorder,
        label = stringResource(R.string.home_filter_rating),
        value = value,
        active = !isDefault,
        expanded = expanded,
        onClick = { expanded = true },
        onDismissRequest = { expanded = false },
        itemWidth = itemWidth,
        menuWidth = 150.dp,
    ) {
        WorkshopRating.entries
            .filter { it != WorkshopRating.ALL && (it != WorkshopRating.MATURE || matureEnabled) }
            .forEach { rating ->
                val checked = rating in normalized
                DropdownMenuItem(
                    text = { Text(rating.label()) },
                    leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                    onClick = {
                        onSelectedSet(selected.toggleRating(rating, multiSelect = true, matureEnabled))
                    },
                )
            }
    }
}

@Composable
private fun HomeGenreDropdown(
    selected: Set<String>,
    onSelectedSet: (Set<String>) -> Unit,
    itemWidth: Dp,
) {
    var expanded by remember { mutableStateOf(false) }
    val allGenres = DEFAULT_HOME_GENRE_SELECTION
    val isDefault = selected == allGenres
    val valueInfo by remember(selected, isDefault) {
        derivedStateOf {
            when {
                isDefault -> Triple(0, "", "")
                selected.isEmpty() -> Triple(1, "", "")
                selected.size <= 2 -> Triple(2, selected.joinToString("、"), "")
                else -> Triple(3, "", selected.size.toString())
            }
        }
    }
    val displayValue: String? =
        when (valueInfo.first) {
            0 -> null
            1 -> stringResource(R.string.home_filter_none)
            2 -> valueInfo.second
            3 -> stringResource(R.string.home_filter_count, selected.size)
            else -> null
        }
    FilterPillTrigger(
        icon = Icons.Outlined.Palette,
        label = stringResource(R.string.home_filter_genre),
        value = displayValue,
        active = !isDefault,
        expanded = expanded,
        onClick = { expanded = true },
        onDismissRequest = { expanded = false },
        itemWidth = itemWidth,
        menuWidth = 260.dp,
    ) {
        Column(
            modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
        ) {
            var searchQuery by remember { mutableStateOf("") }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton({ onSelectedSet(allGenres) }, Modifier.weight(1f)) { Text(stringResource(R.string.home_filter_select_all)) }
                TextButton({ onSelectedSet(allGenres - selected) }, Modifier.weight(1f)) { Text(stringResource(R.string.home_filter_invert)) }
            }
            GenreSearchField(searchQuery, { searchQuery = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val filtered = remember(searchQuery) {
                if (searchQuery.isBlank()) WorkshopFilterCatalog.genres
                else WorkshopFilterCatalog.genres.filter { it.contains(searchQuery, ignoreCase = true) }
            }
            filtered.forEach { genre ->
                val checked = !isDefault && genre in selected
                DropdownMenuItem(
                    text = { Text(genre.localizedGenre()) },
                    leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                    onClick = { onSelectedSet(selected.toggleBounded(genre, allGenres)) },
                )
            }
        }
    }
}

@Composable
private fun HomeResolutionDropdown(
    selected: Set<String>,
    onSelectedSet: (Set<String>) -> Unit,
    itemWidth: Dp,
) {
    var expanded by remember { mutableStateOf(false) }
    val allResolutions = DEFAULT_HOME_RESOLUTION_SELECTION
    val isDefault = selected == allResolutions
    val valueInfo by remember(selected, isDefault) {
        derivedStateOf {
            when {
                isDefault -> Triple(0, "", "")
                selected.isEmpty() -> Triple(1, "", "")
                selected.size <= 2 -> Triple(2, selected.joinToString("、"), "")
                else -> Triple(3, "", selected.size.toString())
            }
        }
    }
    val displayValue: String? =
        when (valueInfo.first) {
            0 -> null
            1 -> stringResource(R.string.home_filter_none)
            2 -> valueInfo.second
            3 -> stringResource(R.string.home_filter_count, selected.size)
            else -> null
        }
    FilterPillTrigger(
        icon = Icons.Outlined.PhoneAndroid,
        label = stringResource(R.string.home_filter_resolution),
        value = displayValue,
        active = !isDefault,
        expanded = expanded,
        onClick = { expanded = true },
        onDismissRequest = { expanded = false },
        itemWidth = itemWidth,
        menuWidth = 260.dp,
    ) {
        Column(
            modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton({ onSelectedSet(allResolutions) }, Modifier.weight(1f)) { Text(stringResource(R.string.home_filter_select_all)) }
                TextButton({ onSelectedSet(allResolutions - selected) }, Modifier.weight(1f)) { Text(stringResource(R.string.home_filter_invert)) }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            WorkshopFilterCatalog.resolutions.forEach { resolution ->
                val checked = !isDefault && resolution in selected
                DropdownMenuItem(
                    text = { Text(resolution.localizedResolution()) },
                    leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                    onClick = { onSelectedSet(selected.toggleBounded(resolution, allResolutions)) },
                )
            }
        }
    }
}

@Composable
private fun GenreSearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = WallHubSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                decorationBox = { inner ->
                    if (query.isBlank()) {
                        Text(stringResource(R.string.home_filter_search_genre), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                },
            )
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChanged("") }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Outlined.Cancel,
                        stringResource(R.string.home_filter_clear),
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterPillTrigger(
    icon: ImageVector,
    label: String,
    value: String?,
    active: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onDismissRequest: () -> Unit,
    itemWidth: Dp,
    menuWidth: Dp = 160.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "Arrow")
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = Modifier.width(itemWidth)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            shape = RoundedCornerShape(FILTER_ITEM_CORNER_RADIUS),
            color = bg,
            contentColor = fg,
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = FILTER_PILL_HEIGHT)
                    .padding(start = WallHubSpacing.sm, end = WallHubSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(WallHubSizeTokens.smallIcon),
                    tint = fg,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (value != null) {
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            color = fg.copy(alpha = FILTER_VALUE_ALPHA),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    Icons.Outlined.KeyboardArrowDown, null,
                    Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
                    tint = fg,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = Modifier.width(menuWidth),
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
            content = content,
        )
    }
}

@Composable
private fun ExactPhraseRow(
    checked: Boolean,
    showReset: Boolean,
    onReset: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .heightIn(min = WallHubSizeTokens.listItemMinimumHeight)
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ).padding(horizontal = WallHubSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.home_exact_phrase),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (showReset) {
            IconButton(onClick = onReset) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.home_reset),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
            ),
        )
    }
}

private val typesForFilter = listOf(WorkshopType.SCENE, WorkshopType.VIDEO, WorkshopType.WEB)
private val FILTER_BAR_GAP = WallHubSpacing.xs
private val FILTER_BAR_HORIZONTAL_PADDING = WallHubSpacing.md
private val FILTER_BAR_CONTENT_PADDING = WallHubSpacing.xs
private val FILTER_BAR_VERTICAL_PADDING = WallHubSpacing.xs
private val FILTER_PILL_HEIGHT = 64.dp
private val FILTER_ITEM_CORNER_RADIUS = 16.dp
private const val FILTER_VALUE_ALPHA = 0.78f
private val FILTER_BAR_CORNER_RADIUS = 16.dp
private val FILTER_BAR_BORDER_WIDTH = 1.dp
