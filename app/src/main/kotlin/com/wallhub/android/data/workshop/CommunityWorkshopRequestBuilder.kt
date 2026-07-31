package com.wallhub.android.data.workshop

import android.net.Uri
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import javax.inject.Singleton

@Singleton
internal fun buildDetailUrl(workshopId: Long): String =
    Uri
        .Builder()
        .scheme("https")
        .authority("steamcommunity.com")
        .appendPath("sharedfiles")
        .appendPath("filedetails")
        .appendQueryParameter("id", workshopId.toString())
        .build()
        .toString()

internal fun buildBrowseUrl(query: WorkshopBrowseQuery): String {
    query.creatorId?.let { creatorId ->
        return Uri
            .Builder()
            .scheme("https")
            .authority("steamcommunity.com")
            .appendPath("profiles")
            .appendPath(creatorId)
            .appendPath("myworkshopfiles")
            .appendQueryParameter("appid", WALLPAPER_ENGINE_APP_ID.toString())
            .appendQueryParameter("p", query.page.toString())
            .appendQueryParameter("numperpage", AUTHOR_BROWSE_PAGE_SIZE.toString())
            .apply {
                appendFilterTags(
                    selected = query.effectiveTypes().mapNotNull { type -> type.steamTag() }.toSet(),
                    all = WORKSHOP_TYPE_TAGS,
                )
                appendFilterTags(
                    selected = query.effectiveRatings().mapNotNull { rating -> rating.steamTag }.toSet(),
                    all = CONTENT_RATING_TAGS,
                )
                appendFilterTags(selected = query.genres, all = COMMUNITY_GENRE_TAGS)
                appendFilterTags(selected = query.resolutions, all = RESOLUTION_TAGS)
                query.tags
                    .plus(query.officialTags)
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(MAX_REQUIRED_TAGS)
                    .forEach { tag -> appendQueryParameter("requiredtags[]", tag) }
            }.build()
            .toString()
    }
    val sort =
        when (query.sort) {
            WorkshopSort.TRENDING -> "trend"
            WorkshopSort.MOST_RECENT -> "mostrecent"
            WorkshopSort.TOP_RATED -> "toprated"
            WorkshopSort.MOST_VOTES -> "mostvotes"
            WorkshopSort.MOST_SUBSCRIBERS -> "totaluniquesubscribers"
        }
    return Uri
        .Builder()
        .scheme("https")
        .authority("steamcommunity.com")
        .appendPath("workshop")
        .appendPath("browse")
        .appendQueryParameter("appid", WALLPAPER_ENGINE_APP_ID.toString())
        .appendQueryParameter("browsesort", sort)
        .appendQueryParameter("section", "readytouseitems")
        .appendQueryParameter("actualsort", sort)
        .appendQueryParameter("p", query.page.toString())
        .appendQueryParameter("numperpage", query.pageSize.toString())
        .appendQueryParameter("num_per_page", query.pageSize.toString())
        .apply {
            query.searchText.takeIf(String::isNotBlank)?.let { searchText ->
                appendQueryParameter("searchtext", searchText)
            }
            if (query.sort == WorkshopSort.TRENDING && query.days > 0) {
                appendQueryParameter("days", query.days.coerceIn(1, 365).toString())
            }
            appendFilterTags(
                selected = query.effectiveTypes().mapNotNull { type -> type.steamTag() }.toSet(),
                all = WORKSHOP_TYPE_TAGS,
            )
            appendFilterTags(
                selected = query.effectiveRatings().mapNotNull { rating -> rating.steamTag }.toSet(),
                all = CONTENT_RATING_TAGS,
            )
            appendFilterTags(selected = query.genres, all = COMMUNITY_GENRE_TAGS)
            appendFilterTags(selected = query.resolutions, all = RESOLUTION_TAGS)
            query.tags
                .plus(query.officialTags)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_REQUIRED_TAGS)
                .forEach { tag -> appendQueryParameter("requiredtags[]", tag) }
        }.build()
        .toString()
}

internal fun buildSteamApiBrowseUrl(
    query: WorkshopBrowseQuery,
    steamApiKey: String,
): String {
    val requiredTags =
        linkedSetOf<String>().apply {
            query
                .effectiveTypes()
                .singleOrNull()
                ?.steamTag()
                ?.let(::add)
            query
                .effectiveRatings()
                .singleOrNull()
                ?.steamTag
                ?.let(::add)
            addAll(query.tags)
            addAll(query.officialTags)
            query.genres.singleOrNull()?.let(::add)
            query.resolutions.singleOrNull()?.let(::add)
        }
    return Uri
        .Builder()
        .scheme("https")
        .authority("api.steampowered.com")
        .appendPath("IPublishedFileService")
        .appendPath("QueryFiles")
        .appendPath("v1")
        .appendQueryParameter("key", steamApiKey)
        .appendQueryParameter(
            "query_type",
            if (query.searchText.isNotBlank()) "12" else query.sort.steamApiQueryType().toString(),
        ).appendQueryParameter("page", query.page.toString())
        .appendQueryParameter("numperpage", query.pageSize.toString())
        .appendQueryParameter("creator_appid", WALLPAPER_ENGINE_APP_ID.toString())
        .appendQueryParameter("appid", WALLPAPER_ENGINE_APP_ID.toString())
        .appendQueryParameter("filetype", "0")
        .appendQueryParameter("match_all_tags", "1")
        .appendQueryParameter("return_tags", "1")
        .appendQueryParameter("return_previews", "1")
        .appendQueryParameter("return_short_description", "1")
        .appendQueryParameter("return_metadata", "1")
        .apply {
            query.searchText.takeIf(String::isNotBlank)?.let {
                appendQueryParameter("search_text", it)
            }
            if (query.sort == WorkshopSort.TRENDING && query.days > 0) {
                appendQueryParameter("days", query.days.coerceIn(1, 365).toString())
                appendQueryParameter("include_recent_votes_only", "1")
            }
            requiredTags.forEachIndexed { index, tag ->
                appendQueryParameter("requiredtags[$index]", tag)
            }
        }.build()
        .toString()
}

internal fun WorkshopBrowseQuery.normalized(): WorkshopBrowseQuery =
    copy(
        page = page.coerceAtLeast(1),
        pageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE),
        searchText = searchText.trim().take(MAX_SEARCH_LENGTH),
        creatorId = creatorId?.filter(Char::isDigit)?.takeIf(String::isNotBlank),
        types = effectiveTypes(),
        tags =
            tags
                .map(String::trim)
                .filter(String::isNotBlank)
                .take(MAX_REQUIRED_TAGS)
                .toSet(),
        genres = genres.map(String::trim).filter { it in COMMUNITY_GENRE_TAGS }.toSet(),
        officialTags = officialTags.map(String::trim).filter { it in OFFICIAL_TAGS }.toSet(),
        resolutions = resolutions.map(String::trim).filter { it in RESOLUTION_TAGS }.toSet(),
        ratings = effectiveRatings(),
        days = days.coerceIn(0, 365),
    )

internal fun WorkshopBrowseQuery.matches(summary: WorkshopSummary): Boolean {
    val selectedTypes = effectiveTypes()
    if (selectedTypes.isNotEmpty() && summary.type !in selectedTypes) return false
    val itemTags = summary.tags.map(String::lowercase).toSet()
    val requiredTags = tags + officialTags
    if (!requiredTags.all { tag -> tag.lowercase() in itemTags }) return false
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

internal fun WorkshopBrowseQuery.effectiveTypes(): Set<WorkshopType> =
    types.filter { it != WorkshopType.UNKNOWN }.toSet().ifEmpty {
        type?.takeIf { it != WorkshopType.UNKNOWN }?.let(::setOf).orEmpty()
    }

internal fun WorkshopBrowseQuery.effectiveRatings(): Set<WorkshopRating> = ratings.ifEmpty { setOf(WorkshopRating.EVERYONE) }

internal fun WorkshopBrowseQuery.communityBrowsePageSize(): Int = if (creatorId == null) pageSize else AUTHOR_BROWSE_PAGE_SIZE

internal fun Uri.Builder.appendFilterTags(
    selected: Set<String>,
    all: Set<String>,
) {
    val normalized = selected.map(String::trim).filter { it in all }.toSet()
    if (normalized.size == 1) {
        appendQueryParameter("requiredtags[]", normalized.single())
        return
    }
    if (normalized.size > 1 && normalized.size < all.size) {
        all
            .filterNot(normalized::contains)
            .forEach { tag -> appendQueryParameter("excludedtags[]", tag) }
    }
}

internal fun WorkshopType.steamTag(): String? =
    when (this) {
        WorkshopType.VIDEO -> "Video"
        WorkshopType.SCENE -> "Scene"
        WorkshopType.WEB -> "Web"
        WorkshopType.UNKNOWN -> null
    }

internal fun WorkshopSort.steamApiQueryType(): Int =
    when (this) {
        WorkshopSort.TRENDING -> 3
        WorkshopSort.MOST_RECENT -> 1
        WorkshopSort.TOP_RATED -> 0
        WorkshopSort.MOST_VOTES -> 11
        WorkshopSort.MOST_SUBSCRIBERS -> 9
    }

internal const val WALLPAPER_ENGINE_APP_ID = 431960
internal const val AUTHOR_BROWSE_PAGE_SIZE = 30
internal const val MAX_PAGE_SIZE = 30
internal const val MAX_DIRECT_BROWSE_PAGE = 1_000
internal const val MAX_COMMENT_PAGE_SIZE = 50
internal const val MAX_SEARCH_LENGTH = 128
internal const val MAX_REQUIRED_TAGS = 48
internal const val MAX_STEAM_PROFILE_BATCH_SIZE = 100
internal const val PUBLISHED_FILE_PUBLIC_COMMENT_THREAD = 5
internal const val RESULT_OK = 1
internal const val USER_AGENT = "WallHub-Android/0.5 (Public Workshop Browser)"
internal const val PUBLISHED_FILE_DETAILS_URL =
    "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
internal const val COMMUNITY_COMMENTS_API_URL =
    "https://api.steampowered.com/ICommunityService/GetCommentThread/v1/"
internal const val COMMUNITY_COMMENTS_HTML_URL =
    "https://steamcommunity.com/comment/PublishedFile_Public/render"
internal val WORKSHOP_TYPE_TAGS = setOf("Scene", "Video", "Web")
internal val CONTENT_RATING_TAGS = setOf("Everyone", "Questionable", "Mature")
internal val COMMUNITY_GENRE_TAGS =
    setOf(
        "Abstract",
        "Animal",
        "Anime",
        "Cartoon",
        "CGI",
        "Cyberpunk",
        "Fantasy",
        "Game",
        "Girls",
        "Guys",
        "Landscape",
        "Medieval",
        "Memes",
        "MMD",
        "Music",
        "Nature",
        "Pixel art",
        "Relaxing",
        "Retro",
        "Sci-Fi",
        "Sports",
        "Technology",
        "Television",
        "Vehicle",
        "Unspecified",
    )
internal val OFFICIAL_TAGS =
    setOf(
        "Approved",
        "Audio responsive",
        "3D",
        "Customizable",
        "Puppet Warp",
        "HDR",
        "Media Integration",
        "User Shortcut",
        "Video Texture",
        "Asset Pack",
    )
internal val RESOLUTION_TAGS =
    setOf(
        "Standard",
        "1280 x 720",
        "1366 x 768",
        "1920 x 1080",
        "2560 x 1440",
        "3840 x 2160",
        "Ultrawide",
        "2560 x 1080",
        "3440 x 1440",
        "Dual monitor",
        "3840 x 1080",
        "5120 x 1440",
        "7680 x 2160",
        "Triple monitor",
        "4096 x 768",
        "5760 x 1080",
        "7680 x 1440",
        "11520 x 2160",
        "Portrait",
        "720 x 1280",
        "1080 x 1920",
        "1440 x 2560",
        "2160 x 3840",
        "Other resolution",
        "Dynamic resolution",
    )
