package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopFilterCatalog
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.core.model.matchesSteamWallpaper
import com.wallhub.android.core.model.normalizedCreatedRange
import com.wallhub.android.core.model.normalizedRequiredTagGroups
import com.wallhub.android.core.model.workshopAuthorSearchOrNull
import com.wallhub.android.core.model.workshopDetailTagSearch
import com.wallhub.android.core.model.steamSearchText
import com.wallhub.android.core.model.steamTagCriteria
import com.wallhub.android.core.model.steamQueryType
import steam.webui.publishedfile.CPublishedFile_QueryFiles_Request
import steam.webui.publishedfile.CPublishedFile_QueryFiles_Request_DateRange
import steam.webui.publishedfile.CPublishedFile_QueryFiles_Request_KVTag
import steam.webui.publishedfile.CPublishedFile_QueryFiles_Request_TagGroup
import steam.webui.publishedfile.CPublishedFile_GetUserFiles_Request
import steam.webui.publishedfile.CPublishedFile_QueryFiles_Response
import steam.webui.publishedfile.CPublishedFile_GetUserFiles_Response
import steam.webui.publishedfile.PublishedFileDetails

internal fun buildUnifiedWorkshopBrowseRequest(query: WorkshopBrowseQuery): CPublishedFile_QueryFiles_Request {
    val normalized = query.normalizedForUnifiedBrowse()
    val criteria = normalized.steamTagCriteria()
    return CPublishedFile_QueryFiles_Request(
        query_type = normalized.sort.steamQueryType(),
        page = normalized.page,
        numperpage = normalized.pageSize,
        creator_appid = WALLPAPER_ENGINE_APP_ID,
        appid = WALLPAPER_ENGINE_APP_ID,
        filetype = 0,
        match_all_tags = true,
        return_tags = true,
        return_previews = true,
        return_short_description = true,
        return_metadata = true,
        return_details = true,
        search_text = normalized.steamSearchText().takeIf(String::isNotBlank),
        days = if (normalized.sort == WorkshopSort.TRENDING) normalized.days else 0,
        language = normalized.language,
        requiredtags = criteria.requiredTags,
        excludedtags = criteria.excludedTags,
        required_kv_tags =
            if (normalized.mobileCompatibleOnly) {
                listOf(
                    CPublishedFile_QueryFiles_Request_KVTag(
                        key = "app_workshop_eula_version",
                        value_ = "3",
                    ),
                )
            } else {
                emptyList()
            },
        taggroups =
            normalized.normalizedRequiredTagGroups().map { group ->
                CPublishedFile_QueryFiles_Request_TagGroup(tags = group.toList())
            },
        date_range_created =
            normalized.normalizedCreatedRange()?.let { range ->
                CPublishedFile_QueryFiles_Request_DateRange(
                    timestamp_start = range.first.toInt(),
                    timestamp_end = range.last.toInt(),
                )
            },
    )
}

internal fun buildUnifiedWorkshopAuthorRequest(query: WorkshopBrowseQuery): CPublishedFile_GetUserFiles_Request {
    val normalized = query.normalizedForUnifiedBrowse()
    val criteria = normalized.steamTagCriteria()
    return CPublishedFile_GetUserFiles_Request(
        steamid = normalized.creatorId?.toLongOrNull() ?: 0L,
        appid = WALLPAPER_ENGINE_APP_ID,
        page = normalized.page,
        numperpage = normalized.pageSize,
        type = "myfiles",
        sortmethod = "lastupdated",
        language = normalized.language,
        return_tags = true,
        return_previews = true,
        return_short_description = true,
        return_metadata = true,
        return_vote_data = true,
        requiredtags = criteria.requiredTags,
        excludedtags = criteria.excludedTags,
    )
}

internal fun mapUnifiedWorkshopBrowseResponse(
    query: WorkshopBrowseQuery,
    response: CPublishedFile_QueryFiles_Response,
): WorkshopPage {
    val normalized = query.normalizedForUnifiedBrowse()
    val items =
        response.publishedfiledetails
            .asSequence()
            .filter { detail -> detail.result == ERESULT_OK }
            .map { detail -> detail.toWorkshopSummary() }
            .filter(normalized::matchesSteamWallpaper)
            .take(normalized.pageSize)
            .toList()
    val total = response.total?.takeIf { it >= 0 }
    val totalPages = total?.toMaximumPage(normalized.pageSize)
    return WorkshopPage(
        items = items,
        page = normalized.page,
        hasNextPage =
            if (total != null) {
                normalized.page.toLong() * normalized.pageSize.toLong() < total.toLong()
            } else {
                response.publishedfiledetails.size >= normalized.pageSize
            },
        totalCount = total,
        totalPages = totalPages,
    )
}

internal fun mapUnifiedWorkshopAuthorResponse(
    query: WorkshopBrowseQuery,
    response: CPublishedFile_GetUserFiles_Response,
): WorkshopPage {
    val normalized = query.normalizedForUnifiedBrowse()
    val items =
        response.publishedfiledetails
            .asSequence()
            .filter { detail -> detail.result == ERESULT_OK }
            .map { detail -> detail.toWorkshopSummary() }
            .filter(normalized::matchesSteamWallpaper)
            .take(normalized.pageSize)
            .toList()
    val total = response.total?.takeIf { it >= 0 }
    return WorkshopPage(
        items = items,
        page = normalized.page,
        hasNextPage =
            if (total != null) {
                normalized.page.toLong() * normalized.pageSize.toLong() < total.toLong()
            } else {
                response.publishedfiledetails.size >= normalized.pageSize
            },
        totalCount = total,
        totalPages = total?.toMaximumPage(normalized.pageSize),
    )
}

internal fun PublishedFileDetails.toWorkshopSummary(
    collection: AccountWorkshopCollection? = null,
): WorkshopSummary {
    val sourceTags = tags.mapNotNull { tag -> tag.tag.orEmpty().trim().takeIf(String::isNotBlank) }
    val type =
        when {
            sourceTags.any { it.equals("Video", ignoreCase = true) } -> WorkshopType.VIDEO
            sourceTags.any { it.equals("Scene", ignoreCase = true) } -> WorkshopType.SCENE
            sourceTags.any { it.equals("Web", ignoreCase = true) || it.equals("Website", ignoreCase = true) } -> {
                WorkshopType.WEB
            }
            else -> WorkshopType.UNKNOWN
        }
    val id = publishedfileid ?: 0L
    return WorkshopSummary(
        id = id,
        title = title.orEmpty(),
        author = "",
        creatorId = (creator ?: 0L).toString(),
        previewUrl = preview_url.orEmpty().takeIf(String::isNotBlank),
        type = type,
        tags = sourceTags,
        subscriptions =
            (subscriptions ?: 0).toLong().takeIf { it > 0L }
                ?: (lifetime_subscriptions ?: 0).toLong().takeIf { it > 0L },
        favorites =
            (favorited ?: 0).toLong().takeIf { it > 0L }
                ?: (lifetime_favorited ?: 0).toLong().takeIf { it > 0L },
        views = (views ?: 0).toLong().takeIf { it > 0L },
        fileSizeBytes = (file_size ?: 0L).takeIf { it > 0L },
        subscriptionState =
            if (collection == AccountWorkshopCollection.SUBSCRIPTIONS) {
                SubscriptionState.SUBSCRIBED
            } else {
                SubscriptionState.UNKNOWN
            },
        favoriteState =
            if (collection == AccountWorkshopCollection.FAVORITES) {
                FavoriteState.FAVORITED
            } else {
                FavoriteState.UNKNOWN
            },
        isTitlePlaceholder = title.orEmpty().isBlank(),
        authorPlaceholder = WorkshopAuthorPlaceholder.USER_WITH_ID,
    )
}

internal fun decorateWithProfile(
    item: WorkshopSummary,
    profiles: Map<Long, SteamProfile>,
): WorkshopSummary {
    val profile = item.creatorId?.toLongOrNull()?.let(profiles::get)
    return item.copy(
        author = profile?.displayName ?: item.author,
        authorAvatarUrl = profile?.avatarUrl ?: item.authorAvatarUrl,
        authorPlaceholder =
            if (profile == null) {
                item.authorPlaceholder
            } else {
                WorkshopAuthorPlaceholder.NONE
            },
    )
}

private fun WorkshopBrowseQuery.normalizedForUnifiedBrowse(): WorkshopBrowseQuery =
    copy(
        page = page.coerceAtLeast(1),
        pageSize = pageSize.coerceIn(1, MAX_PUBLIC_WORKSHOP_PAGE_SIZE),
        searchText = searchText.trim().take(MAX_PUBLIC_WORKSHOP_SEARCH_LENGTH),
        creatorId = creatorId?.trim()?.takeIf(String::isNotBlank) ?: searchText.workshopAuthorSearchOrNull(),
        types = types.filter { it != WorkshopType.UNKNOWN }.toSet(),
        tags =
            (tags + searchText.workshopDetailTagSearch())
                .map(String::trim)
                .filter(String::isNotBlank)
                .take(MAX_PUBLIC_WORKSHOP_TAGS)
                .toSet(),
        excludedTags =
            excludedTags
                .map(String::trim)
                .filter(String::isNotBlank)
                .take(MAX_PUBLIC_WORKSHOP_TAGS)
                .toSet(),
        requiredTagGroups =
            requiredTagGroups
                .map { group -> group.map(String::trim).filter(String::isNotBlank).take(MAX_PUBLIC_WORKSHOP_TAGS).toSet() }
                .filter(Set<String>::isNotEmpty)
                .take(MAX_PUBLIC_WORKSHOP_TAGS),
        genres = genres.map(String::trim).filter { it in WorkshopFilterCatalog.genres }.toSet(),
        officialTags = officialTags.map(String::trim).filter { it in WorkshopFilterCatalog.officialTags }.toSet(),
        excludedOfficialTags = excludedOfficialTags.map(String::trim).filter { it in WorkshopFilterCatalog.officialTags }.toSet(),
        resolutions = resolutions.map(String::trim).filter { it in WorkshopFilterCatalog.resolutions }.toSet(),
        ratings = ratings.ifEmpty { setOf(com.wallhub.android.core.model.WorkshopRating.EVERYONE) },
        days = days.coerceIn(0, 365),
        createdAfterEpochSeconds = createdAfterEpochSeconds?.coerceIn(0L, Int.MAX_VALUE.toLong()),
        createdBeforeEpochSeconds = createdBeforeEpochSeconds?.coerceIn(0L, Int.MAX_VALUE.toLong()),
    )

private fun Int.toMaximumPage(pageSize: Int): Int {
    val safePageSize = pageSize.coerceAtLeast(1).toLong()
    return ((coerceAtLeast(0).toLong() + safePageSize - 1L) / safePageSize)
        .coerceIn(1L, MAX_DIRECT_BROWSE_PAGE.toLong())
        .toInt()
}

internal const val ERESULT_OK = 1
internal const val WALLPAPER_ENGINE_APP_ID = 431960
private const val MAX_PUBLIC_WORKSHOP_PAGE_SIZE = 50
private const val MAX_PUBLIC_WORKSHOP_TAGS = 48
private const val MAX_PUBLIC_WORKSHOP_SEARCH_LENGTH = 128
private const val MAX_DIRECT_BROWSE_PAGE = 1_000
