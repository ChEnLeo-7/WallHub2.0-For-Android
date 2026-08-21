@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.model.DEFAULT_HOME_PAGE_SIZE
import com.wallhub.android.core.model.SteamAccessRepository
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.WorkshopFilterCatalog
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.core.model.workshopSearchIdOrNull

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
    val excludedOfficialTags: Set<String> = emptySet(),
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
            excludedOfficialTags = excludedOfficialTags.intersect(WorkshopFilterCatalog.officialTags.toSet()),
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

internal fun HomeUiState.filterSelection(): HomeFilterSelection =
    HomeFilterSelection(
        sort = sort,
        days = days,
        types = selectedTypes,
        ratings = selectedRatings,
        genres = selectedGenres,
        officialTags = selectedOfficialTags,
        excludedOfficialTags = selectedExcludedOfficialTags,
        resolutions = selectedResolutions,
    ).normalized(matureContentEnabled)

const val HOME_AUTHOR_SEARCH_CREATOR_ARGUMENT = "authorSearchCreator"
const val HOME_TAG_SEARCH_ARGUMENT = "tagSearchTag"

data class HomeUiState(
    val query: String = "",
    val creatorId: String? = null,
    val requiredTags: Set<String> = emptySet(),
    val exactPhrase: Boolean = false,
    val selectedTypes: Set<WorkshopType> = emptySet(),
    val selectedRatings: Set<WorkshopRating> = DEFAULT_HOME_RATING_SELECTION,
    val selectedGenres: Set<String> = DEFAULT_HOME_GENRE_SELECTION,
    val selectedOfficialTags: Set<String> = emptySet(),
    val selectedExcludedOfficialTags: Set<String> = emptySet(),
    val selectedResolutions: Set<String> = DEFAULT_HOME_RESOLUTION_SELECTION,
    val sort: WorkshopSort = WorkshopSort.TRENDING,
    val days: Int = 30,
    val viewMode: HomeViewMode = HomeViewMode.GRID,
    val pageSize: Int = DEFAULT_HOME_PAGE_SIZE,
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
    @StringRes val errorRes: Int? = null,
    val successfulSearchToken: Long = 0L,
    val homeSearchFab: Boolean = true,
) {
    val activeFilterCount: Int
        get() = filterSelection().activeSectionCount() + if (requiredTags.isNotEmpty()) 1 else 0
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
        val exactPhrase: Boolean? = null,
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
        @StringRes val messageRes: Int,
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
        @StringRes val messageRes: Int,
        val formatArgs: List<Any> = emptyList(),
    ) : HomeEffect

    data class ShowMessageText(
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
        @StringRes val messageRes: Int,
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
    return query.substringAfter(':').trim().takeIf(String::isNotBlank)
}

internal fun String.workshopIdOrNull(): Long? {
    val query = trim()
    val candidate =
        when {
            query.matches(Regex("^[0-9]{6,}$")) -> query
            query.startsWith("id:", ignoreCase = true) -> query.substringAfter(':').trim()
            else -> query.workshopSearchIdOrNull()?.toString()
        }
    return candidate?.toLongOrNull()?.takeIf { it > 0L }
}

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
        errorRes = null,
    )

internal fun HomeUiState.asTagSearchState(tag: String): HomeUiState {
    val normalizedTag = tag.trim()
    val genre = WorkshopFilterCatalog.genres.firstOrNull { it.equals(normalizedTag, ignoreCase = true) }
    val officialTag = WorkshopFilterCatalog.officialTags.firstOrNull { it.equals(normalizedTag, ignoreCase = true) }
    val resolution =
        WorkshopFilterCatalog.resolutions.firstOrNull { it.equals(normalizedTag, ignoreCase = true) }
    return copy(
        query = "",
        creatorId = null,
        requiredTags = if (genre == null && officialTag == null && resolution == null) setOf(normalizedTag) else emptySet(),
        selectedGenres = genre?.let(::setOf) ?: DEFAULT_HOME_GENRE_SELECTION,
        selectedOfficialTags = officialTag?.let(::setOf).orEmpty(),
        selectedExcludedOfficialTags = emptySet(),
        selectedResolutions = resolution?.let(::setOf) ?: DEFAULT_HOME_RESOLUTION_SELECTION,
        sort = WorkshopSort.MOST_RECENT,
        days = 0,
        error = null,
        errorRes = null,
    )
}

internal fun initialHomeUiState(
    authorSearchCreator: String?,
    tagSearchTag: String?,
): HomeUiState {
    val normalizedCreatorId =
        authorSearchCreator
            ?.filter(Char::isDigit)
            ?.takeIf(String::isNotBlank)
    if (normalizedCreatorId != null) return HomeUiState().asAuthorSearchState(normalizedCreatorId)
    return tagSearchTag
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { HomeUiState().asTagSearchState(it) }
        ?: HomeUiState()
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
    @StringRes failureMessageRes: Int,
) {
    if (shouldPrewarm && !steamAccessRepository.prewarmSteamIp(dataSource)) {
        throw HomeResourceMessageException(failureMessageRes)
    }
}

internal class HomeResourceMessageException(
    @StringRes val messageRes: Int,
) : IllegalStateException()
