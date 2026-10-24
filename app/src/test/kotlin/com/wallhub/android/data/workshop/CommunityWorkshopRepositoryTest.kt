package com.wallhub.android.data.workshop

import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopVideoOpenRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityWorkshopRepositoryTest {
    @Test
    fun `friend activity bypasses Community and Web API settings and uses only unified account route`() =
        runBlocking {
            val requests = mutableListOf<String>()
            val unified =
                object : SteamUnifiedWorkshopRepository by UnavailableCmRepository {
                    override suspend fun browsePublic(query: WorkshopBrowseQuery): WorkshopPage {
                        assertEquals(WorkshopSort.FRIENDS_FAVORITES, query.sort)
                        return WorkshopPage(emptyList(), page = 1, hasNextPage = false)
                    }
                }
            val settings =
                object : SettingsRepository by CmSettingsRepository {
                    override val preferences =
                        MutableStateFlow(
                            AppPreferences(
                                steamWorkshopDataSource = SteamWorkshopDataSource.WEB_API,
                                steamApiKey = "must-not-be-used",
                            ),
                        )
                }
            val client =
                OkHttpClient.Builder().addInterceptor { chain ->
                    requests += chain.request().url.toString()
                    throw AssertionError("Friend activity must not use HTTP")
                }.build()

            val page = CommunityWorkshopRepository(settings, unified, client).browse(
                WorkshopBrowseQuery(sort = WorkshopSort.FRIENDS_FAVORITES),
            )

            assertEquals(emptyList<Long>(), page.items.map { it.id })
            assertEquals(emptyList<String>(), requests)
        }

    @Test
    fun `community comments prefer canonical owner POST and fall back after positive total schema failure`() =
        runBlocking {
            val requests = mutableListOf<Pair<String, String>>()
            val settings =
                object : SettingsRepository by CmSettingsRepository {
                    override val preferences =
                        MutableStateFlow(AppPreferences(steamWorkshopDataSource = SteamWorkshopDataSource.COMMUNITY_HTML))
                }
            val client =
                OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    requests += request.method to request.url.encodedPath
                    val body =
                        if (requests.size == 1) {
                            """{"success":1,"total_count":2,"comments_html":"<div>changed schema</div>"}"""
                        } else {
                            """{"success":1,"total_count":0,"comments_html":""}"""
                        }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                        .message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
                }.build()

            val page = CommunityWorkshopRepository(settings, UnavailableCmRepository, client).getComments(
                workshopId = WORKSHOP_ID,
                start = 0,
                count = 10,
                ownerId = "76561198000000000",
            )

            assertEquals(0, page.total)
            assertTrue(page.comments.isEmpty())
            assertEquals("POST", requests.first().first)
            assertEquals("/comment/PublishedFile_Public/render/76561198000000000/$WORKSHOP_ID/", requests.first().second)
            assertTrue(requests.size >= 2)
        }

    @Test
    fun `public comments positive total with no parsed rows is schema failure`() {
        val payload = org.json.JSONObject("""{"total_count":3,"comments":[]}""")

        assertThrows(java.io.IOException::class.java) {
            parsePublicCommentsPage(payload, 0, 10, "76561198000000000")
        }
    }

    @Test
    fun `community merge keeps HTML prose and fills JSON metrics identity and size in both models`() {
        val html = parseCommunityHtmlDetail(CommunityWorkshopHtmlTest.DETAIL_HTML, 42)
        val json = CommunityWorkshopParser.parseDetails(
            """{"response":{"publishedfiledetails":[{"result":1,"publishedfileid":"42",
            "title":"JSON title","creator":"76561198000000000","creator_name":"JSON author",
            "short_description":"JSON description","preview_url":"https://images.steamusercontent.com/ugc/42/P/",
            "subscriptions":"7","favorited":"8","views":"9","file_size":"123",
            "time_created":"10","time_updated":"11","consumer_app_id":"431960","hcontent_file":"999","tags":[]}]}}""",
        ).single()

        val merged = mergeCommunityDetail(html, json)

        assertEquals("Title & more", merged.summary.title)
        assertEquals("Creator", merged.summary.author)
        assertEquals("First\nNested\nLast", merged.description)
        assertEquals(7L, merged.summary.subscriptions)
        assertEquals(8L, merged.summary.favorites)
        assertEquals(9L, merged.summary.views)
        assertEquals(123L, merged.fileSizeBytes)
        assertEquals(123L, merged.summary.fileSizeBytes)
        assertEquals(10L, merged.createdAt)
        assertEquals(11L, merged.updatedAt)
        assertEquals(999L, merged.contentIdentity.manifestId)
    }

    @Test
    fun `fast HTML refresh preserves precise fields from the visible seed`() {
        val fresh = parseCommunityHtmlDetail(CommunityWorkshopHtmlTest.DETAIL_HTML, 42)
        val seed =
            CommunityWorkshopParser.parseDetails(
                """{"response":{"publishedfiledetails":[{"result":1,"publishedfileid":"42",
                "title":"Seed title","creator":"76561198000000000","creator_name":"Seed author",
                "preview_url":"https://images.steamusercontent.com/ugc/42/P/","subscriptions":"7",
                "favorited":"8","views":"9","file_size":"123","time_created":"10",
                "time_updated":"11","consumer_app_id":"431960","hcontent_file":"999","tags":[]}]}}""",
            ).single().let { detail ->
                detail.copy(
                    summary =
                        detail.summary.copy(
                            subscriptionState = SubscriptionState.SUBSCRIBED,
                            favoriteState = FavoriteState.FAVORITED,
                        ),
                )
            }

        val merged = mergeFreshDetailWithSeed(fresh, seed)

        assertEquals("Title & more", merged.summary.title)
        assertEquals("First\nNested\nLast", merged.description)
        assertEquals(7L, merged.summary.subscriptions)
        assertEquals(8L, merged.summary.favorites)
        assertEquals(9L, merged.summary.views)
        assertEquals(123L, merged.summary.fileSizeBytes)
        assertEquals(123L, merged.fileSizeBytes)
        assertEquals(10L, merged.createdAt)
        assertEquals(11L, merged.updatedAt)
        assertEquals(999L, merged.contentIdentity.manifestId)
        assertEquals(SubscriptionState.SUBSCRIBED, merged.summary.subscriptionState)
        assertEquals(FavoriteState.FAVORITED, merged.summary.favoriteState)
    }

    @Test
    fun `slow JSON cannot hold successful HTML to the shared client timeout`() =
        runBlocking {
            MockWebServer().use { server ->
                server.dispatcher = detailDispatcher(
                    html = MockResponse().setBody(CommunityWorkshopHtmlTest.DETAIL_HTML),
                    json =
                        MockResponse()
                            .setBody(PUBLIC_DETAIL_JSON)
                            .setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS),
                )
                val repository = communityRepositoryFor(server, detailCallTimeoutMs = 300)

                val startedAt = System.nanoTime()
                val detail = repository.getDetail(WORKSHOP_ID)
                val elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

                assertEquals("Title & more", detail.summary.title)
                assertTrue("successful HTML should return without waiting for the slow JSON", elapsedMs < 1_000)
            }
        }

    @Test
    fun `failed HTML degrades to successful public JSON`() =
        runBlocking {
            MockWebServer().use { server ->
                server.dispatcher = detailDispatcher(
                    html = MockResponse().setResponseCode(503),
                    json = MockResponse().setBody(PUBLIC_DETAIL_JSON),
                )

                val detail = communityRepositoryFor(server).getDetail(WORKSHOP_ID)

                assertEquals("Fallback item", detail.summary.title)
                assertEquals("Loaded from public details", detail.description)
            }
        }

    @Test
    fun `failed public JSON degrades to successful HTML`() =
        runBlocking {
            MockWebServer().use { server ->
                server.dispatcher = detailDispatcher(
                    html = MockResponse().setBody(CommunityWorkshopHtmlTest.DETAIL_HTML),
                    json = MockResponse().setResponseCode(503),
                )

                val detail = communityRepositoryFor(server).getDetail(WORKSHOP_ID)

                assertEquals("Title & more", detail.summary.title)
                assertEquals("First\nNested\nLast", detail.description)
            }
        }

    @Test
    fun `public detail retains video content identity for the play request`() {
        val detail =
            CommunityWorkshopParser.parseDetails(
                """
                {"response":{"publishedfiledetails":[{"result":1,"publishedfileid":"42",
                "consumer_app_id":431960,"consumer_depot_id":431961,"hcontent_file":"999",
                "title":"Clip","file_size":"123","filename":"clip.mp4",
                "file_url":"https://cdn.example/clip.mp4","tags":[{"tag":"video"}]}]}}
                """.trimIndent(),
            ).single()

        assertEquals(
            WorkshopVideoOpenRequest(
                workshopId = 42L,
                title = "Clip",
                expectedSizeBytes = 123L,
                consumerAppId = 431960L,
                depotId = 431961L,
                manifestId = 999L,
                fileName = "clip.mp4",
                directUrl = "https://cdn.example/clip.mp4",
            ),
            WorkshopVideoOpenRequest.from(detail),
        )
    }

    @Test
    fun `failed detail row cannot shift the following items unsigned manifest`() {
        val details =
            CommunityWorkshopParser.parseDetails(
                """
                {"response":{"publishedfiledetails":[
                  {"result":9,"publishedfileid":"1"},
                  {"result":1,"publishedfileid":"2","consumer_app_id":431960,
                   "hcontent_file":"18000000000000000001","title":"Second","tags":[{"tag":"video"}]}
                ]}}
                """.trimIndent(),
            )

        assertEquals(1, details.size)
        assertEquals(2L, details.single().summary.id)
        assertEquals(18_000_000_000_000_000_001UL.toLong(), details.single().contentIdentity.manifestId)
        assertEquals(431960L, details.single().contentIdentity.depotId)
    }

    @Test
    fun `CM batch returns only CM details without cross-source fallback`() =
        runBlocking {
            val cmDetail = CommunityWorkshopParser.parseDetails(PUBLIC_DETAIL_JSON).single()
            val repository =
                repositoryWithPublicResponse(
                    200,
                    PUBLIC_SECOND_DETAIL_JSON,
                    unified = PartialCmRepository(cmDetail),
                )

            val details = repository.getDetails(listOf(WORKSHOP_ID, SECOND_WORKSHOP_ID))

            assertEquals(listOf(WORKSHOP_ID), details.map { it.summary.id })
        }

    @Test
    fun `CM unavailable detail fails without calling another source`() {
        val repository = repositoryWithPublicResponse(200, PUBLIC_DETAIL_JSON)

        val failure =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.getDetail(WORKSHOP_ID) }
            }

        assertEquals("Steam CM is temporarily unavailable; retry this item", failure.message)
    }

    @Test
    fun `HTML detail combines Community page with keyless public JSON and never uses CM`() = runBlocking {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add("${request.method} ${request.url.host}${request.url.encodedPath}")
            val (body, mediaType) = if (request.url.host == "api.steampowered.com") {
                Thread.sleep(25L)
                val apiRequestNumber = requests.count { it.startsWith("POST api.steampowered.com") }
                val id = if (apiRequestNumber <= 2) 42L else 43L
                PUBLIC_DETAIL_JSON.replace(WORKSHOP_ID.toString(), id.toString()) to "application/json"
            } else {
                assertEquals("steamcommunity.com", request.url.host)
                assertEquals("GET", request.method)
                CommunityWorkshopHtmlTest.DETAIL_HTML to "text/html"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(body.toResponseBody(mediaType.toMediaType())).build()
        }.build()
        val settings = object : SettingsRepository by CmSettingsRepository {
            override val preferences = MutableStateFlow(AppPreferences(
                steamWorkshopDataSource = SteamWorkshopDataSource.COMMUNITY_HTML,
                steamApiKey = "must-not-be-used",
            ))
        }
        val unified = object : SteamUnifiedWorkshopRepository by UnavailableCmRepository {
            override suspend fun getPublicDetail(workshopId: Long): WorkshopDetail? = error("Unexpected CM request")
            override suspend fun browsePublic(query: WorkshopBrowseQuery): WorkshopPage? = error("Unexpected CM request")
        }
        val repository = CommunityWorkshopRepository(settings, unified, client)
        assertEquals("Title & more", repository.getDetail(42).summary.title)
        assertEquals("Title & more", repository.peekDetailSeed(42)?.summary?.title)
        assertEquals(listOf(42L, 43L), repository.getDetails(listOf(42, 43)).map { it.summary.id })
        assertEquals(6, requests.size)
        assertEquals(3, requests.count { it.startsWith("GET steamcommunity.com") })
        assertEquals(3, requests.count { it.startsWith("POST api.steampowered.com") })
        repository.invalidateDiscoverCache()
        assertEquals(null, repository.peekDetailSeed(42))
    }

    private fun communityRepositoryFor(
        server: MockWebServer,
        detailCallTimeoutMs: Long = 3_000L,
    ): CommunityWorkshopRepository {
        val serverUrl = server.url("/")
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val original = chain.request()
                    val redirected =
                        original
                            .newBuilder()
                            .url(
                                original.url
                                    .newBuilder()
                                    .scheme(serverUrl.scheme)
                                    .host(serverUrl.host)
                                    .port(serverUrl.port)
                                    .build(),
                            ).header("X-Test-Origin", original.url.host)
                            .build()
                    chain.proceed(redirected)
                }.build()
        val settings =
            object : SettingsRepository by CmSettingsRepository {
                override val preferences =
                    MutableStateFlow(AppPreferences(steamWorkshopDataSource = SteamWorkshopDataSource.COMMUNITY_HTML))
            }
        return CommunityWorkshopRepository(
            settingsRepository = settings,
            unifiedWorkshopRepository = UnavailableCmRepository,
            client = client,
            detailCallTimeoutMs = detailCallTimeoutMs,
        )
    }

    private fun detailDispatcher(
        html: MockResponse,
        json: MockResponse,
    ): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.getHeader("X-Test-Origin")) {
                    "steamcommunity.com" -> html
                    "api.steampowered.com" -> json
                    else -> MockResponse().setResponseCode(404)
                }
        }

    private fun repositoryWithPublicResponse(
        status: Int,
        body: String,
        unified: SteamUnifiedWorkshopRepository = UnavailableCmRepository,
    ): CommunityWorkshopRepository {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    throw AssertionError("CM must not call HTTP: ${chain.request().url.host}")
                }.build()
        return CommunityWorkshopRepository(
            settingsRepository = CmSettingsRepository,
            unifiedWorkshopRepository = unified,
            client = client,
        )
    }

    private object ApiSettingsRepository : SettingsRepository by CmSettingsRepository {
        override val preferences: Flow<AppPreferences> =
            MutableStateFlow(
                AppPreferences(
                    steamWorkshopDataSource = SteamWorkshopDataSource.WEB_API,
                    steamApiKey = "test-key",
                ),
            )
    }

    private object CmSettingsRepository : SettingsRepository {
        override val preferences: Flow<AppPreferences> =
            MutableStateFlow(
                AppPreferences(steamWorkshopDataSource = SteamWorkshopDataSource.CM_WEBSOCKET),
            )

        override suspend fun setTheme(theme: ThemePreference) = Unit

        override suspend fun setAccent(
            accent: AccentPreference,
            customColor: String?,
        ) = Unit

        override suspend fun setHomePreferences(
            pageSize: Int,
            columns: Int,
            multiSelect: Boolean,
            cardAction: HomeCardAction,
            matureContentEnabled: Boolean,
        ) = Unit

        override suspend fun setDownloadPreferences(
            maxConcurrentDownloads: Int,
            chunkDownloadConcurrency: Int,
            proxyUrl: String,
            mediaCacheLimitMb: Int,
        ) = Unit

        override suspend fun setOutputDirectory(
            treeUri: String,
            label: String,
        ) = Unit

        override suspend fun clearOutputDirectory() = Unit
    }

    private class PartialCmRepository(
        private val detail: WorkshopDetail,
    ) : SteamUnifiedWorkshopRepository {
        override suspend fun browsePublic(query: WorkshopBrowseQuery): WorkshopPage? = null

        override suspend fun getPublicDetail(workshopId: Long): WorkshopDetail? =
            detail.takeIf { it.summary.id == workshopId }

        override suspend fun getPublicDetails(workshopIds: List<Long>): List<WorkshopDetail> =
            listOfNotNull(detail.takeIf { it.summary.id in workshopIds })

        override suspend fun getAuthenticatedComments(
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String,
        ): WorkshopCommentPage? = null
    }

    private object UnavailableCmRepository : SteamUnifiedWorkshopRepository {
        override suspend fun browsePublic(query: WorkshopBrowseQuery): WorkshopPage? = null

        override suspend fun getPublicDetail(workshopId: Long): WorkshopDetail? = null

        override suspend fun getAuthenticatedComments(
            workshopId: Long,
            start: Int,
            count: Int,
            ownerId: String,
        ): WorkshopCommentPage? = null
    }

    private companion object {
        const val WORKSHOP_ID = 123456L
        const val SECOND_WORKSHOP_ID = 654321L
        val PUBLIC_DETAIL_JSON =
            """
            {"response":{"publishedfiledetails":[{"result":1,"publishedfileid":"$WORKSHOP_ID",
            "title":"Fallback item","creator":"76561198000000000","creator_name":"Creator",
            "short_description":"Loaded from public details","tags":[]}]}}
            """.trimIndent()
        val PUBLIC_SECOND_DETAIL_JSON =
            """
            {"response":{"publishedfiledetails":[{"result":1,"publishedfileid":"$SECOND_WORKSHOP_ID",
            "title":"Public missing item","creator":"76561198000000001","creator_name":"Creator",
            "short_description":"Filled from public details","tags":[]}]}}
            """.trimIndent()
    }
}
