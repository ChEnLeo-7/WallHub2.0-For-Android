package com.wallhub.android.feature.discover

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.model.DiscoverFeedbackRepository
import com.wallhub.android.core.model.DiscoverSavedQuery
import com.wallhub.android.core.model.DiscoverSavedQueryCategory
import com.wallhub.android.core.model.DiscoverSavedQueryRepository
import com.wallhub.android.core.model.DiscoverSavedQuerySource
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.WorkshopSort
import dagger.hilt.android.lifecycle.HiltViewModel
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DiscoverFollowingViewModel
    @Inject
    constructor(
        private val savedQueryRepository: DiscoverSavedQueryRepository,
        private val feedbackRepository: DiscoverFeedbackRepository,
    ) : ViewModel() {
        val queries = savedQueryRepository.queries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun remove(query: DiscoverSavedQuery) {
            viewModelScope.launch {
                savedQueryRepository.remove(query.id)
                if (query.source == DiscoverSavedQuerySource.FAVORITE) feedbackRepository.setFavorited(query.id, false)
            }
        }
    }

@Composable
fun DiscoverFollowingRoute(
    onBack: () -> Unit,
    onAddQuery: () -> Unit,
    onOpenQuery: (DiscoverSavedQuery) -> Unit,
    viewModel: DiscoverFollowingViewModel = hiltViewModel(),
) {
    val queries by viewModel.queries.collectAsStateWithLifecycle()
    WallHubPageScaffold(
        title = stringResource(R.string.discover_following),
        showBackButton = true,
        onNavigateUp = onBack,
        actions = {
            IconButton(onClick = onAddQuery) {
                Icon(Icons.Outlined.Add, stringResource(R.string.discover_create_query))
            }
        },
    ) { padding ->
        if (queries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.discover_following_empty), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onAddQuery, modifier = Modifier.padding(top = 16.dp)) {
                        Icon(Icons.Outlined.Add, null)
                        Text(stringResource(R.string.discover_create_query), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(queries, key = DiscoverSavedQuery::id) { query ->
                    ListItem(
                        headlineContent = { Text(query.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                if (query.source == DiscoverSavedQuerySource.FAVORITE) {
                                    stringResource(R.string.discover_followed_topic)
                                } else {
                                    stringResource(query.category.labelRes())
                                },
                            )
                        },
                        leadingContent = {
                            if (!query.previewUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = query.previewUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Icon(query.category.icon(), null, modifier = Modifier.size(32.dp))
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { viewModel.remove(query) }) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.discover_remove_followed_query))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.clickable { onOpenQuery(query) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

private sealed interface DiscoverQueryEditorEffect {
    data class Saved(val query: DiscoverSavedQuery) : DiscoverQueryEditorEffect
}

@HiltViewModel
class DiscoverQueryEditorViewModel
    @Inject
    constructor(
        private val repository: DiscoverSavedQueryRepository,
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val effectChannel = Channel<DiscoverQueryEditorEffect>(Channel.BUFFERED)
        private val effects: Flow<DiscoverQueryEditorEffect> = effectChannel.receiveAsFlow()

        fun observeSaved(onSaved: suspend (DiscoverSavedQuery) -> Unit) {
            viewModelScope.launch {
                effects.collect { effect -> if (effect is DiscoverQueryEditorEffect.Saved) onSaved(effect.query) }
            }
        }

        fun save(
            category: DiscoverSavedQueryCategory,
            sort: DiscoverCustomSort,
            input: String,
            title: String,
        ) {
            val normalized = input.trim()
            viewModelScope.launch {
                val preferences = settingsRepository.preferences.first()
                val identity = "${category.name}|${sort.name}|$normalized"
                val query =
                    DiscoverSavedQuery(
                        id = "custom:${identity.sha256().take(24)}",
                        title = title,
                        category = category,
                        source = DiscoverSavedQuerySource.CUSTOM,
                        semantic = sort.semantic,
                        searchText = normalized.takeIf { category == DiscoverSavedQueryCategory.KEYWORD }.orEmpty(),
                        creatorId = normalized.takeIf { category == DiscoverSavedQueryCategory.CREATOR },
                        collectionId = normalized.toLongOrNull().takeIf { category == DiscoverSavedQueryCategory.COLLECTION },
                        sort = sort.sort,
                        days = sort.days,
                        exactPhrase = false,
                        allowNsfw = preferences.matureContentEnabled,
                    )
                repository.upsert(query)
                effectChannel.send(DiscoverQueryEditorEffect.Saved(query))
            }
        }
    }

enum class DiscoverCustomSort(
    val sort: WorkshopSort,
    val days: Int,
    val semantic: String,
    @StringRes val labelRes: Int,
) {
    TREND_YEAR(WorkshopSort.TRENDING, 365, "trend_year", R.string.discover_sort_trend_year),
    TREND_WEEK(WorkshopSort.TRENDING, 7, "trend_week", R.string.discover_sort_trend_week),
    RECENT(WorkshopSort.MOST_RECENT, 0, "most_recent", R.string.discover_sort_recent),
    SUBSCRIPTIONS(WorkshopSort.MOST_SUBSCRIBERS, 0, "subscriptions", R.string.discover_sort_subscriptions),
    CREATOR_VOTES(WorkshopSort.MOST_VOTES, 0, "published_votes", R.string.discover_sort_creator_votes),
    CREATOR_RECENT(WorkshopSort.MOST_RECENT, 0, "published_desc", R.string.discover_sort_creator_recent),
    COLLECTION(WorkshopSort.MOST_RECENT, 0, "collection", R.string.discover_query_category_collection),
}

@Composable
fun DiscoverQueryEditorRoute(
    onBack: () -> Unit,
    onSaved: (DiscoverSavedQuery) -> Unit,
    viewModel: DiscoverQueryEditorViewModel = hiltViewModel(),
) {
    var category by remember { mutableStateOf(DiscoverSavedQueryCategory.KEYWORD) }
    var sort by remember { mutableStateOf(DiscoverCustomSort.TREND_YEAR) }
    var input by remember { mutableStateOf("") }
    val creatorPrefix = stringResource(R.string.discover_query_category_creator)
    val collectionPrefix = stringResource(R.string.discover_query_category_collection)
    LaunchedEffect(viewModel) { viewModel.observeSaved { onSaved(it) } }
    val valid =
        input.trim().isNotEmpty() &&
            (category == DiscoverSavedQueryCategory.KEYWORD || input.trim().all(Char::isDigit))
    WallHubPageScaffold(
        title = stringResource(R.string.discover_create_query),
        showBackButton = true,
        onNavigateUp = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.discover_query_category), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiscoverSavedQueryCategory.entries.filter { it != DiscoverSavedQueryCategory.TOPIC }.forEach { item ->
                    FilterChip(
                        selected = category == item,
                        onClick = {
                            category = item
                            sort = item.sortOptions().first()
                        },
                        label = { Text(stringResource(item.labelRes())) },
                        leadingIcon = { Icon(item.icon(), null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            if (category != DiscoverSavedQueryCategory.COLLECTION) {
                DiscoverSortMenu(
                    options = category.sortOptions(),
                    selected = sort,
                    onSelected = { sort = it },
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(category.inputLabelRes())) },
                supportingText = {
                    if (input.isNotBlank() && !valid) Text(stringResource(R.string.discover_query_numeric_error))
                },
                isError = input.isNotBlank() && !valid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (category == DiscoverSavedQueryCategory.KEYWORD) KeyboardType.Text else KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onBack) { Text(stringResource(android.R.string.cancel)) }
                Button(
                    enabled = valid,
                    onClick = {
                        val title =
                            when (category) {
                                DiscoverSavedQueryCategory.KEYWORD -> input.trim()
                                DiscoverSavedQueryCategory.CREATOR -> "$creatorPrefix ${input.trim()}"
                                DiscoverSavedQueryCategory.COLLECTION -> "$collectionPrefix ${input.trim()}"
                                DiscoverSavedQueryCategory.TOPIC -> input.trim()
                            }
                        viewModel.save(category, sort, input, title)
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(stringResource(android.R.string.ok)) }
            }
        }
    }
}

@Composable
private fun DiscoverSortMenu(
    options: List<DiscoverCustomSort>,
    selected: DiscoverCustomSort,
    onSelected: (DiscoverCustomSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.discover_query_sort), style = MaterialTheme.typography.titleSmall)
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(stringResource(selected.labelRes))
                Icon(Icons.Outlined.KeyboardArrowDown, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.labelRes)) },
                        onClick = {
                            expanded = false
                            onSelected(option)
                        },
                    )
                }
            }
        }
    }
}

private fun DiscoverSavedQueryCategory.sortOptions(): List<DiscoverCustomSort> =
    when (this) {
        DiscoverSavedQueryCategory.KEYWORD -> listOf(DiscoverCustomSort.TREND_YEAR, DiscoverCustomSort.TREND_WEEK, DiscoverCustomSort.RECENT, DiscoverCustomSort.SUBSCRIPTIONS)
        DiscoverSavedQueryCategory.CREATOR -> listOf(DiscoverCustomSort.CREATOR_VOTES, DiscoverCustomSort.CREATOR_RECENT)
        DiscoverSavedQueryCategory.COLLECTION -> listOf(DiscoverCustomSort.COLLECTION)
        DiscoverSavedQueryCategory.TOPIC -> listOf(DiscoverCustomSort.TREND_YEAR)
    }

@StringRes
private fun DiscoverSavedQueryCategory.labelRes(): Int =
    when (this) {
        DiscoverSavedQueryCategory.KEYWORD, DiscoverSavedQueryCategory.TOPIC -> R.string.discover_query_category_keyword
        DiscoverSavedQueryCategory.CREATOR -> R.string.discover_query_category_creator
        DiscoverSavedQueryCategory.COLLECTION -> R.string.discover_query_category_collection
    }

@StringRes
private fun DiscoverSavedQueryCategory.inputLabelRes(): Int =
    when (this) {
        DiscoverSavedQueryCategory.KEYWORD, DiscoverSavedQueryCategory.TOPIC -> R.string.discover_query_keyword
        DiscoverSavedQueryCategory.CREATOR -> R.string.discover_query_creator_id
        DiscoverSavedQueryCategory.COLLECTION -> R.string.discover_query_collection_id
    }

private fun DiscoverSavedQueryCategory.icon() =
    when (this) {
        DiscoverSavedQueryCategory.KEYWORD, DiscoverSavedQueryCategory.TOPIC -> Icons.Outlined.Search
        DiscoverSavedQueryCategory.CREATOR -> Icons.Outlined.PersonSearch
        DiscoverSavedQueryCategory.COLLECTION -> Icons.Outlined.CollectionsBookmark
    }

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }
