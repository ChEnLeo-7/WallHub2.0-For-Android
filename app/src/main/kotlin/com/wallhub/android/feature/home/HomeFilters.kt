@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.home

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubFilterChip
import com.wallhub.android.core.designsystem.WallHubShapeTokens
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.WorkshopFilterCatalog
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopType
import java.util.Locale
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HomeFiltersSheet(
    applied: HomeFilterSelection,
    config: HomeFilterUiConfig,
    initialPage: HomeFilterPage,
    exactPhrase: Boolean,
    onToggleExactPhrase: () -> Unit,
    onDismiss: (HomeFilterSelection) -> Unit,
) {
    val pages = HomeFilterPage.entries
    val defaults = HomeFilterSelection.defaults().normalized(config.matureContentEnabled)
    var draft by rememberSaveable(stateSaver = homeFilterSelectionSaver) {
        mutableStateOf(applied)
    }
    var selectedPage by rememberSaveable { mutableStateOf(initialPage) }
    val browseScrollState = rememberScrollState()
    val contentScrollState = rememberScrollState()
    val themeScrollState = rememberScrollState()
    val displayScrollState = rememberScrollState()
    val pageScrollStates =
        remember(
            browseScrollState,
            contentScrollState,
            themeScrollState,
            displayScrollState,
        ) {
            mapOf(
                HomeFilterPage.BROWSE to browseScrollState,
                HomeFilterPage.CONTENT to contentScrollState,
                HomeFilterPage.THEME to themeScrollState,
                HomeFilterPage.DISPLAY to displayScrollState,
            )
        }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val updateSelection: (HomeFilterSelection) -> Unit = { selection ->
        val normalized = selection.normalized(config.matureContentEnabled)
        draft = normalized
    }
    val currentDraft by rememberUpdatedState(draft)

    ModalBottomSheet(
        onDismissRequest = { onDismiss(currentDraft) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        sheetMaxWidth = WallHubSizeTokens.modalContentMaxWidth,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val condensed = maxHeight < 680.dp || LocalDensity.current.fontScale > 1.3f
            val compact = maxWidth < 840.dp || condensed
            val horizontalPadding = if (compact) WallHubSpacing.md else WallHubSpacing.lg
            val contentMaxHeight = maxHeight * if (compact) 0.92f else 0.84f
            Column(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(if (compact) 1f else 0.92f)
                        .widthIn(max = WallHubSizeTokens.modalContentMaxWidth)
                        .heightIn(max = contentMaxHeight),
            ) {
                HomeFilterSheetHeader(
                    draft = draft,
                    defaults = defaults,
                    onReset = { updateSelection(defaults) },
                    horizontalPadding = horizontalPadding,
                )
                FilterChip(
                    selected = exactPhrase,
                    onClick = onToggleExactPhrase,
                    label = { Text(stringResource(R.string.home_exact_phrase)) },
                    leadingIcon = {
                        if (exactPhrase) Icon(Icons.Outlined.Check, contentDescription = null)
                    },
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                )
                if (compact) {
                    HomeFilterPageNavigation(
                        pages = pages,
                        selectedPage = selectedPage,
                        draft = draft,
                        compact = true,
                        onPageSelected = { page -> selectedPage = page },
                    )
                    AnimatedContent(
                        targetState = selectedPage,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = contentMaxHeight),
                        transitionSpec = { homeFilterPageContentTransform() },
                        label = "HomeFilterPage",
                    ) { page ->
                        HomeFilterPageContent(
                            page = page,
                            config = config,
                            draft = draft,
                            scrollStates = pageScrollStates,
                            onDraftChanged = updateSelection,
                        )
                    }
                } else {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = contentMaxHeight)
                                .padding(horizontal = horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.lg),
                    ) {
                        HomeFilterPageNavigation(
                            pages = pages,
                            selectedPage = selectedPage,
                            draft = draft,
                            compact = false,
                            onPageSelected = { page -> selectedPage = page },
                            modifier = Modifier.width(208.dp),
                        )
                        AnimatedContent(
                            targetState = selectedPage,
                            modifier = Modifier.weight(1f),
                            transitionSpec = { homeFilterPageContentTransform() },
                            label = "HomeFilterPage",
                        ) { page ->
                            HomeFilterPageContent(
                                page = page,
                                config = config,
                                draft = draft,
                                scrollStates = pageScrollStates,
                                onDraftChanged = updateSelection,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun homeFilterPageContentTransform(): ContentTransform =
    ContentTransform(
        targetContentEnter =
            fadeIn(
                animationSpec =
                    tween(
                        durationMillis = HOME_FILTER_PAGE_ENTER_DURATION_MS,
                        delayMillis = HOME_FILTER_PAGE_EXIT_DURATION_MS,
                        easing = HOME_FILTER_PAGE_EASING,
                    ),
            ),
        initialContentExit =
            fadeOut(
                animationSpec =
                    tween(
                        durationMillis = HOME_FILTER_PAGE_EXIT_DURATION_MS,
                        easing = HOME_FILTER_PAGE_EASING,
                    ),
            ),
        sizeTransform =
            SizeTransform(clip = true) { _, _ ->
                tween(
                    durationMillis = HOME_FILTER_SHEET_PAGE_SIZE_DURATION_MS,
                    delayMillis = HOME_FILTER_PAGE_EXIT_DURATION_MS,
                    easing = HOME_FILTER_PAGE_EASING,
                )
            },
    )

@Composable
internal fun HomeFilterSheetHeader(
    draft: HomeFilterSelection,
    defaults: HomeFilterSelection,
    onReset: () -> Unit,
    horizontalPadding: Dp,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    bottom = WallHubSpacing.xs,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
    ) {
        Text(
            text = stringResource(R.string.home_filter_and_sort),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onReset,
            enabled = draft != defaults,
        ) {
            Text(stringResource(R.string.home_reset))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeFilterPageNavigation(
    pages: List<HomeFilterPage>,
    selectedPage: HomeFilterPage,
    draft: HomeFilterSelection,
    compact: Boolean,
    onPageSelected: (HomeFilterPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (compact) {
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            PrimaryTabRow(
                selectedTabIndex = pages.indexOf(selectedPage).coerceAtLeast(0),
                modifier =
                    modifier
                        .fillMaxWidth()
                        .padding(horizontal = WallHubSpacing.md)
                        .clip(MaterialTheme.shapes.large),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {},
            ) {
                pages.forEach { page ->
                    val activeCount = page.activeSectionCount(draft)
                    Tab(
                        selected = selectedPage == page,
                        onClick = { onPageSelected(page) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (activeCount > 0) Badge { Text(activeCount.toString()) }
                                },
                            ) {
                                Icon(
                                    imageVector = page.icon(),
                                    contentDescription = null,
                                    modifier = Modifier.size(WallHubSizeTokens.smallIcon),
                                )
                            }
                        },
                        text = {
                            Text(
                                text = page.label(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    } else {
        Column(
            modifier = modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
        ) {
            pages.forEach { page ->
                val activeCount = page.activeSectionCount(draft)
                NavigationDrawerItem(
                    selected = selectedPage == page,
                    onClick = { onPageSelected(page) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Icon(
                            imageVector = page.icon(),
                            contentDescription = null,
                        )
                    },
                    label = {
                        Text(
                            text = page.label(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    badge = {
                        if (activeCount > 0) Badge { Text(activeCount.toString()) }
                    },
                    shape = MaterialTheme.shapes.large,
                    colors =
                        NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedBadgeColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedBadgeColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
        }
    }
}

@Composable
internal fun HomeFilterPageContent(
    page: HomeFilterPage,
    config: HomeFilterUiConfig,
    draft: HomeFilterSelection,
    scrollStates: Map<HomeFilterPage, ScrollState>,
    onDraftChanged: (HomeFilterSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when (page) {
            HomeFilterPage.BROWSE ->
                HomeBrowseFilterPage(
                    config = config,
                    draft = draft,
                    scrollState = scrollStates.getValue(page),
                    onDraftChanged = onDraftChanged,
                )

            HomeFilterPage.CONTENT ->
                HomeContentFilterPage(
                    config = config,
                    draft = draft,
                    scrollState = scrollStates.getValue(page),
                    onDraftChanged = onDraftChanged,
                )

            HomeFilterPage.THEME ->
                HomeThemeFilterPage(
                    config = config,
                    draft = draft,
                    scrollState = scrollStates.getValue(page),
                    onDraftChanged = onDraftChanged,
                )

            HomeFilterPage.DISPLAY ->
                HomeDisplayFilterPage(
                    config = config,
                    draft = draft,
                    scrollState = scrollStates.getValue(page),
                    onDraftChanged = onDraftChanged,
                )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeBrowseFilterPage(
    config: HomeFilterUiConfig,
    draft: HomeFilterSelection,
    scrollState: ScrollState,
    onDraftChanged: (HomeFilterSelection) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.md),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
    ) {
        HomeFilterSectionCard {
            HomeFilterSectionHeading(
                title = stringResource(R.string.home_sort_by),
                supportingText = stringResource(R.string.home_sort_by_supporting),
            )
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            ) {
                WorkshopSort.entries.forEach { sort ->
                    HomeFilterChoiceRow(
                        label = sort.label(),
                        selected = draft.sort == sort,
                        onClick = { onDraftChanged(draft.copy(sort = sort)) },
                    )
                }
            }
        }
        HomeFilterSectionCard(enabled = draft.sort == WorkshopSort.TRENDING) {
            HomeFilterSectionHeading(
                title = stringResource(R.string.home_time_range),
                supportingText = stringResource(R.string.home_time_range_supporting),
                enabled = draft.sort == WorkshopSort.TRENDING,
            )
            FlowRow(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            ) {
                timeRangeOptions(draft.days).forEach { (days, label) ->
                    HomeDraftFilterChip(
                        label = label,
                        selected = draft.days == days,
                        enabled = draft.sort == WorkshopSort.TRENDING,
                        singleChoice = true,
                        onClick = { onDraftChanged(draft.copy(days = days)) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(WallHubSpacing.xxs))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeContentFilterPage(
    config: HomeFilterUiConfig,
    draft: HomeFilterSelection,
    scrollState: ScrollState,
    onDraftChanged: (HomeFilterSelection) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.md),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
    ) {
        HomeFilterSectionCard {
            HomeFilterSectionHeading(
                title = stringResource(R.string.home_wallpaper_type),
                supportingText =
                    if (config.multiSelect) {
                        stringResource(R.string.home_multiple_types_allowed)
                    } else {
                        stringResource(R.string.home_single_selection)
                    },
            )
            FlowRow(
                modifier = if (config.multiSelect) Modifier else Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            ) {
                HomeDraftFilterChip(
                    label = stringResource(R.string.home_any),
                    selected = draft.types.isEmpty(),
                    singleChoice = !config.multiSelect,
                    onClick = { onDraftChanged(draft.copy(types = emptySet())) },
                )
                listOf(WorkshopType.SCENE, WorkshopType.VIDEO, WorkshopType.WEB).forEach { type ->
                    HomeDraftFilterChip(
                        label = type.label(),
                        selected = type in draft.types,
                        singleChoice = !config.multiSelect,
                        onClick = {
                            onDraftChanged(
                                draft.copy(
                                    types = draft.types.toggleOptional(type, config.multiSelect),
                                ),
                            )
                        },
                    )
                }
            }
        }
        HomeFilterSectionCard {
            HomeFilterSectionHeading(
                title = stringResource(R.string.home_age_rating),
                supportingText =
                    if (config.matureContentEnabled) {
                        stringResource(R.string.home_mature_options_available)
                    } else {
                        stringResource(R.string.home_mature_disabled)
                    },
            )
            FlowRow(
                modifier = if (config.multiSelect) Modifier else Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            ) {
                WorkshopRating.entries
                    .filter { it != WorkshopRating.MATURE || config.matureContentEnabled }
                    .forEach { rating ->
                        HomeDraftFilterChip(
                            label =
                                if (rating == WorkshopRating.ALL && !config.matureContentEnabled) {
                                    stringResource(R.string.home_all_allowed)
                                } else {
                                    rating.label()
                                },
                            selected =
                                draft.ratings.isRatingSelected(
                                    rating = rating,
                                    matureContentEnabled = config.matureContentEnabled,
                                ),
                            singleChoice = !config.multiSelect,
                            onClick = {
                                onDraftChanged(
                                    draft.copy(
                                        ratings =
                                            draft.ratings.toggleRating(
                                                rating = rating,
                                                multiSelect = config.multiSelect,
                                                matureContentEnabled = config.matureContentEnabled,
                                            ),
                                    ),
                                )
                            },
                        )
                    }
            }
        }
        Spacer(modifier = Modifier.height(WallHubSpacing.xxs))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeThemeFilterPage(
    config: HomeFilterUiConfig,
    draft: HomeFilterSelection,
    scrollState: ScrollState,
    onDraftChanged: (HomeFilterSelection) -> Unit,
) {
    val allGenres = DEFAULT_HOME_GENRE_SELECTION
    val genresUnrestricted = draft.genres == allGenres
    val allOfficialTags = WorkshopFilterCatalog.officialTags.toSet()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.md),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
    ) {
        HomeFilterSectionCard {
            HomeFilterSectionHeading(
                title = stringResource(R.string.home_genres),
                supportingText = stringResource(R.string.home_genres_supporting),
                actionLabel = stringResource(R.string.home_invert),
                actionEnabled = !genresUnrestricted,
                onAction = {
                    onDraftChanged(draft.copy(genres = draft.genres.invertBounded(allGenres)))
                },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            ) {
                HomeDraftFilterChip(
                    label = stringResource(R.string.home_any),
                    selected = genresUnrestricted,
                    onClick = { onDraftChanged(draft.copy(genres = allGenres)) },
                )
                WorkshopFilterCatalog.genres.forEach { genre ->
                    HomeDraftFilterChip(
                        label = genre.localizedGenre(),
                        selected = !genresUnrestricted && genre in draft.genres,
                        onClick = {
                            onDraftChanged(
                                draft.copy(
                                    genres = draft.genres.toggleBounded(genre, allGenres),
                                ),
                            )
                        },
                    )
                }
            }
        }
        HomeFilterSectionCard {
            HomeFilterSectionHeading(
                title = stringResource(R.string.home_official_features),
                supportingText = stringResource(R.string.home_official_features_supporting),
                actionLabel = stringResource(R.string.home_invert),
                actionEnabled = draft.officialTags.isNotEmpty(),
                onAction = {
                    onDraftChanged(
                        draft.copy(officialTags = allOfficialTags - draft.officialTags),
                    )
                },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            ) {
                HomeDraftFilterChip(
                    label = stringResource(R.string.home_any),
                    selected = draft.officialTags.isEmpty(),
                    onClick = { onDraftChanged(draft.copy(officialTags = emptySet())) },
                )
                WorkshopFilterCatalog.officialTags.forEach { tag ->
                    HomeDraftFilterChip(
                        label = tag.localizedOfficialTag(),
                        selected = tag in draft.officialTags,
                        onClick = {
                            onDraftChanged(
                                draft.copy(
                                    officialTags = draft.officialTags.toggleOptional(tag, multiSelect = true),
                                ),
                            )
                        },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(WallHubSpacing.xxs))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeDisplayFilterPage(
    config: HomeFilterUiConfig,
    draft: HomeFilterSelection,
    scrollState: ScrollState,
    onDraftChanged: (HomeFilterSelection) -> Unit,
) {
    val allResolutions = DEFAULT_HOME_RESOLUTION_SELECTION
    val unrestricted = draft.resolutions == allResolutions
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.md),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
    ) {
        HomeFilterSectionCard {
            HomeFilterSectionHeading(
                title = stringResource(R.string.home_resolution),
                supportingText = stringResource(R.string.home_resolution_supporting),
                actionLabel = stringResource(R.string.home_invert),
                actionEnabled = !unrestricted,
                onAction = {
                    onDraftChanged(
                        draft.copy(resolutions = draft.resolutions.invertBounded(allResolutions)),
                    )
                },
            )
            HomeDraftFilterChip(
                label = stringResource(R.string.home_any),
                selected = unrestricted,
                onClick = { onDraftChanged(draft.copy(resolutions = allResolutions)) },
            )
            WorkshopFilterCatalog.resolutionGroups.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs)) {
                    Text(
                        text = group.id.localizedResolutionGroup(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                    ) {
                        group.options.forEach { resolution ->
                            HomeDraftFilterChip(
                                label = resolution.localizedResolution(),
                                selected = !unrestricted && resolution in draft.resolutions,
                                onClick = {
                                    onDraftChanged(
                                        draft.copy(
                                            resolutions =
                                                draft.resolutions.toggleBounded(
                                                    resolution,
                                                    allResolutions,
                                                ),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(WallHubSpacing.xxs))
    }
}

@Composable
internal fun HomeFilterSectionCard(
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = WallHubSpacing.none,
    ) {
        Column(
            modifier = Modifier.padding(WallHubSpacing.md),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            CompositionLocalProvider(
                LocalContentColor provides
                    MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (enabled) 1f else 0.55f,
                    ),
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun HomeFilterSectionHeading(
    title: String,
    supportingText: String,
    enabled: Boolean = true,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.55f),
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    enabled = enabled && actionEnabled,
                ) {
                    Text(actionLabel)
                }
            }
        }
        Text(
            text = supportingText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.55f),
        )
    }
}

@Composable
internal fun HomeFilterChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        contentColor =
            if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        tonalElevation = WallHubSpacing.none,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = WallHubSizeTokens.listItemMinimumHeight)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = onClick,
                    ).padding(horizontal = WallHubSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun HomeDraftFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    singleChoice: Boolean = false,
) {
    WallHubFilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        enabled = enabled,
        singleChoice = singleChoice,
    )
}

@Composable
internal fun HomeFilterPage.label(): String =
    when (this) {
        HomeFilterPage.BROWSE -> stringResource(R.string.home_filter_page_browse)
        HomeFilterPage.CONTENT -> stringResource(R.string.home_filter_page_content)
        HomeFilterPage.THEME -> stringResource(R.string.home_filter_page_theme)
        HomeFilterPage.DISPLAY -> stringResource(R.string.home_filter_page_display)
    }

@Composable
internal fun HomeFilterPage.summary(
    selection: HomeFilterSelection,
    state: HomeUiState,
): String =
    when (this) {
        HomeFilterPage.BROWSE ->
            if (selection.sort == WorkshopSort.TRENDING) {
                "${selection.sort.label()} · ${selection.days.label()}"
            } else {
                selection.sort.label()
            }

        HomeFilterPage.CONTENT ->
            when (activeSectionCount(selection)) {
                0 -> stringResource(R.string.home_any)
                1 ->
                    if (selection.types.isNotEmpty()) {
                        selection.types.summary(stringResource(R.string.home_any))
                    } else {
                        selection.ratings.summary(state.matureContentEnabled)
                    }

                else -> stringResource(R.string.home_type_and_rating)
            }

        HomeFilterPage.THEME ->
            if (activeSectionCount(selection) == 0) {
                stringResource(R.string.home_any)
            } else {
                val sectionCount = activeSectionCount(selection)
                pluralStringResource(R.plurals.home_sections, sectionCount, sectionCount)
            }

        HomeFilterPage.DISPLAY ->
            if (selection.resolutions == DEFAULT_HOME_RESOLUTION_SELECTION) {
                stringResource(R.string.home_any)
            } else {
                pluralStringResource(
                    R.plurals.home_selected_count,
                    selection.resolutions.size,
                    selection.resolutions.size,
                )
            }
    }

internal fun HomeFilterPage.icon(): ImageVector =
    when (this) {
        HomeFilterPage.BROWSE -> Icons.Outlined.Schedule
        HomeFilterPage.CONTENT -> Icons.Outlined.GridView
        HomeFilterPage.THEME -> Icons.Outlined.Palette
        HomeFilterPage.DISPLAY -> Icons.Outlined.PhoneAndroid
    }

internal fun HomeFilterPage.activeSectionCount(selection: HomeFilterSelection): Int =
    when (this) {
        HomeFilterPage.BROWSE ->
            when {
                selection.sort != WorkshopSort.TRENDING -> 1
                selection.days != 30 -> 1
                else -> 0
            }

        HomeFilterPage.CONTENT ->
            listOf(
                selection.types.isNotEmpty(),
                selection.ratings != DEFAULT_HOME_RATING_SELECTION,
            ).count { it }

        HomeFilterPage.THEME ->
            listOf(
                selection.genres != DEFAULT_HOME_GENRE_SELECTION,
                selection.officialTags.isNotEmpty(),
            ).count { it }

        HomeFilterPage.DISPLAY -> if (selection.resolutions != DEFAULT_HOME_RESOLUTION_SELECTION) 1 else 0
    }

internal fun <T> Set<T>.toggleOptional(
    value: T,
    multiSelect: Boolean,
): Set<T> =
    when {
        !multiSelect -> setOf(value)
        value in this -> this - value
        else -> this + value
    }

internal fun <T> Set<T>.toggleBounded(
    value: T,
    allOptions: Set<T>,
): Set<T> {
    val current = if (isEmpty() || this == allOptions) allOptions else this
    if (current == allOptions) return setOf(value)
    val next = if (value in current) current - value else current + value
    return if (next.isEmpty() || next == allOptions) allOptions else next
}

internal fun <T> Set<T>.invertBounded(allOptions: Set<T>): Set<T> = (allOptions - this).ifEmpty { allOptions }

internal fun Set<WorkshopRating>.toggleRating(
    rating: WorkshopRating,
    multiSelect: Boolean,
    matureContentEnabled: Boolean,
): Set<WorkshopRating> =
    when {
        rating == WorkshopRating.ALL ->
            if (matureContentEnabled) {
                setOf(WorkshopRating.ALL)
            } else {
                SAFE_HOME_RATING_SELECTION
            }

        !multiSelect -> setOf(rating)
        !matureContentEnabled && normalizedRatings(false) == SAFE_HOME_RATING_SELECTION -> setOf(rating)
        rating in normalizedRatings(matureContentEnabled) ->
            (normalizedRatings(matureContentEnabled) - rating).ifEmpty { DEFAULT_HOME_RATING_SELECTION }

        else ->
            (normalizedRatings(matureContentEnabled) - WorkshopRating.ALL + rating)
                .normalizedRatings(matureContentEnabled)
    }

internal fun Set<WorkshopRating>.normalizedRatings(matureContentEnabled: Boolean): Set<WorkshopRating> {
    if (WorkshopRating.ALL in this) {
        return if (matureContentEnabled) setOf(WorkshopRating.ALL) else SAFE_HOME_RATING_SELECTION
    }
    return filterNot { it == WorkshopRating.MATURE && !matureContentEnabled }
        .toSet()
        .ifEmpty { DEFAULT_HOME_RATING_SELECTION }
}

internal fun Set<WorkshopRating>.isRatingSelected(
    rating: WorkshopRating,
    matureContentEnabled: Boolean,
): Boolean {
    val normalized = normalizedRatings(matureContentEnabled)
    return if (rating == WorkshopRating.ALL && !matureContentEnabled) {
        normalized == SAFE_HOME_RATING_SELECTION
    } else if (!matureContentEnabled && normalized == SAFE_HOME_RATING_SELECTION) {
        false
    } else {
        rating in normalized
    }
}

@Composable
internal fun WorkshopSort.label(): String =
    when (this) {
        WorkshopSort.TRENDING -> stringResource(R.string.home_sort_popular)
        WorkshopSort.MOST_RECENT -> stringResource(R.string.home_sort_most_recent)
        WorkshopSort.TOP_RATED -> stringResource(R.string.home_sort_top_rated)
        WorkshopSort.MOST_VOTES -> stringResource(R.string.home_sort_most_votes)
        WorkshopSort.MOST_SUBSCRIBERS -> stringResource(R.string.home_sort_most_subscribers)
    }

@Composable
internal fun Int.label(): String =
    when (this) {
        0 -> stringResource(R.string.home_all_time)
        1 -> stringResource(R.string.home_today)
        7 -> stringResource(R.string.home_7_days)
        30 -> stringResource(R.string.home_30_days)
        90 -> stringResource(R.string.home_3_months)
        180 -> stringResource(R.string.home_6_months)
        365 -> stringResource(R.string.home_1_year)
        else -> pluralStringResource(R.plurals.home_days, this, this)
    }

@Composable
internal fun timeRangeOptions(currentDays: Int): List<Pair<Int, String>> {
    val finiteRanges =
        (listOf(1, 7, 30, 90, 180, 365) + currentDays)
            .filter { it > 0 }
            .distinct()
            .sorted()
    return (finiteRanges + 0).map { it to it.label() }
}

@Composable
internal fun WorkshopType.label(): String =
    when (this) {
        WorkshopType.VIDEO -> stringResource(R.string.home_video)
        WorkshopType.SCENE -> stringResource(R.string.home_scene)
        WorkshopType.WEB -> stringResource(R.string.home_web)
        WorkshopType.UNKNOWN -> stringResource(R.string.home_wallpaper)
    }

@Composable
internal fun Set<WorkshopType>.summary(all: String): String = if (isEmpty()) all else map { it.label() }.joinToString(" / ")

@Composable
internal fun WorkshopRating.label(): String =
    when (this) {
        WorkshopRating.ALL -> stringResource(R.string.home_all)
        WorkshopRating.EVERYONE -> stringResource(R.string.home_rating_everyone)
        WorkshopRating.QUESTIONABLE -> stringResource(R.string.home_rating_questionable)
        WorkshopRating.MATURE -> stringResource(R.string.home_rating_mature)
    }

@Composable
internal fun Set<WorkshopRating>.summary(matureContentEnabled: Boolean): String {
    val normalized = normalizedRatings(matureContentEnabled)
    return when {
        WorkshopRating.ALL in normalized -> WorkshopRating.ALL.label()
        !matureContentEnabled && normalized == SAFE_HOME_RATING_SELECTION ->
            stringResource(R.string.home_all_allowed)

        else -> normalized.map { it.label() }.joinToString(" / ")
    }
}

@Composable
internal fun HomeCardAction.label(): String =
    when (this) {
        HomeCardAction.DOWNLOAD -> stringResource(R.string.home_download)
        HomeCardAction.PLAY_VIDEO -> stringResource(R.string.home_play)
        HomeCardAction.OPEN_STEAM -> stringResource(R.string.home_open_steam)
    }

internal fun HomeCardAction.icon() =
    when (this) {
        HomeCardAction.DOWNLOAD -> Icons.Outlined.Download
        HomeCardAction.PLAY_VIDEO -> Icons.Outlined.PlayArrow
        HomeCardAction.OPEN_STEAM -> Icons.Outlined.OpenInNew
    }

internal fun formatCompact(value: Long): String {
    val locale = Locale.getDefault()
    val isChinese = locale.language == Locale.CHINESE.language
    return when {
        (isChinese && value >= 10_000) || (!isChinese && value >= 1_000_000) ->
            String.format(
                locale,
                if (isChinese) "%.1f 万" else "%.1fM",
                if (isChinese) value / 10_000.0 else value / 1_000_000.0,
            )
        value >= 1_000 -> String.format(Locale.getDefault(), "%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}

@Composable
internal fun String.localizedGenre(): String = genreLabelRes()?.let { stringResource(it) } ?: this

@StringRes
private fun String.genreLabelRes(): Int? =
    when (this) {
        "Abstract" -> R.string.home_genre_abstract
        "Animal" -> R.string.home_genre_animal
        "Anime" -> R.string.home_genre_anime
        "Cartoon" -> R.string.home_genre_cartoon
        "CGI" -> R.string.home_genre_cgi
        "Cyberpunk" -> R.string.home_genre_cyberpunk
        "Fantasy" -> R.string.home_genre_fantasy
        "Game" -> R.string.home_genre_game
        "Girls" -> R.string.home_genre_girls
        "Guys" -> R.string.home_genre_guys
        "Landscape" -> R.string.home_genre_landscape
        "Medieval" -> R.string.home_genre_medieval
        "Memes" -> R.string.home_genre_memes
        "MMD" -> R.string.home_genre_mmd
        "Music" -> R.string.home_genre_music
        "Nature" -> R.string.home_genre_nature
        "Pixel art" -> R.string.home_genre_pixel_art
        "Relaxing" -> R.string.home_genre_relaxing
        "Retro" -> R.string.home_genre_retro
        "Sci-Fi" -> R.string.home_genre_sci_fi
        "Sports" -> R.string.home_genre_sports
        "Technology" -> R.string.home_genre_technology
        "Television" -> R.string.home_genre_television
        "Vehicle" -> R.string.home_genre_vehicle
        "Unspecified" -> R.string.home_genre_unspecified
        else -> null
    }

@Composable
internal fun String.localizedOfficialTag(): String = officialTagLabelRes()?.let { stringResource(it) } ?: this

@StringRes
private fun String.officialTagLabelRes(): Int? =
    when (this) {
        "Approved" -> R.string.home_tag_approved
        "Audio responsive" -> R.string.home_tag_audio_responsive
        "Customizable" -> R.string.home_tag_customizable
        "Puppet Warp" -> R.string.home_tag_puppet_warp
        "Media Integration" -> R.string.home_tag_media_integration
        "User Shortcut" -> R.string.home_tag_user_shortcut
        "Video Texture" -> R.string.home_tag_video_texture
        "Asset Pack" -> R.string.home_tag_asset_pack
        else -> null
    }

@Composable
internal fun String.localizedResolutionGroup(): String =
    stringResource(
        when (this) {
            "widescreen" -> R.string.home_resolution_group_widescreen
            "ultrawide" -> R.string.home_resolution_group_ultrawide
            "dual" -> R.string.home_resolution_group_dual
            "triple" -> R.string.home_resolution_group_triple
            "portrait" -> R.string.home_resolution_group_portrait
            else -> R.string.home_resolution_group_other
        },
    )

@Composable
internal fun String.localizedResolution(): String = resolutionLabelRes()?.let { stringResource(it) } ?: this

@StringRes
private fun String.resolutionLabelRes(): Int? =
    when (this) {
        "Standard" -> R.string.home_resolution_standard
        "Ultrawide" -> R.string.home_resolution_ultrawide
        "Dual monitor" -> R.string.home_resolution_dual_monitor
        "Triple monitor" -> R.string.home_resolution_triple_monitor
        "Portrait" -> R.string.home_resolution_portrait
        "Other resolution" -> R.string.home_resolution_other
        "Dynamic resolution" -> R.string.home_resolution_dynamic
        else -> null
    }

internal const val FILTER_COLLAPSE_OFFSET_PX = 24
internal const val HOME_FILTER_PAGE_EXIT_DURATION_MS = 90
internal const val HOME_FILTER_PAGE_ENTER_DURATION_MS = 150
internal const val HOME_FILTER_SHEET_PAGE_SIZE_DURATION_MS = 220
internal const val FILTER_SAVER_SEPARATOR = "\u001F"
internal const val HOME_FILTER_PAGE_SIZE_DURATION_MS = 300
internal val HOME_FILTER_PAGE_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

// Keep the discover header compact while providing explicit no-label content
// padding so the hint and entered text are never vertically clipped.
internal val HOME_SEARCH_FIELD_HEIGHT = WallHubSpacing.xxl
internal val HOME_GRID_HORIZONTAL_PADDING = WallHubSpacing.md
internal val HOME_GRID_ITEM_SPACING = WallHubSpacing.compact
internal const val HOME_AUTO_LOAD_MORE_THRESHOLD = 4
internal const val HOME_VIEW_LAYOUT_ANIMATION_DURATION_MS = 400
internal const val HOME_VIEW_CARD_LAYOUT_DURATION_MS = HOME_VIEW_LAYOUT_ANIMATION_DURATION_MS
internal const val HOME_VIEW_TYPE_TAG_LAYOUT_DURATION_MS = 260
internal const val HOME_VIEW_LAYOUT_POSITION_EPSILON_PX = 0.5f
internal const val HOME_VIEW_LAYOUT_SCALE_EPSILON = 0.005f
internal const val HOME_VIEW_LAYOUT_MIN_SCALE = 0.01f
internal const val HOME_VIEW_EDGE_ENTRY_OFFSET_FRACTION = 0.08f
internal const val HOME_VIEW_ACTION_CONTENT_FADE_START = 0.18f
internal const val HOME_VIEW_ACTION_CONTENT_FADE_END = 0.58f
internal val HOME_VIEW_LAYOUT_EASING = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
internal val HOME_COVER_CORNER_RADIUS = WallHubSpacing.sm
internal val HOME_WALLPAPER_CARD_SHAPE = WallHubShapeTokens.medium
internal const val HOME_COMPACT_TYPE_TAG_SCALE = 0.84f
internal val HOME_TYPE_TAG_HORIZONTAL_PADDING = WallHubSpacing.xs
internal val HOME_TYPE_TAG_VERTICAL_PADDING = WallHubSpacing.xxs
internal val HOME_CONTEXT_MENU_PRESS_TRANSLATION_Y = WallHubSpacing.hairline
internal const val HOME_CONTEXT_MENU_GRID_PRESS_SCALE = 0.985f
internal const val HOME_CONTEXT_MENU_LIST_PRESS_SCALE = 0.99f
internal const val HOME_CONTEXT_MENU_PRESS_STIFFNESS = 500f
internal val HOME_CONTEXT_MENU_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
internal val CARD_TITLE_HEIGHT = WallHubSizeTokens.cardTitleHeight
internal val CARD_ACTION_HEIGHT = WallHubSizeTokens.compactActionHeight
internal val TWO_COLUMN_CARD_COPY_TOP_PADDING = WallHubSpacing.xs
internal val TWO_COLUMN_CARD_TITLE_STATISTICS_SPACING = WallHubSpacing.xxs
internal val TWO_COLUMN_CARD_STATISTICS_LINE_HEIGHT = 14.sp
internal val GRID_CARD_STATISTICS_LINE_HEIGHT = 16.sp
internal val TWO_COLUMN_CARD_STATISTICS_ICON_SIZE = 13.dp
internal val TWO_COLUMN_CARD_STATISTICS_ICON_SPACING = 2.5.dp
internal val TWO_COLUMN_CARD_STATISTICS_ITEM_SPACING = 5.dp
internal val LIST_CARD_ACTION_TOP_PADDING = 7.dp
internal val LIST_CARD_TITLE_STATISTICS_SPACING = 7.dp
internal val VIEW_MODE_TOGGLE_LABEL_INSET = 5.dp
internal val TWO_COLUMN_CARD_STATISTICS_ROW_SPACING = WallHubSpacing.xxxs
internal val TWO_COLUMN_CARD_ACTION_TOP_PADDING = WallHubSpacing.dense
internal const val TWO_COLUMN_CARD_STATISTICS_MIN_FONT_SIZE = 10.5f
internal const val TWO_COLUMN_CARD_STATISTICS_MAX_FONT_SIZE = 11.5f
internal const val TWO_COLUMN_CARD_STATISTICS_FONT_WIDTH_DIVISOR = 4.6f
internal val HOME_VIEW_MODE_TOGGLE_INSET = 3.dp
internal val HOME_VIEW_MODE_TOGGLE_BUTTON_SIZE = 42.dp
internal val HOME_VIEW_MODE_TOGGLE_HEIGHT = 48.dp
internal val HOME_VIEW_MODE_TOGGLE_WIDTH = 90.dp
internal const val HOME_VIEW_MODE_TOGGLE_DURATION_MS = 240
internal val GRID_CARD_ACTION_CORNER_RADIUS = WallHubSpacing.sm
internal val LIST_CARD_ACTION_CORNER_RADIUS = WallHubSpacing.sm
internal val LIST_CARD_MEDIA_SIZE = 104.dp
internal val LIST_CARD_ACTION_SIZE = WallHubSizeTokens.compactActionHeight
internal val LIST_CARD_ACTION_END_PADDING = WallHubSpacing.compact
internal val LIST_CARD_COPY_HORIZONTAL_PADDING = 20.dp
internal val GRID_CARD_COPY_HORIZONTAL_PADDING = 20.dp
