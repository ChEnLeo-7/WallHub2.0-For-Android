package com.wallhub.android.data.workshop

import android.net.Uri
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommunityWorkshopRepository
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val unifiedWorkshopRepository: SteamUnifiedWorkshopRepository,
        clientFactory: SteamHttpClientFactory,
    ) : WorkshopRepository {
        private val steamProfiles = ConcurrentHashMap<String, SteamWebProfile>()
        private val client =
            clientFactory
                .newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()

        override suspend fun browse(query: WorkshopBrowseQuery): WorkshopPage =
            withContext(Dispatchers.IO) {
                val normalizedQuery = query.normalized()
                val preferences = settingsRepository.preferences.first()
                val steamApiKey = preferences.steamApiKey.trim()
                when (preferences.steamWorkshopDataSource) {
                    SteamWorkshopDataSource.COMMUNITY_HTML -> browseViaCommunity(normalizedQuery)
                    SteamWorkshopDataSource.WEB_API -> {
                        require(steamApiKey.isNotEmpty()) { "Steam Web API 数据源需要先配置 API Key" }
                        require(normalizedQuery.creatorId == null) { "Steam Web API 数据源暂不支持按作者浏览" }
                        enrichPageAuthors(browseViaSteamApi(normalizedQuery, steamApiKey), steamApiKey)
                    }

                    SteamWorkshopDataSource.CM_WEBSOCKET -> {
                        require(normalizedQuery.creatorId == null) { "Steam CM 数据源暂不支持按作者浏览" }
                        unifiedWorkshopRepository.browsePublic(normalizedQuery)
                            ?: error("暂时无法建立 Steam CM WebSocket 会话")
                    }
                }
            }

        override suspend fun getDetail(workshopId: Long): WorkshopDetail =
            withContext(Dispatchers.IO) {
                require(workshopId > 0L) { "创意工坊项目 ID 无效" }
                val preferences = settingsRepository.preferences.first()
                val steamApiKey = preferences.steamApiKey.trim()
                when (preferences.steamWorkshopDataSource) {
                    SteamWorkshopDataSource.COMMUNITY_HTML -> {
                        val detail =
                            getDetails(listOf(workshopId), steamApiKey).firstOrNull()
                                ?: error("Steam 未返回该创意工坊项目，可能已删除或不可公开访问")
                        val authorName = CommunityWorkshopParser.extractAuthorName(get(buildDetailUrl(workshopId)))
                        detail.copy(summary = detail.summary.copy(author = authorName ?: detail.summary.author))
                    }

                    SteamWorkshopDataSource.WEB_API -> {
                        val detail =
                            getDetails(listOf(workshopId), steamApiKey).firstOrNull()
                                ?: error("Steam Web API 未返回该创意工坊项目")
                        enrichDetailAuthor(detail, steamApiKey)
                    }

                    SteamWorkshopDataSource.CM_WEBSOCKET ->
                        unifiedWorkshopRepository
                            .getPublicDetail(workshopId)
                            ?: error("Steam CM 未返回该创意工坊项目")
                }
            }

        override suspend fun getComments(
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String?,
        ): WorkshopCommentPage =
            withContext(Dispatchers.IO) {
                require(workshopId > 0L) { "创意工坊项目 ID 无效" }
                val safeStart = start.coerceAtLeast(0)
                val safeCount = count.coerceIn(1, MAX_COMMENT_PAGE_SIZE)
                val preferences = settingsRepository.preferences.first()
                val steamApiKey = preferences.steamApiKey.trim()
                val safeOwnerId =
                    ownerId.orEmpty().filter(Char::isDigit).takeIf(String::isNotBlank)
                        ?: resolveWorkshopOwnerId(workshopId, preferences.steamWorkshopDataSource, steamApiKey)
                when (preferences.steamWorkshopDataSource) {
                    SteamWorkshopDataSource.COMMUNITY_HTML ->
                        requestCommunityCommentsPage(
                            workshopId = workshopId,
                            start = safeStart,
                            count = safeCount,
                            ownerId = safeOwnerId,
                        )

                    SteamWorkshopDataSource.WEB_API ->
                        enrichCommentAuthors(
                            page =
                                requestPublicCommentsPage(
                                    workshopId = workshopId,
                                    start = safeStart,
                                    count = safeCount,
                                    ownerId = safeOwnerId,
                                ),
                            steamApiKey = steamApiKey,
                        )

                    SteamWorkshopDataSource.CM_WEBSOCKET ->
                        unifiedWorkshopRepository
                            .getAuthenticatedComments(
                                workshopId = workshopId,
                                start = safeStart,
                                count = safeCount,
                                ownerId = safeOwnerId,
                            )
                            ?: error("Steam CM 评论需要先登录 Steam")
                }
            }

        private suspend fun resolveWorkshopOwnerId(
            workshopId: Long,
            source: SteamWorkshopDataSource,
            steamApiKey: String,
        ): String {
            val creatorId =
                when (source) {
                    SteamWorkshopDataSource.CM_WEBSOCKET ->
                        unifiedWorkshopRepository
                            .getPublicDetail(workshopId)
                            ?.creatorId
                    SteamWorkshopDataSource.COMMUNITY_HTML,
                    SteamWorkshopDataSource.WEB_API,
                    -> getDetails(listOf(workshopId), steamApiKey).firstOrNull()?.creatorId
                }
            return creatorId?.filter(Char::isDigit)?.takeIf(String::isNotBlank)
                ?: error("无法确定该创意工坊项目的作者")
        }

        private fun requestCommunityCommentsPage(
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String,
        ): WorkshopCommentPage {
            val routes =
                listOf(
                    ownerId to "$COMMUNITY_COMMENTS_HTML_URL/$ownerId/$workshopId/",
                    "" to "$COMMUNITY_COMMENTS_HTML_URL/$workshopId/-1/",
                )
            var lastFailure: Throwable? = null
            routes.forEach { (routeOwnerId, url) ->
                listOf(true, false).forEach { usePost ->
                    try {
                        requestCommunityCommentsRoute(
                            url = url,
                            workshopId = workshopId,
                            start = start,
                            count = count,
                            ownerId = routeOwnerId,
                            creatorId = ownerId,
                            usePost = usePost,
                        )?.let { return it }
                    } catch (error: Throwable) {
                        lastFailure = error
                    }
                }
            }
            throw lastFailure ?: IOException("Steam Community 未返回评论数据")
        }

        private fun requestCommunityCommentsRoute(
            url: String,
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String,
            creatorId: String,
            usePost: Boolean,
        ): WorkshopCommentPage? {
            val form =
                FormBody
                    .Builder()
                    .add("start", start.toString())
                    .add("count", count.toString())
                    .add("feature2", "-1")
                    .add("l", "schinese")
                    .add("userreview_offset", "-1")
                    .build()
            val requestUrl =
                if (usePost) {
                    url
                } else {
                    Uri
                        .parse(url)
                        .buildUpon()
                        .appendQueryParameter("start", start.toString())
                        .appendQueryParameter("count", count.toString())
                        .appendQueryParameter("feature2", "-1")
                        .appendQueryParameter("l", "schinese")
                        .appendQueryParameter("userreview_offset", "-1")
                        .build()
                        .toString()
                }
            val request =
                Request
                    .Builder()
                    .url(requestUrl)
                    .apply { if (usePost) post(form) else get() }
                    .header("Accept", "application/json,text/javascript,*/*;q=0.01")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                    .header("Origin", "https://steamcommunity.com")
                    .header("Referer", buildDetailUrl(workshopId))
                    .header("User-Agent", USER_AGENT)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
            val payload =
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Steam Community 评论请求失败：HTTP ${response.code}")
                    }
                    JSONObject(response.body.string())
                }
            val html =
                sequenceOf("comments_html", "html", "comments")
                    .map(payload::optString)
                    .firstOrNull(String::isNotBlank)
                    .orEmpty()
            val comments = CommunityWorkshopParser.parseComments(html, count, creatorId)
            val failed =
                when (val success = payload.opt("success")) {
                    false -> true
                    is Number -> success.toInt() == 0
                    is String -> success == "0" || success.equals("false", ignoreCase = true)
                    else -> false
                }
            if (comments.isEmpty() && failed) return null
            val total =
                sequenceOf("total_count", "total", "comment_count")
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

        private fun requestPublicCommentsPage(
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String,
        ): WorkshopCommentPage {
            val input =
                JSONObject()
                    .put("steamid", ownerId)
                    .put("comment_thread_type", PUBLISHED_FILE_PUBLIC_COMMENT_THREAD)
                    .put("gidfeature", workshopId.toString())
                    .put("start", start)
                    .put("count", count)
            val request =
                Request
                    .Builder()
                    .url(COMMUNITY_COMMENTS_API_URL)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .post(FormBody.Builder().add("input_json", input.toString()).build())
                    .build()
            val payload =
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Steam 公共评论请求失败：HTTP ${response.code}")
                    }
                    val result = response.header("X-EResult")?.toIntOrNull()
                    val body = response.body.string()
                    if (result != null && result != RESULT_OK) {
                        throw IOException("Steam 公共评论请求失败：EResult $result")
                    }
                    JSONObject(body).optJSONObject("response") ?: JSONObject()
                }
            return parsePublicCommentsPage(payload, start, count, ownerId)
        }

        private fun enrichPageAuthors(
            page: WorkshopPage,
            steamApiKey: String,
        ): WorkshopPage {
            val profiles =
                loadSteamProfiles(
                    steamIds = page.items.mapNotNull(WorkshopSummary::creatorId).toSet(),
                    steamApiKey = steamApiKey,
                )
            if (profiles.isEmpty()) return page
            return page.copy(
                items =
                    page.items.map { item ->
                        val profile = item.creatorId?.let(profiles::get) ?: return@map item
                        item.copy(author = profile.displayName)
                    },
            )
        }

        private fun enrichDetailAuthor(
            detail: WorkshopDetail,
            steamApiKey: String,
        ): WorkshopDetail {
            if (!detail.summary.author.isFallbackSteamName()) return detail
            val creatorId = detail.creatorId ?: detail.summary.creatorId ?: return detail
            val profile = loadSteamProfiles(setOf(creatorId), steamApiKey)[creatorId] ?: return detail
            return detail.copy(summary = detail.summary.copy(author = profile.displayName))
        }

        private fun enrichCommentAuthors(
            page: WorkshopCommentPage,
            steamApiKey: String,
        ): WorkshopCommentPage {
            val profiles =
                loadSteamProfiles(
                    steamIds = page.comments.mapNotNull(WorkshopComment::authorId).toSet(),
                    steamApiKey = steamApiKey,
                )
            if (profiles.isEmpty()) return page
            return page.copy(
                comments =
                    page.comments.map { comment ->
                        val profile = comment.authorId?.let(profiles::get) ?: return@map comment
                        comment.copy(author = profile.displayName, avatarUrl = profile.avatarUrl)
                    },
            )
        }

        private fun loadSteamProfiles(
            steamIds: Set<String>,
            steamApiKey: String,
        ): Map<String, SteamWebProfile> {
            val validIds =
                steamIds
                    .mapNotNull { value ->
                        value.filter(Char::isDigit).takeIf(String::isNotBlank)
                    }.toSet()
            if (validIds.isEmpty() || steamApiKey.isBlank()) return emptyMap()
            val missing = validIds.filterNot(steamProfiles::containsKey)
            missing.chunked(MAX_STEAM_PROFILE_BATCH_SIZE).forEach { batch ->
                val url =
                    Uri
                        .Builder()
                        .scheme("https")
                        .authority("api.steampowered.com")
                        .appendPath("ISteamUser")
                        .appendPath("GetPlayerSummaries")
                        .appendPath("v2")
                        .appendQueryParameter("key", steamApiKey)
                        .appendQueryParameter("steamids", batch.joinToString(","))
                        .build()
                        .toString()
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .build()
                runCatching {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val players =
                            JSONObject(response.body.string())
                                .optJSONObject("response")
                                ?.optJSONArray("players")
                                ?: JSONArray()
                        for (index in 0 until players.length()) {
                            val player = players.optJSONObject(index) ?: continue
                            val steamId = player.optString("steamid").takeIf(String::isNotBlank) ?: continue
                            val displayName = player.optString("personaname").trim()
                            if (displayName.isBlank()) continue
                            steamProfiles[steamId] =
                                SteamWebProfile(
                                    displayName = displayName,
                                    avatarUrl = player.optString("avatarfull").takeIf(String::isNotBlank),
                                )
                        }
                    }
                }
            }
            return validIds.mapNotNull { steamId -> steamProfiles[steamId]?.let { steamId to it } }.toMap()
        }

        private fun String.isFallbackSteamName(): Boolean = equals("Steam 创作者", ignoreCase = true) || startsWith("Steam 用户")

        private fun browseViaCommunity(normalizedQuery: WorkshopBrowseQuery): WorkshopPage {
            val html = get(buildBrowseUrl(normalizedQuery))
            val sourcePageSize = normalizedQuery.communityBrowsePageSize()
            val totalCount = CommunityWorkshopParser.extractTotalCount(html)
            val totalPages =
                CommunityWorkshopParser
                    .extractTotalPages(html)
                    ?.coerceAtMost(MAX_DIRECT_BROWSE_PAGE)
                    ?: totalCount?.toMaximumPage(sourcePageSize)
            val ids =
                CommunityWorkshopParser
                    .extractItemIds(html)
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
            val items =
                ids
                    .mapNotNull(detailMap::get)
                    .filter { item ->
                        normalizedQuery.creatorId == null || item.creatorId == normalizedQuery.creatorId
                    }.filter { item -> normalizedQuery.matches(item.summary) }
            return WorkshopPage(
                items = items.map(WorkshopDetail::summary),
                page = normalizedQuery.page,
                hasNextPage =
                    ids.size >= sourcePageSize &&
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
            val items =
                details
                    .filter { item -> normalizedQuery.matches(item.summary) }
                    .take(normalizedQuery.pageSize)
            val total =
                JSONObject(body)
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
                hasNextPage =
                    details.size >= normalizedQuery.pageSize &&
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
            val form =
                FormBody
                    .Builder()
                    .add("itemcount", ids.size.toString())
                    .add("includetags", "true")
                    .add("short_description", "true")
                    .apply {
                        ids.forEachIndexed { index, id -> add("publishedfileids[$index]", id.toString()) }
                    }.build()
            val request =
                Request
                    .Builder()
                    .url(PUBLISHED_FILE_DETAILS_URL)
                    .post(form)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .apply {
                        steamApiKey.takeIf(String::isNotBlank)?.let { header("x-webapi-key", it) }
                    }.build()
            val body =
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Steam 公共详情请求失败：HTTP ${response.code}")
                    }
                    response.body.string()
                }
            return CommunityWorkshopParser.parseDetails(body)
        }

        private fun get(url: String): String {
            val request =
                Request
                    .Builder()
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

        private fun getSteamApi(
            url: String,
            steamApiKey: String,
        ): String {
            val request =
                Request
                    .Builder()
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
    }
