@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.discover

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil.compose.AsyncImage
import com.wallhub.android.DISCOVER_GROUP_SEPARATOR
import com.wallhub.android.DISCOVER_VALUE_SEPARATOR
import com.wallhub.android.DiscoverResultsDestination
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubContextMenuAction
import com.wallhub.android.core.designsystem.WallHubContextMenuCard
import com.wallhub.android.core.designsystem.WallHubContextMenuLayer
import com.wallhub.android.core.designsystem.WallHubContextMenuMetadataItem
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.rememberWallHubContextMenuState
import com.wallhub.android.core.designsystem.localizedAuthor
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.designsystem.requiresLegacyPublicDownloadPermission
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadTaskRepository
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayArrow

data class DiscoverResultsState(
    val items: List<WorkshopSummary> = emptyList(),
    val isLoading: Boolean = false,
    val page: Int = 0,
    val hasMore: Boolean = true,
    val error: String? = null,
)

sealed interface DiscoverResultsEffect {
    data class ShowMessage(
        val messageRes: Int,
        val formatArgs: List<Any> = emptyList(),
    ) : DiscoverResultsEffect
}

@HiltViewModel
class DiscoverResultsViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        @ApplicationContext private val applicationContext: Context,
        private val workshopRepository: WorkshopRepository,
        private val collectionResolver: DiscoverCollectionResolver,
        private val networkBudget: DiscoverNetworkBudget,
        private val downloadTaskRepository: DownloadTaskRepository,
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        internal val destination = savedStateHandle.toRoute<DiscoverResultsDestination>()
        private val mutableState = MutableStateFlow(DiscoverResultsState())
        val state: StateFlow<DiscoverResultsState> = mutableState.asStateFlow()
        private var loadJob: Job? = null
        private val effectChannel = Channel<DiscoverResultsEffect>(Channel.BUFFERED)
        val effects: Flow<DiscoverResultsEffect> = effectChannel.receiveAsFlow()

        init {
            loadNextPage()
        }

        fun retry() {
            if (mutableState.value.items.isEmpty()) {
                mutableState.value = DiscoverResultsState()
            }
            loadNextPage()
        }

        fun loadNextPage() {
            val current = mutableState.value
            if (current.isLoading || !current.hasMore) return
            val nextPage = current.page + 1
            loadJob =
                viewModelScope.launch {
                    mutableState.value = current.copy(isLoading = true, error = null)
                    try {
                        val result =
                            destination.collectionId?.let { collectionId ->
                                collectionResolver.browse(collectionId, nextPage, RESULTS_PAGE_SIZE)
                            } ?: networkBudget.withPermit {
                                workshopRepository.browse(destination.toWorkshopQuery(nextPage))
                            }
                        val filtered = destination.filterRequiredTagGroups(result.items)
                        mutableState.value =
                            mutableState.value.copy(
                                items = (mutableState.value.items + filtered).distinctBy(WorkshopSummary::id),
                                isLoading = false,
                                page = nextPage,
                                hasMore = result.hasNextPage,
                                error = null,
                            )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        mutableState.value =
                            mutableState.value.copy(
                                isLoading = false,
                                error = error.message ?: "discover_results_failed",
                            )
                    }
                }
        }

        fun download(item: WorkshopSummary) {
            viewModelScope.launch {
                runCatching {
                    val preferences = settingsRepository.preferences.first()
                    downloadTaskRepository.enqueue(
                        DownloadRequest(
                            workshopId = item.id,
                            title = applicationContext.localizedTitle(item),
                            type = item.type,
                            previewUrl = item.previewUrl,
                            expectedTotalBytes = item.fileSizeBytes ?: 0L,
                            outputTreeUri = preferences.outputTreeUri,
                            exportFormat = ExportFormat.AUTO,
                        ),
                    )
                }.onSuccess { task ->
                    effectChannel.send(
                        DiscoverResultsEffect.ShowMessage(
                            R.string.home_added_to_download_queue,
                            listOf(task.title),
                        ),
                    )
                }.onFailure {
                    effectChannel.send(DiscoverResultsEffect.ShowMessage(R.string.home_unable_to_queue_download))
                }
            }
        }
    }

@Composable
fun DiscoverResultsRoute(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit,
    viewModel: DiscoverResultsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val contextMenuState = rememberWallHubContextMenuState()
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboardManager.current
    val currentOpenDetail by rememberUpdatedState(onOpenDetail)
    val currentSearchAuthor by rememberUpdatedState(onSearchAuthor)
    var pendingDownload by remember { mutableStateOf<WorkshopSummary?>(null) }
    val storagePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val item = pendingDownload ?: return@rememberLauncherForActivityResult
            pendingDownload = null
            if (granted) {
                viewModel.download(item)
            } else {
                Toast.makeText(context, R.string.home_storage_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    LaunchedEffect(viewModel, context, resources) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DiscoverResultsEffect.ShowMessage ->
                    Toast.makeText(
                        context.applicationContext,
                        resources.getString(effect.messageRes, *effect.formatArgs.toTypedArray()),
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        }
    }
    LaunchedEffect(gridState, state.items.size, state.hasMore, state.isLoading) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .map { it >= (gridState.layoutInfo.totalItemsCount - 4).coerceAtLeast(0) }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) viewModel.loadNextPage() }
    }
    WallHubContextMenuLayer(
        state = contextMenuState,
        onActiveChanged = {},
        modifier = Modifier.fillMaxSize(),
    ) {
        WallHubPageScaffold(
            title = discoverResultsTitle(viewModel.destination, state.items.firstOrNull()?.author),
            showBackButton = true,
            onNavigateUp = onBack,
        ) { padding ->
            when {
                state.items.isEmpty() && state.isLoading ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                state.items.isEmpty() && state.error != null ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.discover_results_load_failed), color = MaterialTheme.colorScheme.error)
                            Button(onClick = viewModel::retry, modifier = Modifier.padding(top = 12.dp)) {
                                Text(stringResource(R.string.discover_retry))
                            }
                        }
                    }
                state.items.isEmpty() ->
                    WallHubEmptyState(
                        icon = Icons.Outlined.Collections,
                        title = stringResource(R.string.discover_rail_empty),
                        actionLabel = stringResource(R.string.discover_retry),
                        onAction = viewModel::retry,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                else ->
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(180.dp),
                        state = gridState,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .onGloballyPositioned { contextMenuState.gridCoordinates = it },
                        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.items, key = WorkshopSummary::id) { item ->
                            val itemTitle = item.localizedTitle()
                            val itemAuthor = item.localizedAuthor()
                            WallHubContextMenuCard(
                                itemId = item.id,
                                shape = MaterialTheme.shapes.medium,
                                state = contextMenuState,
                                onClick = { currentOpenDetail(item.id) },
                                clickLabel = stringResource(R.string.home_view_details),
                                longClickLabel = stringResource(R.string.home_open_actions_menu),
                                modifier = Modifier.fillMaxWidth(),
                                menuContent = { dismiss ->
                                    WallHubContextMenuMetadataItem(
                                        label = stringResource(R.string.home_wallpaper_title),
                                        value = itemTitle,
                                        icon = Icons.Outlined.ContentCopy,
                                        onClick = {
                                            clipboard.setText(AnnotatedString(itemTitle))
                                            Toast.makeText(context, R.string.home_wallpaper_title_copied, Toast.LENGTH_SHORT).show()
                                            dismiss()
                                        },
                                    )
                                    WallHubContextMenuMetadataItem(
                                        label = stringResource(R.string.home_author),
                                        value = itemAuthor,
                                        icon = Icons.Outlined.PersonOutline,
                                        onClick = {
                                            dismiss()
                                            currentSearchAuthor(item.creatorId ?: item.author)
                                        },
                                    )
                                    WallHubContextMenuMetadataItem(
                                        label = stringResource(R.string.home_project_id),
                                        value = item.id.toString(),
                                        icon = Icons.Outlined.ContentCopy,
                                        onClick = {
                                            clipboard.setText(AnnotatedString(item.id.toString()))
                                            Toast.makeText(context, R.string.home_project_id_copied, Toast.LENGTH_SHORT).show()
                                            dismiss()
                                        },
                                    )
                                    Spacer(Modifier.padding(top = WallHubSpacing.xxxs))
                                    WallHubContextMenuAction(
                                        text = stringResource(R.string.home_download),
                                        icon = Icons.Outlined.Download,
                                        onClick = {
                                            dismiss()
                                            if (context.requiresLegacyPublicDownloadPermission()) {
                                                pendingDownload = item
                                                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                            } else {
                                                viewModel.download(item)
                                            }
                                        },
                                    )
                                    if (item.type == WorkshopType.VIDEO) {
                                        WallHubContextMenuAction(
                                            text = stringResource(R.string.home_open_video_details),
                                            icon = Icons.Outlined.PlayArrow,
                                            onClick = {
                                                dismiss()
                                                currentOpenDetail(item.id)
                                            },
                                        )
                                    }
                                    WallHubContextMenuAction(
                                        text = stringResource(R.string.home_open_in_steam),
                                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                                        onClick = {
                                            dismiss()
                                            val intent =
                                                Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse("https://steamcommunity.com/sharedfiles/filedetails/?id=${item.id}"),
                                                )
                                            runCatching { context.startActivity(intent) }
                                                .onFailure { currentOpenDetail(item.id) }
                                        },
                                    )
                                },
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    AsyncImage(
                                        model = item.previewUrl,
                                        contentDescription = itemTitle,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                                    )
                                    Text(
                                        itemTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
                                    )
                                    Text(
                                        itemAuthor,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
                                    )
                                }
                            }
                        }
                        if (state.isLoading) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun discoverResultsTitle(
    destination: DiscoverResultsDestination,
    resolvedAuthor: String?,
): String =
    when (destination.titleKind) {
        DiscoverTitleKind.RECENT_APPROVED.name -> stringResource(R.string.discover_rail_recent_positive)
        DiscoverTitleKind.TRENDING_MONTH.name -> stringResource(R.string.discover_rail_trending)
        DiscoverTitleKind.TRENDING_YEAR.name -> stringResource(R.string.discover_rail_trending_year)
        DiscoverTitleKind.MOBILE.name -> stringResource(R.string.discover_rail_mobile)
        DiscoverTitleKind.AUDIO_RESPONSIVE.name -> stringResource(R.string.discover_rail_audio_responsive)
        DiscoverTitleKind.CREATOR.name ->
            destination.titleArgument
                ?.takeUnless { it == destination.creatorId }
                ?: resolvedAuthor
                ?: stringResource(R.string.discover_rail_creator)
        DiscoverTitleKind.COLLECTION.name -> stringResource(R.string.discover_rail_collection)
        DiscoverTitleKind.GENRE.name -> stringResource(R.string.discover_rail_genre, destination.titleArgument.orEmpty())
        DiscoverTitleKind.TOP_YEAR.name -> stringResource(R.string.discover_top_year, destination.titleArgument.orEmpty())
        DiscoverTitleKind.SEASONAL_SPRING.name -> stringResource(R.string.discover_seasonal_spring)
        DiscoverTitleKind.SEASONAL_SUMMER.name -> stringResource(R.string.discover_seasonal_summer)
        DiscoverTitleKind.SEASONAL_FALL.name -> stringResource(R.string.discover_seasonal_fall)
        DiscoverTitleKind.SEASONAL_HALLOWEEN.name -> stringResource(R.string.discover_seasonal_halloween)
        DiscoverTitleKind.SEASONAL_WINTER.name -> stringResource(R.string.discover_seasonal_winter)
        DiscoverTitleKind.FRIEND_FAVORITES.name -> stringResource(R.string.discover_friend_favorites)
        DiscoverTitleKind.FRIEND_CREATED.name -> stringResource(R.string.discover_friend_created)
        else -> stringResource(R.string.discover_rail_keyword, destination.titleArgument.orEmpty())
    }

private fun DiscoverResultsDestination.toWorkshopQuery(page: Int): WorkshopBrowseQuery =
    WorkshopBrowseQuery(
        page = page,
        pageSize = RESULTS_PAGE_SIZE,
        searchText = searchText,
        creatorId = creatorId,
        types = types.splitValues().mapNotNullTo(linkedSetOf()) { name -> WorkshopType.entries.firstOrNull { it.name == name } },
        tags = tags.splitValues().toSet(),
        excludedTags = excludedTags.splitValues().toSet(),
        officialTags = officialTags.splitValues().toSet(),
        excludedOfficialTags = excludedOfficialTags.splitValues().toSet(),
        requiredTagGroups = requiredTagGroups.splitGroups(),
        days = days,
        exactPhrase = exactPhrase,
        sort = WorkshopSort.entries.firstOrNull { it.name == sort } ?: WorkshopSort.TRENDING,
        allowNsfw = allowNsfw,
        mobileCompatibleOnly = mobileCompatibleOnly,
        createdAfterEpochSeconds = createdAfterEpochSeconds,
        createdBeforeEpochSeconds = createdBeforeEpochSeconds,
    )

private fun DiscoverResultsDestination.filterRequiredTagGroups(items: List<WorkshopSummary>): List<WorkshopSummary> {
    val groups = requiredTagGroups.splitGroups()
    if (groups.isEmpty()) return items
    return items.filter { item ->
        val itemTags = item.tags.mapTo(hashSetOf()) { it.lowercase() }
        groups.all { group -> group.any { it.lowercase() in itemTags } }
    }
}

private fun String.splitValues(): List<String> =
    split(DISCOVER_VALUE_SEPARATOR).map(String::trim).filter(String::isNotEmpty)

private fun String.splitGroups(): List<Set<String>> =
    split(DISCOVER_GROUP_SEPARATOR).map(String::splitValues).filter(List<String>::isNotEmpty).map(List<String>::toSet)

private const val RESULTS_PAGE_SIZE = 20
