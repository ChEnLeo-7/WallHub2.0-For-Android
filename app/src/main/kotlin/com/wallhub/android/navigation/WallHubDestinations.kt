package com.wallhub.android

import com.wallhub.android.feature.discover.DiscoverRailSpec
import com.wallhub.android.feature.discover.DiscoverTitleKind
import com.wallhub.android.core.model.DiscoverSavedQuery
import com.wallhub.android.core.model.DiscoverSavedQueryCategory
import com.wallhub.android.core.model.DiscoverSavedQuerySource
import com.wallhub.android.core.model.WorkshopSort
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface WallHubDestination

@Serializable internal data object HomeDestination : WallHubDestination
/** Dedicated exploration surface; kept separate from [HomeDestination] so the two feeds can evolve independently. */
@Serializable internal data object DiscoverDestination : WallHubDestination
@Serializable internal data object DiscoverFollowingDestination : WallHubDestination
@Serializable internal data object DiscoverQueryEditorDestination : WallHubDestination
@Serializable
internal data class DiscoverResultsDestination(
    val railId: String,
    val titleKind: String,
    val titleArgument: String? = null,
    val semantic: String,
    val searchText: String = "",
    val creatorId: String? = null,
    val collectionId: Long? = null,
    val tags: String = "",
    val excludedTags: String = "",
    val officialTags: String = "",
    val excludedOfficialTags: String = "",
    val requiredTagGroups: String = "",
    val types: String = "",
    val ratings: String = "",
    val genres: String = "",
    val resolutions: String = "",
    val sort: String = "TRENDING",
    val days: Int = 30,
    val exactPhrase: Boolean = false,
    val allowNsfw: Boolean = false,
    val mobileCompatibleOnly: Boolean = false,
    val filtersEnabled: Boolean = false,
    val createdAfterEpochSeconds: Long? = null,
    val createdBeforeEpochSeconds: Long? = null,
) : WallHubDestination
/** Account-centric surface containing links to the legacy personal sections. */
@Serializable internal data object ProfileDestination : WallHubDestination
@Serializable internal data object DownloadsDestination : WallHubDestination
@Serializable internal data object LibraryDestination : WallHubDestination
/** Direct personal collection entry used by the Profile page. */
@Serializable
internal data class LibraryCollectionDestination(
    val collection: String,
) : WallHubDestination
@Serializable internal data object LocalDestination : WallHubDestination
@Serializable internal data object SettingsDestination : WallHubDestination

// Preserves the legacy route while providing a direct entry to the Steam sign-in settings.
@Serializable internal data object SteamLoginDestination : WallHubDestination

@Serializable
internal data class AuthorSearchDestination(
    val authorSearchCreator: String,
) : WallHubDestination

@Serializable
internal data class TagSearchDestination(
    val tagSearchTag: String,
) : WallHubDestination

@Serializable
internal data class WorkshopDetailDestination(
    val workshopId: Long,
) : WallHubDestination

@Serializable
internal data class LocalVideoPlayerDestination(
    val taskId: String,
) : WallHubDestination

@Serializable
internal data class OnlineVideoPlayerDestination(
    val workshopId: Long,
) : WallHubDestination

internal fun String.authorSearchDestinationOrNull(): AuthorSearchDestination? =
    filter(Char::isDigit)
        .takeIf(String::isNotBlank)
        ?.let(::AuthorSearchDestination)

internal fun DiscoverRailSpec.toResultsDestination(resolvedTitle: String? = null): DiscoverResultsDestination {
    val query = queryPlan.query
    return DiscoverResultsDestination(
        railId = id,
        titleKind = titleKind.name,
        titleArgument = resolvedTitle?.takeIf(String::isNotBlank) ?: titleArgument,
        semantic = semantic,
        searchText = query?.searchText.orEmpty(),
        creatorId = query?.creatorId,
        collectionId = queryPlan.collectionId,
        tags = query?.tags.orEmpty().joinToString(DISCOVER_VALUE_SEPARATOR),
        excludedTags = query?.excludedTags.orEmpty().joinToString(DISCOVER_VALUE_SEPARATOR),
        officialTags = query?.officialTags.orEmpty().joinToString(DISCOVER_VALUE_SEPARATOR),
        excludedOfficialTags = query?.excludedOfficialTags.orEmpty().joinToString(DISCOVER_VALUE_SEPARATOR),
        requiredTagGroups =
            (query?.requiredTagGroups.orEmpty() + queryPlan.clientRequiredTagGroups).joinToString(DISCOVER_GROUP_SEPARATOR) { group ->
                group.joinToString(DISCOVER_VALUE_SEPARATOR)
            },
        types = query?.types.orEmpty().joinToString(DISCOVER_VALUE_SEPARATOR) { it.name },
        ratings = query?.ratings.orEmpty().joinToString(DISCOVER_VALUE_SEPARATOR) { it.name },
        genres = query?.genres.orEmpty().joinToString(DISCOVER_VALUE_SEPARATOR),
        resolutions = query?.resolutions.orEmpty().joinToString(DISCOVER_VALUE_SEPARATOR),
        sort = query?.sort?.name ?: "TRENDING",
        days = query?.days ?: 30,
        exactPhrase = query?.exactPhrase == true,
        allowNsfw = query?.allowNsfw == true,
        mobileCompatibleOnly = query?.mobileCompatibleOnly == true,
        createdAfterEpochSeconds = query?.createdAfterEpochSeconds,
        createdBeforeEpochSeconds = query?.createdBeforeEpochSeconds,
    )
}

internal fun DiscoverSavedQuery.toResultsDestination(): DiscoverResultsDestination =
    DiscoverResultsDestination(
        railId = id,
        titleKind =
            when (category) {
                DiscoverSavedQueryCategory.CREATOR -> DiscoverTitleKind.CREATOR.name
                DiscoverSavedQueryCategory.COLLECTION -> DiscoverTitleKind.COLLECTION.name
                else -> DiscoverTitleKind.KEYWORD.name
            },
        titleArgument = title.takeUnless { category == DiscoverSavedQueryCategory.CREATOR && source == DiscoverSavedQuerySource.CUSTOM },
        semantic = semantic,
        searchText = searchText,
        creatorId = creatorId,
        collectionId = collectionId,
        tags = tags.joinToString(DISCOVER_VALUE_SEPARATOR),
        excludedTags = excludedTags.joinToString(DISCOVER_VALUE_SEPARATOR),
        officialTags = officialTags.joinToString(DISCOVER_VALUE_SEPARATOR),
        excludedOfficialTags = excludedOfficialTags.joinToString(DISCOVER_VALUE_SEPARATOR),
        requiredTagGroups = requiredTagGroups.joinToString(DISCOVER_GROUP_SEPARATOR) { it.joinToString(DISCOVER_VALUE_SEPARATOR) },
        types = types.joinToString(DISCOVER_VALUE_SEPARATOR) { it.name },
        ratings = ratings.joinToString(DISCOVER_VALUE_SEPARATOR) { it.name },
        genres = genres.joinToString(DISCOVER_VALUE_SEPARATOR),
        resolutions = resolutions.joinToString(DISCOVER_VALUE_SEPARATOR),
        sort = sort.name,
        days = days,
        exactPhrase = exactPhrase,
        allowNsfw = allowNsfw,
        mobileCompatibleOnly = mobileCompatibleOnly,
        filtersEnabled = true,
    )

internal fun friendResultsDestination(favorites: Boolean): DiscoverResultsDestination =
    DiscoverResultsDestination(
        railId = if (favorites) "friends:favorites" else "friends:created",
        titleKind = if (favorites) DiscoverTitleKind.FRIEND_FAVORITES.name else DiscoverTitleKind.FRIEND_CREATED.name,
        semantic = if (favorites) "friends_favorites" else "friends_created",
        sort = if (favorites) WorkshopSort.FRIENDS_FAVORITES.name else WorkshopSort.FRIENDS_CREATED.name,
        days = 0,
        filtersEnabled = true,
    )

internal const val DISCOVER_VALUE_SEPARATOR = "|"
internal const val DISCOVER_GROUP_SEPARATOR = ";"
