@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.localizedAuthor
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.model.DiscoverRailFeedback
import com.wallhub.android.feature.discover.model.DiscoverMetadataSource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun DiscoverRoute(
    onOpenDetail: (Long) -> Unit,
    onOpenRail: (DiscoverRailSpec, String?) -> Unit = { _, _ -> },
    onOpenFollowing: () -> Unit = {},
    onOpenFriendFavorites: () -> Unit = {},
    onOpenFriendCreated: () -> Unit = {},
    refreshRequest: Int = 0,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    DisposableEffect(viewModel) {
        viewModel.setVisible(true)
        onDispose { viewModel.setVisible(false) }
    }
    var handledRefreshRequest by rememberSaveable { mutableStateOf(refreshRequest) }
    LaunchedEffect(refreshRequest) {
        if (refreshRequest != handledRefreshRequest) {
            handledRefreshRequest = refreshRequest
            if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
                listState.animateScrollToItem(0)
            } else {
                viewModel.refresh()
            }
        }
    }
    LaunchedEffect(listState, state.rails.size, state.isLoading, state.hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .map { it >= (listState.layoutInfo.totalItemsCount - 2).coerceAtLeast(0) }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd && state.hasMore) viewModel.loadMore() }
    }
    WallHubPageScaffold(
        title = "",
        titleContent = {
            DiscoverContentMenu(onOpenFollowing, onOpenFriendFavorites, onOpenFriendCreated)
        },
    ) { padding ->
        when {
            state.isPreparing && state.rails.isEmpty() ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            state.rails.isEmpty() && state.error != null -> DiscoverFullPageError(padding, viewModel::refresh)
            else ->
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    if (state.metadataSource == DiscoverMetadataSource.STATIC_FALLBACK) {
                        item(key = "discover-fallback-notice") {
                            Text(
                                stringResource(R.string.discover_static_fallback),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    items(state.rails, key = { it.spec.id }) { rail ->
                        DiscoverRail(
                            rail = rail,
                            feedback = state.feedback[rail.spec.feedbackKey],
                            onOpenDetail = onOpenDetail,
                            onOpenRail = onOpenRail,
                            onRetry = { viewModel.retryRail(rail.spec.id) },
                            onLike = { viewModel.toggleLike(rail.spec.feedbackKey) },
                            onDislike = { viewModel.toggleDislike(rail.spec.feedbackKey) },
                            onFavorite = { title -> viewModel.toggleFavorite(rail, title) },
                        )
                    }
                    item {
                        Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                            when {
                                state.rails.any { it.loadState == DiscoverRailLoadState.LOADING || it.loadState == DiscoverRailLoadState.QUEUED } -> CircularProgressIndicator()
                                state.hasMore ->
                                    IconButton(onClick = viewModel::loadMore) {
                                        Icon(Icons.Outlined.Refresh, stringResource(R.string.discover_load_more))
                                    }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun DiscoverFullPageError(
    padding: PaddingValues,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.discover_load_failed), color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.discover_retry))
            }
        }
    }
}

@Composable
private fun DiscoverRail(
    rail: DiscoverRailState,
    feedback: DiscoverRailFeedback?,
    onOpenDetail: (Long) -> Unit,
    onOpenRail: (DiscoverRailSpec, String?) -> Unit,
    onRetry: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onFavorite: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        DiscoverRailHeader(rail)
        when (rail.loadState) {
            DiscoverRailLoadState.QUEUED,
            DiscoverRailLoadState.LOADING,
            -> Box(Modifier.fillMaxWidth().height(236.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            DiscoverRailLoadState.READY ->
                if (rail.featuredItems.isNotEmpty()) {
                    DiscoverFeaturedCarouselRail(rail, onOpenRail)
                } else {
                    DiscoverCarouselRail(rail, onOpenDetail)
                }
            DiscoverRailLoadState.EMPTY ->
                Text(
                    stringResource(R.string.discover_rail_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            DiscoverRailLoadState.FAILED_RETRYABLE,
            DiscoverRailLoadState.FAILED_FINAL,
            -> Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.discover_rail_load_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRetry) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.discover_retry_rail))
                }
            }
            DiscoverRailLoadState.EVICTED -> Unit
        }
        if (rail.loadState == DiscoverRailLoadState.READY) {
            DiscoverRailActions(
                rail = rail,
                feedback = feedback,
                onOpenRail = onOpenRail,
                onLike = onLike,
                onDislike = onDislike,
                onFavorite = onFavorite,
            )
        }
    }
}

@Composable
private fun DiscoverRailHeader(rail: DiscoverRailState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(railTitle(rail), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun DiscoverRailActions(
    rail: DiscoverRailState,
    feedback: DiscoverRailFeedback?,
    onOpenRail: (DiscoverRailSpec, String?) -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onFavorite: (String) -> Unit,
) {
    val title = railTitle(rail)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        DiscoverTooltipIconButton(stringResource(R.string.discover_like_rail), onLike) {
            Icon(if (feedback?.liked == true) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, null)
        }
        DiscoverTooltipIconButton(stringResource(R.string.discover_dislike_rail), onDislike) {
            Icon(if (feedback?.disliked == true) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, null)
        }
        DiscoverTooltipIconButton(stringResource(R.string.discover_favorite_rail), { onFavorite(title) }) {
            Icon(if (feedback?.favorited == true) Icons.Filled.Star else Icons.Outlined.StarBorder, null)
        }
        if (rail.spec.children.isEmpty()) {
            DiscoverTooltipIconButton(stringResource(R.string.discover_see_more), { onOpenRail(rail.spec, rail.resolvedTitle) }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
            }
        }
    }
}

@Composable
private fun railTitle(rail: DiscoverRailState): String =
    when (rail.spec.titleKind) {
        DiscoverTitleKind.RECENT_APPROVED -> stringResource(R.string.discover_rail_recent_positive)
        DiscoverTitleKind.TRENDING_MONTH -> stringResource(R.string.discover_rail_trending)
        DiscoverTitleKind.TRENDING_YEAR -> stringResource(R.string.discover_rail_trending_year)
        DiscoverTitleKind.MOBILE -> stringResource(R.string.discover_rail_mobile)
        DiscoverTitleKind.AUDIO_RESPONSIVE -> stringResource(R.string.discover_rail_audio_responsive)
        DiscoverTitleKind.GENRE -> stringResource(R.string.discover_rail_genre, rail.resolvedTitle ?: rail.spec.titleArgument.orEmpty())
        DiscoverTitleKind.CREATOR -> if (rail.spec.children.isNotEmpty()) stringResource(R.string.discover_focus_creators) else rail.resolvedTitle ?: stringResource(R.string.discover_rail_creator)
        DiscoverTitleKind.COLLECTION -> if (rail.spec.children.isNotEmpty()) stringResource(R.string.discover_focus_collections) else stringResource(R.string.discover_rail_collection)
        DiscoverTitleKind.KEYWORD -> stringResource(R.string.discover_rail_keyword, rail.resolvedTitle ?: rail.spec.titleArgument.orEmpty())
        DiscoverTitleKind.TOP_YEAR -> stringResource(R.string.discover_top_year, rail.spec.titleArgument.orEmpty())
        DiscoverTitleKind.SEASONAL_SPRING -> stringResource(R.string.discover_seasonal_spring)
        DiscoverTitleKind.SEASONAL_SUMMER -> stringResource(R.string.discover_seasonal_summer)
        DiscoverTitleKind.SEASONAL_FALL -> stringResource(R.string.discover_seasonal_fall)
        DiscoverTitleKind.SEASONAL_HALLOWEEN -> stringResource(R.string.discover_seasonal_halloween)
        DiscoverTitleKind.SEASONAL_WINTER -> stringResource(R.string.discover_seasonal_winter)
        DiscoverTitleKind.FRIEND_FAVORITES -> stringResource(R.string.discover_friend_favorites)
        DiscoverTitleKind.FRIEND_CREATED -> stringResource(R.string.discover_friend_created)
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DiscoverCarouselRail(
    rail: DiscoverRailState,
    onOpenDetail: (Long) -> Unit,
) {
    val carouselState = rememberCarouselState(itemCount = { rail.items.size })
    HorizontalMultiBrowseCarousel(
        state = carouselState,
        preferredItemWidth = 220.dp,
        itemSpacing = 8.dp,
        minSmallItemWidth = 56.dp,
        maxSmallItemWidth = 88.dp,
        contentPadding = PaddingValues(horizontal = 16.dp),
        flingBehavior = CarouselDefaults.multiBrowseFlingBehavior(carouselState),
        modifier = Modifier.fillMaxWidth().height(236.dp),
    ) { page ->
        val item = rail.items[page]
        val itemTitle = item.localizedTitle()
        val itemAuthor = item.localizedAuthor()
        val showMetadata = carouselItemDrawInfo.size >= with(LocalDensity.current) { 160.dp.toPx() }
        Card(
            onClick = { onOpenDetail(item.id) },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxSize().maskClip(MaterialTheme.shapes.extraLarge),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(item.previewUrl, itemTitle, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (showMetadata) {
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = DISCOVER_CARD_TEXT_SCRIM_ALPHA))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = itemTitle,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Text(
                            itemAuthor,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.78f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DiscoverFeaturedCarouselRail(
    rail: DiscoverRailState,
    onOpenRail: (DiscoverRailSpec, String?) -> Unit,
) {
    val carouselState = rememberCarouselState(itemCount = { rail.featuredItems.size })
    HorizontalMultiBrowseCarousel(
        state = carouselState,
        preferredItemWidth = 190.dp,
        itemSpacing = 8.dp,
        minSmallItemWidth = 56.dp,
        maxSmallItemWidth = 88.dp,
        contentPadding = PaddingValues(horizontal = 16.dp),
        flingBehavior = CarouselDefaults.multiBrowseFlingBehavior(carouselState),
        modifier = Modifier.fillMaxWidth().height(210.dp),
    ) { page ->
        val featured = rail.featuredItems[page]
        val featuredTitle = featuredTitle(featured)
        Card(
            onClick = { onOpenRail(featured.spec, featuredTitle) },
            modifier = Modifier.fillMaxSize().maskClip(MaterialTheme.shapes.extraLarge),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = featured.cover.previewUrl,
                    contentDescription = featuredTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = DISCOVER_CARD_TEXT_SCRIM_ALPHA))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (featured.spec.category == DiscoverCategory.CREATOR) {
                        if (!featured.cover.authorAvatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = featured.cover.authorAvatarUrl,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Group,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                    Text(
                        text = featuredTitle,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        modifier = Modifier.padding(top = if (featured.spec.category == DiscoverCategory.CREATOR) 4.dp else 0.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun featuredTitle(featured: DiscoverFeaturedItem): String =
    when (featured.spec.titleKind) {
        DiscoverTitleKind.TOP_YEAR -> stringResource(R.string.discover_top_year, featured.spec.titleArgument.orEmpty())
        DiscoverTitleKind.SEASONAL_SPRING -> stringResource(R.string.discover_seasonal_spring)
        DiscoverTitleKind.SEASONAL_SUMMER -> stringResource(R.string.discover_seasonal_summer)
        DiscoverTitleKind.SEASONAL_FALL -> stringResource(R.string.discover_seasonal_fall)
        DiscoverTitleKind.SEASONAL_HALLOWEEN -> stringResource(R.string.discover_seasonal_halloween)
        DiscoverTitleKind.SEASONAL_WINTER -> stringResource(R.string.discover_seasonal_winter)
        else -> featured.title
    }

@Composable
private fun DiscoverContentMenu(
    onOpenFollowing: () -> Unit,
    onOpenFriendFavorites: () -> Unit,
    onOpenFriendCreated: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.Bookmarks, null)
            Text(stringResource(R.string.discover_content_menu), modifier = Modifier.padding(start = 8.dp))
            Icon(Icons.Outlined.KeyboardArrowDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.discover_following)) },
                leadingIcon = { Icon(Icons.Outlined.Bookmarks, null) },
                onClick = {
                    expanded = false
                    onOpenFollowing()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.discover_friend_favorites)) },
                leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, null) },
                onClick = {
                    expanded = false
                    onOpenFriendFavorites()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.discover_friend_created)) },
                leadingIcon = { Icon(Icons.Outlined.Group, null) },
                onClick = {
                    expanded = false
                    onOpenFriendCreated()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverTooltipIconButton(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) { icon() }
    }
}

private const val DISCOVER_CARD_TEXT_SCRIM_ALPHA = 0.56f
