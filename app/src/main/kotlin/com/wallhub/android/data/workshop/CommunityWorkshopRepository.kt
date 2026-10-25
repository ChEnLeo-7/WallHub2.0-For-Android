package com.wallhub.android.data.workshop

import android.util.Log
import com.wallhub.android.core.model.CmPublicDetailResult
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.matchesSteamWallpaper
import com.wallhub.android.core.model.workshopSearchIdOrNull
import com.wallhub.android.core.model.workshopAuthorSearchOrNull
import com.wallhub.android.data.downloads.readBytesBounded
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommunityWorkshopRepository internal constructor(
    private val settingsRepository: SettingsRepository,
    private val unifiedWorkshopRepository: SteamUnifiedWorkshopRepository,
    private val client: OkHttpClient,
    private val anonymousReadClient: OkHttpClient = client,
    detailCallTimeoutMs: Long = COMMUNITY_DETAIL_CALL_TIMEOUT_MS,
) : WorkshopRepository {
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        unifiedWorkshopRepository: SteamUnifiedWorkshopRepository,
        clientFactory: SteamHttpClientFactory,
    ) : this(
        settingsRepository = settingsRepository,
        unifiedWorkshopRepository = unifiedWorkshopRepository,
        client =
            clientFactory
                .newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build(),
        anonymousReadClient =
            clientFactory
                .newAnonymousReadBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build(),
    )

        private val commentsClient =
            anonymousReadClient.newBuilder()
                .callTimeout(COMMUNITY_COMMENT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
        private val communityDetailClient =
            anonymousReadClient.newBuilder()
                .callTimeout(detailCallTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
        private val publicDetailClient =
            anonymousReadClient.newBuilder()
                .callTimeout(detailCallTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
        private val steamProfiles = ConcurrentHashMap<SteamProfileCacheKey, SteamWebProfile>()
        private val steamProfileGeneration = AtomicLong()
        private val detailSeeds = ConcurrentHashMap<DetailSeedKey, DetailSeed>()
        private val dataSourceEpoch = AtomicLong()
        @Volatile private var activeDataSource: SteamWorkshopDataSource? = null

        override fun peekDetailSeed(workshopId: Long): WorkshopDetail? {
            val source = activeDataSource ?: return null
            val key = DetailSeedKey(dataSourceEpoch.get(), source, workshopId)
            return detailSeeds[key]
                ?.takeIf { System.nanoTime() - it.storedAtNanos <= DETAIL_SEED_TTL_NANOS }
                ?.detail
        }

        override suspend fun invalidateDiscoverCache() {
            steamProfileGeneration.incrementAndGet()
            dataSourceEpoch.incrementAndGet()
            steamProfiles.clear()
            detailSeeds.clear()
        }

        private fun selectDataSource(source: SteamWorkshopDataSource): Long = synchronized(detailSeeds) {
            if (activeDataSource != source) {
                activeDataSource = source
                dataSourceEpoch.incrementAndGet()
                detailSeeds.clear()
            }
            dataSourceEpoch.get()
        }

        private fun rememberDetail(source: SteamWorkshopDataSource, epoch: Long, detail: WorkshopDetail) {
            if (activeDataSource != source || dataSourceEpoch.get() != epoch) return
            detailSeeds[DetailSeedKey(epoch, source, detail.summary.id)] = DetailSeed(detail, System.nanoTime())
            if (detailSeeds.size > MAX_DETAIL_SEEDS) {
                detailSeeds.entries.minByOrNull { it.value.storedAtNanos }?.let { detailSeeds.remove(it.key, it.value) }
            }
        }

        private fun rememberPage(source: SteamWorkshopDataSource, epoch: Long, page: WorkshopPage) {
            page.items.forEach { summary ->
                rememberDetail(source, epoch, WorkshopDetail(summary = summary, fileSizeBytes = summary.fileSizeBytes))
            }
        }

        override suspend fun browse(query: WorkshopBrowseQuery): WorkshopPage =
            withContext(Dispatchers.IO) {
                val normalizedQuery = query.normalized()
                val preferences = settingsRepository.preferences.first()
                val source = preferences.steamWorkshopDataSource
                val epoch = selectDataSource(source)
                if (
                    normalizedQuery.sort == WorkshopSort.FRIENDS_FAVORITES ||
                    normalizedQuery.sort == WorkshopSort.FRIENDS_CREATED
                ) {
                    return@withContext unifiedWorkshopRepository.browsePublic(normalizedQuery)
                        ?: error("A signed-in Steam session is required for friend Workshop activity")
                }
                require(
                    preferences.steamWorkshopDataSource != SteamWorkshopDataSource.COMMUNITY_HTML ||
                        (normalizedQuery.createdAfterEpochSeconds == null &&
                            normalizedQuery.createdBeforeEpochSeconds == null),
                ) {
                    "Community HTML does not support created-date Workshop queries"
                }
                val steamApiKey = preferences.steamApiKey.trim()
                val page = when (source) {
                    SteamWorkshopDataSource.COMMUNITY_HTML -> browseViaCommunity(normalizedQuery)
                    SteamWorkshopDataSource.WEB_API -> {
                        val workshopId = normalizedQuery.searchText.workshopSearchIdOrNull()
                        require(steamApiKey.isNotEmpty()) {
                            "The Steam Web API data source requires an API key"
                        }
                        if (workshopId != null) {
                            val detail = getPublishedFileDetails(listOf(workshopId), steamApiKey).firstOrNull()
                                ?: error("Steam Web API did not return this Workshop item")
                            WorkshopPage(
                                items = listOf(enrichDetailAuthor(detail, steamApiKey).summary),
                                page = normalizedQuery.page,
                                hasNextPage = false,
                                totalCount = 1,
                                totalPages = 1,
                            )
                        } else {
                            enrichPageAuthors(
                                if (normalizedQuery.creatorId != null) {
                                    browseViaSteamApiAuthor(normalizedQuery, steamApiKey)
                                } else {
                                    browseViaSteamApi(normalizedQuery, steamApiKey)
                                },
                                steamApiKey,
                            )
                        }
                    }

                    SteamWorkshopDataSource.CM_WEBSOCKET ->
                        unifiedWorkshopRepository.browsePublic(normalizedQuery)
                            ?: error("Failed to establish a Steam CM WebSocket session")
                }
                rememberPage(source, epoch, page)
                page
            }

        override suspend fun getDetail(workshopId: Long): WorkshopDetail =
            withContext(Dispatchers.IO) {
                require(workshopId > 0L) { "Invalid Workshop item ID" }
                val startedAt = System.nanoTime()
                val preferences = settingsRepository.preferences.first()
                val source = preferences.steamWorkshopDataSource
                val epoch = selectDataSource(source)
                val seed = peekDetailSeed(workshopId)
                Log.d(LOG_TAG, "detail begin id=$workshopId source=$source seed=${seed != null}")
                if (seed != null) {
                    Log.d(LOG_TAG, "detail seed id=$workshopId ms=${elapsedMs(startedAt)}")
                }
                val steamApiKey = preferences.steamApiKey.trim()
                val freshDetail = when (source) {
                    SteamWorkshopDataSource.COMMUNITY_HTML -> getCommunityDetail(workshopId)

                    SteamWorkshopDataSource.WEB_API -> {
                        val loaded =
                            getPublishedFileDetails(listOf(workshopId), steamApiKey).firstOrNull()
                                ?: error("Steam Web API did not return this Workshop item")
                        enrichDetailAuthor(loaded, steamApiKey)
                    }

                    SteamWorkshopDataSource.CM_WEBSOCKET ->
                        when (val outcome = unifiedWorkshopRepository.getPublicDetailResult(workshopId)) {
                            is CmPublicDetailResult.Available -> outcome.detail
                            CmPublicDetailResult.NotFound ->
                                error("Steam CM reports this Workshop item was not found or is not publicly accessible")
                            is CmPublicDetailResult.Unavailable ->
                                error("Steam CM is temporarily unavailable; retry this item")
                        }
                }
                val detail = seed?.let { mergeFreshDetailWithSeed(freshDetail, it) } ?: freshDetail
                rememberDetail(source, epoch, detail)
                Log.d(LOG_TAG, "detail fresh id=$workshopId ms=${elapsedMs(startedAt)}")
                detail
            }

        override suspend fun getComments(
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String?,
        ): WorkshopCommentPage =
            withContext(Dispatchers.IO) {
                require(workshopId > 0L) { "Invalid Workshop item ID" }
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
                            ?: error("Steam CM comments require a Steam login")
                }
            }

        override suspend fun getDetails(workshopIds: List<Long>): List<WorkshopDetail> =
            withContext(Dispatchers.IO) {
                val ids = workshopIds.filter { it > 0L }.distinct()
                if (ids.isEmpty()) return@withContext emptyList()
                val preferences = settingsRepository.preferences.first()
                val source = preferences.steamWorkshopDataSource
                val epoch = selectDataSource(source)
                val steamApiKey = preferences.steamApiKey.trim()
                val details = when (source) {
                    SteamWorkshopDataSource.COMMUNITY_HTML -> ids.map { getCommunityDetail(it) }
                    SteamWorkshopDataSource.WEB_API -> getPublishedFileDetails(ids, steamApiKey).map { enrichDetailAuthor(it, steamApiKey) }
                    SteamWorkshopDataSource.CM_WEBSOCKET -> {
                        val batch = unifiedWorkshopRepository.getPublicDetailsResult(ids)
                        if (batch.missingCount > 0) {
                            Log.w(LOG_TAG, "Steam CM batch returned partial details missing=${batch.missingCount}")
                        }
                        batch.available
                    }
                }
                details.forEach { rememberDetail(source, epoch, it) }
                details
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
                    SteamWorkshopDataSource.COMMUNITY_HTML -> getCommunityDetail(workshopId).creatorId
                    SteamWorkshopDataSource.WEB_API ->
                        getPublishedFileDetails(listOf(workshopId), steamApiKey).firstOrNull()?.creatorId
                }
            return creatorId?.filter(Char::isDigit)?.takeIf(String::isNotBlank)
                ?: error("Failed to determine the author of this Workshop item")
        }

        private suspend fun requestCommunityCommentsPage(
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String,
        ): WorkshopCommentPage = coroutineScope {
            suspend fun request(routeOwnerId: String, url: String): WorkshopCommentPage =
                requestCommunityCommentsRoute(
                    url = url,
                    workshopId = workshopId,
                    start = start,
                    count = count,
                    ownerId = routeOwnerId,
                    creatorId = ownerId,
                ) ?: throw IOException("Steam Community returned no comment data")

            suspend fun attempt(block: suspend () -> WorkshopCommentPage): Result<WorkshopCommentPage> =
                try {
                    Result.success(block())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Result.failure(error)
                }

            val primary = async {
                attempt {
                    request(ownerId, "$COMMUNITY_COMMENTS_HTML_URL/$ownerId/$workshopId/")
                }
            }
            delay(COMMUNITY_COMMENT_HEDGE_DELAY_MS)
            if (primary.isCompleted) {
                primary.await().getOrNull()?.let { return@coroutineScope it }
            }
            val backup = async {
                attempt {
                    request("", "$COMMUNITY_COMMENTS_HTML_URL/$workshopId/-1/")
                }
            }
            if (primary.isCompleted) return@coroutineScope backup.await().getOrThrow()

            val first = select<Pair<Boolean, Result<WorkshopCommentPage>>> {
                primary.onAwait { true to it }
                backup.onAwait { false to it }
            }
            first.second.getOrNull()?.let { page ->
                if (first.first) backup.cancel() else primary.cancel()
                return@coroutineScope page
            }
            if (first.first) backup.await().getOrThrow() else primary.await().getOrThrow()
        }

        private suspend fun requestCommunityCommentsRoute(
            url: String,
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String,
            creatorId: String,
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
            val request =
                Request
                    .Builder()
                    .url(url)
                    .post(form)
                    .header("Accept", "application/json,text/javascript,*/*;q=0.01")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                    .header("Origin", "https://steamcommunity.com")
                    .header("Referer", buildDetailUrl(workshopId))
                    .header("User-Agent", USER_AGENT)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
            val payload =
                JSONObject(
                    commentsClient.newCall(request).awaitBoundedBody(
                        failureMessage = "Steam Community comment request failed",
                        maxBytes = MAX_COMMUNITY_COMMENT_BYTES,
                    ),
                )
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
            if (comments.isEmpty() && total != 0) {
                throw IOException("Steam Community comments schema returned no rows for total=${total ?: "unknown"}")
            }
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
                commentsClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Steam public comment request failed: HTTP ${response.code}")
                    }
                    val result = response.header("X-EResult")?.toIntOrNull()
                    val body = response.body.string()
                    if (result != null && result != RESULT_OK) {
                        throw IOException("Steam public comment request failed: EResult $result")
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
                        item.copy(
                            author = profile.displayName,
                            authorAvatarUrl = profile.avatarUrl,
                            authorPlaceholder = WorkshopAuthorPlaceholder.NONE,
                        )
                    },
            )
        }

        private fun enrichDetailAuthor(
            detail: WorkshopDetail,
            steamApiKey: String,
        ): WorkshopDetail {
            if (detail.summary.authorPlaceholder == WorkshopAuthorPlaceholder.NONE) return detail
            val creatorId = detail.creatorId ?: detail.summary.creatorId ?: return detail
            val profile = loadSteamProfiles(setOf(creatorId), steamApiKey)[creatorId] ?: return detail
            return detail.copy(
                summary =
                    detail.summary.copy(
                        author = profile.displayName,
                        authorAvatarUrl = profile.avatarUrl,
                        authorPlaceholder = WorkshopAuthorPlaceholder.NONE,
                    ),
            )
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
                        comment.copy(
                            author = profile.displayName,
                            avatarUrl = profile.avatarUrl,
                            isAuthorPlaceholder = false,
                        )
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
            val generation = steamProfileGeneration.get()
            val keys = validIds.associateWith { steamId -> SteamProfileCacheKey(generation, steamId) }
            val missing = validIds.filter { steamId -> steamProfiles[keys.getValue(steamId)] == null }
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
                            if (generation == steamProfileGeneration.get()) {
                                steamProfiles[SteamProfileCacheKey(generation, steamId)] =
                                    SteamWebProfile(
                                        displayName = displayName,
                                        avatarUrl = player.optString("avatarfull").takeIf(String::isNotBlank),
                                    )
                            }
                        }
                    }
                }
            }
            return validIds.mapNotNull { steamId -> steamProfiles[keys.getValue(steamId)]?.let { steamId to it } }.toMap()
        }

        private fun browseViaCommunity(normalizedQuery: WorkshopBrowseQuery): WorkshopPage =
            parseCommunityHtmlBrowse(get(buildBrowseUrl(normalizedQuery)), normalizedQuery)

        private fun browseViaSteamApi(
            normalizedQuery: WorkshopBrowseQuery,
            steamApiKey: String,
        ): WorkshopPage {
            val body = getSteamApi(buildSteamApiBrowseUrl(normalizedQuery, steamApiKey), steamApiKey)
            val details = CommunityWorkshopParser.parseDetails(body)
            val items =
                details
                    .filter { item -> normalizedQuery.matchesSteamWallpaper(item.summary) }
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
            val page = WorkshopPage(
                items = items.map(WorkshopDetail::summary),
                page = normalizedQuery.page,
                hasNextPage =
                    details.size >= normalizedQuery.pageSize &&
                        (totalPages == null || normalizedQuery.page < totalPages),
                totalCount = total,
                totalPages = totalPages,
            )
            return page
        }

        private fun browseViaSteamApiAuthor(
            normalizedQuery: WorkshopBrowseQuery,
            steamApiKey: String,
        ): WorkshopPage {
            val body = getSteamApi(buildSteamApiAuthorBrowseUrl(normalizedQuery, steamApiKey), steamApiKey)
            val details = CommunityWorkshopParser.parseDetails(body)
            val response = JSONObject(body).optJSONObject("response")
            val total = response?.optInt("total", 0)?.takeIf { it > 0 }
            return WorkshopPage(
                items = details.filter { normalizedQuery.matchesSteamWallpaper(it.summary) }.take(normalizedQuery.pageSize).map(WorkshopDetail::summary),
                page = normalizedQuery.page,
                hasNextPage = details.size >= normalizedQuery.pageSize,
                totalCount = total,
                totalPages = total?.toMaximumPage(normalizedQuery.pageSize),
            )
        }

        private fun Int.toMaximumPage(pageSize: Int): Int {
            val safePageSize = pageSize.coerceAtLeast(1).toLong()
            return ((coerceAtLeast(0).toLong() + safePageSize - 1L) / safePageSize)
                .coerceIn(1L, MAX_DIRECT_BROWSE_PAGE.toLong())
                .toInt()
        }

        private fun getPublishedFileDetails(
            ids: List<Long>,
            steamApiKey: String = "",
            requestClient: OkHttpClient = client,
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
                requestClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Steam public details request failed: HTTP ${response.code}")
                    }
                    response.body.byteStream().use { input ->
                        input.readBytesBounded(MAX_PUBLIC_DETAILS_BYTES).toString(Charsets.UTF_8)
                    }
                }
            return CommunityWorkshopParser.parseDetails(body)
        }

        private suspend fun getCommunityDetail(workshopId: Long): WorkshopDetail = coroutineScope {
            val html = async {
                timedDetailBranch(workshopId, "html") {
                    requestCommunityHtmlDetail(workshopId)
                }
            }
            val json = async {
                timedDetailBranch(workshopId, "json") {
                    requestPublicJsonDetail(workshopId)
                }
            }
            val first = select<DetailBranchResult> {
                html.onAwait { DetailBranchResult(DetailBranch.HTML, it) }
                json.onAwait { DetailBranchResult(DetailBranch.JSON, it) }
            }
            val second = if (first.branch == DetailBranch.HTML) json else html
            val firstDetail = first.result.getOrNull()
            if (firstDetail != null) {
                val secondResult = if (second.isCompleted) second.await() else null
                val secondDetail = secondResult?.getOrNull()
                if (secondDetail == null) {
                    second.cancel(CancellationException("Community detail already has a successful branch"))
                    return@coroutineScope firstDetail
                }
                return@coroutineScope if (first.branch == DetailBranch.HTML) {
                    mergeCommunityDetail(firstDetail, secondDetail)
                } else {
                    mergeCommunityDetail(secondDetail, firstDetail)
                }
            }

            val secondResult = second.await()
            val secondDetail = secondResult.getOrNull()
            if (secondDetail != null) return@coroutineScope secondDetail
            throw IOException(
                "Steam Community detail HTML and JSON requests failed",
                first.result.exceptionOrNull() ?: secondResult.exceptionOrNull(),
            )
        }

        private suspend fun requestCommunityHtmlDetail(workshopId: Long): WorkshopDetail {
            val request =
                Request
                    .Builder()
                    .url(buildDetailUrl(workshopId))
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                    .header("User-Agent", USER_AGENT)
                    .build()
            val body = communityDetailClient.newCall(request).awaitBoundedBody(
                failureMessage = "Steam Community detail request failed",
                maxBytes = MAX_COMMUNITY_HTML_BYTES,
            )
            return parseCommunityHtmlDetail(body, workshopId)
        }

        private suspend fun requestPublicJsonDetail(workshopId: Long): WorkshopDetail {
            val form =
                FormBody
                    .Builder()
                    .add("itemcount", "1")
                    .add("includetags", "true")
                    .add("short_description", "true")
                    .add("publishedfileids[0]", workshopId.toString())
                    .build()
            val request =
                Request
                    .Builder()
                    .url(PUBLISHED_FILE_DETAILS_URL)
                    .post(form)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .build()
            val body = publicDetailClient.newCall(request).awaitBoundedBody(
                failureMessage = "Steam public details request failed",
                maxBytes = MAX_PUBLIC_DETAILS_BYTES,
            )
            return CommunityWorkshopParser.parseDetails(body).firstOrNull()
                ?: throw IOException("Steam public details returned no usable detail")
        }

        private suspend inline fun <T> timedDetailBranch(
            workshopId: Long,
            branch: String,
            crossinline request: suspend () -> T,
        ): Result<T> {
            val startedAt = System.nanoTime()
            return try {
                request().also {
                    Log.d(LOG_TAG, "detail $branch id=$workshopId ms=${elapsedMs(startedAt)} outcome=success")
                }.let(Result.Companion::success)
            } catch (error: CancellationException) {
                Log.d(LOG_TAG, "detail $branch id=$workshopId ms=${elapsedMs(startedAt)} outcome=cancelled")
                throw error
            } catch (error: Throwable) {
                Log.w(
                    LOG_TAG,
                    "detail $branch id=$workshopId ms=${elapsedMs(startedAt)} " +
                        "outcome=failure error=${error.javaClass.simpleName}",
                )
                Result.failure(error)
            }
        }

        private fun get(
            url: String,
            requestClient: OkHttpClient = client,
        ): String {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                    .header("User-Agent", USER_AGENT)
                    .build()
            return requestClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Steam Workshop browse request failed: HTTP ${response.code}")
                }
                response.body.byteStream().use { input ->
                    input.readBytesBounded(MAX_COMMUNITY_HTML_BYTES).toString(Charsets.UTF_8)
                }
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
                    throw IOException("Steam Web API query failed: HTTP ${response.code}")
                }
                response.body.string()
            }
        }
    }

private suspend fun Call.awaitBoundedBody(
    failureMessage: String,
    maxBytes: Int,
): String =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    error: IOException,
                ) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    try {
                        response.use {
                            if (!it.isSuccessful) {
                                throw IOException("$failureMessage: HTTP ${it.code}")
                            }
                            val body =
                                it.body.byteStream().use { input ->
                                    input.readBytesBounded(maxBytes).toString(Charsets.UTF_8)
                                }
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.success(body))
                            }
                        }
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                    }
                }
            },
        )
    }

internal fun mergeFreshDetailWithSeed(
    fresh: WorkshopDetail,
    seed: WorkshopDetail,
): WorkshopDetail {
    require(fresh.summary.id == seed.summary.id) { "Workshop detail IDs do not match" }
    val freshSummary = fresh.summary
    val seedSummary = seed.summary
    val freshIdentity = fresh.contentIdentity
    val seedIdentity = seed.contentIdentity
    return fresh.copy(
        summary =
            freshSummary.copy(
                title = freshSummary.title.ifBlank { seedSummary.title },
                author = freshSummary.author.ifBlank { seedSummary.author },
                creatorId = freshSummary.creatorId ?: seedSummary.creatorId,
                authorAvatarUrl = freshSummary.authorAvatarUrl ?: seedSummary.authorAvatarUrl,
                previewUrl = freshSummary.previewUrl ?: seedSummary.previewUrl,
                type =
                    if (freshSummary.type != com.wallhub.android.core.model.WorkshopType.UNKNOWN) {
                        freshSummary.type
                    } else {
                        seedSummary.type
                    },
                tags = freshSummary.tags.ifEmpty { seedSummary.tags },
                subscriptions = freshSummary.subscriptions ?: seedSummary.subscriptions,
                favorites = freshSummary.favorites ?: seedSummary.favorites,
                views = freshSummary.views ?: seedSummary.views,
                fileSizeBytes = freshSummary.fileSizeBytes ?: seedSummary.fileSizeBytes,
                subscriptionState =
                    freshSummary.subscriptionState.takeUnless { it == SubscriptionState.UNKNOWN }
                        ?: seedSummary.subscriptionState,
                favoriteState =
                    freshSummary.favoriteState.takeUnless { it == FavoriteState.UNKNOWN }
                        ?: seedSummary.favoriteState,
                isTitlePlaceholder =
                    if (freshSummary.title.isNotBlank()) {
                        freshSummary.isTitlePlaceholder
                    } else {
                        seedSummary.isTitlePlaceholder
                    },
                authorPlaceholder =
                    if (freshSummary.author.isNotBlank()) {
                        freshSummary.authorPlaceholder
                    } else {
                        seedSummary.authorPlaceholder
                    },
            ),
        description = fresh.description.ifBlank { seed.description },
        fileSizeBytes = fresh.fileSizeBytes ?: seed.fileSizeBytes,
        previewMediaUrl = fresh.previewMediaUrl ?: seed.previewMediaUrl,
        canPlay = fresh.canPlay || seed.canPlay,
        createdAt = fresh.createdAt ?: seed.createdAt,
        updatedAt = fresh.updatedAt ?: seed.updatedAt,
        subscriptions = fresh.subscriptions ?: seed.subscriptions,
        creatorId = fresh.creatorId ?: seed.creatorId,
        contentIdentity =
            freshIdentity.copy(
                consumerAppId = freshIdentity.consumerAppId ?: seedIdentity.consumerAppId,
                depotId = freshIdentity.depotId ?: seedIdentity.depotId,
                manifestId = freshIdentity.manifestId ?: seedIdentity.manifestId,
                fileName = freshIdentity.fileName ?: seedIdentity.fileName,
                directUrl = freshIdentity.directUrl ?: seedIdentity.directUrl,
            ),
    )
}

internal fun mergeCommunityDetail(html: WorkshopDetail, json: WorkshopDetail): WorkshopDetail {
    require(html.summary.id == json.summary.id) { "Workshop detail IDs do not match" }
    val fileSize = json.fileSizeBytes ?: json.summary.fileSizeBytes ?: html.fileSizeBytes ?: html.summary.fileSizeBytes
    val preview = json.previewMediaUrl ?: json.summary.previewUrl ?: html.previewMediaUrl ?: html.summary.previewUrl
    val htmlSummary = html.summary
    val jsonSummary = json.summary
    return json.copy(
        summary = jsonSummary.copy(
            title = htmlSummary.title.ifBlank { jsonSummary.title },
            author = htmlSummary.author.ifBlank { jsonSummary.author },
            creatorId = htmlSummary.creatorId ?: jsonSummary.creatorId,
            authorAvatarUrl = htmlSummary.authorAvatarUrl ?: jsonSummary.authorAvatarUrl,
            previewUrl = preview,
            type = if (htmlSummary.type != com.wallhub.android.core.model.WorkshopType.UNKNOWN) htmlSummary.type else jsonSummary.type,
            tags = htmlSummary.tags.ifEmpty { jsonSummary.tags },
            fileSizeBytes = fileSize,
            isTitlePlaceholder = htmlSummary.title.isBlank() && jsonSummary.isTitlePlaceholder,
            authorPlaceholder = if (htmlSummary.author.isNotBlank()) htmlSummary.authorPlaceholder else jsonSummary.authorPlaceholder,
        ),
        description = html.description.ifBlank { json.description },
        fileSizeBytes = fileSize,
        previewMediaUrl = preview,
        creatorId = html.creatorId ?: json.creatorId,
    )
}

private fun elapsedMs(startedAtNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

private enum class DetailBranch {
    HTML,
    JSON,
}

private data class DetailBranchResult(
    val branch: DetailBranch,
    val result: Result<WorkshopDetail>,
)

private data class DetailSeedKey(
    val epoch: Long,
    val source: SteamWorkshopDataSource,
    val workshopId: Long,
)

private data class DetailSeed(
    val detail: WorkshopDetail,
    val storedAtNanos: Long,
)

private data class SteamProfileCacheKey(
    val generation: Long,
    val steamId: String,
)

private const val MAX_PUBLIC_DETAILS_BYTES = 1024 * 1024
private const val MAX_COMMUNITY_COMMENT_BYTES = 2 * 1024 * 1024
private const val COMMUNITY_COMMENT_HEDGE_DELAY_MS = 400L
private const val COMMUNITY_COMMENT_CALL_TIMEOUT_MS = 6_000L
private const val COMMUNITY_DETAIL_CALL_TIMEOUT_MS = 6_000L
private const val MAX_DETAIL_SEEDS = 256
private val DETAIL_SEED_TTL_NANOS = TimeUnit.MINUTES.toNanos(30)
private const val LOG_TAG = "WallHubWorkshop"
