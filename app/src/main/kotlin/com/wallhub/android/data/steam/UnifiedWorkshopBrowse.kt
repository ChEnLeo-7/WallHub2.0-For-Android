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
import com.wallhub.android.core.model.workshopAuthorSearchOrNull
import com.wallhub.android.core.model.workshopDetailTagSearch
import com.wallhub.android.core.model.steamSearchText
import com.wallhub.android.core.model.steamTagCriteria
import com.wallhub.android.core.model.steamQueryType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient

internal fun buildUnifiedWorkshopBrowseRequest(
    query: WorkshopBrowseQuery,
): SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request {
    val normalized = query.normalizedForUnifiedBrowse()
    val criteria = normalized.steamTagCriteria()
    return SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request
        .newBuilder()
        .setQueryType(normalized.sort.steamQueryType())
        .setPage(normalized.page)
        .setNumperpage(normalized.pageSize)
        .setCreatorAppid(WALLPAPER_ENGINE_APP_ID)
        .setAppid(WALLPAPER_ENGINE_APP_ID)
        .setFiletype(0)
        .setMatchAllTags(true)
        .setReturnTags(true)
        .setReturnPreviews(true)
        .setReturnShortDescription(true)
        .setReturnMetadata(true)
        .setReturnDetails(true)
        .apply {
            normalized.steamSearchText().takeIf(String::isNotBlank)?.let(::setSearchText)
            if (normalized.sort == WorkshopSort.TRENDING && normalized.days > 0) {
                setDays(normalized.days)
            }
            setLanguage(normalized.language)
            addAllRequiredtags(criteria.requiredTags)
            addAllExcludedtags(criteria.excludedTags)
            if (normalized.mobileCompatibleOnly) {
                addRequiredKvTags(
                    SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request.KVTag
                        .newBuilder()
                        .setKey("app_workshop_eula_version")
                        .setValue("3")
                        .build(),
                )
            }
        }.build()
}

internal fun buildUnifiedWorkshopAuthorRequest(
    query: WorkshopBrowseQuery,
): SteammessagesPublishedfileSteamclient.CPublishedFile_GetUserFiles_Request {
    val normalized = query.normalizedForUnifiedBrowse()
    val criteria = normalized.steamTagCriteria()
    return SteammessagesPublishedfileSteamclient.CPublishedFile_GetUserFiles_Request
        .newBuilder()
        .setSteamid(normalized.creatorId?.toLongOrNull() ?: 0L)
        .setAppid(WALLPAPER_ENGINE_APP_ID)
        .setPage(normalized.page)
        .setNumperpage(normalized.pageSize)
        .setType("myfiles")
        .setSortmethod("lastupdated")
        .setLanguage(normalized.language)
        .setReturnTags(true)
        .setReturnPreviews(true)
        .setReturnShortDescription(true)
        .setReturnMetadata(true)
        .setReturnVoteData(true)
        .addAllRequiredtags(criteria.requiredTags)
        .addAllExcludedtags(criteria.excludedTags)
        .build()
}

internal fun mapUnifiedWorkshopBrowseResponse(
    query: WorkshopBrowseQuery,
    response: SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Response,
): WorkshopPage {
    val normalized = query.normalizedForUnifiedBrowse()
    val items =
        response.publishedfiledetailsList
            .asSequence()
            .filter { detail -> detail.result == EResult.OK.code() }
            .map { detail -> detail.toWorkshopSummary() }
            .filter(normalized::matchesSteamWallpaper)
            .take(normalized.pageSize)
            .toList()
    val total = response.total.takeIf { response.hasTotal() && it >= 0 }
    val totalPages = total?.toMaximumPage(normalized.pageSize)
    return WorkshopPage(
        items = items,
        page = normalized.page,
        hasNextPage =
            if (total != null) {
                normalized.page.toLong() * normalized.pageSize.toLong() < total.toLong()
            } else {
                response.publishedfiledetailsCount >= normalized.pageSize
            },
        totalCount = total,
        totalPages = totalPages,
    )
}

internal fun mapUnifiedWorkshopAuthorResponse(
    query: WorkshopBrowseQuery,
    response: SteammessagesPublishedfileSteamclient.CPublishedFile_GetUserFiles_Response,
): WorkshopPage {
    val normalized = query.normalizedForUnifiedBrowse()
    val items =
        response.publishedfiledetailsList
            .asSequence()
            .filter { detail -> detail.result == EResult.OK.code() }
            .map { detail -> detail.toWorkshopSummary() }
            .filter(normalized::matchesSteamWallpaper)
            .take(normalized.pageSize)
            .toList()
    val total = response.total.takeIf { response.hasTotal() && it >= 0 }
    return WorkshopPage(
        items = items,
        page = normalized.page,
        hasNextPage =
            if (total != null) {
                normalized.page.toLong() * normalized.pageSize.toLong() < total.toLong()
            } else {
                response.publishedfiledetailsCount >= normalized.pageSize
            },
        totalCount = total,
        totalPages = total?.toMaximumPage(normalized.pageSize),
    )
}

internal fun SteammessagesPublishedfileSteamclient.PublishedFileDetails.toWorkshopSummary(
    collection: AccountWorkshopCollection? = null,
): WorkshopSummary {
    val sourceTags = tagsList.mapNotNull { tag -> tag.tag.trim().takeIf(String::isNotBlank) }
    val type =
        when {
            sourceTags.any { it.equals("Video", ignoreCase = true) } -> WorkshopType.VIDEO
            sourceTags.any { it.equals("Scene", ignoreCase = true) } -> WorkshopType.SCENE
            sourceTags.any { it.equals("Web", ignoreCase = true) || it.equals("Website", ignoreCase = true) } -> {
                WorkshopType.WEB
            }
            else -> WorkshopType.UNKNOWN
        }
    return WorkshopSummary(
        id = publishedfileid,
        title = title,
        author = "",
        creatorId = creator.toString(),
        previewUrl = previewUrl.takeIf(String::isNotBlank),
        type = type,
        tags = sourceTags,
        subscriptions =
            subscriptions.toLong().takeIf { it > 0L }
                ?: lifetimeSubscriptions.toLong().takeIf { it > 0L },
        favorites =
            favorited.toLong().takeIf { it > 0L }
                ?: lifetimeFavorited.toLong().takeIf { it > 0L },
        views = views.toLong().takeIf { it > 0L },
        fileSizeBytes = fileSize.takeIf { it > 0L },
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
        isTitlePlaceholder = title.isBlank(),
        authorPlaceholder = WorkshopAuthorPlaceholder.USER_WITH_ID,
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
        genres = genres.map(String::trim).filter { it in WorkshopFilterCatalog.genres }.toSet(),
        officialTags = officialTags.map(String::trim).filter { it in WorkshopFilterCatalog.officialTags }.toSet(),
        excludedOfficialTags = excludedOfficialTags.map(String::trim).filter { it in WorkshopFilterCatalog.officialTags }.toSet(),
        resolutions = resolutions.map(String::trim).filter { it in WorkshopFilterCatalog.resolutions }.toSet(),
        ratings = ratings.ifEmpty { setOf(com.wallhub.android.core.model.WorkshopRating.EVERYONE) },
        days = days.coerceIn(0, 365),
    )

private fun Int.toMaximumPage(pageSize: Int): Int {
    val safePageSize = pageSize.coerceAtLeast(1).toLong()
    return ((coerceAtLeast(0).toLong() + safePageSize - 1L) / safePageSize)
        .coerceIn(1L, MAX_DIRECT_BROWSE_PAGE.toLong())
        .toInt()
}

private const val MAX_PUBLIC_WORKSHOP_PAGE_SIZE = 50
private const val MAX_PUBLIC_WORKSHOP_TAGS = 48
private const val MAX_PUBLIC_WORKSHOP_SEARCH_LENGTH = 128
private const val MAX_DIRECT_BROWSE_PAGE = 1_000
