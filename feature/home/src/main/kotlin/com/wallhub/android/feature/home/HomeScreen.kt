package com.wallhub.android.feature.home

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubFilterChip
import com.wallhub.android.core.designsystem.LocalWallHubToastState
import com.wallhub.android.core.designsystem.WallHubContextMenuAction as HomeContextMenuItem
import com.wallhub.android.core.designsystem.WallHubContextMenuCardPreview as SharedContextMenuCardPreview
import com.wallhub.android.core.designsystem.WallHubContextMenuDefaults
import com.wallhub.android.core.designsystem.WallHubContextMenuMetadataItem as HomeContextMenuMetadataItem
import com.wallhub.android.core.designsystem.WallHubContextMenuPositionProvider as HomeContextMenuPositionProvider
import com.wallhub.android.core.designsystem.WallHubContextMenuSurface
import com.wallhub.android.core.designsystem.WallHubIcons as Icons
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubSecondaryButton
import com.wallhub.android.core.designsystem.WallHubToastHost
import com.wallhub.android.core.designsystem.WallHubPaginationControl
import com.wallhub.android.core.designsystem.formatMegabytes
import com.wallhub.android.core.designsystem.text
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadTaskRepository
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.requiresLegacyPublicDownloadPermission
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopFilterCatalog
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

enum class HomeViewMode {
    GRID,
    LIST,
}

internal data class HomeCardLayoutKey(
    val viewMode: HomeViewMode,
    val effectiveColumns: Int,
) {
    val listMode: Boolean
        get() = viewMode == HomeViewMode.LIST

    companion object {
        fun resolve(viewMode: HomeViewMode, columns: Int): HomeCardLayoutKey = HomeCardLayoutKey(
            viewMode = viewMode,
            effectiveColumns = if (viewMode == HomeViewMode.LIST) 1 else columns.coerceAtLeast(1),
        )
    }
}

private enum class HomeFilterPage {
    BROWSE,
    CONTENT,
    THEME,
    DISPLAY,
}

private val DEFAULT_HOME_GENRE_SELECTION = WorkshopFilterCatalog.genres.toSet()
private val DEFAULT_HOME_RESOLUTION_SELECTION = WorkshopFilterCatalog.resolutions.toSet()
private val DEFAULT_HOME_RATING_SELECTION = setOf(WorkshopRating.EVERYONE)
private val SAFE_HOME_RATING_SELECTION = setOf(
    WorkshopRating.EVERYONE,
    WorkshopRating.QUESTIONABLE,
)

@Immutable
data class HomeFilterSelection(
    val sort: WorkshopSort,
    val days: Int,
    val types: Set<WorkshopType>,
    val ratings: Set<WorkshopRating>,
    val genres: Set<String>,
    val officialTags: Set<String>,
    val resolutions: Set<String>,
) {
    fun normalized(matureContentEnabled: Boolean): HomeFilterSelection {
        return copy(
            days = days.coerceIn(0, 365),
            types = types.filter { it != WorkshopType.UNKNOWN }.toSet(),
            ratings = ratings.normalizedRatings(matureContentEnabled),
            genres = genres
                .intersect(DEFAULT_HOME_GENRE_SELECTION)
                .ifEmpty { DEFAULT_HOME_GENRE_SELECTION },
            officialTags = officialTags.intersect(WorkshopFilterCatalog.officialTags.toSet()),
            resolutions = resolutions
                .intersect(DEFAULT_HOME_RESOLUTION_SELECTION)
                .ifEmpty { DEFAULT_HOME_RESOLUTION_SELECTION },
        )
    }

    fun activeSectionCount(): Int =
        (if (sort != WorkshopSort.TRENDING) 1 else 0) +
            (if (sort == WorkshopSort.TRENDING && days != 30) 1 else 0) +
            (if (types.isNotEmpty()) 1 else 0) +
            (if (ratings != DEFAULT_HOME_RATING_SELECTION) 1 else 0) +
            (if (genres != DEFAULT_HOME_GENRE_SELECTION) 1 else 0) +
            (if (officialTags.isNotEmpty()) 1 else 0) +
            (if (resolutions != DEFAULT_HOME_RESOLUTION_SELECTION) 1 else 0)

    companion object {
        fun defaults(): HomeFilterSelection = HomeFilterSelection(
            sort = WorkshopSort.TRENDING,
            days = 30,
            types = emptySet(),
            ratings = DEFAULT_HOME_RATING_SELECTION,
            genres = DEFAULT_HOME_GENRE_SELECTION,
            officialTags = emptySet(),
            resolutions = DEFAULT_HOME_RESOLUTION_SELECTION,
        )
    }
}

@Immutable
private data class HomeFilterUiConfig(
    val language: AppLanguage,
    val multiSelect: Boolean,
    val matureContentEnabled: Boolean,
)

private val homeFilterSelectionSaver = listSaver<HomeFilterSelection, String>(
    save = { selection ->
        listOf(
            selection.sort.name,
            selection.days.toString(),
            selection.types.joinToString(FILTER_SAVER_SEPARATOR) { it.name },
            selection.ratings.joinToString(FILTER_SAVER_SEPARATOR) { it.name },
            selection.genres.joinToString(FILTER_SAVER_SEPARATOR),
            selection.officialTags.joinToString(FILTER_SAVER_SEPARATOR),
            selection.resolutions.joinToString(FILTER_SAVER_SEPARATOR),
        )
    },
    restore = { values ->
        runCatching {
            HomeFilterSelection(
                sort = WorkshopSort.valueOf(values[0]),
                days = values[1].toInt(),
                types = values[2].enumSet<WorkshopType>(),
                ratings = values[3].enumSet<WorkshopRating>(),
                genres = values[4].savedStringSet(),
                officialTags = values[5].savedStringSet(),
                resolutions = values[6].savedStringSet(),
            )
        }.getOrElse { HomeFilterSelection.defaults() }
    },
)

private inline fun <reified T : Enum<T>> String.enumSet(): Set<T> =
    savedStringSet().mapNotNull { name -> enumValues<T>().firstOrNull { it.name == name } }.toSet()

private fun String.savedStringSet(): Set<String> =
    takeIf(String::isNotEmpty)?.split(FILTER_SAVER_SEPARATOR)?.toSet().orEmpty()

private fun HomeUiState.filterSelection(): HomeFilterSelection = HomeFilterSelection(
    sort = sort,
    days = days,
    types = selectedTypes,
    ratings = selectedRatings,
    genres = selectedGenres,
    officialTags = selectedOfficialTags,
    resolutions = selectedResolutions,
).normalized(matureContentEnabled)

const val HOME_AUTHOR_SEARCH_CREATOR_ARGUMENT = "authorSearchCreator"

data class HomeUiState(
    val query: String = "",
    val creatorId: String? = null,
    val exactPhrase: Boolean = false,
    val selectedTypes: Set<WorkshopType> = emptySet(),
    val selectedRatings: Set<WorkshopRating> = DEFAULT_HOME_RATING_SELECTION,
    val selectedGenres: Set<String> = DEFAULT_HOME_GENRE_SELECTION,
    val selectedOfficialTags: Set<String> = emptySet(),
    val selectedResolutions: Set<String> = DEFAULT_HOME_RESOLUTION_SELECTION,
    val sort: WorkshopSort = WorkshopSort.TRENDING,
    val days: Int = 30,
    val viewMode: HomeViewMode = HomeViewMode.GRID,
    val language: AppLanguage = AppLanguage.ZH,
    val pageSize: Int = 24,
    val columns: Int = 2,
    val multiSelect: Boolean = true,
    val matureContentEnabled: Boolean = false,
    val steamApiKey: String = "",
    val steamWorkshopDataSource: SteamWorkshopDataSource = SteamWorkshopDataSource.COMMUNITY_HTML,
    val cardAction: HomeCardAction = HomeCardAction.DOWNLOAD,
    val paginationMode: HomePaginationMode = HomePaginationMode.INFINITE_SCROLL,
    val outputTreeUri: String? = null,
    val items: List<WorkshopSummary> = emptyList(),
    val authorDisplayNames: Map<Long, String> = emptyMap(),
    val nextPage: Int = 2,
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val hasNextPage: Boolean = false,
    val totalCount: Int? = null,
    val isInitialLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isPageLoading: Boolean = false,
    val error: String? = null,
    val actionMessage: String? = null,
    val successfulSearchToken: Long = 0L,
) {
    val activeFilterCount: Int
        get() = filterSelection().activeSectionCount()
}

private fun String.creatorIdOrNull(): String? {
    val query = trim()
    if (!query.startsWith("author:", ignoreCase = true)) return null
    return query.substringAfter(':').filter(Char::isDigit).takeIf(String::isNotBlank)
}

private fun String.isSteamAuthorPlaceholder(): Boolean =
    this == "Steam 创作者" || startsWith("Steam 用户 ")

private fun HomeUiState.asAuthorSearchState(creatorId: String): HomeUiState = copy(
    query = "author:$creatorId",
    creatorId = creatorId,
    exactPhrase = false,
    selectedTypes = emptySet(),
    selectedRatings = if (matureContentEnabled) {
        setOf(WorkshopRating.ALL)
    } else {
        DEFAULT_HOME_RATING_SELECTION
    },
    selectedGenres = DEFAULT_HOME_GENRE_SELECTION,
    selectedOfficialTags = emptySet(),
    selectedResolutions = DEFAULT_HOME_RESOLUTION_SELECTION,
    sort = WorkshopSort.MOST_RECENT,
    days = 0,
    error = null,
    actionMessage = null,
)

internal fun initialHomeUiState(authorSearchCreator: String?): HomeUiState {
    val normalizedCreatorId = authorSearchCreator
        ?.filter(Char::isDigit)
        ?.takeIf(String::isNotBlank)
        ?: return HomeUiState()
    return HomeUiState().asAuthorSearchState(normalizedCreatorId)
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val workshopRepository: WorkshopRepository,
    private val settingsRepository: SettingsRepository,
    private val downloadTaskRepository: DownloadTaskRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        initialHomeUiState(savedStateHandle.get(HOME_AUTHOR_SEARCH_CREATOR_ARGUMENT)),
    )
    private var loadJob: Job? = null
    private var requestVersion = 0L
    private var preferencesInitialized = false
    private var unsubmittedQuery: String? = null
    private val authorNameRequests = mutableSetOf<Long>()

    val uiState: StateFlow<HomeUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.preferences.collect { preferences ->
                val previous = mutableState.value
                val firstPreferences = !preferencesInitialized
                val sanitizedRatings = if (
                    firstPreferences && previous.creatorId != null && preferences.matureContentEnabled
                ) {
                    setOf(WorkshopRating.ALL)
                } else {
                    previous.selectedRatings.normalizedRatings(preferences.matureContentEnabled)
                }
                val requiresReload = previous.pageSize != preferences.homePageSize ||
                    previous.matureContentEnabled != preferences.matureContentEnabled ||
                    previous.paginationMode != preferences.homePaginationMode ||
                    previous.steamApiKey != preferences.steamApiKey ||
                    previous.steamWorkshopDataSource != preferences.steamWorkshopDataSource
                mutableState.value = previous.copy(
                    language = preferences.language,
                    pageSize = preferences.homePageSize,
                    columns = preferences.homeColumns,
                    multiSelect = preferences.homeFilterMultiSelect,
                    steamApiKey = preferences.steamApiKey,
                    steamWorkshopDataSource = preferences.steamWorkshopDataSource,
                    cardAction = preferences.homeCardAction,
                    paginationMode = preferences.homePaginationMode,
                    matureContentEnabled = preferences.matureContentEnabled,
                    outputTreeUri = preferences.outputTreeUri,
                    selectedRatings = sanitizedRatings,
                )
                preferencesInitialized = true
                if (firstPreferences || requiresReload) refresh()
            }
        }
    }

    fun updateQuery(query: String) {
        val current = mutableState.value
        if (unsubmittedQuery == null) unsubmittedQuery = current.query
        mutableState.value = current.copy(
            query = query,
            creatorId = null,
            error = null,
            actionMessage = null,
        )
    }

    fun submitSearch() {
        val current = mutableState.value
        unsubmittedQuery = null
        mutableState.value = current.copy(creatorId = current.query.creatorIdOrNull())
        refreshAfterSearch()
    }

    fun restoreUnsubmittedQuery() {
        val restoredQuery = unsubmittedQuery ?: return
        unsubmittedQuery = null
        mutableState.value = mutableState.value.copy(
            query = restoredQuery,
            creatorId = restoredQuery.creatorIdOrNull(),
            error = null,
            actionMessage = null,
        )
    }

    fun searchAuthor(creator: String) {
        val normalized = creator.filter(Char::isDigit)
        if (normalized.isEmpty()) return
        mutableState.value = mutableState.value.asAuthorSearchState(normalized)
        refreshAfterSearch()
    }

    fun requestAuthorDisplayName(item: WorkshopSummary) {
        if (!item.author.isSteamAuthorPlaceholder() || item.id in authorNameRequests) return
        authorNameRequests += item.id
        viewModelScope.launch {
            val authorName = runCatching {
                workshopRepository.getDetail(item.id).summary.author
            }.getOrNull()?.takeUnless(String::isSteamAuthorPlaceholder)
            if (!authorName.isNullOrBlank()) {
                mutableState.value = mutableState.value.let { state ->
                    state.copy(authorDisplayNames = state.authorDisplayNames + (item.id to authorName))
                }
            } else {
                authorNameRequests -= item.id
            }
        }
    }

    fun toggleExactPhrase() {
        mutableState.value = mutableState.value.copy(exactPhrase = !mutableState.value.exactPhrase)
        refresh()
    }

    fun applyFilters(selection: HomeFilterSelection) {
        val current = mutableState.value
        val normalized = selection.normalized(current.matureContentEnabled)
        if (normalized == current.filterSelection()) return
        mutableState.value = current.copy(
            sort = normalized.sort,
            days = normalized.days,
            selectedTypes = normalized.types,
            selectedRatings = normalized.ratings,
            selectedGenres = normalized.genres,
            selectedOfficialTags = normalized.officialTags,
            selectedResolutions = normalized.resolutions,
        )
        refresh()
    }

    fun setViewMode(viewMode: HomeViewMode) {
        val current = mutableState.value
        if (current.viewMode == viewMode) return
        mutableState.value = current.copy(viewMode = viewMode)
    }

    fun refresh() {
        reloadFirstPage(scrollToTopOnSuccess = false)
    }

    private fun refreshAfterSearch() {
        reloadFirstPage(scrollToTopOnSuccess = true)
    }

    private fun reloadFirstPage(scrollToTopOnSuccess: Boolean) {
        loadJob?.cancel()
        requestVersion += 1
        loadPage(
            page = 1,
            append = false,
            version = requestVersion,
            scrollToTopOnSuccess = scrollToTopOnSuccess,
        )
    }

    fun resetAndRefresh() {
        unsubmittedQuery = null
        val current = mutableState.value
        mutableState.value = current.copy(
            query = "",
            creatorId = null,
            exactPhrase = false,
            selectedTypes = emptySet(),
            selectedRatings = setOf(WorkshopRating.EVERYONE),
            selectedGenres = DEFAULT_HOME_GENRE_SELECTION,
            selectedOfficialTags = emptySet(),
            selectedResolutions = DEFAULT_HOME_RESOLUTION_SELECTION,
            sort = WorkshopSort.TRENDING,
            days = 30,
            actionMessage = null,
            error = null,
        )
        refresh()
    }

    fun loadNextPage() {
        val state = mutableState.value
        if (
            state.paginationMode != HomePaginationMode.INFINITE_SCROLL ||
            state.isInitialLoading ||
            state.isLoadingMore ||
            !state.hasNextPage
        ) return
        loadPage(page = state.nextPage, append = true, version = requestVersion)
    }

    fun selectPage(page: Int) {
        val state = mutableState.value
        if (state.paginationMode != HomePaginationMode.PAGED || state.isInitialLoading || state.isPageLoading) return
        val targetPage = page.coerceAtLeast(1)
        if (targetPage == state.currentPage) return
        loadJob?.cancel()
        requestVersion += 1
        loadPage(page = targetPage, append = false, version = requestVersion)
    }

    fun enqueueCardDownload(item: WorkshopSummary) {
        viewModelScope.launch {
            runCatching {
                downloadTaskRepository.enqueue(
                    DownloadRequest(
                        workshopId = item.id,
                        title = item.title,
                        type = item.type,
                        previewUrl = item.previewUrl,
                        expectedTotalBytes = item.fileSizeBytes ?: 0L,
                        outputTreeUri = mutableState.value.outputTreeUri,
                        exportFormat = ExportFormat.AUTO,
                    ),
                )
            }.onSuccess {
                mutableState.value = mutableState.value.copy(
                    actionMessage = "已加入下载队列：${it.title}",
                )
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    actionMessage = error.message ?: "无法加入下载队列",
                )
            }
        }
    }

    fun reportLegacyStoragePermissionDenied() {
        mutableState.value = mutableState.value.copy(
            actionMessage = "未授予存储权限，无法导出到 Download/WallHub",
        )
    }

    fun dismissActionMessage() {
        if (mutableState.value.actionMessage != null) {
            mutableState.value = mutableState.value.copy(actionMessage = null)
        }
    }

    private fun loadPage(
        page: Int,
        append: Boolean,
        version: Long,
        scrollToTopOnSuccess: Boolean = false,
    ) {
        val requestState = mutableState.value
        loadJob = viewModelScope.launch {
            mutableState.value = requestState.copy(
                isInitialLoading = !append && requestState.items.isEmpty(),
                isLoadingMore = append,
                isPageLoading = !append && requestState.items.isNotEmpty(),
                error = null,
            )
            try {
                val response = workshopRepository.browse(
                    WorkshopBrowseQuery(
                        page = page,
                        pageSize = requestState.pageSize,
                        searchText = requestState.query,
                        creatorId = requestState.creatorId,
                        types = requestState.selectedTypes,
                        genres = requestState.selectedGenres.asEffectiveFilter(DEFAULT_HOME_GENRE_SELECTION),
                        officialTags = requestState.selectedOfficialTags,
                        resolutions = requestState.selectedResolutions.asEffectiveFilter(DEFAULT_HOME_RESOLUTION_SELECTION),
                        ratings = requestState.selectedRatings,
                        days = requestState.days,
                        exactPhrase = requestState.exactPhrase,
                        sort = requestState.sort,
                    ),
                )
                if (version != requestVersion) return@launch
                val mergedState = withContext(Dispatchers.Default) {
                    response.mergeInto(
                        previous = requestState,
                        append = append,
                    )
                }
                if (version != requestVersion) return@launch
                mutableState.value = if (scrollToTopOnSuccess) {
                    mergedState.copy(successfulSearchToken = requestState.successfulSearchToken + 1L)
                } else {
                    mergedState
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (version != requestVersion) return@launch
                mutableState.value = requestState.copy(
                    isInitialLoading = false,
                    isLoadingMore = false,
                    isPageLoading = false,
                    error = error.message ?: "无法加载 Steam 创意工坊，请稍后重试",
                )
            }
        }
    }

    private fun WorkshopPage.mergeInto(
        previous: HomeUiState,
        append: Boolean,
    ): HomeUiState {
        val resolvedTotalCount = resolveHomeTotalCount(
            reportedTotalCount = totalCount,
            previousTotalCount = previous.totalCount,
            append = append,
        )
        val mergedItems = if (append) {
            (previous.items + items).distinctBy(WorkshopSummary::id)
        } else {
            items
        }
        return previous.copy(
            items = mergedItems,
            nextPage = page.nextPageOrLast(),
            currentPage = page,
            totalPages = resolveHomeTotalPages(
                reportedTotalPages = this.totalPages,
                totalCount = resolvedTotalCount,
                pageSize = previous.pageSize,
                page = page,
                hasNextPage = hasNextPage,
            ),
            hasNextPage = hasNextPage,
            totalCount = resolvedTotalCount,
            isInitialLoading = false,
            isLoadingMore = false,
            isPageLoading = false,
            error = null,
        )
    }
}

private fun Set<String>.asEffectiveFilter(allOptions: Set<String>): Set<String> =
    takeUnless { it == allOptions }.orEmpty()

internal fun resolveHomeTotalCount(
    reportedTotalCount: Int?,
    previousTotalCount: Int?,
    append: Boolean,
): Int? = reportedTotalCount ?: previousTotalCount.takeIf { append }

internal fun resolveHomeTotalPages(
    reportedTotalPages: Int?,
    totalCount: Int?,
    pageSize: Int,
    page: Int,
    hasNextPage: Boolean,
): Int {
    reportedTotalPages?.let { return it.coerceAtLeast(1) }
    totalCount?.let { count ->
        val safePageSize = pageSize.coerceAtLeast(1).toLong()
        return ((count.coerceAtLeast(0).toLong() + safePageSize - 1L) / safePageSize)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
    }
    return if (hasNextPage) page.nextPageOrLast() else page.coerceAtLeast(1)
}

private fun Int.nextPageOrLast(): Int = if (this < Int.MAX_VALUE) this + 1 else Int.MAX_VALUE

@Composable
fun HomeRoute(
    onOpenDetail: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    scrollToTopRequest: Int = 0,
    onContextMenuActiveChanged: (Boolean) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var pendingLegacyStorageDownload by remember { mutableStateOf<WorkshopSummary?>(null) }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pendingItem = pendingLegacyStorageDownload
        pendingLegacyStorageDownload = null
        if (granted && pendingItem != null) {
            viewModel.enqueueCardDownload(pendingItem)
        } else if (!granted) {
            viewModel.reportLegacyStoragePermissionDenied()
        }
    }
    val requestDownload: (WorkshopSummary) -> Unit = { item ->
        if (context.requiresLegacyPublicDownloadPermission()) {
            pendingLegacyStorageDownload = item
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.enqueueCardDownload(item)
        }
    }
    HomeScreen(
        state = state,
        onQueryChanged = viewModel::updateQuery,
        onSubmitSearch = {
            val requestedCreatorId = state.query.creatorIdOrNull()
            if (requestedCreatorId != null && (onBack == null || state.creatorId != requestedCreatorId)) {
                viewModel.restoreUnsubmittedQuery()
                onSearchAuthor(requestedCreatorId)
            } else {
                viewModel.submitSearch()
            }
        },
        onToggleExactPhrase = viewModel::toggleExactPhrase,
        onApplyFilters = viewModel::applyFilters,
        onViewModeSelected = viewModel::setViewMode,
        onResetAndRefresh = viewModel::resetAndRefresh,
        onRetry = viewModel::refresh,
        onLoadNextPage = viewModel::loadNextPage,
        onPageSelected = viewModel::selectPage,
        onDownload = requestDownload,
        onActionMessageDismissed = viewModel::dismissActionMessage,
        onOpenDetail = onOpenDetail,
        onSearchAuthor = onSearchAuthor,
        onAuthorNameRequested = viewModel::requestAuthorDisplayName,
        onBack = onBack,
        scrollToTopRequest = scrollToTopRequest,
        onContextMenuActiveChanged = onContextMenuActiveChanged,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onQueryChanged: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onToggleExactPhrase: () -> Unit,
    onApplyFilters: (HomeFilterSelection) -> Unit,
    onViewModeSelected: (HomeViewMode) -> Unit,
    onResetAndRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadNextPage: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onDownload: (WorkshopSummary) -> Unit,
    onActionMessageDismissed: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit = {},
    onAuthorNameRequested: (WorkshopSummary) -> Unit = {},
    onBack: (() -> Unit)? = null,
    scrollToTopRequest: Int = 0,
    onContextMenuActiveChanged: (Boolean) -> Unit = {},
) {
    var filterSheetInitialPage by rememberSaveable { mutableStateOf<HomeFilterPage?>(null) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    var handledScrollToTopRequest by rememberSaveable { mutableIntStateOf(scrollToTopRequest) }
    var handledSearchToken by remember { mutableLongStateOf(state.successfulSearchToken) }
    var searchBoundsInRoot by remember { mutableStateOf<IntRect?>(null) }
    val contextMenuGeometry = remember { HomeContextMenuGeometry() }
    var activeContextMenuTarget by remember { mutableStateOf<HomeContextMenuTarget?>(null) }
    var renderedContextMenuTarget by remember { mutableStateOf<HomeContextMenuTarget?>(null) }
    val contextMenuActive = activeContextMenuTarget != null
    val contextMenuBackdropProgress by animateFloatAsState(
        targetValue = if (contextMenuActive) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (contextMenuActive) {
                WallHubContextMenuDefaults.EnterDurationMillis
            } else {
                WallHubContextMenuDefaults.ExitDurationMillis
            },
            easing = WallHubContextMenuDefaults.Easing,
        ),
        label = "HomeContextMenuBackdrop",
        finishedListener = { completedProgress ->
            if (completedProgress == 0f && activeContextMenuTarget == null) {
                renderedContextMenuTarget = null
            }
        },
    )
    val openContextMenu: (HomeContextMenuTarget) -> Unit = { target ->
        activeContextMenuTarget = target
        renderedContextMenuTarget = target
        onContextMenuActiveChanged(true)
    }
    val dismissContextMenu: (Long) -> Unit = { itemId ->
        if (activeContextMenuTarget?.itemId == itemId) {
            activeContextMenuTarget = null
            onContextMenuActiveChanged(false)
        }
    }
    DisposableEffect(Unit) {
        onDispose { onContextMenuActiveChanged(false) }
    }
    LaunchedEffect(state.actionMessage) {
        if (state.actionMessage != null) {
            delay(HOME_TOP_TOAST_DURATION_MS)
            onActionMessageDismissed()
        }
    }
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > handledScrollToTopRequest) {
            val isAtTop = gridState.firstVisibleItemIndex == 0 &&
                gridState.firstVisibleItemScrollOffset == 0
            if (isAtTop && !state.isInitialLoading && !state.isLoadingMore) {
                onRetry()
            } else {
                gridState.animateScrollToItem(0)
            }
            handledScrollToTopRequest = scrollToTopRequest
        }
    }
    LaunchedEffect(state.successfulSearchToken) {
        if (state.successfulSearchToken > handledSearchToken) {
            gridState.scrollToItem(0)
            handledSearchToken = state.successfulSearchToken
        }
    }
    val filtersCollapsed by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 ||
                gridState.firstVisibleItemScrollOffset > FILTER_COLLAPSE_OFFSET_PX
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { contextMenuGeometry.rootCoordinates = it }
            .pointerInput(searchBoundsInRoot) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    val point = IntOffset(
                        down.position.x.roundToInt(),
                        down.position.y.roundToInt(),
                    )
                    if (searchBoundsInRoot?.contains(point) != true) {
                        focusManager.clearFocus(force = true)
                    }
                }
            },
    ) {
        WallHubToastHost(
            message = state.actionMessage,
            onDismiss = onActionMessageDismissed,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        contextMenuBackdropProgress > 0f
                    ) {
                        Modifier.blur(
                            WallHubContextMenuDefaults.BackgroundBlurRadius * contextMenuBackdropProgress,
                        )
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (renderedContextMenuTarget != null) {
                        Modifier.semantics { invisibleToUser() }
                    } else {
                        Modifier
                    },
                ),
        ) {
            WallHubPageScaffold(
                title = "WallHub",
                topBarContent = {
                    HomeSearchTopBar(
                        state = state,
                        onQueryChanged = onQueryChanged,
                        onSubmitSearch = onSubmitSearch,
                        onToggleExactPhrase = onToggleExactPhrase,
                        onSearchBoundsChanged = { bounds -> searchBoundsInRoot = bounds },
                        onBack = onBack,
                        onResetAndRefresh = {
                            onResetAndRefresh()
                            coroutineScope.launch { gridState.scrollToItem(0) }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    AnimatedVisibility(
                        visible = !filtersCollapsed,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        HomeFilterPanel(
                            state = state,
                            onOpenFilters = { page -> filterSheetInitialPage = page },
                        )
                    }
                    HomeResultsHeader(
                        state = state,
                        onViewModeSelected = onViewModeSelected,
                    )
                    HomeResults(
                        state = state,
                        onRetry = onRetry,
                        onLoadNextPage = onLoadNextPage,
                        onPageSelected = { page ->
                            onPageSelected(page)
                            coroutineScope.launch { gridState.animateScrollToItem(0) }
                        },
                        onOpenDetail = onOpenDetail,
                        onPrimaryAction = { item ->
                            when (state.cardAction) {
                                HomeCardAction.DOWNLOAD -> onDownload(item)
                                HomeCardAction.PLAY_VIDEO -> onOpenDetail(item.id)
                                HomeCardAction.OPEN_STEAM -> {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://steamcommunity.com/sharedfiles/filedetails/?id=${item.id}"),
                                    )
                                    runCatching { context.startActivity(intent) }
                                        .onFailure { onOpenDetail(item.id) }
                                }
                            }
                        },
                        onDownload = onDownload,
                        onSearchAuthor = onSearchAuthor,
                        onAuthorNameRequested = onAuthorNameRequested,
                        gridState = gridState,
                        contextMenuPreviewItemId = renderedContextMenuTarget?.itemId,
                        contextMenuGeometry = contextMenuGeometry,
                        onContextMenuOpen = openContextMenu,
                        onContextMenuDismiss = dismissContextMenu,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (contextMenuBackdropProgress > 0f) {
            val isDarkBackground = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val scrimAlpha = if (isDarkBackground) {
                WallHubContextMenuDefaults.DarkScrimAlpha
            } else {
                WallHubContextMenuDefaults.LightScrimAlpha
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.scrim.copy(
                            alpha = scrimAlpha * contextMenuBackdropProgress,
                        ),
                    ),
            )
        }
        renderedContextMenuTarget?.let { target ->
            HomeContextMenuCardPreview(
                target = target,
                elevationProgress = contextMenuBackdropProgress,
            )
        }
    }

    filterSheetInitialPage?.let { initialPage ->
        HomeFiltersSheet(
            applied = state.filterSelection(),
            config = HomeFilterUiConfig(
                language = state.language,
                multiSelect = state.multiSelect,
                matureContentEnabled = state.matureContentEnabled,
            ),
            initialPage = initialPage,
            onDismiss = { filterSheetInitialPage = null },
            onSelectionChanged = onApplyFilters,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeSearchTopBar(
    state: HomeUiState,
    onQueryChanged: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onToggleExactPhrase: () -> Unit,
    onSearchBoundsChanged: (IntRect) -> Unit,
    onBack: (() -> Unit)?,
    onResetAndRefresh: () -> Unit,
) {
    var exactPhraseMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var searchFieldFocused by remember { mutableStateOf(false) }
    var imeWasVisibleForSearch by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(searchFieldFocused, imeVisible) {
        when {
            !searchFieldFocused -> imeWasVisibleForSearch = false
            imeVisible -> imeWasVisibleForSearch = true
            imeWasVisibleForSearch -> focusManager.clearFocus(force = true)
        }
    }
    Surface(
        modifier = Modifier,
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = state.text("返回", "Back"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Text(
                    text = "WallHub",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(onClick = onResetAndRefresh)
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                HomeFlatCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HOME_SEARCH_FIELD_HEIGHT)
                        .onGloballyPositioned { coordinates ->
                            val topLeft = coordinates.positionInRoot()
                            onSearchBoundsChanged(
                                IntRect(
                                    left = topLeft.x.roundToInt(),
                                    top = topLeft.y.roundToInt(),
                                    right = topLeft.x.roundToInt() + coordinates.size.width,
                                    bottom = topLeft.y.roundToInt() + coordinates.size.height,
                                ),
                            )
                        },
                    shape = HOME_WALLPAPER_CARD_SHAPE,
                ) {
                    BasicTextField(
                        value = state.query,
                        onValueChange = { query ->
                            onQueryChanged(query)
                            // Keep the exact-phrase chooser visible while the user
                            // continues typing, without recreating a focus-owning popup.
                            exactPhraseMenuExpanded = searchFieldFocused && query.isNotBlank()
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .onFocusChanged { focusState ->
                                searchFieldFocused = focusState.isFocused
                                exactPhraseMenuExpanded = focusState.isFocused && state.query.isNotBlank()
                            }
                            .padding(start = 12.dp, end = 4.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = { onSubmitSearch() },
                        ),
                        decorationBox = { innerTextField ->
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (state.query.isBlank()) {
                                        Text(
                                            text = state.text("搜索创意工坊", "Search Workshop"),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    innerTextField()
                                }
                                IconButton(
                                    onClick = onSubmitSearch,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Search,
                                        contentDescription = state.text("搜索", "Search"),
                                    )
                                }
                            }
                        },
                    )
                }
                DropdownMenu(
                    expanded = exactPhraseMenuExpanded && state.query.isNotBlank(),
                    onDismissRequest = { exactPhraseMenuExpanded = false },
                    offset = DpOffset(0.dp, 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    containerColor = if (state.exactPhrase) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    tonalElevation = 0.dp,
                    shadowElevation = 6.dp,
                    // The search field must retain IME focus while this optional
                    // checkbox is shown. A focusable dropdown steals that focus on
                    // every edit on some Android devices.
                    properties = PopupProperties(focusable = false),
                ) {
                    Row(
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable {
                                onToggleExactPhrase()
                                exactPhraseMenuExpanded = false
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(
                                    if (state.exactPhrase) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLowest
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.exactPhrase) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                        Text(
                            text = state.text("精确匹配短语", "Exact phrase"),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.exactPhrase) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeFilterPanel(
    state: HomeUiState,
    onOpenFilters: (HomeFilterPage) -> Unit,
) {
    val selection = state.filterSelection()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.text("浏览条件", "Browse settings"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (state.activeFilterCount == 0) {
                        state.text("使用默认条件", "Using defaults")
                    } else {
                        state.text(
                            "已启用 ${state.activeFilterCount} 项",
                            "${state.activeFilterCount} active",
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BadgedBox(
                badge = {
                    if (state.activeFilterCount > 0) {
                        Badge { Text(state.activeFilterCount.toString()) }
                    }
                },
            ) {
                FilledTonalIconButton(
                    onClick = { onOpenFilters(HomeFilterPage.BROWSE) },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = state.text("打开全部筛选", "Open all filters"),
                    )
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(HomeFilterPage.entries, key = { it }) { page ->
                HomeConditionChip(
                    label = page.label(state.language),
                    value = page.summary(selection, state),
                    active = page.activeSectionCount(selection) > 0,
                    onClick = { onOpenFilters(page) },
                )
            }
        }
    }
}

@Composable
private fun HomeConditionChip(
    label: String,
    value: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "$label · $value",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun HomeFlatCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val clickableModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(color)
                .then(clickableModifier),
            content = content,
        )
    }
}

@Composable
private fun HomeResultsHeader(
    state: HomeUiState,
    onViewModeSelected: (HomeViewMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.text("发现壁纸", "Discover wallpapers"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    state.isInitialLoading -> state.text("正在加载…", "Loading…")
                    state.totalCount != null -> state.text("约 ${state.totalCount} 个项目", "About ${state.totalCount} items")
                    else -> state.text("${state.items.size} 个项目", "${state.items.size} items")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HomeViewModeToggle(
            selected = state.viewMode,
            onViewModeSelected = onViewModeSelected,
            gridContentDescription = state.text("网格视图", "Grid view"),
            listContentDescription = state.text("列表视图", "List view"),
        )
    }
}

@Composable
private fun HomeViewModeToggle(
    selected: HomeViewMode,
    onViewModeSelected: (HomeViewMode) -> Unit,
    gridContentDescription: String,
    listContentDescription: String,
) {
    val indicatorOffset by animateDpAsState(
        targetValue = if (selected == HomeViewMode.GRID) {
            HOME_VIEW_MODE_TOGGLE_INSET
        } else {
            HOME_VIEW_MODE_TOGGLE_INSET + HOME_VIEW_MODE_TOGGLE_BUTTON_SIZE
        },
        animationSpec = tween(
            durationMillis = HOME_VIEW_MODE_TOGGLE_DURATION_MS,
            easing = HOME_CONTEXT_MENU_EASING,
        ),
        label = "HomeViewModeIndicator",
    )
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            modifier = Modifier
                .width(HOME_VIEW_MODE_TOGGLE_WIDTH)
                .height(HOME_VIEW_MODE_TOGGLE_HEIGHT),
        ) {
            Surface(
                modifier = Modifier
                    .offset(x = indicatorOffset, y = HOME_VIEW_MODE_TOGGLE_INSET)
                    .size(HOME_VIEW_MODE_TOGGLE_BUTTON_SIZE),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primary,
            ) {}
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(HOME_VIEW_MODE_TOGGLE_INSET),
            ) {
                ViewModeButton(
                    selected = selected == HomeViewMode.GRID,
                    icon = Icons.Outlined.GridView,
                    contentDescription = gridContentDescription,
                    onClick = { onViewModeSelected(HomeViewMode.GRID) },
                )
                ViewModeButton(
                    selected = selected == HomeViewMode.LIST,
                    icon = Icons.Outlined.ViewList,
                    contentDescription = listContentDescription,
                    onClick = { onViewModeSelected(HomeViewMode.LIST) },
                )
            }
        }
    }
}

@Composable
private fun ViewModeButton(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(
            durationMillis = HOME_VIEW_MODE_TOGGLE_DURATION_MS,
            easing = HOME_CONTEXT_MENU_EASING,
        ),
        label = "HomeViewModeIcon",
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(HOME_VIEW_MODE_TOGGLE_BUTTON_SIZE),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HomeResults(
    state: HomeUiState,
    onRetry: () -> Unit,
    onLoadNextPage: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onPrimaryAction: (WorkshopSummary) -> Unit,
    onDownload: (WorkshopSummary) -> Unit,
    onSearchAuthor: (String) -> Unit,
    onAuthorNameRequested: (WorkshopSummary) -> Unit,
    gridState: LazyGridState,
    contextMenuPreviewItemId: Long?,
    contextMenuGeometry: HomeContextMenuGeometry,
    onContextMenuOpen: (HomeContextMenuTarget) -> Unit,
    onContextMenuDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shouldAutoLoadMore by remember(gridState, state) {
        derivedStateOf {
            if (
                state.paginationMode != HomePaginationMode.INFINITE_SCROLL ||
                state.isInitialLoading ||
                state.isLoadingMore ||
                !state.hasNextPage
            ) {
                false
            } else {
                val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisibleIndex >= (state.items.lastIndex - HOME_AUTO_LOAD_MORE_THRESHOLD).coerceAtLeast(0)
            }
        }
    }
    LaunchedEffect(shouldAutoLoadMore, state.nextPage, state.paginationMode) {
        if (shouldAutoLoadMore) onLoadNextPage()
    }
    PullToRefreshBox(
        isRefreshing = state.isInitialLoading || state.isPageLoading,
        onRefresh = onRetry,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = tween(
                    durationMillis = HOME_FILTER_PAGE_SIZE_DURATION_MS,
                    easing = HOME_FILTER_PAGE_EASING,
                ),
            ),
    ) {
        when {
        state.isInitialLoading -> {
            Box(modifier = Modifier.fillMaxSize())
        }

        state.error != null && state.items.isEmpty() -> {
            WallHubEmptyState(
                icon = Icons.Outlined.Refresh,
                title = state.error,
                actionLabel = state.text("重试", "Retry"),
                onAction = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
        }

        state.items.isEmpty() -> {
            WallHubEmptyState(
                icon = Icons.Outlined.Search,
                title = state.text("没有找到符合条件的壁纸", "No matching wallpapers"),
                actionLabel = state.text("重新加载", "Reload"),
                onAction = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
        }

        else -> {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val layoutKey = HomeCardLayoutKey.resolve(state.viewMode, state.columns)
                val edgeEntryState = remember { HomeLayoutEdgeEntryState(layoutKey) }
                val edgeEntryRequestId = edgeEntryState.update(layoutKey)
                val animateLayoutEdgeEntry = edgeEntryState.isActive
                val contentWidth = (maxWidth - HOME_GRID_HORIZONTAL_PADDING * 2).coerceAtLeast(0.dp)
                val gridCardWidth = (
                    contentWidth -
                        HOME_GRID_ITEM_SPACING * (state.columns - 1).toFloat()
                    ).coerceAtLeast(0.dp) / state.columns.toFloat()
                val gridStatisticsAvailableWidth =
                    (gridCardWidth - GRID_CARD_COPY_HORIZONTAL_PADDING).coerceAtLeast(0.dp)
                val listStatisticsAvailableWidth = (
                    contentWidth -
                        LIST_CARD_MEDIA_SIZE -
                        LIST_CARD_ACTION_SIZE -
                        LIST_CARD_ACTION_END_PADDING -
                        LIST_CARD_COPY_HORIZONTAL_PADDING
                    ).coerceAtLeast(0.dp)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(layoutKey.effectiveColumns),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            contextMenuGeometry.gridCoordinates = coordinates
                            edgeEntryState.complete(edgeEntryRequestId)
                        },
                    contentPadding = PaddingValues(
                        horizontal = HOME_GRID_HORIZONTAL_PADDING,
                        vertical = 8.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(HOME_GRID_ITEM_SPACING),
                    verticalArrangement = Arrangement.spacedBy(HOME_GRID_ITEM_SPACING),
                ) {
                    items(
                        items = state.items,
                        key = WorkshopSummary::id,
                        contentType = { item -> item.type },
                    ) { item ->
                        WorkshopCard(
                            modifier = Modifier,
                            item = item,
                            authorDisplayName = state.authorDisplayNames[item.id],
                            language = state.language,
                            layoutKey = layoutKey,
                            animateEdgeEntry = animateLayoutEdgeEntry,
                            gridShowFileSize = state.columns < 3,
                            gridShowFavorites = state.columns < 4,
                            gridStatisticsAvailableWidth = gridStatisticsAvailableWidth,
                            listStatisticsAvailableWidth = listStatisticsAvailableWidth,
                            isContextMenuPreviewTarget = contextMenuPreviewItemId == item.id,
                            action = state.cardAction,
                            onOpen = { onOpenDetail(item.id) },
                            onPrimaryAction = { onPrimaryAction(item) },
                            onDownload = { onDownload(item) },
                            onSearchAuthor = { onSearchAuthor(item.creatorId ?: item.author) },
                            onAuthorNameRequested = { onAuthorNameRequested(item) },
                            contextMenuGeometry = contextMenuGeometry,
                            onContextMenuOpen = onContextMenuOpen,
                            onContextMenuDismiss = onContextMenuDismiss,
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        when {
                            state.paginationMode == HomePaginationMode.INFINITE_SCROLL && state.isLoadingMore -> Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator() }

                            state.paginationMode == HomePaginationMode.PAGED -> HomePagination(
                                currentPage = state.currentPage,
                                totalPages = state.totalPages,
                                isLoading = state.isPageLoading,
                                language = state.language,
                                onPageSelected = onPageSelected,
                            )

                            else -> Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun HomePagination(
    currentPage: Int,
    totalPages: Int,
    isLoading: Boolean,
    language: AppLanguage,
    onPageSelected: (Int) -> Unit,
) {
    WallHubPaginationControl(
        currentPage = currentPage,
        totalPages = totalPages,
        isLoading = isLoading,
        currentContentDescription = language.text(
            "当前第 $currentPage 页；当前已知最大页码为 $totalPages；点击输入页码",
            "Page $currentPage; known last page $totalPages; tap to enter a page",
        ),
        onPageSelected = onPageSelected,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkshopCard(
    modifier: Modifier = Modifier,
    item: WorkshopSummary,
    authorDisplayName: String?,
    language: AppLanguage,
    layoutKey: HomeCardLayoutKey,
    animateEdgeEntry: Boolean,
    gridShowFileSize: Boolean,
    gridShowFavorites: Boolean,
    gridStatisticsAvailableWidth: Dp,
    listStatisticsAvailableWidth: Dp,
    isContextMenuPreviewTarget: Boolean,
    action: HomeCardAction,
    onOpen: () -> Unit,
    onPrimaryAction: () -> Unit,
    onDownload: () -> Unit,
    onSearchAuthor: () -> Unit,
    onAuthorNameRequested: () -> Unit,
    contextMenuGeometry: HomeContextMenuGeometry,
    onContextMenuOpen: (HomeContextMenuTarget) -> Unit,
    onContextMenuDismiss: (Long) -> Unit,
) {
    val listMode = layoutKey.listMode
    val twoColumnGrid = !listMode && layoutKey.effectiveColumns == 2
    val layoutMotion = rememberHomeViewCardLayoutMotion(
        layoutKey = layoutKey,
        animateEdgeEntry = animateEdgeEntry,
    )
    val contextMenuPreviewLayer = rememberGraphicsLayer()
    val cardPosition = remember { HomeCardPositionHolder() }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var contextMenuVisible by remember { mutableStateOf(false) }
    var contextMenuMounted by remember { mutableStateOf(false) }
    var contextMenuRequested by remember { mutableStateOf(false) }
    var contextMenuEntranceRequest by remember { mutableIntStateOf(0) }
    var contextMenuTarget by remember(item.id) { mutableStateOf<HomeContextMenuTarget?>(null) }
    var contextMenuPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val toastState = LocalWallHubToastState.current
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val contextMenuPositionProvider = remember(contextMenuPositionInWindow, density) {
        HomeContextMenuPositionProvider(
            touchPosition = contextMenuPositionInWindow,
            touchOffsetPx = with(density) { WallHubContextMenuDefaults.TouchOffset.roundToPx() },
        )
    }
    val contextMenuAlpha by animateFloatAsState(
        targetValue = if (contextMenuVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (contextMenuVisible) {
                WallHubContextMenuDefaults.EnterDurationMillis
            } else {
                WallHubContextMenuDefaults.ExitDurationMillis
            },
            easing = WallHubContextMenuDefaults.Easing,
        ),
        label = "HomeContextMenuFade",
    )
    LaunchedEffect(contextMenuVisible) {
        if (!contextMenuVisible && contextMenuMounted) {
            delay(WallHubContextMenuDefaults.ExitDurationMillis.toLong())
            if (!contextMenuRequested) contextMenuMounted = false
        }
    }
    LaunchedEffect(contextMenuMounted, contextMenuEntranceRequest) {
        if (contextMenuMounted) {
            withFrameNanos { }
            if (contextMenuRequested) contextMenuVisible = true
        }
    }
    DisposableEffect(item.id) {
        onDispose { onContextMenuDismiss(item.id) }
    }
    fun dismissContextMenu() {
        contextMenuRequested = false
        contextMenuVisible = false
        onContextMenuDismiss(item.id)
    }
    fun openContextMenuAt(position: Offset) {
        val target = contextMenuGeometry.captureTarget(
            itemId = item.id,
            graphicsLayer = contextMenuPreviewLayer,
            cardCoordinates = cardPosition.cardCoordinates,
            touchCoordinates = cardPosition.touchCoordinates,
            touchPosition = position,
            shape = layoutMotion.cardShape(),
        ) ?: return
        contextMenuTarget = target
        contextMenuPositionInWindow = target.touchPositionInWindow
        onAuthorNameRequested()
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        onContextMenuOpen(target)
        contextMenuRequested = true
        contextMenuVisible = false
        contextMenuMounted = true
        contextMenuEntranceRequest += 1
    }
    val interactionModifier = Modifier
        .pointerInput(item.id) {
            detectTapGestures(
                onPress = { position ->
                    val press = PressInteraction.Press(position)
                    interactionSource.emit(press)
                    interactionSource.emit(
                        if (tryAwaitRelease()) {
                            PressInteraction.Release(press)
                        } else {
                            PressInteraction.Cancel(press)
                        },
                    )
                },
                onTap = { onOpen() },
                onLongPress = ::openContextMenuAt,
            )
        }
        .semantics {
            role = Role.Button
            onClick(label = language.text("查看详情", "View details")) {
                onOpen()
                true
            }
            onLongClick(label = language.text("打开操作菜单", "Open actions menu")) {
                val size = cardPosition.touchCoordinates?.size
                if (size == null || size.width <= 0 || size.height <= 0) {
                    false
                } else {
                    openContextMenuAt(Offset(size.width / 2f, size.height / 2f))
                    true
                }
            }
        }
    val pressActive = isPressed && !isContextMenuPreviewTarget
    val recordContextMenuPreview = isPressed || contextMenuMounted || isContextMenuPreviewTarget
    val pressedScale by animateFloatAsState(
        targetValue = when {
            !pressActive -> 1f
            listMode -> HOME_CONTEXT_MENU_LIST_PRESS_SCALE
            else -> HOME_CONTEXT_MENU_GRID_PRESS_SCALE
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = HOME_CONTEXT_MENU_PRESS_STIFFNESS,
        ),
        label = "WorkshopCardPressScale",
    )
    val pressedTranslationY by animateDpAsState(
        targetValue = if (pressActive) HOME_CONTEXT_MENU_PRESS_TRANSLATION_Y else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = HOME_CONTEXT_MENU_PRESS_STIFFNESS,
        ),
        label = "WorkshopCardPressTranslation",
    )
    // Grid and list use different composition branches; keep the scale state
    // above them so an interrupted switch continues from its presented value.
    val typeTagScale = animateFloatAsState(
        targetValue = if (listMode) HOME_COMPACT_TYPE_TAG_SCALE else 1f,
        animationSpec = tween(
            durationMillis = HOME_VIEW_TYPE_TAG_LAYOUT_DURATION_MS,
            easing = HOME_VIEW_LAYOUT_EASING,
        ),
        label = "WorkshopCoverTypeTagScale",
    )
    Box(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        HomeFlatCard(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { cardPosition.cardCoordinates = it }
                .drawWithContent recordCard@{
                    if (recordContextMenuPreview) {
                        contextMenuPreviewLayer.record {
                            this@recordCard.drawContent()
                        }
                        if (!isContextMenuPreviewTarget) drawLayer(contextMenuPreviewLayer)
                    } else {
                        drawContent()
                    }
                }
                .graphicsLayer {
                    transformOrigin = TransformOrigin.Center
                    scaleX = pressedScale
                    scaleY = pressedScale
                    translationY = pressedTranslationY.toPx()
                }
                .then(layoutMotion.cardModifier())
                .onGloballyPositioned { cardPosition.touchCoordinates = it }
                .then(interactionModifier),
            shape = layoutMotion.cardShape(),
        ) {
            if (listMode) {
                WorkshopListCardContent(
                    item = item,
                    language = language,
                    typeTagScale = typeTagScale,
                    action = action,
                    showFileSize = true,
                    showFavorites = true,
                    statisticsAvailableWidth = listStatisticsAvailableWidth,
                    layoutMotion = layoutMotion,
                    onPrimaryAction = onPrimaryAction,
                )
            } else {
                Column {
                    WorkshopCoverFrame(
                        item = item,
                        language = language,
                        compact = false,
                        typeTagScale = typeTagScale,
                        coverShape = layoutMotion.coverShape(),
                        typeTagModifier = layoutMotion.tagModifier(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .then(layoutMotion.mediaModifier()),
                    )
                    WorkshopCardCopy(
                        item = item,
                        language = language,
                        compact = false,
                        twoColumnGrid = twoColumnGrid,
                        showFileSize = gridShowFileSize,
                        showFavorites = gridShowFavorites,
                        statisticsAvailableWidth = gridStatisticsAvailableWidth,
                        modifier = Modifier
                            .padding(
                                start = 10.dp,
                                top = if (twoColumnGrid) TWO_COLUMN_CARD_COPY_TOP_PADDING else 10.dp,
                                end = 10.dp,
                            )
                            .then(layoutMotion.contentModifier()),
                    )
                    WorkshopGridCardAction(
                        action = action,
                        language = language,
                        layoutMotion = layoutMotion,
                        onPrimaryAction = onPrimaryAction,
                        modifier = Modifier.padding(
                            start = 10.dp,
                            top = if (twoColumnGrid) TWO_COLUMN_CARD_ACTION_TOP_PADDING else 7.dp,
                            end = 10.dp,
                            bottom = 10.dp,
                        ),
                    )
                }
            }
        }
        if (contextMenuMounted) {
            Popup(
                popupPositionProvider = contextMenuPositionProvider,
                onDismissRequest = { dismissContextMenu() },
                properties = PopupProperties(focusable = true),
            ) {
                val menuWidth = WallHubContextMenuDefaults.menuWidth(
                    cardWidth = contextMenuTarget?.let { target ->
                        with(density) { target.cardBounds.width.toDp() }
                    },
                    language = language,
                )
                WallHubContextMenuSurface(
                    width = menuWidth,
                    modifier = Modifier
                        .graphicsLayer { alpha = contextMenuAlpha }
                        .then(
                            if (contextMenuVisible) {
                                Modifier
                            } else {
                                Modifier
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            do {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                                event.changes.forEach { it.consume() }
                                            } while (event.changes.any { it.pressed })
                                        }
                                    }
                                    .clearAndSetSemantics {}
                            },
                        ),
                ) {
                                HomeContextMenuMetadataItem(
                                    label = if (language == AppLanguage.EN) "Wallpaper title" else "Wallpaper 标题",
                                    value = item.title,
                                    icon = Icons.Outlined.ContentCopy,
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(item.title))
                                        toastState.show(
                                            if (language == AppLanguage.EN) "Wallpaper title copied" else "已复制 Wallpaper 标题",
                                        )
                                        dismissContextMenu()
                                    },
                                )
                                HomeContextMenuMetadataItem(
                                    label = if (language == AppLanguage.EN) "Author" else "作者",
                                    value = authorDisplayName
                                        ?: item.author.takeUnless(String::isSteamAuthorPlaceholder)
                                        ?: language.text(
                                            "正在获取 Steam 用户名",
                                            "Loading Steam username…",
                                        ),
                                    icon = Icons.Outlined.PersonOutline,
                                    onClick = {
                                        dismissContextMenu()
                                        onSearchAuthor()
                                    },
                                )
                                HomeContextMenuMetadataItem(
                                    label = if (language == AppLanguage.EN) "Project ID" else "项目 ID",
                                    value = item.id.toString(),
                                    icon = Icons.Outlined.ContentCopy,
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(item.id.toString()))
                                        toastState.show(
                                            if (language == AppLanguage.EN) "Project ID copied" else "已复制项目 ID",
                                        )
                                        dismissContextMenu()
                                    },
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                HomeContextMenuItem(
                                    text = if (language == AppLanguage.EN) "Download" else "下载",
                                    icon = Icons.Outlined.Download,
                                    onClick = {
                                        dismissContextMenu()
                                        onDownload()
                                    },
                                )
                                if (item.type == WorkshopType.VIDEO) {
                                    HomeContextMenuItem(
                                        text = if (language == AppLanguage.EN) {
                                            "Open video details"
                                        } else {
                                            "视频播放"
                                        },
                                        icon = Icons.Outlined.PlayArrow,
                                        onClick = {
                                            dismissContextMenu()
                                            onOpen()
                                        },
                                    )
                                }
                                HomeContextMenuItem(
                                    text = if (language == AppLanguage.EN) "Open in Steam" else "打开 Steam",
                                    icon = Icons.Outlined.OpenInNew,
                                    onClick = {
                                        dismissContextMenu()
                                        val intent = Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://steamcommunity.com/sharedfiles/filedetails/?id=${item.id}"),
                                        )
                                        runCatching { context.startActivity(intent) }.onFailure { onOpen() }
                                    },
                                )
                }
            }
        }
    }
}

private data class HomeContextMenuTarget(
    val itemId: Long,
    val graphicsLayer: GraphicsLayer,
    val cardBounds: Rect,
    val clipBounds: Rect,
    val touchPositionInWindow: Offset,
    val shape: Shape,
)

private class HomeContextMenuGeometry {
    var rootCoordinates: LayoutCoordinates? = null
    var gridCoordinates: LayoutCoordinates? = null

    fun captureTarget(
        itemId: Long,
        graphicsLayer: GraphicsLayer,
        cardCoordinates: LayoutCoordinates?,
        touchCoordinates: LayoutCoordinates?,
        touchPosition: Offset,
        shape: Shape,
    ): HomeContextMenuTarget? {
        val root = rootCoordinates?.takeIf(LayoutCoordinates::isAttached) ?: return null
        val grid = gridCoordinates?.takeIf(LayoutCoordinates::isAttached) ?: return null
        val card = cardCoordinates?.takeIf(LayoutCoordinates::isAttached) ?: return null
        val touchTarget = touchCoordinates?.takeIf(LayoutCoordinates::isAttached) ?: return null
        val cardBounds = root.localBoundingBoxOf(card, clipBounds = false)
        val clipBounds = root.localBoundingBoxOf(grid, clipBounds = true)
        val touchPositionInWindow = touchTarget.localToWindow(touchPosition)
        if (
            cardBounds.width <= 0f ||
            cardBounds.height <= 0f ||
            clipBounds.width <= 0f ||
            clipBounds.height <= 0f ||
            !touchPositionInWindow.x.isFinite() ||
            !touchPositionInWindow.y.isFinite()
        ) {
            return null
        }
        return HomeContextMenuTarget(
            itemId = itemId,
            graphicsLayer = graphicsLayer,
            cardBounds = cardBounds,
            clipBounds = clipBounds,
            touchPositionInWindow = touchPositionInWindow,
            shape = shape,
        )
    }
}

private class HomeCardPositionHolder {
    var cardCoordinates: LayoutCoordinates? = null
    var touchCoordinates: LayoutCoordinates? = null
}

@Composable
private fun HomeContextMenuCardPreview(
    target: HomeContextMenuTarget,
    elevationProgress: Float,
) {
    SharedContextMenuCardPreview(
        graphicsLayer = target.graphicsLayer,
        cardBounds = target.cardBounds,
        clipBounds = target.clipBounds,
        shape = target.shape,
        elevationProgress = elevationProgress,
    )
}

/**
 * Mirrors the Web card's layout projection when the discover grid switches
 * between its grid and single-column modes. Lazy grids place every item at its
 * new bounds in one pass, so the card and its main children each apply an
 * inverse transform first and then ease it away. This keeps position, width and
 * height continuous instead of changing card content at the first layout frame.
 */
internal data class HomeLayoutTransaction(
    val epoch: Long,
    val requestId: Long,
    val sourceKey: HomeCardLayoutKey,
    val targetKey: HomeCardLayoutKey,
)

internal class HomeLayoutTransactionState(initialKey: HomeCardLayoutKey) {
    var requestedKey: HomeCardLayoutKey = initialKey
        private set
    var measuredKey: HomeCardLayoutKey = initialKey
        private set
    var epoch: Long = 0L
        private set
    var requestId: Long = 0L
        private set

    fun request(key: HomeCardLayoutKey) {
        if (key != requestedKey) requestId += 1L
        requestedKey = key
    }

    fun consumeForMeasurement(expectedRequestId: Long = requestId): HomeLayoutTransaction? {
        if (expectedRequestId != requestId) return null
        if (requestedKey == measuredKey) return null
        val transaction = HomeLayoutTransaction(
            epoch = epoch + 1L,
            requestId = requestId,
            sourceKey = measuredKey,
            targetKey = requestedKey,
        )
        epoch = transaction.epoch
        return transaction
    }

    fun commit(transaction: HomeLayoutTransaction): Boolean {
        if (transaction.epoch != epoch || transaction.requestId != requestId) return false
        measuredKey = transaction.targetKey
        return true
    }
}

internal class HomeLayoutEdgeEntryState(initialKey: HomeCardLayoutKey) {
    private var layoutKey = initialKey
    private var completedRequestId by mutableLongStateOf(0L)

    var requestId: Long = 0L
        private set

    val isActive: Boolean
        get() = requestId != completedRequestId

    fun update(key: HomeCardLayoutKey): Long {
        if (key != layoutKey) {
            layoutKey = key
            requestId += 1L
        }
        return requestId
    }

    fun complete(expectedRequestId: Long): Boolean {
        if (expectedRequestId != requestId) return false
        completedRequestId = expectedRequestId
        return true
    }
}

internal enum class HomeCardProjectionParticipant {
    CARD,
    MEDIA,
    TAG,
    CONTENT,
    ACTION,
}

internal data class HomeCardProjectionTransforms(
    val card: HomeCardLayoutTransform,
    val media: HomeCardLayoutTransform,
    val tag: HomeCardLayoutTransform,
    val content: HomeCardLayoutTransform,
    val action: HomeCardLayoutTransform,
) {
    operator fun get(participant: HomeCardProjectionParticipant): HomeCardLayoutTransform = when (participant) {
        HomeCardProjectionParticipant.CARD -> card
        HomeCardProjectionParticipant.MEDIA -> media
        HomeCardProjectionParticipant.TAG -> tag
        HomeCardProjectionParticipant.CONTENT -> content
        HomeCardProjectionParticipant.ACTION -> action
    }

    fun with(
        participant: HomeCardProjectionParticipant,
        transform: HomeCardLayoutTransform,
    ): HomeCardProjectionTransforms = when (participant) {
        HomeCardProjectionParticipant.CARD -> copy(card = transform)
        HomeCardProjectionParticipant.MEDIA -> copy(media = transform)
        HomeCardProjectionParticipant.TAG -> copy(tag = transform)
        HomeCardProjectionParticipant.CONTENT -> copy(content = transform)
        HomeCardProjectionParticipant.ACTION -> copy(action = transform)
    }

    companion object {
        val Identity = HomeCardProjectionTransforms(
            card = HomeCardLayoutTransform.Identity,
            media = HomeCardLayoutTransform.Identity,
            tag = HomeCardLayoutTransform.Identity,
            content = HomeCardLayoutTransform.Identity,
            action = HomeCardLayoutTransform.Identity,
        )
    }
}

internal class HomeCardProjectionGroupRun internal constructor(
    val id: Long,
    val epoch: Long,
    val transforms: HomeCardProjectionTransforms,
    val cardInitialAlpha: Float,
    val shouldAnimate: Boolean,
    initialProgress: Float,
) {
    var progress by mutableFloatStateOf(initialProgress)
        private set

    internal fun updateProgress(value: Float) {
        progress = value
    }
}

internal class HomeCardProjectionGroupStage internal constructor(
    val transaction: HomeLayoutTransaction,
    val sourceTransforms: HomeCardProjectionTransforms,
    val sourceCardAlpha: Float,
    val edgeEntryRequired: Boolean,
    sourceBounds: Map<HomeCardProjectionParticipant, HomeCardBounds?>,
) {
    private val sourceBounds = sourceBounds.toMap()
    private val measuredBounds = mutableMapOf<HomeCardProjectionParticipant, HomeCardBounds?>()
    private var stagedTransforms = HomeCardProjectionTransforms.Identity
    private val readyParticipants = mutableSetOf<HomeCardProjectionParticipant>()

    val epoch: Long
        get() = transaction.epoch
    val requestId: Long
        get() = transaction.requestId
    val targetKey: HomeCardLayoutKey
        get() = transaction.targetKey
    val isReady: Boolean
        get() = readyParticipants.size == HomeCardProjectionParticipant.entries.size

    fun sourceBounds(participant: HomeCardProjectionParticipant): HomeCardBounds? = sourceBounds[participant]

    fun hasMeasurement(participant: HomeCardProjectionParticipant): Boolean =
        measuredBounds.containsKey(participant)

    fun targetBounds(participant: HomeCardProjectionParticipant): HomeCardBounds? = measuredBounds[participant]

    fun recordMeasurement(participant: HomeCardProjectionParticipant, bounds: HomeCardBounds?) {
        if (participant !in readyParticipants) measuredBounds[participant] = bounds
    }

    fun markMissingMeasurements() {
        HomeCardProjectionParticipant.entries.forEach { participant ->
            measuredBounds.putIfAbsent(participant, null)
        }
    }

    fun isParticipantReady(participant: HomeCardProjectionParticipant): Boolean =
        participant in readyParticipants

    fun stage(participant: HomeCardProjectionParticipant, transform: HomeCardLayoutTransform) {
        if (participant in readyParticipants) return
        stagedTransforms = stagedTransforms.with(participant, transform)
        readyParticipants += participant
    }

    fun stagedTransform(participant: HomeCardProjectionParticipant): HomeCardLayoutTransform? =
        stagedTransforms[participant].takeIf { participant in readyParticipants }

    fun displayTransform(participant: HomeCardProjectionParticipant): HomeCardLayoutTransform =
        stagedTransform(participant) ?: sourceTransforms[participant]

    fun committedTransforms(): HomeCardProjectionTransforms {
        check(isReady)
        return stagedTransforms
    }
}

internal class HomeCardProjectionGroupState(initialEpoch: Long = 0L) {
    private var nextRunId = 0L
    private var displayVersion by mutableIntStateOf(0)
    var activeRun by mutableStateOf(
        HomeCardProjectionGroupRun(
            id = nextRunId,
            epoch = initialEpoch,
            transforms = HomeCardProjectionTransforms.Identity,
            cardInitialAlpha = 1f,
            shouldAnimate = false,
            initialProgress = 1f,
        ),
    )
        private set
    var pendingStage by mutableStateOf<HomeCardProjectionGroupStage?>(null)
        private set

    val progress: Float
        get() {
            displayVersion
            return if (pendingStage != null) 0f else activeRun.progress
        }

    val requiresGraphicsLayer: Boolean
        get() = pendingStage != null || activeRun.progress < 1f

    fun currentTransform(participant: HomeCardProjectionParticipant): HomeCardLayoutTransform {
        displayVersion
        return pendingStage?.displayTransform(participant)
            ?: activeRun.transforms[participant].at(activeRun.progress)
    }

    fun captureCurrentTransforms(): HomeCardProjectionTransforms {
        var transforms = HomeCardProjectionTransforms.Identity
        HomeCardProjectionParticipant.entries.forEach { participant ->
            transforms = transforms.with(participant, currentTransform(participant))
        }
        return transforms
    }

    fun currentCardAlpha(): Float {
        displayVersion
        return pendingStage?.sourceCardAlpha ?: activeCardAlpha()
    }

    fun beginStage(
        transaction: HomeLayoutTransaction,
        sourceBounds: Map<HomeCardProjectionParticipant, HomeCardBounds?>,
        edgeEntryEnabled: Boolean = false,
    ): HomeCardProjectionGroupStage {
        val replacedStage = pendingStage
        val edgeEntryRequired = sourceBounds[HomeCardProjectionParticipant.CARD]?.hasArea() != true &&
            (edgeEntryEnabled || replacedStage?.edgeEntryRequired == true)
        val stage = HomeCardProjectionGroupStage(
            transaction = transaction,
            sourceTransforms = replacedStage?.sourceTransforms ?: captureCurrentTransforms(),
            sourceCardAlpha = replacedStage?.sourceCardAlpha ?: if (edgeEntryRequired) 0f else activeCardAlpha(),
            edgeEntryRequired = edgeEntryRequired,
            sourceBounds = sourceBounds,
        )
        pendingStage = stage
        displayVersion += 1
        return stage
    }

    fun recordMeasurement(
        expectedStage: HomeCardProjectionGroupStage,
        participant: HomeCardProjectionParticipant,
        bounds: HomeCardBounds?,
    ): Boolean {
        if (pendingStage !== expectedStage) return false
        expectedStage.recordMeasurement(participant, bounds)
        displayVersion += 1
        return true
    }

    fun stageParticipant(
        expectedStage: HomeCardProjectionGroupStage,
        participant: HomeCardProjectionParticipant,
        transform: HomeCardLayoutTransform,
    ): Boolean {
        if (pendingStage !== expectedStage) return false
        expectedStage.stage(participant, transform)
        displayVersion += 1
        if (expectedStage.isReady) commit(expectedStage)
        return true
    }

    fun markMissingMeasurements(expectedStage: HomeCardProjectionGroupStage): Boolean {
        if (pendingStage !== expectedStage) return false
        expectedStage.markMissingMeasurements()
        displayVersion += 1
        return true
    }

    fun settleUnreadyParticipants(expectedStage: HomeCardProjectionGroupStage): Boolean {
        if (pendingStage !== expectedStage) return false
        HomeCardProjectionParticipant.entries.forEach { participant ->
            if (!expectedStage.isParticipantReady(participant)) {
                expectedStage.stage(participant, HomeCardLayoutTransform.Identity)
            }
        }
        displayVersion += 1
        if (expectedStage.isReady) commit(expectedStage)
        return true
    }

    fun updateProgress(expectedRun: HomeCardProjectionGroupRun, value: Float): Boolean {
        expectedRun.updateProgress(value)
        return activeRun === expectedRun && pendingStage == null
    }

    fun startStandalone(
        transforms: HomeCardProjectionTransforms,
        cardInitialAlpha: Float,
    ) {
        pendingStage = null
        nextRunId += 1L
        activeRun = HomeCardProjectionGroupRun(
            id = nextRunId,
            epoch = activeRun.epoch,
            transforms = transforms,
            cardInitialAlpha = cardInitialAlpha,
            shouldAnimate = true,
            initialProgress = 0f,
        )
        displayVersion += 1
    }

    fun cancelStageIntoRun(expectedStage: HomeCardProjectionGroupStage): Boolean {
        if (pendingStage !== expectedStage) return false
        pendingStage = null
        nextRunId += 1L
        activeRun = HomeCardProjectionGroupRun(
            id = nextRunId,
            epoch = activeRun.epoch,
            transforms = expectedStage.sourceTransforms,
            cardInitialAlpha = expectedStage.sourceCardAlpha,
            shouldAnimate = true,
            initialProgress = 0f,
        )
        displayVersion += 1
        return true
    }

    private fun activeCardAlpha(): Float =
        activeRun.cardInitialAlpha + (1f - activeRun.cardInitialAlpha) * activeRun.progress

    private fun commit(stage: HomeCardProjectionGroupStage) {
        if (pendingStage !== stage) return
        nextRunId += 1L
        activeRun = HomeCardProjectionGroupRun(
            id = nextRunId,
            epoch = stage.epoch,
            transforms = stage.committedTransforms(),
            cardInitialAlpha = stage.sourceCardAlpha,
            shouldAnimate = true,
            initialProgress = 0f,
        )
        pendingStage = null
        displayVersion += 1
    }
}

@Composable
private fun rememberHomeViewCardLayoutMotion(
    layoutKey: HomeCardLayoutKey,
    animateEdgeEntry: Boolean,
): HomeViewCardLayoutMotion {
    val motion = remember { HomeViewCardLayoutMotion(layoutKey, animateEdgeEntry) }
    motion.updateLayout(layoutKey, animateEdgeEntry)

    HomeProjectionGroupEffect(motion)

    return motion
}

@Composable
private fun HomeProjectionGroupEffect(motion: HomeViewCardLayoutMotion) {
    val run = motion.activeGroupRun
    LaunchedEffect(run) {
        if (run.shouldAnimate) {
            motion.animate(run)
        }
    }
    val pendingStage = motion.pendingGroupStage
    LaunchedEffect(pendingStage) {
        if (pendingStage != null) {
            withFrameNanos { }
            motion.settleMissingParticipants(pendingStage)
        }
    }
}

private class HomeCardProjectionSlot(initialLayoutKey: HomeCardLayoutKey) {
    var previousBounds: HomeCardBounds? = null
    var previousLayoutKey: HomeCardLayoutKey = initialLayoutKey
    var consumedLayoutEpoch: Long = 0L
}

private class HomeViewCardLayoutMotion(
    initialLayoutKey: HomeCardLayoutKey,
    initialAnimateEdgeEntry: Boolean,
) {
    private val transactions = HomeLayoutTransactionState(initialLayoutKey)
    private val projectionGroup = HomeCardProjectionGroupState()
    private val slots = HomeCardProjectionParticipant.entries.associateWith {
        HomeCardProjectionSlot(initialLayoutKey)
    }
    private val layoutKey: HomeCardLayoutKey
        get() = transactions.requestedKey
    private var animateEdgeEntry = initialAnimateEdgeEntry
    private var coverCornerFrom by mutableStateOf(HomeCoverCorners.forListMode(initialLayoutKey.listMode))
    private var coverCornerTo by mutableStateOf(HomeCoverCorners.forListMode(initialLayoutKey.listMode))
    private var actionCornerFrom by mutableStateOf(HomeActionCorners.forListMode(initialLayoutKey.listMode))
    private var actionCornerTo by mutableStateOf(HomeActionCorners.forListMode(initialLayoutKey.listMode))
    private var actionLabelFrom by mutableFloatStateOf(if (initialLayoutKey.listMode) 0f else 1f)
    private var actionLabelTo by mutableFloatStateOf(if (initialLayoutKey.listMode) 0f else 1f)

    val activeGroupRun: HomeCardProjectionGroupRun
        get() = projectionGroup.activeRun
    val pendingGroupStage: HomeCardProjectionGroupStage?
        get() = projectionGroup.pendingStage

    fun updateLayout(value: HomeCardLayoutKey, shouldAnimateEdgeEntry: Boolean) {
        transactions.request(value)
        animateEdgeEntry = shouldAnimateEdgeEntry
        val pendingStage = projectionGroup.pendingStage
        if (
            pendingStage != null &&
            pendingStage.requestId != transactions.requestId &&
            value == transactions.measuredKey
        ) {
            beginProjectedVisualTransition(value.listMode)
            projectionGroup.cancelStageIntoRun(pendingStage)
        }
    }

    private fun beginProjectedVisualTransition(targetListMode: Boolean) {
        coverCornerFrom = currentCoverCorners()
        coverCornerTo = HomeCoverCorners.forListMode(targetListMode)
        actionCornerFrom = currentActionCorners()
        actionCornerTo = HomeActionCorners.forListMode(targetListMode)
        actionLabelFrom = actionLabelVisibility()
        actionLabelTo = if (targetListMode) 0f else 1f
    }

    fun cardModifier(
        onPositioned: (Offset) -> Unit = {},
    ): Modifier {
        val callbackKey = layoutKey
        val callbackRequestId = transactions.requestId
        val cardSlot = slot(HomeCardProjectionParticipant.CARD)
        return Modifier.onGloballyPositioned { coordinates ->
            val currentBounds = HomeCardBounds(
                position = coordinates.positionInRoot(),
                size = coordinates.size,
            )
            onPositioned(currentBounds.position)
            val wasUnmeasured = cardSlot.previousBounds == null
            recordProjectionMeasurement(
                participant = HomeCardProjectionParticipant.CARD,
                callbackKey = callbackKey,
                callbackRequestId = callbackRequestId,
                bounds = currentBounds,
            )
            if (
                wasUnmeasured &&
                animateEdgeEntry &&
                projectionGroup.pendingStage == null &&
                currentBounds.hasArea()
            ) {
                projectionGroup.startStandalone(
                    transforms = HomeCardProjectionTransforms.Identity.with(
                        HomeCardProjectionParticipant.CARD,
                        HomeCardLayoutTransform(
                            translationX = 0f,
                            translationY = currentBounds.size.height * HOME_VIEW_EDGE_ENTRY_OFFSET_FRACTION,
                            scaleX = 1f,
                            scaleY = 1f,
                        ),
                    ),
                    cardInitialAlpha = 0f,
                )
            }
        }
            .then(
                if (
                    animateEdgeEntry ||
                    projectionGroup.requiresGraphicsLayer ||
                    cardSlot.previousLayoutKey != layoutKey
                ) {
                    Modifier.graphicsLayer {
                        applyProjectionToLayer(
                            participant = HomeCardProjectionParticipant.CARD,
                            layerScope = this,
                        )
                    }
                } else {
                    Modifier
                },
            )
    }

    fun mediaModifier(): Modifier = projectionModifier(
        participant = HomeCardProjectionParticipant.MEDIA,
        parentParticipant = HomeCardProjectionParticipant.CARD,
    )

    fun tagModifier(): Modifier {
        val callbackKey = layoutKey
        val callbackRequestId = transactions.requestId
        val tagSlot = slot(HomeCardProjectionParticipant.TAG)
        return Modifier.onGloballyPositioned { coordinates ->
            recordProjectionMeasurement(
                participant = HomeCardProjectionParticipant.TAG,
                callbackKey = callbackKey,
                callbackRequestId = callbackRequestId,
                bounds = HomeCardBounds(
                    position = coordinates.positionInParent(),
                    size = coordinates.size,
                ),
            )
        }.then(
            if (
                animateEdgeEntry ||
                    projectionGroup.requiresGraphicsLayer ||
                    tagSlot.previousLayoutKey != layoutKey
            ) {
                Modifier.graphicsLayer {
                    applyProjectionToLayer(
                        participant = HomeCardProjectionParticipant.TAG,
                        layerScope = this,
                        parentTransform = projectionGroup.currentTransform(HomeCardProjectionParticipant.MEDIA),
                    )
                }
            } else {
                Modifier
            },
        )
    }

    fun contentModifier(): Modifier = projectionModifier(
        participant = HomeCardProjectionParticipant.CONTENT,
        parentParticipant = HomeCardProjectionParticipant.CARD,
    )

    fun actionModifier(): Modifier = projectionModifier(
        participant = HomeCardProjectionParticipant.ACTION,
        // Grid buttons and the list action have different aspect ratios. Project
        // both dimensions so the control changes size continuously with the card.
        parentParticipant = HomeCardProjectionParticipant.CARD,
    )

    fun actionContentModifier(): Modifier {
        val actionSlot = slot(HomeCardProjectionParticipant.ACTION)
        if (
            !animateEdgeEntry &&
            !projectionGroup.requiresGraphicsLayer &&
            actionSlot.previousLayoutKey == layoutKey
        ) {
            return Modifier
        }
        return Modifier.graphicsLayer {
            // Keep content compensation on the same draw frame as the outer action projection.
            val actionTransform = projectionGroup.currentTransform(HomeCardProjectionParticipant.ACTION)
            transformOrigin = TransformOrigin.Center
            scaleX = 1f / actionTransform.scaleX.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
            scaleY = 1f / actionTransform.scaleY.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
        }
    }

    fun actionLabelVisibility(): Float {
        val contentProgress = (
            (projectionGroup.progress - HOME_VIEW_ACTION_CONTENT_FADE_START) /
                (HOME_VIEW_ACTION_CONTENT_FADE_END - HOME_VIEW_ACTION_CONTENT_FADE_START)
            ).coerceIn(0f, 1f)
        return actionLabelFrom + (actionLabelTo - actionLabelFrom) * contentProgress
    }

    fun cardShape(): Shape = HomeCoverCorners.forCard().toProjectedShape(
        transform = projectionGroup.currentTransform(HomeCardProjectionParticipant.CARD),
    )

    fun coverShape(): Shape = currentCoverCorners().toProjectedShape(
        transform = projectionGroup.currentTransform(HomeCardProjectionParticipant.MEDIA),
    )

    fun actionShape(): Shape {
        return currentActionCorners().toCoverCorners().toProjectedShape(
            transform = projectionGroup.currentTransform(HomeCardProjectionParticipant.ACTION),
        )
    }

    private fun projectionModifier(
        participant: HomeCardProjectionParticipant,
        parentParticipant: HomeCardProjectionParticipant,
    ): Modifier {
        val callbackKey = layoutKey
        val callbackRequestId = transactions.requestId
        val projectionSlot = slot(participant)
        return Modifier.onGloballyPositioned { coordinates ->
            recordProjectionMeasurement(
                participant = participant,
                callbackKey = callbackKey,
                callbackRequestId = callbackRequestId,
                bounds = HomeCardBounds(
                    position = coordinates.positionInRoot(),
                    size = coordinates.size,
                ),
            )
        }.then(
            if (
                animateEdgeEntry ||
                    projectionGroup.requiresGraphicsLayer ||
                    projectionSlot.previousLayoutKey != layoutKey
            ) {
                Modifier.graphicsLayer {
                    applyProjectionToLayer(
                        participant = participant,
                        layerScope = this,
                        parentTransform = projectionGroup.currentTransform(parentParticipant),
                    )
                }
            } else {
                Modifier
            },
        )
    }

    private fun slot(participant: HomeCardProjectionParticipant): HomeCardProjectionSlot =
        checkNotNull(slots[participant])

    private fun recordProjectionMeasurement(
        participant: HomeCardProjectionParticipant,
        callbackKey: HomeCardLayoutKey,
        callbackRequestId: Long,
        bounds: HomeCardBounds,
    ) {
        if (callbackRequestId != transactions.requestId || callbackKey != layoutKey) return

        var stage = projectionGroup.pendingStage
        val transaction = if (stage == null || stage.requestId != callbackRequestId) {
            transactions.consumeForMeasurement(callbackRequestId)
        } else {
            null
        }
        if (transaction != null) {
            beginProjectedVisualTransition(transaction.targetKey.listMode)
            val sourceBounds = HomeCardProjectionParticipant.entries.associateWith { sourceParticipant ->
                val sourceSlot = slot(sourceParticipant)
                sourceSlot.previousBounds
                    ?.takeIf(HomeCardBounds::hasArea)
                    ?.takeIf { sourceSlot.previousLayoutKey == transaction.sourceKey }
            }
            stage = projectionGroup.beginStage(
                transaction = transaction,
                sourceBounds = sourceBounds,
                edgeEntryEnabled = animateEdgeEntry,
            )
        }

        if (
            stage != null &&
            stage.requestId == callbackRequestId &&
            stage.targetKey == callbackKey
        ) {
            if (
                projectionGroup.recordMeasurement(
                    expectedStage = stage,
                    participant = participant,
                    bounds = bounds.takeIf(HomeCardBounds::hasArea),
                )
            ) {
                resolveStage(stage)
            }
        } else {
            val projectionSlot = slot(participant)
            projectionSlot.previousBounds = bounds
            projectionSlot.previousLayoutKey = callbackKey
        }
    }

    private fun resolveStage(stage: HomeCardProjectionGroupStage) {
        if (projectionGroup.pendingStage !== stage) return
        stageCard(stage)
        stageChild(stage, HomeCardProjectionParticipant.MEDIA, HomeChildScaleMode.UNIFORM)
        stageChild(stage, HomeCardProjectionParticipant.CONTENT, HomeChildScaleMode.NONE)
        stageChild(stage, HomeCardProjectionParticipant.ACTION, HomeChildScaleMode.NON_UNIFORM)
        stageTag(stage)
        if (projectionGroup.pendingStage !== stage) finalizeStage(stage)
    }

    private fun stageCard(stage: HomeCardProjectionGroupStage) {
        val participant = HomeCardProjectionParticipant.CARD
        if (!stage.hasMeasurement(participant) || stage.isParticipantReady(participant)) return
        val sourceBounds = stage.sourceBounds(participant)
        val targetBounds = stage.targetBounds(participant)
        val transform = calculateHomeCardInitialProjection(
            sourceBounds = sourceBounds,
            sourceTransform = stage.sourceTransforms[participant],
            targetBounds = targetBounds,
            edgeEntryRequired = stage.edgeEntryRequired,
        )
        projectionGroup.stageParticipant(stage, participant, transform)
    }

    private fun stageChild(
        stage: HomeCardProjectionGroupStage,
        participant: HomeCardProjectionParticipant,
        scaleMode: HomeChildScaleMode,
    ) {
        if (
            !stage.isParticipantReady(HomeCardProjectionParticipant.CARD) ||
            !stage.hasMeasurement(participant) ||
            stage.isParticipantReady(participant)
        ) {
            return
        }
        val sourceCardBounds = stage.sourceBounds(HomeCardProjectionParticipant.CARD)
        val targetCardBounds = stage.targetBounds(HomeCardProjectionParticipant.CARD)
        val visibleCardBounds = sourceCardBounds?.project(
            stage.sourceTransforms[HomeCardProjectionParticipant.CARD],
        )
        val sourceBounds = stage.sourceBounds(participant)
        val targetBounds = stage.targetBounds(participant)
        val transform = if (
            sourceCardBounds != null &&
            targetCardBounds != null &&
            visibleCardBounds != null &&
            sourceBounds != null &&
            targetBounds != null
        ) {
            val oldVisibleBounds = sourceBounds.projectWithinCard(
                cardBounds = sourceCardBounds,
                visibleCardBounds = visibleCardBounds,
                transform = stage.sourceTransforms[participant],
            )
            when (scaleMode) {
                HomeChildScaleMode.NONE -> targetBounds.inversePositionProjectionWithinCard(
                    cardBounds = targetCardBounds,
                    visibleCardBounds = visibleCardBounds,
                    targetVisibleBounds = oldVisibleBounds,
                )

                HomeChildScaleMode.UNIFORM -> targetBounds.inverseUniformScaleProjectionWithinCard(
                    cardBounds = targetCardBounds,
                    visibleCardBounds = visibleCardBounds,
                    targetVisibleBounds = oldVisibleBounds,
                )

                HomeChildScaleMode.NON_UNIFORM -> targetBounds.inverseScaleProjectionWithinCard(
                    cardBounds = targetCardBounds,
                    visibleCardBounds = visibleCardBounds,
                    targetVisibleBounds = oldVisibleBounds,
                )
            }
        } else {
            HomeCardLayoutTransform.Identity
        }
        projectionGroup.stageParticipant(stage, participant, transform)
    }

    private fun stageTag(stage: HomeCardProjectionGroupStage) {
        val participant = HomeCardProjectionParticipant.TAG
        if (
            !stage.isParticipantReady(HomeCardProjectionParticipant.CARD) ||
            !stage.isParticipantReady(HomeCardProjectionParticipant.MEDIA) ||
            !stage.hasMeasurement(participant) ||
            stage.isParticipantReady(participant)
        ) {
            return
        }
        val sourceCardBounds = stage.sourceBounds(HomeCardProjectionParticipant.CARD)
        val targetCardBounds = stage.targetBounds(HomeCardProjectionParticipant.CARD)
        val sourceMediaBounds = stage.sourceBounds(HomeCardProjectionParticipant.MEDIA)
        val targetMediaBounds = stage.targetBounds(HomeCardProjectionParticipant.MEDIA)
        val sourceBounds = stage.sourceBounds(participant)
        val targetBounds = stage.targetBounds(participant)
        val initialMediaTransform = stage.stagedTransform(HomeCardProjectionParticipant.MEDIA)
        val transform = if (
            sourceCardBounds != null &&
            targetCardBounds != null &&
            sourceMediaBounds != null &&
            targetMediaBounds != null &&
            sourceBounds != null &&
            targetBounds != null &&
            initialMediaTransform != null
        ) {
            calculateHomeTagProjection(
                sourceBounds = sourceBounds,
                targetBounds = targetBounds,
                sourceTagTransform = stage.sourceTransforms[participant],
                sourceMediaTransform = stage.sourceTransforms[HomeCardProjectionParticipant.MEDIA],
                initialMediaTransform = initialMediaTransform,
            )
        } else {
            HomeCardLayoutTransform.Identity
        }
        projectionGroup.stageParticipant(stage, participant, transform)
    }

    private fun finalizeStage(stage: HomeCardProjectionGroupStage) {
        if (!transactions.commit(stage.transaction)) return
        HomeCardProjectionParticipant.entries.forEach { participant ->
            val projectionSlot = slot(participant)
            projectionSlot.previousBounds = stage.targetBounds(participant)
            projectionSlot.previousLayoutKey = stage.targetKey
            projectionSlot.consumedLayoutEpoch = stage.epoch
        }
    }

    fun settleMissingParticipants(stage: HomeCardProjectionGroupStage) {
        if (!projectionGroup.markMissingMeasurements(stage)) return
        resolveStage(stage)
        if (projectionGroup.pendingStage === stage) {
            projectionGroup.settleUnreadyParticipants(stage)
            if (projectionGroup.pendingStage !== stage) finalizeStage(stage)
        }
    }

    suspend fun animate(run: HomeCardProjectionGroupRun) {
        animate(
            initialValue = run.progress,
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = HOME_VIEW_CARD_LAYOUT_DURATION_MS,
                easing = HOME_VIEW_LAYOUT_EASING,
            ),
        ) { value, _ ->
            projectionGroup.updateProgress(run, value)
        }
    }

    private fun currentCoverCorners(): HomeCoverCorners =
        coverCornerFrom.interpolateTo(coverCornerTo, projectionGroup.progress)

    private fun currentActionCorners(): HomeActionCorners =
        actionCornerFrom.interpolateTo(actionCornerTo, projectionGroup.progress)

    private fun applyProjectionToLayer(
        participant: HomeCardProjectionParticipant,
        layerScope: androidx.compose.ui.graphics.GraphicsLayerScope,
        parentTransform: HomeCardLayoutTransform? = null,
    ) {
        val visibleTransform = projectionGroup.currentTransform(participant)
        val parentScaleX = parentTransform?.scaleX ?: 1f
        val parentScaleY = parentTransform?.scaleY ?: 1f
        layerScope.transformOrigin = TransformOrigin(0f, 0f)
        layerScope.translationX = visibleTransform.translationX
        layerScope.translationY = visibleTransform.translationY
        layerScope.alpha = if (participant == HomeCardProjectionParticipant.CARD) {
            projectionGroup.currentCardAlpha()
        } else {
            1f
        }
        layerScope.scaleX = visibleTransform.scaleX / parentScaleX.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
        layerScope.scaleY = visibleTransform.scaleY / parentScaleY.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
    }
}

private enum class HomeChildScaleMode {
    NONE,
    UNIFORM,
    NON_UNIFORM,
}

internal data class HomeCardBounds(
    val position: Offset,
    val size: IntSize,
) {
    fun hasArea(): Boolean = size.width > 0 && size.height > 0

    fun project(transform: HomeCardLayoutTransform): HomeCardVisualBounds = HomeCardVisualBounds(
        position = position + Offset(transform.translationX, transform.translationY),
        width = size.width * transform.scaleX,
        height = size.height * transform.scaleY,
    )

    fun projectWithinCard(
        cardBounds: HomeCardBounds,
        visibleCardBounds: HomeCardVisualBounds,
        transform: HomeCardLayoutTransform,
    ): HomeCardVisualBounds {
        val cardScaleX = visibleCardBounds.width / cardBounds.size.width
        val cardScaleY = visibleCardBounds.height / cardBounds.size.height
        return HomeCardVisualBounds(
            position = visibleCardBounds.position + Offset(
                (position.x - cardBounds.position.x + transform.translationX) * cardScaleX,
                (position.y - cardBounds.position.y + transform.translationY) * cardScaleY,
            ),
            width = size.width * transform.scaleX,
            height = size.height * transform.scaleY,
        )
    }

    fun inversePositionProjectionWithinCard(
        cardBounds: HomeCardBounds,
        visibleCardBounds: HomeCardVisualBounds,
        targetVisibleBounds: HomeCardVisualBounds,
    ): HomeCardLayoutTransform {
        val cardScaleX = visibleCardBounds.width / cardBounds.size.width
        val cardScaleY = visibleCardBounds.height / cardBounds.size.height
        return HomeCardLayoutTransform(
            translationX =
                (targetVisibleBounds.position.x - visibleCardBounds.position.x) / cardScaleX -
                    (position.x - cardBounds.position.x),
            translationY =
                (targetVisibleBounds.position.y - visibleCardBounds.position.y) / cardScaleY -
                    (position.y - cardBounds.position.y),
            scaleX = 1f,
            scaleY = 1f,
        )
    }

    fun inverseUniformScaleProjectionWithinCard(
        cardBounds: HomeCardBounds,
        visibleCardBounds: HomeCardVisualBounds,
        targetVisibleBounds: HomeCardVisualBounds,
    ): HomeCardLayoutTransform {
        val positionTransform = inversePositionProjectionWithinCard(
            cardBounds = cardBounds,
            visibleCardBounds = visibleCardBounds,
            targetVisibleBounds = targetVisibleBounds,
        )
        // Both grid and list covers are square. One shared scale factor keeps
        // that aspect ratio locked while restoring the Web-style size motion.
        val uniformScale = targetVisibleBounds.width / size.width
        return positionTransform.copy(
            scaleX = uniformScale,
            scaleY = uniformScale,
        )
    }

    fun inverseScaleProjectionWithinCard(
        cardBounds: HomeCardBounds,
        visibleCardBounds: HomeCardVisualBounds,
        targetVisibleBounds: HomeCardVisualBounds,
    ): HomeCardLayoutTransform {
        val positionTransform = inversePositionProjectionWithinCard(
            cardBounds = cardBounds,
            visibleCardBounds = visibleCardBounds,
            targetVisibleBounds = targetVisibleBounds,
        )
        return positionTransform.copy(
            scaleX = targetVisibleBounds.width / size.width,
            scaleY = targetVisibleBounds.height / size.height,
        )
    }
}

internal data class HomeCardVisualBounds(
    val position: Offset,
    val width: Float,
    val height: Float,
) {
    fun hasArea(): Boolean = width > 0f && height > 0f
}

internal fun calculateHomeCardInitialProjection(
    sourceBounds: HomeCardBounds?,
    sourceTransform: HomeCardLayoutTransform,
    targetBounds: HomeCardBounds?,
    edgeEntryRequired: Boolean,
): HomeCardLayoutTransform {
    val visibleBounds = sourceBounds?.project(sourceTransform)
    return when {
        visibleBounds != null && targetBounds != null -> HomeCardLayoutTransform(
            translationX = visibleBounds.position.x - targetBounds.position.x,
            translationY = visibleBounds.position.y - targetBounds.position.y,
            scaleX = visibleBounds.width / targetBounds.size.width,
            scaleY = visibleBounds.height / targetBounds.size.height,
        )

        edgeEntryRequired && targetBounds != null -> HomeCardLayoutTransform.Identity.copy(
            translationY = targetBounds.size.height * HOME_VIEW_EDGE_ENTRY_OFFSET_FRACTION,
        )

        else -> HomeCardLayoutTransform.Identity
    }
}

internal fun calculateHomeTagProjection(
    sourceBounds: HomeCardBounds,
    targetBounds: HomeCardBounds,
    sourceTagTransform: HomeCardLayoutTransform,
    sourceMediaTransform: HomeCardLayoutTransform,
    initialMediaTransform: HomeCardLayoutTransform,
): HomeCardLayoutTransform {
    val sourceOffset = Offset(
        x = (sourceBounds.position.x + sourceTagTransform.translationX) * sourceMediaTransform.scaleX,
        y = (sourceBounds.position.y + sourceTagTransform.translationY) * sourceMediaTransform.scaleY,
    )
    val initialMediaScaleX = initialMediaTransform.scaleX.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
    val initialMediaScaleY = initialMediaTransform.scaleY.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)
    val uniformScale = sourceBounds.size.width.toFloat() * sourceTagTransform.scaleX /
        targetBounds.size.width.toFloat()
    return HomeCardLayoutTransform(
        translationX = sourceOffset.x / initialMediaScaleX - targetBounds.position.x,
        translationY = sourceOffset.y / initialMediaScaleY - targetBounds.position.y,
        scaleX = uniformScale,
        scaleY = uniformScale,
    )
}

private data class HomeCoverCorners(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomEnd: Dp,
    val bottomStart: Dp,
) {
    fun interpolateTo(target: HomeCoverCorners, progress: Float): HomeCoverCorners = HomeCoverCorners(
        topStart = topStart.interpolateTo(target.topStart, progress),
        topEnd = topEnd.interpolateTo(target.topEnd, progress),
        bottomEnd = bottomEnd.interpolateTo(target.bottomEnd, progress),
        bottomStart = bottomStart.interpolateTo(target.bottomStart, progress),
    )

    fun toProjectedShape(transform: HomeCardLayoutTransform): Shape =
        HomeProjectedRoundedCornerShape(
            topStart = topStart.project(transform),
            topEnd = topEnd.project(transform),
            bottomEnd = bottomEnd.project(transform),
            bottomStart = bottomStart.project(transform),
        )

    companion object {
        fun forCard(): HomeCoverCorners = HomeCoverCorners(
            topStart = HOME_COVER_CORNER_RADIUS,
            topEnd = HOME_COVER_CORNER_RADIUS,
            bottomEnd = HOME_COVER_CORNER_RADIUS,
            bottomStart = HOME_COVER_CORNER_RADIUS,
        )

        fun forListMode(listMode: Boolean): HomeCoverCorners = if (listMode) {
            HomeCoverCorners(
                topStart = HOME_COVER_CORNER_RADIUS,
                topEnd = 0.dp,
                bottomEnd = 0.dp,
                bottomStart = HOME_COVER_CORNER_RADIUS,
            )
        } else {
            HomeCoverCorners(
                topStart = HOME_COVER_CORNER_RADIUS,
                topEnd = HOME_COVER_CORNER_RADIUS,
                bottomEnd = 0.dp,
                bottomStart = 0.dp,
            )
        }
    }
}

private fun Dp.interpolateTo(target: Dp, progress: Float): Dp =
    (value + (target.value - value) * progress.coerceIn(0f, 1f)).dp

private fun Dp.project(transform: HomeCardLayoutTransform): HomeProjectedCornerRadius =
    HomeProjectedCornerRadius(
        horizontal = (value / transform.scaleX.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)).dp,
        vertical = (value / transform.scaleY.coerceAtLeast(HOME_VIEW_LAYOUT_MIN_SCALE)).dp,
    )

private data class HomeActionCorners(
    val radius: Dp,
) {
    fun interpolateTo(target: HomeActionCorners, progress: Float): HomeActionCorners =
        HomeActionCorners(radius = radius.interpolateTo(target.radius, progress))

    fun toCoverCorners(): HomeCoverCorners = HomeCoverCorners(
        topStart = radius,
        topEnd = radius,
        bottomEnd = radius,
        bottomStart = radius,
    )

    companion object {
        fun forListMode(listMode: Boolean): HomeActionCorners = HomeActionCorners(
            radius = if (listMode) {
                LIST_CARD_ACTION_CORNER_RADIUS
            } else {
                GRID_CARD_ACTION_CORNER_RADIUS
            },
        )
    }
}

private data class HomeProjectedCornerRadius(
    val horizontal: Dp,
    val vertical: Dp,
)

private data class HomeProjectedRoundedCornerShape(
    val topStart: HomeProjectedCornerRadius,
    val topEnd: HomeProjectedCornerRadius,
    val bottomEnd: HomeProjectedCornerRadius,
    val bottomStart: HomeProjectedCornerRadius,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        fun HomeProjectedCornerRadius.toCornerRadius(): CornerRadius = CornerRadius(
            x = with(density) { horizontal.toPx() }.coerceIn(0f, size.width / 2f),
            y = with(density) { vertical.toPx() }.coerceIn(0f, size.height / 2f),
        )
        val topLeft = if (layoutDirection == LayoutDirection.Ltr) topStart else topEnd
        val topRight = if (layoutDirection == LayoutDirection.Ltr) topEnd else topStart
        val bottomRight = if (layoutDirection == LayoutDirection.Ltr) bottomEnd else bottomStart
        val bottomLeft = if (layoutDirection == LayoutDirection.Ltr) bottomStart else bottomEnd
        return Outline.Rounded(
            RoundRect(
                0f,
                0f,
                size.width,
                size.height,
                topLeft.toCornerRadius(),
                topRight.toCornerRadius(),
                bottomRight.toCornerRadius(),
                bottomLeft.toCornerRadius(),
            ),
        )
    }
}

internal data class HomeCardLayoutTransform(
    val translationX: Float,
    val translationY: Float,
    val scaleX: Float,
    val scaleY: Float,
) {
    fun at(progress: Float): HomeCardLayoutTransform {
        val remaining = 1f - progress.coerceIn(0f, 1f)
        return HomeCardLayoutTransform(
            translationX = translationX * remaining,
            translationY = translationY * remaining,
            scaleX = 1f + (scaleX - 1f) * remaining,
            scaleY = 1f + (scaleY - 1f) * remaining,
        )
    }

    fun isVisible(): Boolean =
        abs(translationX) > HOME_VIEW_LAYOUT_POSITION_EPSILON_PX ||
            abs(translationY) > HOME_VIEW_LAYOUT_POSITION_EPSILON_PX ||
            abs(scaleX - 1f) > HOME_VIEW_LAYOUT_SCALE_EPSILON ||
            abs(scaleY - 1f) > HOME_VIEW_LAYOUT_SCALE_EPSILON

    companion object {
        val Identity = HomeCardLayoutTransform(
            translationX = 0f,
            translationY = 0f,
            scaleX = 1f,
            scaleY = 1f,
        )
    }
}

@Composable
private fun WorkshopListCardContent(
    item: WorkshopSummary,
    language: AppLanguage,
    typeTagScale: State<Float>,
    action: HomeCardAction,
    showFileSize: Boolean,
    showFavorites: Boolean,
    statisticsAvailableWidth: Dp,
    layoutMotion: HomeViewCardLayoutMotion,
    onPrimaryAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LIST_CARD_MEDIA_SIZE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkshopCoverFrame(
            item = item,
            language = language,
            compact = true,
            typeTagScale = typeTagScale,
            coverShape = layoutMotion.coverShape(),
            typeTagModifier = layoutMotion.tagModifier(),
            modifier = Modifier
                .size(LIST_CARD_MEDIA_SIZE)
                .then(layoutMotion.mediaModifier()),
        )
        WorkshopCardCopy(
            item = item,
            language = language,
            compact = true,
            twoColumnGrid = false,
            showFileSize = showFileSize,
            showFavorites = showFavorites,
            statisticsAvailableWidth = statisticsAvailableWidth,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
                .then(layoutMotion.contentModifier()),
        )
        WorkshopCardActionButton(
            action = action,
            language = language,
            shape = layoutMotion.actionShape(),
            contentModifier = layoutMotion.actionContentModifier(),
            labelVisibility = layoutMotion.actionLabelVisibility(),
            onPrimaryAction = onPrimaryAction,
            modifier = Modifier
                .padding(end = 10.dp)
                .size(LIST_CARD_ACTION_SIZE)
                .then(layoutMotion.actionModifier()),
        )
    }
}

@Composable
private fun WorkshopCoverFrame(
    item: WorkshopSummary,
    language: AppLanguage,
    compact: Boolean,
    typeTagScale: State<Float>,
    coverShape: Shape,
    typeTagModifier: Modifier,
    modifier: Modifier,
) {
    Box(
        modifier = modifier,
    ) {
        WorkshopCover(
            item = item,
            modifier = Modifier.fillMaxSize(),
            shape = coverShape,
        )
        WorkshopCoverTypeTag(
            item = item,
            language = language,
            typeTagScale = typeTagScale,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(if (compact) 6.dp else 8.dp)
                .then(typeTagModifier),
        )
    }
}

@Composable
private fun WorkshopCover(
    item: WorkshopSummary,
    modifier: Modifier,
    shape: Shape,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (item.previewUrl != null) {
            AsyncImage(
                model = item.previewUrl,
                contentDescription = "${item.title} 预览图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.ImageNotSupported,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun WorkshopCoverTypeTag(
    item: WorkshopSummary,
    language: AppLanguage,
    typeTagScale: State<Float>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.graphicsLayer {
            val scale = typeTagScale.value
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = scale
            scaleY = scale
        },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    ) {
        Text(
            text = item.type.label(language),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(
                horizontal = HOME_TYPE_TAG_HORIZONTAL_PADDING,
                vertical = HOME_TYPE_TAG_VERTICAL_PADDING,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkshopCardCopy(
    item: WorkshopSummary,
    language: AppLanguage,
    compact: Boolean,
    twoColumnGrid: Boolean,
    showFileSize: Boolean,
    showFavorites: Boolean,
    statisticsAvailableWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val statisticCount = 1 +
        (if (showFavorites) 1 else 0) +
        (if (showFileSize) 1 else 0)
    val statisticsMetrics = WorkshopCardStatisticsMetrics.forAvailableWidth(
        availableWidth = statisticsAvailableWidth,
        statisticCount = statisticCount,
        compact = compact,
        twoColumnGrid = twoColumnGrid,
    )
    val baseStatisticTextStyle = if (compact) {
        MaterialTheme.typography.labelSmall
    } else {
        MaterialTheme.typography.bodySmall
    }
    val statisticTextStyle = if (twoColumnGrid) {
        baseStatisticTextStyle.copy(
            fontSize = statisticsMetrics.fontSize.sp,
            lineHeight = TWO_COLUMN_CARD_STATISTICS_LINE_HEIGHT,
        )
    } else {
        baseStatisticTextStyle.copy(fontSize = statisticsMetrics.fontSize.sp)
    }
    val minimumCopyHeight = if (!compact) {
        with(density) {
            CARD_TITLE_HEIGHT +
                (if (twoColumnGrid) TWO_COLUMN_CARD_TITLE_STATISTICS_SPACING else 7.dp) +
                (if (twoColumnGrid) {
                    TWO_COLUMN_CARD_STATISTICS_LINE_HEIGHT.toDp()
                } else {
                    GRID_CARD_STATISTICS_LINE_HEIGHT.toDp()
                })
        }
    } else {
        0.dp
    }
    Column(
        modifier = if (compact) modifier else modifier.heightIn(min = minimumCopyHeight),
        verticalArrangement = Arrangement.spacedBy(
            when {
                compact -> 4.dp
                twoColumnGrid -> TWO_COLUMN_CARD_TITLE_STATISTICS_SPACING
                else -> 7.dp
            },
        ),
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (twoColumnGrid) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    space = statisticsMetrics.itemSpacing,
                    alignment = Alignment.Start,
                ),
                verticalArrangement = Arrangement.spacedBy(TWO_COLUMN_CARD_STATISTICS_ROW_SPACING),
                maxItemsInEachRow = 3,
            ) {
                WorkshopCardStatisticsItems(
                    item = item,
                    language = language,
                    showFileSize = showFileSize,
                    showFavorites = showFavorites,
                    textStyle = statisticTextStyle,
                    metrics = statisticsMetrics,
                    overflow = TextOverflow.Clip,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    space = statisticsMetrics.itemSpacing,
                    alignment = Alignment.Start,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkshopCardStatisticsItems(
                    item = item,
                    language = language,
                    showFileSize = showFileSize,
                    showFavorites = showFavorites,
                    textStyle = statisticTextStyle,
                    metrics = statisticsMetrics,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
private fun WorkshopCardStatisticsItems(
    item: WorkshopSummary,
    language: AppLanguage,
    showFileSize: Boolean,
    showFavorites: Boolean,
    textStyle: androidx.compose.ui.text.TextStyle,
    metrics: WorkshopCardStatisticsMetrics,
    overflow: TextOverflow,
) {
    WorkshopCardStatistic(
        icon = Icons.Outlined.FavoriteBorder,
        value = item.subscriptions?.let(language::formatCompact) ?: "—",
        contentDescription = language.text("订阅数", "Subscriptions"),
        textStyle = textStyle,
        iconSize = metrics.iconSize,
        iconSpacing = metrics.iconSpacing,
        overflow = overflow,
    )
    if (showFavorites) {
        WorkshopCardStatistic(
            icon = Icons.Outlined.StarBorder,
            value = item.favorites?.let(language::formatCompact) ?: "—",
            contentDescription = language.text("收藏数", "Favorites"),
            textStyle = textStyle,
            iconSize = metrics.iconSize,
            iconSpacing = metrics.iconSpacing,
            overflow = overflow,
        )
    }
    if (showFileSize) {
        Text(
            text = item.fileSizeBytes?.let(::formatMegabytes) ?: "— MB",
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = overflow,
        )
    }
}

@Composable
private fun WorkshopGridCardAction(
    action: HomeCardAction,
    language: AppLanguage,
    layoutMotion: HomeViewCardLayoutMotion,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WorkshopCardActionButton(
        action = action,
        language = language,
        shape = layoutMotion.actionShape(),
        contentModifier = layoutMotion.actionContentModifier(),
        labelVisibility = layoutMotion.actionLabelVisibility(),
        onPrimaryAction = onPrimaryAction,
        modifier = modifier
            .fillMaxWidth()
            .height(CARD_ACTION_HEIGHT)
            .then(layoutMotion.actionModifier()),
    )
}

@Composable
private fun WorkshopCardActionButton(
    action: HomeCardAction,
    language: AppLanguage,
    shape: Shape,
    contentModifier: Modifier,
    labelVisibility: Float,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionContentColor = MaterialTheme.colorScheme.onPrimary
    val label = action.label(language)
    val labelStyle = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelWidth = remember(label, labelStyle, density, textMeasurer) {
        with(density) {
            textMeasurer.measure(
                text = AnnotatedString(label),
                style = labelStyle,
            ).size.width.toDp()
        }
    }
    val labelProgress = labelVisibility.coerceIn(0f, 1f)
    val labelContainerWidth = 5.dp + labelWidth
    val iconOffsetPx = with(density) {
        labelContainerWidth.toPx() * (1f - labelProgress) / 2f
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Button(
            onClick = onPrimaryAction,
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            contentPadding = PaddingValues(0.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(contentModifier),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .wrapContentWidth(unbounded = true)
                        .graphicsLayer { translationX = iconOffsetPx },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = action.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = actionContentColor,
                    )
                    Box(
                        modifier = Modifier
                            .width(labelContainerWidth)
                            .drawWithContent drawContent@{
                                clipRect(right = size.width * labelProgress) {
                                    this@drawContent.drawContent()
                                }
                            }
                            .graphicsLayer { alpha = labelProgress },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = label,
                            style = labelStyle,
                            color = actionContentColor,
                            modifier = Modifier.padding(start = 5.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkshopCardStatistic(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    contentDescription: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    iconSize: Dp,
    iconSpacing: Dp,
    overflow: TextOverflow,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(iconSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = overflow,
        )
    }
}

private data class WorkshopCardStatisticsMetrics(
    val fontSize: Float,
    val iconSize: Dp,
    val iconSpacing: Dp,
    val itemSpacing: Dp,
) {
    companion object {
        fun forAvailableWidth(
            availableWidth: Dp,
            statisticCount: Int,
            compact: Boolean,
            twoColumnGrid: Boolean,
        ): WorkshopCardStatisticsMetrics {
            val slotWidth = availableWidth.value / statisticCount.coerceAtLeast(1)
            if (twoColumnGrid) {
                return WorkshopCardStatisticsMetrics(
                    fontSize = (slotWidth / TWO_COLUMN_CARD_STATISTICS_FONT_WIDTH_DIVISOR)
                        .coerceIn(
                            TWO_COLUMN_CARD_STATISTICS_MIN_FONT_SIZE,
                            TWO_COLUMN_CARD_STATISTICS_MAX_FONT_SIZE,
                        ),
                    iconSize = TWO_COLUMN_CARD_STATISTICS_ICON_SIZE,
                    iconSpacing = TWO_COLUMN_CARD_STATISTICS_ICON_SPACING,
                    itemSpacing = TWO_COLUMN_CARD_STATISTICS_ITEM_SPACING,
                )
            }
            val maximumFontSize = if (compact) 12f else 13f
            val minimumFontSize = if (compact) 9f else 8.5f
            // Keep the metadata in its natural left-to-right reading order while
            // allowing it to grow slightly on wider cards.
            val fontSize = (slotWidth / 5.5f).coerceIn(minimumFontSize, maximumFontSize)
            return WorkshopCardStatisticsMetrics(
                fontSize = fontSize,
                iconSize = when {
                    fontSize <= 9.5f -> 11.dp
                    fontSize <= 10.5f -> 12.dp
                    compact -> 13.dp
                    else -> 15.dp
                },
                iconSpacing = if (fontSize <= 9.5f) 2.dp else 3.dp,
                itemSpacing = if (fontSize <= 9.5f) 2.dp else if (compact) 4.dp else 6.dp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HomeFiltersSheet(
    applied: HomeFilterSelection,
    config: HomeFilterUiConfig,
    initialPage: HomeFilterPage,
    onDismiss: () -> Unit,
    onSelectionChanged: (HomeFilterSelection) -> Unit,
) {
    val pages = HomeFilterPage.entries
    val defaults = HomeFilterSelection.defaults().normalized(config.matureContentEnabled)
    var draft by rememberSaveable(stateSaver = homeFilterSelectionSaver) {
        mutableStateOf(applied)
    }
    LaunchedEffect(applied) {
        if (draft != applied) draft = applied
    }
    val pagerState = rememberPagerState(
        initialPage = pages.indexOf(initialPage).coerceAtLeast(0),
        pageCount = pages::size,
    )
    val pagerScope = rememberCoroutineScope()
    val selectedPage = pages[pagerState.currentPage.coerceIn(0, pages.lastIndex)]
    val browseScrollState = rememberScrollState()
    val contentScrollState = rememberScrollState()
    val themeScrollState = rememberScrollState()
    val displayScrollState = rememberScrollState()
    val pageScrollStates = remember(
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
        onSelectionChanged(normalized)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        sheetMaxWidth = 920.dp,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val condensed = maxHeight < 680.dp || LocalDensity.current.fontScale > 1.3f
            val compact = maxWidth < 840.dp || condensed
            val horizontalPadding = if (compact) 16.dp else 24.dp
            val contentMaxHeight = maxHeight * if (compact) 0.92f else 0.84f
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(if (compact) 1f else 0.92f)
                    .widthIn(max = 920.dp)
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
                        onPageSelected = { page ->
                            pagerScope.launch { pagerState.animateScrollToPage(pages.indexOf(page)) }
                        },
                    )
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        key = { page -> pages[page].name },
                    ) { pageIndex ->
                        val page = pages[pageIndex]
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = horizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        HomeFilterPageNavigation(
                            pages = pages,
                            selectedPage = selectedPage,
                            draft = draft,
                            language = config.language,
                            compact = false,
                            onPageSelected = { page ->
                                pagerScope.launch { pagerState.animateScrollToPage(pages.indexOf(page)) }
                            },
                            modifier = Modifier.width(208.dp),
                        )
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.weight(1f),
                            key = { page -> pages[page].name },
                        ) { pageIndex ->
                            val page = pages[pageIndex]
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

@Composable
private fun HomeFilterSheetHeader(
    language: AppLanguage,
    draft: HomeFilterSelection,
    defaults: HomeFilterSelection,
    onReset: () -> Unit,
    horizontalPadding: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
private fun HomeFilterPageNavigation(
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
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
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
                                    modifier = Modifier.size(20.dp),
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    colors = NavigationDrawerItemDefaults.colors(
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
private fun HomeFilterPageContent(
    page: HomeFilterPage,
    config: HomeFilterUiConfig,
    draft: HomeFilterSelection,
    scrollStates: Map<HomeFilterPage, ScrollState>,
    onDraftChanged: (HomeFilterSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when (page) {
            HomeFilterPage.BROWSE -> HomeBrowseFilterPage(
                config = config,
                draft = draft,
                scrollState = scrollStates.getValue(page),
                onDraftChanged = onDraftChanged,
            )

            HomeFilterPage.CONTENT -> HomeContentFilterPage(
                config = config,
                draft = draft,
                scrollState = scrollStates.getValue(page),
                onDraftChanged = onDraftChanged,
            )

            HomeFilterPage.THEME -> HomeThemeFilterPage(
                config = config,
                draft = draft,
                scrollState = scrollStates.getValue(page),
                onDraftChanged = onDraftChanged,
            )

            HomeFilterPage.DISPLAY -> HomeDisplayFilterPage(
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
private fun HomeBrowseFilterPage(
    config: HomeFilterUiConfig,
    draft: HomeFilterSelection,
    scrollState: ScrollState,
    onDraftChanged: (HomeFilterSelection) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HomeFilterSectionCard {
            HomeFilterSectionHeading(
                title = config.language.text("排序依据", "Sort by"),
                supportingText = config.language.text(
                    "选择创意工坊结果的排列方式",
                    "Choose how Workshop results are ordered",
                ),
            )
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                supportingText = config.language.text(
                    "仅“热门”排序会使用时间范围",
                    "Time range is available only for Popular sorting",
                ),
                enabled = draft.sort == WorkshopSort.TRENDING,
            )
            FlowRow(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeContentFilterPage(
    config: HomeFilterUiConfig,
    draft: HomeFilterSelection,
    scrollState: ScrollState,
    onDraftChanged: (HomeFilterSelection) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HomeFilterSectionCard {
            HomeFilterSectionHeading(
                title = config.language.text("壁纸类型", "Wallpaper type"),
                supportingText = if (config.multiSelect) {
                    config.language.text("可以同时选择多个类型", "You can select multiple types")
                } else {
                    config.language.text("当前设置为单选", "Currently configured for single selection")
                },
            )
            FlowRow(
                modifier = if (config.multiSelect) Modifier else Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                supportingText = if (config.matureContentEnabled) {
                    config.language.text("已允许显示成人内容选项", "Mature content options are available")
                } else {
                    config.language.text("成人内容已在设置中关闭", "Mature content is disabled in Settings")
                },
            )
            FlowRow(
                modifier = if (config.multiSelect) Modifier else Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WorkshopRating.entries
                    .filter { it != WorkshopRating.MATURE || config.matureContentEnabled }
                    .forEach { rating ->
                        HomeDraftFilterChip(
                            label = if (rating == WorkshopRating.ALL && !config.matureContentEnabled) {
                                config.language.text("全部允许级别", "All allowed")
                            } else {
                                rating.label(config.language)
                            },
                            selected = draft.ratings.isRatingSelected(
                                rating = rating,
                                matureContentEnabled = config.matureContentEnabled,
                            ),
                            singleChoice = !config.multiSelect,
                            onClick = {
                                onDraftChanged(
                                    draft.copy(
                                        ratings = draft.ratings.toggleRating(
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
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeThemeFilterPage(
    config: HomeFilterUiConfig,
    draft: HomeFilterSelection,
    scrollState: ScrollState,
    onDraftChanged: (HomeFilterSelection) -> Unit,
) {
    val allGenres = DEFAULT_HOME_GENRE_SELECTION
    val genresUnrestricted = draft.genres == allGenres
    val allOfficialTags = WorkshopFilterCatalog.officialTags.toSet()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HomeFilterSectionCard {
            HomeFilterSectionHeading(
                title = config.language.text("内容分类", "Genres"),
                supportingText = config.language.text(
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                supportingText = config.language.text(
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeDisplayFilterPage(
    config: HomeFilterUiConfig,
    draft: HomeFilterSelection,
    scrollState: ScrollState,
    onDraftChanged: (HomeFilterSelection) -> Unit,
) {
    val allResolutions = DEFAULT_HOME_RESOLUTION_SELECTION
    val unrestricted = draft.resolutions == allResolutions
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HomeFilterSectionCard {
            HomeFilterSectionHeading(
                title = config.language.text("分辨率", "Resolution"),
                supportingText = config.language.text(
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = group.id.localizedResolutionGroup(config.language),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        group.options.forEach { resolution ->
                            HomeDraftFilterChip(
                                label = resolution.localizedResolution(config.language),
                                selected = !unrestricted && resolution in draft.resolutions,
                                onClick = {
                                    onDraftChanged(
                                        draft.copy(
                                            resolutions = draft.resolutions.toggleBounded(
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
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun HomeFilterSectionCard(
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (enabled) 1f else 0.55f,
                ),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun HomeFilterSectionHeading(
    title: String,
    supportingText: String,
    enabled: Boolean = true,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
private fun HomeFilterChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary,
                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HomeDraftFilterChip(
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
        minHeight = 48.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

private fun HomeFilterPage.label(language: AppLanguage): String = when (this) {
    HomeFilterPage.BROWSE -> language.text("浏览", "Browse")
    HomeFilterPage.CONTENT -> language.text("内容", "Content")
    HomeFilterPage.THEME -> language.text("主题", "Theme")
    HomeFilterPage.DISPLAY -> language.text("屏幕", "Display")
}

private fun HomeFilterPage.summary(
    selection: HomeFilterSelection,
    state: HomeUiState,
): String = when (this) {
    HomeFilterPage.BROWSE -> if (selection.sort == WorkshopSort.TRENDING) {
        "${selection.sort.label(state.language)} · ${selection.days.label(state.language)}"
    } else {
        selection.sort.label(state.language)
    }

    HomeFilterPage.CONTENT -> when (activeSectionCount(selection)) {
        0 -> state.text("不限", "Any")
        1 -> if (selection.types.isNotEmpty()) {
            selection.types.summary(state.language, state.text("不限", "Any"))
        } else {
            selection.ratings.summary(state.language, state.matureContentEnabled)
        }

        else -> state.text("类型与评级", "Type and rating")
    }

    HomeFilterPage.THEME -> if (activeSectionCount(selection) == 0) {
        state.text("不限", "Any")
    } else {
        state.text(
            "${activeSectionCount(selection)} 个分区",
            "${activeSectionCount(selection)} sections",
        )
    }

    HomeFilterPage.DISPLAY -> if (selection.resolutions == DEFAULT_HOME_RESOLUTION_SELECTION) {
        state.text("不限", "Any")
    } else {
        state.text(
            "${selection.resolutions.size} 项",
            "${selection.resolutions.size} selected",
        )
    }
}

private fun HomeFilterPage.icon(): ImageVector = when (this) {
    HomeFilterPage.BROWSE -> Icons.Outlined.Schedule
    HomeFilterPage.CONTENT -> Icons.Outlined.GridView
    HomeFilterPage.THEME -> Icons.Outlined.Palette
    HomeFilterPage.DISPLAY -> Icons.Outlined.PhoneAndroid
}

private fun HomeFilterPage.activeSectionCount(selection: HomeFilterSelection): Int = when (this) {
    HomeFilterPage.BROWSE -> when {
        selection.sort != WorkshopSort.TRENDING -> 1
        selection.days != 30 -> 1
        else -> 0
    }

    HomeFilterPage.CONTENT -> listOf(
        selection.types.isNotEmpty(),
        selection.ratings != DEFAULT_HOME_RATING_SELECTION,
    ).count { it }

    HomeFilterPage.THEME -> listOf(
        selection.genres != DEFAULT_HOME_GENRE_SELECTION,
        selection.officialTags.isNotEmpty(),
    ).count { it }

    HomeFilterPage.DISPLAY -> if (selection.resolutions != DEFAULT_HOME_RESOLUTION_SELECTION) 1 else 0
}

private fun <T> Set<T>.toggleOptional(value: T, multiSelect: Boolean): Set<T> = when {
    !multiSelect -> setOf(value)
    value in this -> this - value
    else -> this + value
}

private fun <T> Set<T>.toggleBounded(value: T, allOptions: Set<T>): Set<T> {
    val current = if (isEmpty() || this == allOptions) allOptions else this
    if (current == allOptions) return setOf(value)
    val next = if (value in current) current - value else current + value
    return if (next.isEmpty() || next == allOptions) allOptions else next
}

internal fun <T> Set<T>.invertBounded(allOptions: Set<T>): Set<T> =
    (allOptions - this).ifEmpty { allOptions }

private fun Set<WorkshopRating>.toggleRating(
    rating: WorkshopRating,
    multiSelect: Boolean,
    matureContentEnabled: Boolean,
): Set<WorkshopRating> = when {
    rating == WorkshopRating.ALL -> if (matureContentEnabled) {
        setOf(WorkshopRating.ALL)
    } else {
        SAFE_HOME_RATING_SELECTION
    }

    !multiSelect -> setOf(rating)
    !matureContentEnabled && normalizedRatings(false) == SAFE_HOME_RATING_SELECTION -> setOf(rating)
    rating in normalizedRatings(matureContentEnabled) ->
        (normalizedRatings(matureContentEnabled) - rating).ifEmpty { DEFAULT_HOME_RATING_SELECTION }

    else -> (normalizedRatings(matureContentEnabled) - WorkshopRating.ALL + rating)
        .normalizedRatings(matureContentEnabled)
}

private fun Set<WorkshopRating>.normalizedRatings(matureContentEnabled: Boolean): Set<WorkshopRating> {
    if (WorkshopRating.ALL in this) {
        return if (matureContentEnabled) setOf(WorkshopRating.ALL) else SAFE_HOME_RATING_SELECTION
    }
    return filterNot { it == WorkshopRating.MATURE && !matureContentEnabled }
        .toSet()
        .ifEmpty { DEFAULT_HOME_RATING_SELECTION }
}

private fun Set<WorkshopRating>.isRatingSelected(
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

private fun HomeUiState.text(zh: String, en: String): String = if (language == AppLanguage.EN) en else zh

private fun WorkshopSort.label(language: AppLanguage): String = when (this) {
    WorkshopSort.TRENDING -> if (language == AppLanguage.EN) "Popular" else "热门"
    WorkshopSort.MOST_RECENT -> if (language == AppLanguage.EN) "Most recent" else "最新"
    WorkshopSort.TOP_RATED -> if (language == AppLanguage.EN) "Top rated" else "最高评分"
    WorkshopSort.MOST_VOTES -> if (language == AppLanguage.EN) "Most votes" else "最多投票"
    WorkshopSort.MOST_SUBSCRIBERS -> if (language == AppLanguage.EN) "Most subscribers" else "最多订阅"
}

private fun Int.label(language: AppLanguage): String = when (this) {
    0 -> if (language == AppLanguage.EN) "All time" else "全部时间"
    1 -> if (language == AppLanguage.EN) "Today" else "今天"
    7 -> if (language == AppLanguage.EN) "7 days" else "7 天"
    30 -> if (language == AppLanguage.EN) "30 days" else "30 天"
    90 -> if (language == AppLanguage.EN) "3 months" else "3 个月"
    180 -> if (language == AppLanguage.EN) "6 months" else "半年"
    365 -> if (language == AppLanguage.EN) "1 year" else "一年"
    else -> if (language == AppLanguage.EN) "$this days" else "$this 天"
}

private fun timeRangeOptions(language: AppLanguage, currentDays: Int): List<Pair<Int, String>> {
    val finiteRanges = (listOf(1, 7, 30, 90, 180, 365) + currentDays)
        .filter { it > 0 }
        .distinct()
        .sorted()
    return (finiteRanges + 0).map { it to it.label(language) }
}

private fun WorkshopType.label(language: AppLanguage): String = when (this) {
    WorkshopType.VIDEO -> if (language == AppLanguage.EN) "Video" else "视频"
    WorkshopType.SCENE -> if (language == AppLanguage.EN) "Scene" else "场景"
    WorkshopType.WEB -> if (language == AppLanguage.EN) "Web" else "网站"
    WorkshopType.UNKNOWN -> if (language == AppLanguage.EN) "Wallpaper" else "壁纸"
}

private fun Set<WorkshopType>.summary(language: AppLanguage, all: String): String =
    if (isEmpty()) all else joinToString(" / ") { it.label(language) }

private fun WorkshopRating.label(language: AppLanguage): String = when (this) {
    WorkshopRating.ALL -> if (language == AppLanguage.EN) "All" else "全部"
    WorkshopRating.EVERYONE -> if (language == AppLanguage.EN) "Everyone" else "大众级"
    WorkshopRating.QUESTIONABLE -> if (language == AppLanguage.EN) "Questionable" else "家长指导级"
    WorkshopRating.MATURE -> if (language == AppLanguage.EN) "Mature" else "限制成人级"
}

private fun Set<WorkshopRating>.summary(
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

private fun HomeCardAction.label(language: AppLanguage): String = when (this) {
    HomeCardAction.DOWNLOAD -> if (language == AppLanguage.EN) "Download" else "下载"
    HomeCardAction.PLAY_VIDEO -> if (language == AppLanguage.EN) "Play" else "播放"
    HomeCardAction.OPEN_STEAM -> if (language == AppLanguage.EN) "Steam" else "打开 Steam"
}

private fun HomeCardAction.icon() = when (this) {
    HomeCardAction.DOWNLOAD -> Icons.Outlined.Download
    HomeCardAction.PLAY_VIDEO -> Icons.Outlined.PlayArrow
    HomeCardAction.OPEN_STEAM -> Icons.Outlined.OpenInNew
}

private fun AppLanguage.formatCompact(value: Long): String = when {
    value >= 1_000_000 -> String.format(if (this == AppLanguage.EN) "%.1fM" else "%.1f 万", if (this == AppLanguage.EN) value / 1_000_000.0 else value / 10_000.0)
    value >= 1_000 -> String.format("%.1fK", value / 1_000.0)
    else -> value.toString()
}

private fun String.localizedGenre(language: AppLanguage): String {
    if (language == AppLanguage.EN) return this
    return HOME_GENRE_LABELS_ZH[this] ?: this
}

private fun String.localizedOfficialTag(language: AppLanguage): String {
    if (language == AppLanguage.EN) return this
    return HOME_OFFICIAL_TAG_LABELS_ZH[this] ?: this
}

private fun String.localizedResolutionGroup(language: AppLanguage): String = if (language == AppLanguage.EN) {
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

private fun String.localizedResolution(language: AppLanguage): String {
    if (language == AppLanguage.EN) return this
    return HOME_RESOLUTION_LABELS_ZH[this] ?: this
}

private val HOME_GENRE_LABELS_ZH = mapOf(
    "Abstract" to "抽象", "Animal" to "动物", "Anime" to "动漫", "Cartoon" to "卡通", "CGI" to "CGI",
    "Cyberpunk" to "赛博朋克", "Fantasy" to "幻想", "Game" to "游戏", "Girls" to "女性", "Guys" to "男性",
    "Landscape" to "风景", "Medieval" to "中世纪", "Memes" to "网络事物", "MMD" to "MMD", "Music" to "音乐",
    "Nature" to "自然", "Pixel art" to "像素艺术", "Relaxing" to "放松", "Retro" to "复古", "Sci-Fi" to "科幻",
    "Sports" to "运动", "Technology" to "科技", "Television" to "电视节目", "Vehicle" to "汽车",
    "Unspecified" to "未指定样式",
)

private val HOME_OFFICIAL_TAG_LABELS_ZH = mapOf(
    "Approved" to "广受好评", "Audio responsive" to "音频响应", "Customizable" to "可自定义",
    "Puppet Warp" to "骨骼变形", "Media Integration" to "媒体集成", "User Shortcut" to "用户快捷方式",
    "Video Texture" to "视频纹理", "Asset Pack" to "资源包",
)

private val HOME_RESOLUTION_LABELS_ZH = mapOf(
    "Standard" to "标准", "Ultrawide" to "超宽（标准）", "Dual monitor" to "双显示器（标准）",
    "Triple monitor" to "三显示器（标准）", "Portrait" to "纵向（标准）",
    "Other resolution" to "其他分辨率", "Dynamic resolution" to "动态分辨率",
)

private const val FILTER_COLLAPSE_OFFSET_PX = 24
private const val FILTER_SAVER_SEPARATOR = "\u001F"
private const val HOME_FILTER_PAGE_SIZE_DURATION_MS = 300
private val HOME_FILTER_PAGE_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val HOME_TOP_TOAST_DURATION_MS = 3_000L
// Keep the discover header compact while providing explicit no-label content
// padding so the hint and entered text are never vertically clipped.
private val HOME_SEARCH_FIELD_HEIGHT = 48.dp
private val HOME_GRID_HORIZONTAL_PADDING = 16.dp
private val HOME_GRID_ITEM_SPACING = 10.dp
private const val HOME_AUTO_LOAD_MORE_THRESHOLD = 4
private const val HOME_VIEW_LAYOUT_ANIMATION_DURATION_MS = 400
private const val HOME_VIEW_CARD_LAYOUT_DURATION_MS = HOME_VIEW_LAYOUT_ANIMATION_DURATION_MS
private const val HOME_VIEW_TYPE_TAG_LAYOUT_DURATION_MS = 260
private const val HOME_VIEW_LAYOUT_POSITION_EPSILON_PX = 0.5f
private const val HOME_VIEW_LAYOUT_SCALE_EPSILON = 0.005f
private const val HOME_VIEW_LAYOUT_MIN_SCALE = 0.01f
private const val HOME_VIEW_EDGE_ENTRY_OFFSET_FRACTION = 0.08f
private const val HOME_VIEW_ACTION_CONTENT_FADE_START = 0.18f
private const val HOME_VIEW_ACTION_CONTENT_FADE_END = 0.58f
private val HOME_VIEW_LAYOUT_EASING = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val HOME_COVER_CORNER_RADIUS = 12.dp
private val HOME_WALLPAPER_CARD_SHAPE = RoundedCornerShape(HOME_COVER_CORNER_RADIUS)
private const val HOME_COMPACT_TYPE_TAG_SCALE = 0.84f
private val HOME_TYPE_TAG_HORIZONTAL_PADDING = 8.dp
private val HOME_TYPE_TAG_VERTICAL_PADDING = 4.dp
private val HOME_CONTEXT_MENU_PRESS_TRANSLATION_Y = 1.dp
private const val HOME_CONTEXT_MENU_GRID_PRESS_SCALE = 0.985f
private const val HOME_CONTEXT_MENU_LIST_PRESS_SCALE = 0.99f
private const val HOME_CONTEXT_MENU_PRESS_STIFFNESS = 500f
private val HOME_CONTEXT_MENU_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val CARD_TITLE_HEIGHT = 44.dp
private val CARD_ACTION_HEIGHT = 40.dp
private val TWO_COLUMN_CARD_COPY_TOP_PADDING = 8.dp
private val TWO_COLUMN_CARD_TITLE_STATISTICS_SPACING = 4.dp
private val TWO_COLUMN_CARD_STATISTICS_LINE_HEIGHT = 14.sp
private val GRID_CARD_STATISTICS_LINE_HEIGHT = 16.sp
private val TWO_COLUMN_CARD_STATISTICS_ICON_SIZE = 13.dp
private val TWO_COLUMN_CARD_STATISTICS_ICON_SPACING = 2.5.dp
private val TWO_COLUMN_CARD_STATISTICS_ITEM_SPACING = 5.dp
private val TWO_COLUMN_CARD_STATISTICS_ROW_SPACING = 2.dp
private val TWO_COLUMN_CARD_ACTION_TOP_PADDING = 6.dp
private const val TWO_COLUMN_CARD_STATISTICS_MIN_FONT_SIZE = 10.5f
private const val TWO_COLUMN_CARD_STATISTICS_MAX_FONT_SIZE = 11.5f
private const val TWO_COLUMN_CARD_STATISTICS_FONT_WIDTH_DIVISOR = 4.6f
private val HOME_VIEW_MODE_TOGGLE_INSET = 3.dp
private val HOME_VIEW_MODE_TOGGLE_BUTTON_SIZE = 34.dp
private val HOME_VIEW_MODE_TOGGLE_HEIGHT = 40.dp
private val HOME_VIEW_MODE_TOGGLE_WIDTH = 74.dp
private const val HOME_VIEW_MODE_TOGGLE_DURATION_MS = 240
private val GRID_CARD_ACTION_CORNER_RADIUS = 12.dp
private val LIST_CARD_ACTION_CORNER_RADIUS = 12.dp
private val LIST_CARD_MEDIA_SIZE = 104.dp
private val LIST_CARD_ACTION_SIZE = 40.dp
private val LIST_CARD_ACTION_END_PADDING = 10.dp
private val LIST_CARD_COPY_HORIZONTAL_PADDING = 20.dp
private val GRID_CARD_COPY_HORIZONTAL_PADDING = 20.dp
