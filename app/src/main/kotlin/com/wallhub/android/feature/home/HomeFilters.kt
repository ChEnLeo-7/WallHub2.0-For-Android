@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.home

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wallhub.android.core.designsystem.WallHubFilterChip
import com.wallhub.android.core.designsystem.WallHubShapeTokens
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.text
import com.wallhub.android.core.model.AppLanguage
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
                    language = config.language,
                    draft = draft,
                    defaults = defaults,
                    onReset = { updateSelection(defaults) },
                    horizontalPadding = horizontalPadding,
                )
                if (compact) {
                    HomeFilterPageNavigation(
                        pages = pages,
                        selectedPage = selectedPage,
                        draft = draft,
                        language = config.language,
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
                            language = config.language,
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
    language: AppLanguage,
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
            text = language.text("筛选与排序", "Filter and sort"),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onReset,
            enabled = draft != defaults,
        ) {
            Text(language.text("恢复默认", "Reset"))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeFilterPageNavigation(
    pages: List<HomeFilterPage>,
    selectedPage: HomeFilterPage,
    draft: HomeFilterSelection,
    language: AppLanguage,
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
                                text = page.label(language),
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
                            text = page.label(language),
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
                title = config.language.text("排序依据", "Sort by"),
                supportingText =
                    config.language.text(
                        "选择创意工坊结果的排列方式",
                        "Choose how Workshop results are ordered",
                    ),
            )
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            ) {
                WorkshopSort.entries.forEach { sort ->
                    HomeFilterChoiceRow(
                        label = sort.label(config.language),
                        selected = draft.sort == sort,
                        onClick = { onDraftChanged(draft.copy(sort = sort)) },
                    )
                }
            }
        }
        HomeFilterSectionCard(enabled = draft.sort == WorkshopSort.TRENDING) {
            HomeFilterSectionHeading(
                title = config.language.text("时间范围", "Time range"),
                supportingText =
                    config.language.text(
                        "仅“热门”排序会使用时间范围",
                        "Time range is available only for Popular sorting",
                    ),
                enabled = draft.sort == WorkshopSort.TRENDING,
            )
            FlowRow(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            ) {
                timeRangeOptions(config.language, draft.days).forEach { (days, label) ->
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
                title = config.language.text("壁纸类型", "Wallpaper type"),
                supportingText =
                    if (config.multiSelect) {
                        config.language.text("可以同时选择多个类型", "You can select multiple types")
                    } else {
                        config.language.text("当前设置为单选", "Currently configured for single selection")
                    },
            )
            FlowRow(
                modifier = if (config.multiSelect) Modifier else Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            ) {
                HomeDraftFilterChip(
                    label = config.language.text("不限", "Any"),
                    selected = draft.types.isEmpty(),
                    singleChoice = !config.multiSelect,
                    onClick = { onDraftChanged(draft.copy(types = emptySet())) },
                )
                listOf(WorkshopType.SCENE, WorkshopType.VIDEO, WorkshopType.WEB).forEach { type ->
                    HomeDraftFilterChip(
                        label = type.label(config.language),
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
                title = config.language.text("年龄评级", "Age rating"),
                supportingText =
                    if (config.matureContentEnabled) {
                        config.language.text("已允许显示成人内容选项", "Mature content options are available")
                    } else {
                        config.language.text("成人内容已在设置中关闭", "Mature content is disabled in Settings")
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
                                    config.language.text("全部允许级别", "All allowed")
                                } else {
                                    rating.label(config.language)
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
                title = config.language.text("内容分类", "Genres"),
                supportingText =
                    config.language.text(
                        "选择至少一个分类；全选时视为不限",
                        "Choose one or more genres; all selected means any",
                    ),
                actionLabel = config.language.text("反选", "Invert"),
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
                    label = config.language.text("不限", "Any"),
                    selected = genresUnrestricted,
                    onClick = { onDraftChanged(draft.copy(genres = allGenres)) },
                )
                WorkshopFilterCatalog.genres.forEach { genre ->
                    HomeDraftFilterChip(
                        label = genre.localizedGenre(config.language),
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
                title = config.language.text("官方特性", "Official features"),
                supportingText =
                    config.language.text(
                        "所选特性需要同时匹配",
                        "Results must match every selected feature",
                    ),
                actionLabel = config.language.text("反选", "Invert"),
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
                    label = config.language.text("不限", "Any"),
                    selected = draft.officialTags.isEmpty(),
                    onClick = { onDraftChanged(draft.copy(officialTags = emptySet())) },
                )
                WorkshopFilterCatalog.officialTags.forEach { tag ->
                    HomeDraftFilterChip(
                        label = tag.localizedOfficialTag(config.language),
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
                title = config.language.text("分辨率", "Resolution"),
                supportingText =
                    config.language.text(
                        "选择至少一个尺寸；全选时视为不限",
                        "Choose one or more sizes; all selected means any",
                    ),
                actionLabel = config.language.text("反选", "Invert"),
                actionEnabled = !unrestricted,
                onAction = {
                    onDraftChanged(
                        draft.copy(resolutions = draft.resolutions.invertBounded(allResolutions)),
                    )
                },
            )
            HomeDraftFilterChip(
                label = config.language.text("不限", "Any"),
                selected = unrestricted,
                onClick = { onDraftChanged(draft.copy(resolutions = allResolutions)) },
            )
            WorkshopFilterCatalog.resolutionGroups.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs)) {
                    Text(
                        text = group.id.localizedResolutionGroup(config.language),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                    ) {
                        group.options.forEach { resolution ->
                            HomeDraftFilterChip(
                                label = resolution.localizedResolution(config.language),
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

internal fun HomeFilterPage.label(language: AppLanguage): String =
    when (this) {
        HomeFilterPage.BROWSE -> language.text("浏览", "Browse")
        HomeFilterPage.CONTENT -> language.text("内容", "Content")
        HomeFilterPage.THEME -> language.text("主题", "Theme")
        HomeFilterPage.DISPLAY -> language.text("屏幕", "Display")
    }

internal fun HomeFilterPage.summary(
    selection: HomeFilterSelection,
    state: HomeUiState,
): String =
    when (this) {
        HomeFilterPage.BROWSE ->
            if (selection.sort == WorkshopSort.TRENDING) {
                "${selection.sort.label(state.language)} · ${selection.days.label(state.language)}"
            } else {
                selection.sort.label(state.language)
            }

        HomeFilterPage.CONTENT ->
            when (activeSectionCount(selection)) {
                0 -> state.text("不限", "Any")
                1 ->
                    if (selection.types.isNotEmpty()) {
                        selection.types.summary(state.language, state.text("不限", "Any"))
                    } else {
                        selection.ratings.summary(state.language, state.matureContentEnabled)
                    }

                else -> state.text("类型与评级", "Type and rating")
            }

        HomeFilterPage.THEME ->
            if (activeSectionCount(selection) == 0) {
                state.text("不限", "Any")
            } else {
                state.text(
                    "${activeSectionCount(selection)} 个分区",
                    "${activeSectionCount(selection)} sections",
                )
            }

        HomeFilterPage.DISPLAY ->
            if (selection.resolutions == DEFAULT_HOME_RESOLUTION_SELECTION) {
                state.text("不限", "Any")
            } else {
                state.text(
                    "${selection.resolutions.size} 项",
                    "${selection.resolutions.size} selected",
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

internal fun HomeUiState.text(
    zh: String,
    en: String,
): String = if (language == AppLanguage.EN) en else zh

internal fun WorkshopSort.label(language: AppLanguage): String =
    when (this) {
        WorkshopSort.TRENDING -> if (language == AppLanguage.EN) "Popular" else "热门"
        WorkshopSort.MOST_RECENT -> if (language == AppLanguage.EN) "Most recent" else "最新"
        WorkshopSort.TOP_RATED -> if (language == AppLanguage.EN) "Top rated" else "最高评分"
        WorkshopSort.MOST_VOTES -> if (language == AppLanguage.EN) "Most votes" else "最多投票"
        WorkshopSort.MOST_SUBSCRIBERS -> if (language == AppLanguage.EN) "Most subscribers" else "最多订阅"
    }

internal fun Int.label(language: AppLanguage): String =
    when (this) {
        0 -> if (language == AppLanguage.EN) "All time" else "全部时间"
        1 -> if (language == AppLanguage.EN) "Today" else "今天"
        7 -> if (language == AppLanguage.EN) "7 days" else "7 天"
        30 -> if (language == AppLanguage.EN) "30 days" else "30 天"
        90 -> if (language == AppLanguage.EN) "3 months" else "3 个月"
        180 -> if (language == AppLanguage.EN) "6 months" else "半年"
        365 -> if (language == AppLanguage.EN) "1 year" else "一年"
        else -> if (language == AppLanguage.EN) "$this days" else "$this 天"
    }

internal fun timeRangeOptions(
    language: AppLanguage,
    currentDays: Int,
): List<Pair<Int, String>> {
    val finiteRanges =
        (listOf(1, 7, 30, 90, 180, 365) + currentDays)
            .filter { it > 0 }
            .distinct()
            .sorted()
    return (finiteRanges + 0).map { it to it.label(language) }
}

internal fun WorkshopType.label(language: AppLanguage): String =
    when (this) {
        WorkshopType.VIDEO -> if (language == AppLanguage.EN) "Video" else "视频"
        WorkshopType.SCENE -> if (language == AppLanguage.EN) "Scene" else "场景"
        WorkshopType.WEB -> if (language == AppLanguage.EN) "Web" else "网站"
        WorkshopType.UNKNOWN -> if (language == AppLanguage.EN) "Wallpaper" else "壁纸"
    }

internal fun Set<WorkshopType>.summary(
    language: AppLanguage,
    all: String,
): String = if (isEmpty()) all else joinToString(" / ") { it.label(language) }

internal fun WorkshopRating.label(language: AppLanguage): String =
    when (this) {
        WorkshopRating.ALL -> if (language == AppLanguage.EN) "All" else "全部"
        WorkshopRating.EVERYONE -> if (language == AppLanguage.EN) "Everyone" else "大众级"
        WorkshopRating.QUESTIONABLE -> if (language == AppLanguage.EN) "Questionable" else "家长指导级"
        WorkshopRating.MATURE -> if (language == AppLanguage.EN) "Mature" else "限制成人级"
    }

internal fun Set<WorkshopRating>.summary(
    language: AppLanguage,
    matureContentEnabled: Boolean,
): String {
    val normalized = normalizedRatings(matureContentEnabled)
    return when {
        WorkshopRating.ALL in normalized -> WorkshopRating.ALL.label(language)
        !matureContentEnabled && normalized == SAFE_HOME_RATING_SELECTION ->
            language.text("全部允许级别", "All allowed")

        else -> normalized.joinToString(" / ") { it.label(language) }
    }
}

internal fun HomeCardAction.label(language: AppLanguage): String =
    when (this) {
        HomeCardAction.DOWNLOAD -> if (language == AppLanguage.EN) "Download" else "下载"
        HomeCardAction.PLAY_VIDEO -> if (language == AppLanguage.EN) "Play" else "播放"
        HomeCardAction.OPEN_STEAM -> if (language == AppLanguage.EN) "Steam" else "打开 Steam"
    }

internal fun HomeCardAction.icon() =
    when (this) {
        HomeCardAction.DOWNLOAD -> Icons.Outlined.Download
        HomeCardAction.PLAY_VIDEO -> Icons.Outlined.PlayArrow
        HomeCardAction.OPEN_STEAM -> Icons.Outlined.OpenInNew
    }

internal fun AppLanguage.formatCompact(value: Long): String =
    when {
        value >= 1_000_000 ->
            String.format(
                Locale.getDefault(),
                if (this ==
                    AppLanguage.EN
                ) {
                    "%.1fM"
                } else {
                    "%.1f 万"
                },
                if (this == AppLanguage.EN) value / 1_000_000.0 else value / 10_000.0,
            )
        value >= 1_000 -> String.format(Locale.getDefault(), "%.1fK", value / 1_000.0)
        else -> value.toString()
    }

internal fun String.localizedGenre(language: AppLanguage): String {
    if (language == AppLanguage.EN) return this
    return HOME_GENRE_LABELS_ZH[this] ?: this
}

internal fun String.localizedOfficialTag(language: AppLanguage): String {
    if (language == AppLanguage.EN) return this
    return HOME_OFFICIAL_TAG_LABELS_ZH[this] ?: this
}

internal fun String.localizedResolutionGroup(language: AppLanguage): String =
    if (language == AppLanguage.EN) {
        replaceFirstChar { it.uppercase() }
    } else {
        when (this) {
            "widescreen" -> "宽屏"
            "ultrawide" -> "超宽屏"
            "dual" -> "双显示器"
            "triple" -> "三显示器"
            "portrait" -> "纵向屏幕 / 手机"
            else -> "其他"
        }
    }

internal fun String.localizedResolution(language: AppLanguage): String {
    if (language == AppLanguage.EN) return this
    return HOME_RESOLUTION_LABELS_ZH[this] ?: this
}

internal val HOME_GENRE_LABELS_ZH =
    mapOf(
        "Abstract" to "抽象",
        "Animal" to "动物",
        "Anime" to "动漫",
        "Cartoon" to "卡通",
        "CGI" to "CGI",
        "Cyberpunk" to "赛博朋克",
        "Fantasy" to "幻想",
        "Game" to "游戏",
        "Girls" to "女性",
        "Guys" to "男性",
        "Landscape" to "风景",
        "Medieval" to "中世纪",
        "Memes" to "网络事物",
        "MMD" to "MMD",
        "Music" to "音乐",
        "Nature" to "自然",
        "Pixel art" to "像素艺术",
        "Relaxing" to "放松",
        "Retro" to "复古",
        "Sci-Fi" to "科幻",
        "Sports" to "运动",
        "Technology" to "科技",
        "Television" to "电视节目",
        "Vehicle" to "汽车",
        "Unspecified" to "未指定样式",
    )

internal val HOME_OFFICIAL_TAG_LABELS_ZH =
    mapOf(
        "Approved" to "广受好评",
        "Audio responsive" to "音频响应",
        "Customizable" to "可自定义",
        "Puppet Warp" to "骨骼变形",
        "Media Integration" to "媒体集成",
        "User Shortcut" to "用户快捷方式",
        "Video Texture" to "视频纹理",
        "Asset Pack" to "资源包",
    )

internal val HOME_RESOLUTION_LABELS_ZH =
    mapOf(
        "Standard" to "标准",
        "Ultrawide" to "超宽（标准）",
        "Dual monitor" to "双显示器（标准）",
        "Triple monitor" to "三显示器（标准）",
        "Portrait" to "纵向（标准）",
        "Other resolution" to "其他分辨率",
        "Dynamic resolution" to "动态分辨率",
    )

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
internal val HOME_VIEW_MODE_TOGGLE_BUTTON_SIZE = 34.dp
internal val HOME_VIEW_MODE_TOGGLE_HEIGHT = WallHubSizeTokens.compactActionHeight
internal val HOME_VIEW_MODE_TOGGLE_WIDTH = 74.dp
internal const val HOME_VIEW_MODE_TOGGLE_DURATION_MS = 240
internal val GRID_CARD_ACTION_CORNER_RADIUS = WallHubSpacing.sm
internal val LIST_CARD_ACTION_CORNER_RADIUS = WallHubSpacing.sm
internal val LIST_CARD_MEDIA_SIZE = 104.dp
internal val LIST_CARD_ACTION_SIZE = WallHubSizeTokens.compactActionHeight
internal val LIST_CARD_ACTION_END_PADDING = WallHubSpacing.compact
internal val LIST_CARD_COPY_HORIZONTAL_PADDING = 20.dp
internal val GRID_CARD_COPY_HORIZONTAL_PADDING = 20.dp
