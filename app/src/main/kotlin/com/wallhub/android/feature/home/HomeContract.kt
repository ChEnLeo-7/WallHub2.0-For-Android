@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.home

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.model.SteamAccessRepository
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.WorkshopFilterCatalog
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType

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
        fun resolve(
            viewMode: HomeViewMode,
            columns: Int,
        ): HomeCardLayoutKey =
            HomeCardLayoutKey(
                viewMode = viewMode,
                effectiveColumns = if (viewMode == HomeViewMode.LIST) 1 else columns.coerceAtLeast(1),
            )
    }
}

internal enum class HomeFilterPage {
    BROWSE,
    CONTENT,
    THEME,
    DISPLAY,
}

internal val DEFAULT_HOME_GENRE_SELECTION = WorkshopFilterCatalog.genres.toSet()
internal val DEFAULT_HOME_RESOLUTION_SELECTION = WorkshopFilterCatalog.resolutions.toSet()
internal val DEFAULT_HOME_RATING_SELECTION = setOf(WorkshopRating.EVERYONE)
internal val SAFE_HOME_RATING_SELECTION =
    setOf(
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
    fun normalized(matureContentEnabled: Boolean): HomeFilterSelection =
        copy(
            days = days.coerceIn(0, 365),
            types = types.filter { it != WorkshopType.UNKNOWN }.toSet(),
            ratings = ratings.normalizedRatings(matureContentEnabled),
            genres =
                genres
                    .intersect(DEFAULT_HOME_GENRE_SELECTION)
                    .ifEmpty { DEFAULT_HOME_GENRE_SELECTION },
            officialTags = officialTags.intersect(WorkshopFilterCatalog.officialTags.toSet()),
            resolutions =
                resolutions
                    .intersect(DEFAULT_HOME_RESOLUTION_SELECTION)
                    .ifEmpty { DEFAULT_HOME_RESOLUTION_SELECTION },
        )

    fun activeSectionCount(): Int =
        (if (sort != WorkshopSort.TRENDING) 1 else 0) +
            (if (sort == WorkshopSort.TRENDING && days != 30) 1 else 0) +
            (if (types.isNotEmpty()) 1 else 0) +
            (if (ratings != DEFAULT_HOME_RATING_SELECTION) 1 else 0) +
            (if (genres != DEFAULT_HOME_GENRE_SELECTION) 1 else 0) +
            (if (officialTags.isNotEmpty()) 1 else 0) +
            (if (resolutions != DEFAULT_HOME_RESOLUTION_SELECTION) 1 else 0)

    companion object {
        fun defaults(): HomeFilterSelection =
            HomeFilterSelection(
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
internal data class HomeFilterUiConfig(
    val language: AppLanguage,
    val multiSelect: Boolean,
    val matureContentEnabled: Boolean,
)

internal val homeFilterSelectionSaver =
    listSaver<HomeFilterSelection, String>(
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

internal fun String.savedStringSet(): Set<String> = takeIf(String::isNotEmpty)?.split(FILTER_SAVER_SEPARATOR)?.toSet().orEmpty()

internal fun HomeUiState.filterSelection(): HomeFilterSelection =
    HomeFilterSelection(
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
    val steamAccessEnabled: Boolean = true,
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
    val isSteamIpPrewarming: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isPageLoading: Boolean = false,
    val error: String? = null,
    val successfulSearchToken: Long = 0L,
) {
    val activeFilterCount: Int
        get() = filterSelection().activeSectionCount()
}

sealed interface HomeAction {
    data class QueryChanged(
        val query: String,
    ) : HomeAction

    data object SubmitSearch : HomeAction

    data object RestoreUnsubmittedQuery : HomeAction

    data object ToggleExactPhrase : HomeAction

    data class ApplyFilters(
        val selection: HomeFilterSelection,
    ) : HomeAction

    data class SelectViewMode(
        val viewMode: HomeViewMode,
    ) : HomeAction

    data object ResetAndRefresh : HomeAction

    data object Refresh : HomeAction

    data object LoadNextPage : HomeAction

    data class SelectPage(
        val page: Int,
    ) : HomeAction

    data class RequestAuthorDisplayName(
        val item: WorkshopSummary,
    ) : HomeAction

    data class RequestDownload(
        val item: WorkshopSummary,
    ) : HomeAction

    data class LegacyStoragePermissionResult(
        val item: WorkshopSummary,
        val granted: Boolean,
    ) : HomeAction

    data class OpenDetail(
        val workshopId: Long,
    ) : HomeAction

    data class SearchAuthor(
        val creator: String,
    ) : HomeAction

    data class CopyText(
        val text: String,
        val message: String,
    ) : HomeAction

    data class OpenSteam(
        val workshopId: Long,
    ) : HomeAction
}

sealed interface HomeEffect {
    data class ResolveLegacyStoragePermission(
        val item: WorkshopSummary,
    ) : HomeEffect

    data class ShowMessage(
        val message: String,
    ) : HomeEffect

    data class OpenDetail(
        val workshopId: Long,
    ) : HomeEffect

    data class SearchAuthor(
        val creator: String,
    ) : HomeEffect

    data class CopyText(
        val text: String,
        val message: String,
    ) : HomeEffect

    data class OpenSteam(
        val workshopId: Long,
    ) : HomeEffect
}

internal data class HomeLoadingIndicatorVisibility(
    val showPullToRefresh: Boolean,
    val showSteamIpPrewarm: Boolean,
)

internal fun HomeUiState.loadingIndicatorVisibility(): HomeLoadingIndicatorVisibility =
    HomeLoadingIndicatorVisibility(
        showPullToRefresh = (isInitialLoading || isPageLoading) && !isSteamIpPrewarming,
        showSteamIpPrewarm = isInitialLoading && isSteamIpPrewarming,
    )

internal fun String.creatorIdOrNull(): String? {
    val query = trim()
    if (!query.startsWith("author:", ignoreCase = true)) return null
    return query.substringAfter(':').filter(Char::isDigit).takeIf(String::isNotBlank)
}

internal fun String.isSteamAuthorPlaceholder(): Boolean = this == "Steam 创作者" || startsWith("Steam 用户 ")

internal fun HomeUiState.asAuthorSearchState(creatorId: String): HomeUiState =
    copy(
        query = "author:$creatorId",
        creatorId = creatorId,
        exactPhrase = false,
        selectedTypes = emptySet(),
        selectedRatings =
            if (matureContentEnabled) {
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
    )

internal fun initialHomeUiState(authorSearchCreator: String?): HomeUiState {
    val normalizedCreatorId =
        authorSearchCreator
            ?.filter(Char::isDigit)
            ?.takeIf(String::isNotBlank)
            ?: return HomeUiState()
    return HomeUiState().asAuthorSearchState(normalizedCreatorId)
}

internal fun shouldPrewarmSteamIp(
    steamAccessEnabled: Boolean,
    dataSource: SteamWorkshopDataSource,
    append: Boolean,
    hasItems: Boolean,
): Boolean =
    steamAccessEnabled &&
        !append &&
        !hasItems &&
        dataSource != SteamWorkshopDataSource.CM_WEBSOCKET

internal suspend fun requireSteamIpPrewarm(
    shouldPrewarm: Boolean,
    dataSource: SteamWorkshopDataSource,
    steamAccessRepository: SteamAccessRepository,
    failureMessage: String,
) {
    if (shouldPrewarm && !steamAccessRepository.prewarmSteamIp(dataSource)) {
        error(failureMessage)
    }
}
