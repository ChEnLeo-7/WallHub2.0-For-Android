package com.wallhub.android.data.steam

import `in`.dragonbra.javasteam.enums.EPublishedFileQueryType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient
import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopFilterCatalog
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType

internal fun buildUnifiedWorkshopBrowseRequest(
    query: WorkshopBrowseQuery,
): SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request {
    val normalized = query.normalizedForUnifiedBrowse()
    val requiredTags = linkedSetOf<String>().apply {
        addAll(normalized.tags)
        addAll(normalized.officialTags)
    }
    val excludedTags = linkedSetOf<String>()
    fun applyCategoryFilter(selected: Set<String>, all: Set<String>) {
        when {
            selected.size == 1 -> requiredTags += selected.single()
            selected.size > 1 && selected.size < all.size -> excludedTags += all - selected
        }
    }
    applyCategoryFilter(
        normalized.effectiveTypes().mapNotNull { type -> type.steamTag() }.toSet(),
        WORKSHOP_TYPE_TAGS,
    )
    applyCategoryFilter(
        normalized.effectiveRatings().mapNotNull { rating -> rating.steamTag }.toSet(),
        CONTENT_RATING_TAGS,
    )
    applyCategoryFilter(normalized.genres, WorkshopFilterCatalog.genres.toSet())
    applyCategoryFilter(normalized.resolutions, WorkshopFilterCatalog.resolutions.toSet())
    return SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request
        .newBuilder()
        .setQueryType(normalized.unifiedQueryType())
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
            normalized.searchText.takeIf(String::isNotBlank)?.let(::setSearchText)
            if (normalized.sort == WorkshopSort.TRENDING && normalized.days > 0) {
                setDays(normalized.days)
                setIncludeRecentVotesOnly(true)
            }
            addAllRequiredtags(requiredTags)
            addAllExcludedtags(excludedTags)
        }
        .build()
}

internal fun mapUnifiedWorkshopBrowseResponse(
    query: WorkshopBrowseQuery,
    response: SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Response,
): WorkshopPage {
    val normalized = query.normalizedForUnifiedBrowse()
    val items = response.publishedfiledetailsList
        .asSequence()
        .filter { detail -> detail.result == EResult.OK.code() }
        .map { detail -> detail.toWorkshopSummary() }
        .filter(normalized::matches)
        .take(normalized.pageSize)
        .toList()
    val total = response.total.takeIf { response.hasTotal() && it >= 0 }
    val totalPages = total?.toMaximumPage(normalized.pageSize)
    return WorkshopPage(
        items = items,
        page = normalized.page,
        hasNextPage = if (total != null) {
            normalized.page.toLong() * normalized.pageSize.toLong() < total.toLong()
        } else {
            response.publishedfiledetailsCount >= normalized.pageSize
        },
        totalCount = total,
        totalPages = totalPages,
    )
}

internal fun SteammessagesPublishedfileSteamclient.PublishedFileDetails.toWorkshopSummary(
    collection: AccountWorkshopCollection? = null,
): WorkshopSummary {
    val sourceTags = tagsList.mapNotNull { tag -> tag.tag.trim().takeIf(String::isNotBlank) }
    val type = when {
        sourceTags.any { it.equals("Video", ignoreCase = true) } -> WorkshopType.VIDEO
        sourceTags.any { it.equals("Scene", ignoreCase = true) } -> WorkshopType.SCENE
        sourceTags.any { it.equals("Web", ignoreCase = true) || it.equals("Website", ignoreCase = true) } -> {
            WorkshopType.WEB
        }
        else -> WorkshopType.UNKNOWN
    }
    return WorkshopSummary(
        id = publishedfileid,
        title = title.ifBlank { "壁纸 $publishedfileid" },
        author = "Steam 用户 $creator",
        creatorId = creator.toString(),
        previewUrl = previewUrl.takeIf(String::isNotBlank),
        type = type,
        tags = sourceTags,
        subscriptions = subscriptions.toLong().takeIf { it > 0L }
            ?: lifetimeSubscriptions.toLong().takeIf { it > 0L },
        favorites = favorited.toLong().takeIf { it > 0L }
            ?: lifetimeFavorited.toLong().takeIf { it > 0L },
        views = views.toLong().takeIf { it > 0L },
        fileSizeBytes = fileSize.takeIf { it > 0L },
        subscriptionState = if (collection == AccountWorkshopCollection.SUBSCRIPTIONS) {
            SubscriptionState.SUBSCRIBED
        } else {
            SubscriptionState.UNKNOWN
        },
        favoriteState = if (collection == AccountWorkshopCollection.FAVORITES) {
            FavoriteState.FAVORITED
        } else {
            FavoriteState.UNKNOWN
        },
    )
}

private fun WorkshopBrowseQuery.normalizedForUnifiedBrowse(): WorkshopBrowseQuery = copy(
    page = page.coerceAtLeast(1),
    pageSize = pageSize.coerceIn(1, MAX_PUBLIC_WORKSHOP_PAGE_SIZE),
    searchText = searchText.trim().take(MAX_PUBLIC_WORKSHOP_SEARCH_LENGTH),
    creatorId = creatorId?.filter(Char::isDigit)?.takeIf(String::isNotBlank),
    types = effectiveTypes(),
    tags = tags.map(String::trim).filter(String::isNotBlank).take(MAX_PUBLIC_WORKSHOP_TAGS).toSet(),
    genres = genres.map(String::trim).filter { it in WorkshopFilterCatalog.genres }.toSet(),
    officialTags = officialTags.map(String::trim).filter { it in WorkshopFilterCatalog.officialTags }.toSet(),
    resolutions = resolutions.map(String::trim).filter { it in WorkshopFilterCatalog.resolutions }.toSet(),
    ratings = effectiveRatings(),
    days = days.coerceIn(0, 365),
)

private fun WorkshopBrowseQuery.matches(summary: WorkshopSummary): Boolean {
    val selectedTypes = effectiveTypes()
    if (selectedTypes.isNotEmpty() && summary.type !in selectedTypes) return false
    val itemTags = summary.tags.map(String::lowercase).toSet()
    if (!(tags + officialTags).all { tag -> tag.lowercase() in itemTags }) return false
    if (genres.isNotEmpty() && genres.none { tag -> tag.lowercase() in itemTags }) return false
    if (resolutions.isNotEmpty() && resolutions.none { tag -> tag.lowercase() in itemTags }) return false
    val selectedRatings = effectiveRatings()
    if (selectedRatings.isNotEmpty() && WorkshopRating.ALL !in selectedRatings) {
        val ratingTags = selectedRatings.mapNotNull(WorkshopRating::steamTag).map(String::lowercase).toSet()
        if (ratingTags.isNotEmpty() && itemTags.none(ratingTags::contains)) return false
    }
    if (exactPhrase && searchText.isNotBlank() && !summary.title.contains(searchText, ignoreCase = true)) return false
    return true
}

private fun WorkshopBrowseQuery.effectiveTypes(): Set<WorkshopType> =
    types.filter { it != WorkshopType.UNKNOWN }.toSet().ifEmpty {
        type?.takeIf { it != WorkshopType.UNKNOWN }?.let(::setOf).orEmpty()
    }

private fun WorkshopBrowseQuery.effectiveRatings(): Set<WorkshopRating> =
    ratings.ifEmpty { setOf(WorkshopRating.EVERYONE) }

private fun WorkshopBrowseQuery.unifiedQueryType(): Int = if (searchText.isNotBlank()) {
    EPublishedFileQueryType.RankedByTextSearch.code()
} else {
    when (sort) {
        WorkshopSort.TRENDING -> EPublishedFileQueryType.RankedByTrend.code()
        WorkshopSort.MOST_RECENT -> EPublishedFileQueryType.RankedByPublicationDate.code()
        WorkshopSort.TOP_RATED -> EPublishedFileQueryType.RankedByVote.code()
        WorkshopSort.MOST_VOTES -> EPublishedFileQueryType.RankedByVotesUp.code()
        WorkshopSort.MOST_SUBSCRIBERS -> EPublishedFileQueryType.RankedByTotalUniqueSubscriptions.code()
    }
}

private fun WorkshopType.steamTag(): String? = when (this) {
    WorkshopType.VIDEO -> "Video"
    WorkshopType.SCENE -> "Scene"
    WorkshopType.WEB -> "Web"
    WorkshopType.UNKNOWN -> null
}

private fun Int.toMaximumPage(pageSize: Int): Int {
    val safePageSize = pageSize.coerceAtLeast(1).toLong()
    return ((coerceAtLeast(0).toLong() + safePageSize - 1L) / safePageSize)
        .coerceIn(1L, MAX_DIRECT_BROWSE_PAGE.toLong())
        .toInt()
}

private const val WALLPAPER_ENGINE_APP_ID = 431960
private const val MAX_PUBLIC_WORKSHOP_PAGE_SIZE = 30
private const val MAX_PUBLIC_WORKSHOP_TAGS = 48
private const val MAX_PUBLIC_WORKSHOP_SEARCH_LENGTH = 128
private const val MAX_DIRECT_BROWSE_PAGE = 1_000
private val WORKSHOP_TYPE_TAGS = setOf("Scene", "Video", "Web")
private val CONTENT_RATING_TAGS = setOf("Everyone", "Questionable", "Mature")
