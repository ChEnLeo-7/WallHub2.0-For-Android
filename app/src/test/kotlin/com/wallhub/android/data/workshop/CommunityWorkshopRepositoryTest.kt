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
import java.io.IOException
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
    fun `CM null loads public detail`() =
        runBlocking {
            val repository = repositoryWithPublicResponse(200, PUBLIC_DETAIL_JSON)

            val detail = repository.getDetail(WORKSHOP_ID)

            assertEquals(WORKSHOP_ID, detail.summary.id)
            assertEquals("Fallback item", detail.summary.title)
        }

    @Test
    fun `CM fallback rejects an oversized public details response`() {
        val repository = repositoryWithPublicResponse(200, "x".repeat(1024 * 1024 + 1))

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.getDetail(WORKSHOP_ID) }
            }

        assertEquals("Steam manifest response exceeds 1048576 bytes", failure.message)
    }

    @Test
    fun `CM null keeps public detail failure as an error`() {
        val repository = repositoryWithPublicResponse(503, "temporarily unavailable")

        val failure =
            assertThrows(IOException::class.java) {
                runBlocking { repository.getDetail(WORKSHOP_ID) }
            }

        assertEquals("Steam public details request failed: HTTP 503", failure.message)
    }

    private fun repositoryWithPublicResponse(
        status: Int,
        body: String,
    ): CommunityWorkshopRepository {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("test response")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
        return CommunityWorkshopRepository(
            settingsRepository = CmSettingsRepository,
            unifiedWorkshopRepository = UnavailableCmRepository,
            client = client,
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
        val PUBLIC_DETAIL_JSON =
            """
            {"response":{"publishedfiledetails":[{"result":1,"publishedfileid":"$WORKSHOP_ID",
            "title":"Fallback item","creator":"76561198000000000","creator_name":"Creator",
            "short_description":"Loaded from public details","tags":[]}]}}
            """.trimIndent()
    }
}
