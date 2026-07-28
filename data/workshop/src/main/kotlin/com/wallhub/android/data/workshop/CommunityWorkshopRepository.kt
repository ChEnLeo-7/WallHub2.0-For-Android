package com.wallhub.android.data.workshop

import android.net.Uri
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class CommunityWorkshopRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val unifiedWorkshopRepository: SteamUnifiedWorkshopRepository,
    clientFactory: SteamHttpClientFactory,
) : WorkshopRepository {
    private val client = clientFactory.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    override suspend fun browse(query: WorkshopBrowseQuery): WorkshopPage = withContext(Dispatchers.IO) {
        val normalizedQuery = query.normalized()
        if (normalizedQuery.creatorId == null) {
            try {
                unifiedWorkshopRepository.browsePublic(normalizedQuery)?.let { return@withContext it }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {}
        }
        val steamApiKey = settingsRepository.preferences.first().steamApiKey.trim()
        if (steamApiKey.isNotEmpty() && normalizedQuery.creatorId == null) {
            try {
                return@withContext browseViaSteamApi(normalizedQuery, steamApiKey)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {}
        }
        browseViaCommunity(normalizedQuery, steamApiKey)
    }

    override suspend fun getDetail(workshopId: Long): WorkshopDetail = withContext(Dispatchers.IO) {
        require(workshopId > 0L) { "创意工坊项目 ID 无效" }
        val steamApiKey = settingsRepository.preferences.first().steamApiKey.trim()
        val detail = getDetails(listOf(workshopId)).firstOrNull()
            ?: error("Steam 未返回该创意工坊项目，可能已删除或不可公开访问")
        val authorName = runCatching {
            CommunityWorkshopParser.extractAuthorName(get(buildDetailUrl(workshopId)))
        }.getOrNull()
        detail.copy(summary = detail.summary.copy(author = authorName ?: detail.summary.author))
    }

    override suspend fun getComments(
        workshopId: Long,
        start: Int,
        count: Int,
        ownerId: String?,
    ): WorkshopCommentPage = withContext(Dispatchers.IO) {
        require(workshopId > 0L) { "创意工坊项目 ID 无效" }
        val safeStart = start.coerceAtLeast(0)
        val safeCount = count.coerceIn(1, MAX_COMMENT_PAGE_SIZE)
        val safeOwnerId = ownerId.orEmpty().filter(Char::isDigit)
        val routes = buildList {
            if (safeOwnerId.isNotBlank()) {
                add(safeOwnerId to "$COMMENTS_BASE_URL/$safeOwnerId/$workshopId/")
            }
            add("" to "$COMMENTS_BASE_URL/$workshopId/-1/")
        }
        var lastFailure: Throwable? = null
        routes.forEach { (routeOwnerId, url) ->
            listOf(true, false).forEach { usePost ->
                try {
                    requestCommentsPage(
                        url = url,
                        workshopId = workshopId,
                        start = safeStart,
                        count = safeCount,
                        ownerId = routeOwnerId,
                        creatorId = safeOwnerId,
                        usePost = usePost,
                    )?.let { return@withContext it }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    lastFailure = error
                }
            }
        }
        throw lastFailure ?: IOException("Steam 未返回评论数据")
    }

    private fun requestCommentsPage(
        url: String,
        workshopId: Long,
        start: Int,
        count: Int,
        ownerId: String,
        creatorId: String,
        usePost: Boolean,
    ): WorkshopCommentPage? {
        val form = FormBody.Builder()
            .add("start", start.toString())
            .add("count", count.toString())
            .add("feature2", "-1")
            .add("l", "schinese")
            .add("userreview_offset", "-1")
            .build()
        val requestUrl = if (usePost) {
            url
        } else {
            Uri.parse(url).buildUpon()
                .appendQueryParameter("start", start.toString())
                .appendQueryParameter("count", count.toString())
                .appendQueryParameter("feature2", "-1")
                .appendQueryParameter("l", "schinese")
                .appendQueryParameter("userreview_offset", "-1")
                .build()
                .toString()
        }
        val request = Request.Builder()
            .url(requestUrl)
            .apply { if (usePost) post(form) else get() }
            .header("Accept", "application/json,text/javascript,*/*;q=0.01")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
            .header("Origin", "https://steamcommunity.com")
            .header("Referer", buildDetailUrl(workshopId))
            .header("User-Agent", USER_AGENT)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Steam 评论请求失败：HTTP ${response.code}")
            }
            response.body.string()
        }
        val payload = JSONObject(body)
        val html = sequenceOf("comments_html", "html", "comments")
            .map(payload::optString)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
        val comments = CommunityWorkshopParser.parseComments(html, count, creatorId)
        val explicitlyFailed = when (val success = payload.opt("success")) {
            false -> true
            is Number -> success.toInt() == 0
            is String -> success == "0" || success.equals("false", ignoreCase = true)
            else -> false
        }
        if (comments.isEmpty() && explicitlyFailed) return null
        val total = sequenceOf("total_count", "total", "comment_count")
            .mapNotNull { key -> payload.opt(key)?.toString()?.toIntOrNull() }
            .firstOrNull()
            ?.coerceAtLeast(0)
        val nextStart = start + maxOf(comments.size, count)
        return WorkshopCommentPage(
            comments = comments,
            start = start,
            count = count,
            nextStart = nextStart,
            total = total,
            hasMore = if (total != null) nextStart < total else comments.size >= count,
            ownerId = ownerId.ifBlank { null },
        )
    }

    private fun browseViaCommunity(
        normalizedQuery: WorkshopBrowseQuery,
        steamApiKey: String,
    ): WorkshopPage {
        val html = get(buildBrowseUrl(normalizedQuery))
        val sourcePageSize = normalizedQuery.communityBrowsePageSize()
        val totalCount = CommunityWorkshopParser.extractTotalCount(html)
        val totalPages = CommunityWorkshopParser.extractTotalPages(html)
            ?.coerceAtMost(MAX_DIRECT_BROWSE_PAGE)
            ?: totalCount?.toMaximumPage(sourcePageSize)
        val ids = CommunityWorkshopParser.extractItemIds(html)
            .take(sourcePageSize)
        if (ids.isEmpty()) {
            return WorkshopPage(
                items = emptyList(),
                page = normalizedQuery.page,
                hasNextPage = false,
                totalCount = totalCount,
                totalPages = totalPages,
            )
        }

        val detailMap = getDetails(ids).associateBy { it.summary.id }
        val items = ids.mapNotNull(detailMap::get)
            .filter { item ->
                normalizedQuery.creatorId == null || item.creatorId == normalizedQuery.creatorId
            }
            .filter { item -> normalizedQuery.matches(item.summary) }
        return WorkshopPage(
            items = items.map(WorkshopDetail::summary),
            page = normalizedQuery.page,
            hasNextPage = ids.size >= sourcePageSize &&
                (totalPages == null || normalizedQuery.page < totalPages),
            totalCount = totalCount,
            totalPages = totalPages,
        )
    }

    private fun browseViaSteamApi(
        normalizedQuery: WorkshopBrowseQuery,
        steamApiKey: String,
    ): WorkshopPage {
        val body = getSteamApi(buildSteamApiBrowseUrl(normalizedQuery, steamApiKey), steamApiKey)
        val details = CommunityWorkshopParser.parseDetails(body)
        val items = details
            .filter { item -> normalizedQuery.matches(item.summary) }
            .take(normalizedQuery.pageSize)
        val total = JSONObject(body)
            .optJSONObject("response")
            ?.opt("total")
            ?.toString()
            ?.toLongOrNull()
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
        val totalPages = total?.toMaximumPage(normalizedQuery.pageSize)
        return WorkshopPage(
            items = items.map(WorkshopDetail::summary),
            page = normalizedQuery.page,
            hasNextPage = details.size >= normalizedQuery.pageSize &&
                (totalPages == null || normalizedQuery.page < totalPages),
            totalCount = total,
            totalPages = totalPages,
        )
    }

    private fun Int.toMaximumPage(pageSize: Int): Int {
        val safePageSize = pageSize.coerceAtLeast(1).toLong()
        return ((coerceAtLeast(0).toLong() + safePageSize - 1L) / safePageSize)
            .coerceIn(1L, MAX_DIRECT_BROWSE_PAGE.toLong())
            .toInt()
    }

    private fun getDetails(
        ids: List<Long>,
        steamApiKey: String = "",
    ): List<WorkshopDetail> {
        val form = FormBody.Builder()
            .add("itemcount", ids.size.toString())
            .add("includetags", "true")
            .add("short_description", "true")
            .apply {
                ids.forEachIndexed { index, id -> add("publishedfileids[$index]", id.toString()) }
            }
            .build()
        val request = Request.Builder()
            .url(PUBLISHED_FILE_DETAILS_URL)
            .post(form)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .apply {
                steamApiKey.takeIf(String::isNotBlank)?.let { header("x-webapi-key", it) }
            }
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Steam 公共详情请求失败：HTTP ${response.code}")
            }
            response.body.string()
        }
        return CommunityWorkshopParser.parseDetails(body)
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Steam 创意工坊浏览请求失败：HTTP ${response.code}")
            }
            response.body.string()
        }
    }

    private fun getSteamApi(url: String, steamApiKey: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("x-webapi-key", steamApiKey)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Steam Web API 查询失败：HTTP ${response.code}")
            }
            response.body.string()
        }
    }

    private fun buildDetailUrl(workshopId: Long): String = Uri.Builder()
        .scheme("https")
        .authority("steamcommunity.com")
        .appendPath("sharedfiles")
        .appendPath("filedetails")
        .appendQueryParameter("id", workshopId.toString())
        .build()
        .toString()

    private fun buildBrowseUrl(query: WorkshopBrowseQuery): String {
        query.creatorId?.let { creatorId ->
            return Uri.Builder()
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
                }
                .build()
                .toString()
        }
        val sort = when (query.sort) {
            WorkshopSort.TRENDING -> "trend"
            WorkshopSort.MOST_RECENT -> "mostrecent"
            WorkshopSort.TOP_RATED -> "toprated"
            WorkshopSort.MOST_VOTES -> "mostvotes"
            WorkshopSort.MOST_SUBSCRIBERS -> "totaluniquesubscribers"
        }
        return Uri.Builder()
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
            }
            .build()
            .toString()
    }

    private fun buildSteamApiBrowseUrl(
        query: WorkshopBrowseQuery,
        steamApiKey: String,
    ): String {
        val requiredTags = linkedSetOf<String>().apply {
            query.effectiveTypes().singleOrNull()?.steamTag()?.let(::add)
            query.effectiveRatings().singleOrNull()?.steamTag?.let(::add)
            addAll(query.tags)
            addAll(query.officialTags)
            query.genres.singleOrNull()?.let(::add)
            query.resolutions.singleOrNull()?.let(::add)
        }
        return Uri.Builder()
            .scheme("https")
            .authority("api.steampowered.com")
            .appendPath("IPublishedFileService")
            .appendPath("QueryFiles")
            .appendPath("v1")
            .appendQueryParameter("key", steamApiKey)
            .appendQueryParameter(
                "query_type",
                if (query.searchText.isNotBlank()) "12" else query.sort.steamApiQueryType().toString(),
            )
            .appendQueryParameter("page", query.page.toString())
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
            }
            .build()
            .toString()
    }

    private fun WorkshopBrowseQuery.normalized(): WorkshopBrowseQuery = copy(
        page = page.coerceAtLeast(1),
        pageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE),
        searchText = searchText.trim().take(MAX_SEARCH_LENGTH),
        creatorId = creatorId?.filter(Char::isDigit)?.takeIf(String::isNotBlank),
        types = effectiveTypes(),
        tags = tags.map(String::trim).filter(String::isNotBlank).take(MAX_REQUIRED_TAGS).toSet(),
        genres = genres.map(String::trim).filter { it in COMMUNITY_GENRE_TAGS }.toSet(),
        officialTags = officialTags.map(String::trim).filter { it in OFFICIAL_TAGS }.toSet(),
        resolutions = resolutions.map(String::trim).filter { it in RESOLUTION_TAGS }.toSet(),
        ratings = effectiveRatings(),
        days = days.coerceIn(0, 365),
    )

    private fun WorkshopBrowseQuery.matches(summary: WorkshopSummary): Boolean {
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

    private fun WorkshopBrowseQuery.effectiveTypes(): Set<WorkshopType> =
        types.filter { it != WorkshopType.UNKNOWN }.toSet().ifEmpty {
            type?.takeIf { it != WorkshopType.UNKNOWN }?.let(::setOf).orEmpty()
        }

    private fun WorkshopBrowseQuery.effectiveRatings(): Set<WorkshopRating> =
        ratings.ifEmpty { setOf(WorkshopRating.EVERYONE) }

    private fun WorkshopBrowseQuery.communityBrowsePageSize(): Int =
        if (creatorId == null) pageSize else AUTHOR_BROWSE_PAGE_SIZE

    private fun Uri.Builder.appendFilterTags(
        selected: Set<String>,
        all: Set<String>,
    ) {
        val normalized = selected.map(String::trim).filter { it in all }.toSet()
        if (normalized.size == 1) {
            appendQueryParameter("requiredtags[]", normalized.single())
            return
        }
        if (normalized.size > 1 && normalized.size < all.size) {
            all.filterNot(normalized::contains)
                .forEach { tag -> appendQueryParameter("excludedtags[]", tag) }
        }
    }

    private fun WorkshopType.steamTag(): String? = when (this) {
        WorkshopType.VIDEO -> "Video"
        WorkshopType.SCENE -> "Scene"
        WorkshopType.WEB -> "Web"
        WorkshopType.UNKNOWN -> null
    }

    private fun WorkshopSort.steamApiQueryType(): Int = when (this) {
        WorkshopSort.TRENDING -> 3
        WorkshopSort.MOST_RECENT -> 1
        WorkshopSort.TOP_RATED -> 0
        WorkshopSort.MOST_VOTES -> 11
        WorkshopSort.MOST_SUBSCRIBERS -> 9
    }

    private companion object {
        const val WALLPAPER_ENGINE_APP_ID = 431960
        const val AUTHOR_BROWSE_PAGE_SIZE = 30
        const val MAX_PAGE_SIZE = 30
        const val MAX_DIRECT_BROWSE_PAGE = 1_000
        const val MAX_COMMENT_PAGE_SIZE = 50
        const val MAX_SEARCH_LENGTH = 128
        const val MAX_REQUIRED_TAGS = 48
        const val USER_AGENT = "WallHub-Android/0.5 (Public Workshop Browser)"
        const val PUBLISHED_FILE_DETAILS_URL =
            "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
        const val COMMENTS_BASE_URL =
            "https://steamcommunity.com/comment/PublishedFile_Public/render"
        val WORKSHOP_TYPE_TAGS = setOf("Scene", "Video", "Web")
        val CONTENT_RATING_TAGS = setOf("Everyone", "Questionable", "Mature")
        val COMMUNITY_GENRE_TAGS = setOf(
            "Abstract", "Animal", "Anime", "Cartoon", "CGI", "Cyberpunk", "Fantasy",
            "Game", "Girls", "Guys", "Landscape", "Medieval", "Memes", "MMD", "Music",
            "Nature", "Pixel art", "Relaxing", "Retro", "Sci-Fi", "Sports", "Technology",
            "Television", "Vehicle", "Unspecified",
        )
        val OFFICIAL_TAGS = setOf(
            "Approved", "Audio responsive", "3D", "Customizable", "Puppet Warp", "HDR",
            "Media Integration", "User Shortcut", "Video Texture", "Asset Pack",
        )
        val RESOLUTION_TAGS = setOf(
            "Standard", "1280 x 720", "1366 x 768", "1920 x 1080", "2560 x 1440", "3840 x 2160",
            "Ultrawide", "2560 x 1080", "3440 x 1440", "Dual monitor", "3840 x 1080", "5120 x 1440",
            "7680 x 2160", "Triple monitor", "4096 x 768", "5760 x 1080", "7680 x 1440", "11520 x 2160",
            "Portrait", "720 x 1280", "1080 x 1920", "1440 x 2560", "2160 x 3840", "Other resolution",
            "Dynamic resolution",
        )
    }
}

internal object CommunityWorkshopParser {
    private val itemIdPattern = Regex(
        """sharedfiles(?:\\/|/)filedetails(?:\\/|/)\?id=(\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val escapedTotalPagesPattern = Regex(
        """(?i)\\+"total_pages\\+"\s*:\s*(\d+)""",
    )
    private val escapedTotalCountPattern = Regex(
        """(?i)\\+"total_count\\+"\s*:\s*(\d+)""",
    )
    private val legacyTotalCountPattern = Regex(
        """(?i)showing\s+[\d,]+-[\d,]+\s+of\s+([\d,]+)""",
    )
    private val pagingInfoPattern = Regex(
        """(?is)<div\b[^>]*\bclass\s*=\s*["'][^"']*\bworkshopBrowsePagingInfo\b[^"']*["'][^>]*>(.*?)</div>""",
    )
    private val pagingInfoNumberPattern = Regex("""\d[\d,]*""")
    private val creatorsBlockPattern = Regex(
        """(?i)<div\b[^>]*\bclass\s*=\s*["'][^"']*\bcreatorsBlock\b[^"']*["'][^>]*>""",
    )
    private val commentBlockStartPattern = Regex(
        """(?i)<div\b[^>]*\bid\s*=\s*["']comment_\d+["'][^>]*>""",
    )
    private val commentAuthorPattern = Regex(
        """(?is)<a\b[^>]*\bclass\s*=\s*["'][^"']*\bcommentthread_author_link\b[^"']*["'][^>]*>(.*?)</a>""",
    )
    private val commentAuthorFallbackPattern = Regex(
        """(?is)<span\b[^>]*\bclass\s*=\s*["'][^"']*\bcommentthread_author\b[^"']*["'][^>]*>(.*?)</span>""",
    )
    private val commentTextPattern = Regex(
        """(?is)<div\b[^>]*\bclass\s*=\s*["'][^"']*\bcommentthread_(?:comment_)?text\b[^"']*["'][^>]*>(.*?)</div>""",
    )
    private val commentAvatarContainerPattern = Regex(
        """(?i)<div\b[^>]*\bclass\s*=\s*["'][^"']*\bcommentthread_comment_avatar\b[^"']*["'][^>]*>""",
    )
    private val commentAvatarProfileLinkPattern = Regex(
        """(?is)<a\b(?=[^>]*\bdata-miniprofile\s*=)[^>]*>(.*?)</a>""",
    )
    private val imageTagPattern = Regex(
        """(?is)<img\b[^>]*>""",
    )
    private val imageSrcPattern = Regex(
        """(?i)\bsrc\s*=\s*["']([^"']+)["']""",
    )
    private val imageSrcSetPattern = Regex(
        """(?i)\bsrcset\s*=\s*["']([^"']+)["']""",
    )
    private val miniProfilePattern = Regex(
        """(?i)\bdata-miniprofile\s*=\s*["'](\d+)["']""",
    )
    private val profileSteamIdPattern = Regex(
        """(?i)\bhref\s*=\s*["'][^"']*/profiles/(\d+)[^"']*["']""",
    )
    private val commentTimestampPattern = Regex(
        """(?i)\bdata-(?:time|timestamp)\s*=\s*["'](\d+)["']""",
    )

    fun extractItemIds(html: String): List<Long> {
        val ids = LinkedHashSet<Long>()
        itemIdPattern.findAll(html).forEach { match ->
            match.groupValues[1].toLongOrNull()?.takeIf { it > 0L }?.let(ids::add)
        }
        return ids.toList()
    }

    fun extractTotalPages(html: String): Int? = escapedTotalPagesPattern.extractInt(html)

    fun extractTotalCount(html: String): Int? = escapedTotalCountPattern.extractInt(html)
        ?: pagingInfoPattern.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { pagingInfo -> pagingInfoNumberPattern.findAll(pagingInfo).lastOrNull()?.value }
            ?.replace(",", "")
            ?.toLongOrNull()
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
        ?: legacyTotalCountPattern.extractInt(html)

    private fun Regex.extractInt(input: String): Int? = find(input)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace(",", "")
        ?.toLongOrNull()
        ?.coerceAtMost(Int.MAX_VALUE.toLong())
        ?.toInt()

    /** Steam's public details endpoint only exposes a creator ID, not the display name. */
    fun extractAuthorName(html: String): String? {
        val creatorsBlock = creatorsBlockPattern.find(html) ?: return null
        val friendContentIndex = html.indexOf(
            string = "friendBlockContent",
            startIndex = creatorsBlock.range.last + 1,
            ignoreCase = true,
        )
        if (friendContentIndex < 0) return null

        val contentStart = html.indexOf('>', friendContentIndex)
        if (contentStart < 0) return null
        val lineBreakIndex = html.indexOf(
            string = "<br",
            startIndex = contentStart + 1,
            ignoreCase = true,
        )
        val contentEnd = if (lineBreakIndex >= 0) {
            lineBreakIndex
        } else {
            html.indexOf("</div>", contentStart + 1, ignoreCase = true)
        }
        if (contentEnd <= contentStart) return null

        return cleanHtml(html.substring(contentStart + 1, contentEnd))
            .lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
    }

    fun parseDetails(json: String): List<WorkshopDetail> {
        val response = JSONObject(json).optJSONObject("response") ?: return emptyList()
        val details = response.optJSONArray("publishedfiledetails") ?: JSONArray()
        return buildList {
            for (index in 0 until details.length()) {
                val detail = details.optJSONObject(index) ?: continue
                if (detail.jsonLong("result") != RESULT_OK) continue
                val id = detail.jsonLong("publishedfileid")
                if (id <= 0L) continue
                add(detail.toWorkshopDetail(id))
            }
        }
    }

    fun parseComments(
        html: String,
        limit: Int,
        creatorId: String? = null,
    ): List<WorkshopComment> {
        if (html.isBlank()) return emptyList()
        val starts = commentBlockStartPattern.findAll(html).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        return buildList {
            starts.forEachIndexed { index, start ->
                val end = starts.getOrNull(index + 1) ?: html.length
                val block = html.substring(start, end)
                val text = commentTextPattern.find(block)?.groupValues?.getOrNull(1)
                    ?.let(::cleanHtml)
                    .orEmpty()
                if (text.isBlank()) return@forEachIndexed
                val authorLink = commentAuthorPattern.find(block)
                val author = (
                    authorLink?.groupValues?.getOrNull(1)
                        ?: commentAuthorFallbackPattern.find(block)?.groupValues?.getOrNull(1)
                    )
                    ?.let(::cleanHtml)
                    ?.takeIf(String::isNotBlank)
                    ?: DEFAULT_COMMENT_AUTHOR
                add(
                    WorkshopComment(
                        author = author,
                        text = text,
                        avatarUrl = extractCommentAvatarUrl(block, authorLink?.range?.first),
                        isCreator = isCreatorComment(authorLink?.value, creatorId),
                        timestamp = commentTimestampPattern.find(block)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toLongOrNull(),
                    ),
                )
                if (size >= limit.coerceIn(1, MAX_COMMENT_PAGE_SIZE)) return@buildList
            }
        }
    }

    private fun extractCommentAvatarUrl(block: String, authorLinkStart: Int?): String? {
        val avatarStart = commentAvatarContainerPattern.find(block)?.range?.first ?: return null
        val avatarEnd = authorLinkStart?.takeIf { it > avatarStart } ?: block.length
        val avatarMarkup = block.substring(avatarStart, avatarEnd)
        val profileMarkup = commentAvatarProfileLinkPattern.find(avatarMarkup)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        val imageTag = imageTagPattern.find(profileMarkup)?.value ?: return null
        val rawSource = imageSrcPattern.find(imageTag)?.groupValues?.getOrNull(1)
            ?: imageSrcSetPattern.find(imageTag)?.groupValues?.getOrNull(1)
        return rawSource
            ?.substringBefore(',')
            ?.trim()
            ?.substringBefore(' ')
            ?.replace("&amp;", "&")
            ?.takeIf(String::isNotBlank)
    }

    private fun isCreatorComment(authorLinkMarkup: String?, creatorId: String?): Boolean {
        val normalizedCreatorId = creatorId
            ?.filter(Char::isDigit)
            ?.takeIf(String::isNotBlank)
            ?: return false
        val directProfileId = authorLinkMarkup
            ?.let(profileSteamIdPattern::find)
            ?.groupValues
            ?.getOrNull(1)
        if (directProfileId == normalizedCreatorId) return true

        val commenterAccountId = authorLinkMarkup
            ?.let(miniProfilePattern::find)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: return false
        val rawCreatorId = normalizedCreatorId.toLongOrNull() ?: return false
        val creatorAccountId = if (rawCreatorId >= STEAM_ID64_ACCOUNT_BASE) {
            rawCreatorId - STEAM_ID64_ACCOUNT_BASE
        } else {
            rawCreatorId
        }
        return commenterAccountId == creatorAccountId
    }

    private fun JSONObject.toWorkshopDetail(id: Long): WorkshopDetail {
        val tags = jsonTags("tags")
        val title = jsonString("title").ifBlank { "壁纸 $id" }
        val previewUrl = jsonString("preview_url").ifBlank { null }
        val description = cleanHtml(jsonString("description").ifBlank {
            jsonString("short_description")
        })
        val summary = WorkshopSummary(
            id = id,
            title = title,
            author = jsonString("creator_name").ifBlank { DEFAULT_AUTHOR_NAME },
            creatorId = jsonString("creator").takeIf(String::isNotBlank),
            previewUrl = previewUrl,
            type = classifyType(tags),
            tags = tags,
            subscriptions = jsonLong("subscriptions").takeIf { it > 0L }
                ?: jsonLong("lifetime_subscriptions").takeIf { it > 0L },
            favorites = jsonLong("favorited").takeIf { it > 0L }
                ?: jsonLong("lifetime_favorited").takeIf { it > 0L },
            views = jsonLong("views").takeIf { it > 0L },
            fileSizeBytes = jsonLong("file_size").takeIf { it > 0L },
            subscriptionState = SubscriptionState.UNKNOWN,
            favoriteState = FavoriteState.UNKNOWN,
        )
        return WorkshopDetail(
            summary = summary,
            description = description,
            fileSizeBytes = jsonLong("file_size").takeIf { it > 0L },
            previewMediaUrl = previewUrl,
            createdAt = jsonLong("time_created").takeIf { it > 0L },
            updatedAt = jsonLong("time_updated").takeIf { it > 0L },
            subscriptions = jsonLong("subscriptions").takeIf { it > 0L },
            creatorId = jsonString("creator").takeIf(String::isNotBlank),
        )
    }

    private fun JSONObject.jsonTags(name: String): List<String> {
        val values = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                val raw = values.optJSONObject(index)?.optString("tag")
                    ?: values.optString(index)
                raw.trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct()
    }

    private fun JSONObject.jsonString(name: String): String = opt(name)?.toString()?.trim().orEmpty()

    private fun JSONObject.jsonLong(name: String): Long = when (val value = opt(name)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun classifyType(tags: List<String>): WorkshopType {
        val normalized = tags.map(String::lowercase).toSet()
        return when {
            "video" in normalized -> WorkshopType.VIDEO
            "web" in normalized || "website" in normalized || "application" in normalized -> WorkshopType.WEB
            "scene" in normalized -> WorkshopType.SCENE
            else -> WorkshopType.UNKNOWN
        }
    }

    private fun cleanHtml(value: String): String = decodeNumericEntities(
        value
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)</(?:p|div|li|h[1-6])\s*>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim(),
    )

    private fun decodeNumericEntities(value: String): String {
        val hexadecimal = Regex("""&#x([0-9a-fA-F]+);""").replace(value) { match ->
            decodeCodePoint(match.groupValues[1].toIntOrNull(16), match.value)
        }
        return Regex("""&#(\d+);""").replace(hexadecimal) { match ->
            decodeCodePoint(match.groupValues[1].toIntOrNull(), match.value)
        }
    }

    private fun decodeCodePoint(codePoint: Int?, fallback: String): String =
        codePoint?.takeIf(Character::isValidCodePoint)
            ?.let { value -> String(Character.toChars(value)) }
            ?: fallback

    private const val RESULT_OK = 1L
    private const val DEFAULT_AUTHOR_NAME = "Steam 创作者"
    private const val DEFAULT_COMMENT_AUTHOR = "Steam 用户"
    private const val MAX_COMMENT_PAGE_SIZE = 50
    private const val STEAM_ID64_ACCOUNT_BASE = 76_561_197_960_265_728L
}
