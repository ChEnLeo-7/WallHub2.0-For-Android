package com.wallhub.android.data.workshop

import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.SteamWorkshopDataSource
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun `HTML detail and batch only request Community and never API or CM`() = runBlocking {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("steamcommunity.com", request.url.host)
            assertEquals("GET", request.method)
            requests.add(request.url.encodedPath)
            val html = if (request.url.encodedPath.contains("browse")) {
                CommunityWorkshopHtmlTest.browseHtml()
            } else {
                CommunityWorkshopHtmlTest.DETAIL_HTML
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(html.toResponseBody("text/html".toMediaType())).build()
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
        assertEquals(listOf(42L, 43L), repository.getDetails(listOf(42, 43)).map { it.summary.id })
        assertEquals(3, requests.size)
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
