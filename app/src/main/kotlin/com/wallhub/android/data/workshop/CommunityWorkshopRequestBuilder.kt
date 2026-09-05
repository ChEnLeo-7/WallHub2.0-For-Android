package com.wallhub.android.data.workshop

import android.net.Uri
import com.wallhub.android.core.model.STEAM_CONTENT_RATING_TAGS
import com.wallhub.android.core.model.STEAM_WORKSHOP_TYPE_TAGS
import com.wallhub.android.core.model.WORKSHOP_RESOLUTION_TAG_MAP
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.matchesSteamWallpaper
import com.wallhub.android.core.model.normalizedCreatedRange
import com.wallhub.android.core.model.normalizedRequiredTagGroups
import com.wallhub.android.core.model.effectiveWorkshopRatings
import com.wallhub.android.core.model.steamSearchText
import com.wallhub.android.core.model.steamTagCriteria
import com.wallhub.android.core.model.steamQueryType
import com.wallhub.android.core.model.workshopAuthorSearchOrNull
import com.wallhub.android.core.model.workshopDetailTagSearch
import org.json.JSONArray
import org.json.JSONObject
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
    val normalized = query.normalized()
    require(
        normalized.sort != WorkshopSort.FRIENDS_FAVORITES &&
            normalized.sort != WorkshopSort.FRIENDS_CREATED,
    ) {
        "Steam friend activity requires the signed-in Unified/CM query path"
    }
    query.creatorId?.let { creatorId ->
        return Uri
            .Builder()
            .scheme("https")
            .authority("steamcommunity.com")
            .appendPath("profiles")
            .appendPath(creatorId)
            .appendPath("myworkshopfiles")
            .appendQueryParameter("appid", WALLPAPER_ENGINE_APP_ID.toString())
            .appendQueryParameter("p", normalized.page.toString())
            .appendQueryParameter("numperpage", normalized.pageSize.toString())
            .build()
            .toString()
    }
    val sort =
        when (query.sort) {
            WorkshopSort.TRENDING -> "trend"
            WorkshopSort.MOST_RECENT -> "mostrecent"
            WorkshopSort.TOP_RATED -> "toprated"
            WorkshopSort.MOST_VOTES -> "mostvotes"
            WorkshopSort.MOST_SUBSCRIBERS -> "totaluniquesubscribers"
            WorkshopSort.FRIENDS_FAVORITES -> "friendsfavorite"
            WorkshopSort.FRIENDS_CREATED -> "friendscreated"
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
        .appendQueryParameter("p", normalized.page.toString())
        .appendQueryParameter("num_per_page", normalized.pageSize.toString())
        .apply {
            normalized.steamSearchText().takeIf(String::isNotBlank)?.let { searchText ->
                appendQueryParameter("searchtext", searchText)
            }
            if (normalized.sort == com.wallhub.android.core.model.WorkshopSort.TRENDING && normalized.days > 0) {
                appendQueryParameter("days", normalized.days.coerceIn(1, 365).toString())
            }
            normalized.steamTagCriteria().requiredTags
                .take(MAX_REQUIRED_TAGS)
                .forEach { tag -> appendQueryParameter("requiredtags[]", tag) }
            normalized.steamTagCriteria().excludedTags.forEach { tag -> appendQueryParameter("excludedtags[]", tag) }
        }.build()
        .toString()
}

internal fun buildSteamApiBrowseUrl(
    query: WorkshopBrowseQuery,
    steamApiKey: String,
): String {
    val normalized = query.normalized()
    require(
        normalized.sort != WorkshopSort.FRIENDS_FAVORITES &&
            normalized.sort != WorkshopSort.FRIENDS_CREATED,
    ) {
        "Steam friend activity requires the signed-in Unified/CM query path"
    }
    val criteria = normalized.steamTagCriteria()
    val input = JSONObject()
        .put("query_type", normalized.sort.steamQueryType())
        .put("page", normalized.page)
        .put("numperpage", normalized.pageSize)
        .put("creator_appid", WALLPAPER_ENGINE_APP_ID)
        .put("appid", WALLPAPER_ENGINE_APP_ID)
        .put("filetype", 0)
        .put("match_all_tags", true)
        .put("requiredtags", JSONArray(criteria.requiredTags))
        .put("excludedtags", JSONArray(criteria.excludedTags))
        .put("return_tags", true)
        .put("return_previews", true)
        .put("return_short_description", true)
        .put("return_metadata", true)
        .put("return_vote_data", true)
        .put("language", normalized.language)
        .put("search_text_target", 0)
    normalized.steamSearchText().takeIf(String::isNotBlank)?.let { input.put("search_text", it) }
    if (normalized.sort == com.wallhub.android.core.model.WorkshopSort.TRENDING && normalized.days > 0) {
        input.put("days", normalized.days.coerceIn(1, 365))
    }
    if (normalized.mobileCompatibleOnly) {
        input.put(
            "required_kv_tags",
            JSONArray().put(JSONObject().put("key", "app_workshop_eula_version").put("value", "3")),
        )
    }
    normalized.normalizedRequiredTagGroups().takeIf(List<List<String>>::isNotEmpty)?.let { groups ->
        input.put(
            "taggroups",
            JSONArray(groups.map { group -> JSONObject().put("tags", JSONArray(group)) }),
        )
    }
    normalized.normalizedCreatedRange()?.let { range ->
        input.put(
            "date_range_created",
            JSONObject()
                .put("timestamp_start", range.first)
                .put("timestamp_end", range.last),
        )
    }
    return Uri
        .Builder()
        .scheme("https")
        .authority("api.steampowered.com")
        .appendPath("IPublishedFileService")
        .appendPath("QueryFiles")
        .appendPath("v1")
        .appendQueryParameter("key", steamApiKey)
        .appendQueryParameter("format", "json")
        .appendQueryParameter("input_json", input.toString())
        .build()
        .toString()
}

internal fun buildSteamApiAuthorBrowseUrl(
    query: WorkshopBrowseQuery,
    steamApiKey: String,
): String {
    val normalized = query.normalized()
    val creator = normalized.creatorId?.filter(Char::isDigit).orEmpty()
    val criteria = normalized.steamTagCriteria()
    val input = JSONObject()
        .put("steamid", creator)
        .put("appid", WALLPAPER_ENGINE_APP_ID)
        .put("page", normalized.page)
        .put("numperpage", normalized.pageSize)
        .put("type", "myfiles")
        .put("sortmethod", "lastupdated")
        .put("requiredtags", JSONArray(criteria.requiredTags))
        .put("excludedtags", JSONArray(criteria.excludedTags))
        .put("return_tags", true)
        .put("return_previews", true)
        .put("return_short_description", true)
        .put("return_metadata", true)
        .put("return_vote_data", true)
        .put("language", normalized.language)
    return Uri.Builder()
        .scheme("https")
        .authority("api.steampowered.com")
        .appendPath("IPublishedFileService")
        .appendPath("GetUserFiles")
        .appendPath("v1")
        .appendQueryParameter("key", steamApiKey)
        .appendQueryParameter("format", "json")
        .appendQueryParameter("input_json", input.toString())
        .build()
        .toString()
}

internal fun WorkshopBrowseQuery.normalized(): WorkshopBrowseQuery =
    copy(
        page = page.coerceAtLeast(1),
        pageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE),
        searchText = searchText.trim().take(MAX_SEARCH_LENGTH),
        creatorId = creatorId?.trim()?.takeIf(String::isNotBlank) ?: searchText.workshopAuthorSearchOrNull(),
        tags =
            (tags + searchText.workshopDetailTagSearch())
                .map(String::trim)
                .filter(String::isNotBlank)
                .take(MAX_REQUIRED_TAGS)
                .toSet(),
        excludedTags =
            excludedTags
                .map(String::trim)
                .filter(String::isNotBlank)
                .take(MAX_REQUIRED_TAGS)
                .toSet(),
        requiredTagGroups =
            requiredTagGroups
                .map { group -> group.map(String::trim).filter(String::isNotBlank).take(MAX_REQUIRED_TAGS).toSet() }
                .filter(Set<String>::isNotEmpty)
                .take(MAX_REQUIRED_TAGS),
        genres = genres.map(String::trim).filter { it in COMMUNITY_GENRE_TAGS }.toSet(),
        officialTags = officialTags.map(String::trim).filter { it in OFFICIAL_TAGS }.toSet(),
        excludedOfficialTags = excludedOfficialTags.map(String::trim).filter { it in OFFICIAL_TAGS }.toSet(),
        resolutions = resolutions.map(String::trim).filter { it in RESOLUTION_TAGS }.toSet(),
        ratings = ratings.ifEmpty { setOf(com.wallhub.android.core.model.WorkshopRating.EVERYONE) },
        days = days.coerceIn(0, 365),
        createdAfterEpochSeconds = createdAfterEpochSeconds?.coerceIn(0L, Int.MAX_VALUE.toLong()),
        createdBeforeEpochSeconds = createdBeforeEpochSeconds?.coerceIn(0L, Int.MAX_VALUE.toLong()),
    )

internal fun WorkshopBrowseQuery.matches(summary: WorkshopSummary): Boolean {
    return matchesSteamWallpaper(summary)
}

internal fun WorkshopBrowseQuery.communityBrowsePageSize(): Int = pageSize

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

internal const val WALLPAPER_ENGINE_APP_ID = 431960
internal const val MAX_PAGE_SIZE = 50
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
internal val WORKSHOP_TYPE_TAGS = STEAM_WORKSHOP_TYPE_TAGS
internal val CONTENT_RATING_TAGS = STEAM_CONTENT_RATING_TAGS
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
internal val RESOLUTION_TAGS = WORKSHOP_RESOLUTION_TAG_MAP.keys
