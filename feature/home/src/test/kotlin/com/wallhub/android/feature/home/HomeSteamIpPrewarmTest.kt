package com.wallhub.android.feature.home

import com.wallhub.android.core.model.SteamAccessRepository
import com.wallhub.android.core.model.SteamAccessState
import com.wallhub.android.core.model.SteamWorkshopDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class HomeSteamIpPrewarmTest {
    @Test
    fun `empty initial Community load waits when acceleration is enabled`() {
        assertTrue(
            shouldPrewarmSteamIp(
                steamAccessEnabled = true,
                dataSource = SteamWorkshopDataSource.COMMUNITY_HTML,
                append = false,
                hasItems = false,
            ),
        )
    }

    @Test
    fun `empty initial Web API load waits when acceleration is enabled`() {
        assertTrue(
            shouldPrewarmSteamIp(
                steamAccessEnabled = true,
                dataSource = SteamWorkshopDataSource.WEB_API,
                append = false,
                hasItems = false,
            ),
        )
    }

    @Test
    fun `CM and disabled acceleration do not wait for HTTPS prewarm`() {
        assertFalse(
            shouldPrewarmSteamIp(
                steamAccessEnabled = true,
                dataSource = SteamWorkshopDataSource.CM_WEBSOCKET,
                append = false,
                hasItems = false,
            ),
        )
        assertFalse(
            shouldPrewarmSteamIp(
                steamAccessEnabled = false,
                dataSource = SteamWorkshopDataSource.COMMUNITY_HTML,
                append = false,
                hasItems = false,
            ),
        )
    }

    @Test
    fun `existing results and pagination do not wait for prewarm`() {
        assertFalse(
            shouldPrewarmSteamIp(
                steamAccessEnabled = true,
                dataSource = SteamWorkshopDataSource.COMMUNITY_HTML,
                append = false,
                hasItems = true,
            ),
        )
        assertFalse(
            shouldPrewarmSteamIp(
                steamAccessEnabled = true,
                dataSource = SteamWorkshopDataSource.COMMUNITY_HTML,
                append = true,
                hasItems = false,
            ),
        )
    }

    @Test
    fun `prewarm and pull-to-refresh indicators are mutually exclusive`() {
        val prewarming = HomeUiState(
            isInitialLoading = true,
            isSteamIpPrewarming = true,
        ).loadingIndicatorVisibility()
        assertTrue(prewarming.showSteamIpPrewarm)
        assertFalse(prewarming.showPullToRefresh)

        val refreshing = HomeUiState(
            isInitialLoading = true,
            isSteamIpPrewarming = false,
        ).loadingIndicatorVisibility()
        assertFalse(refreshing.showSteamIpPrewarm)
        assertTrue(refreshing.showPullToRefresh)

        val pageLoading = HomeUiState(
            isInitialLoading = false,
            isPageLoading = true,
            isSteamIpPrewarming = false,
        ).loadingIndicatorVisibility()
        assertFalse(pageLoading.showSteamIpPrewarm)
        assertTrue(pageLoading.showPullToRefresh)
    }

    @Test
    fun `prewarm gate blocks the following request until completion`() = runBlocking {
        val prewarm = CompletableDeferred<Boolean>()
        val steamAccess = FakeSteamAccessRepository { prewarm.await() }
        var browseRequests = 0
        val load = async(start = CoroutineStart.UNDISPATCHED) {
            requireSteamIpPrewarm(
                shouldPrewarm = true,
                dataSource = SteamWorkshopDataSource.COMMUNITY_HTML,
                steamAccessRepository = steamAccess,
                failureMessage = "prewarm failed",
            )
            browseRequests += 1
        }

        assertEquals(1, steamAccess.prewarmRequests)
        assertEquals(0, browseRequests)

        prewarm.complete(true)
        load.await()

        assertEquals(1, browseRequests)
    }

    @Test
    fun `failed prewarm blocks the following request and retry probes again`() = runBlocking {
        var prewarmReady = false
        val steamAccess = FakeSteamAccessRepository { prewarmReady }
        var browseRequests = 0

        val firstError = try {
            requireSteamIpPrewarm(
                shouldPrewarm = true,
                dataSource = SteamWorkshopDataSource.COMMUNITY_HTML,
                steamAccessRepository = steamAccess,
                failureMessage = "prewarm failed",
            )
            browseRequests += 1
            null
        } catch (error: IllegalStateException) {
            error
        }

        assertEquals("prewarm failed", firstError?.message)
        assertEquals(1, steamAccess.prewarmRequests)
        assertEquals(0, browseRequests)

        prewarmReady = true
        requireSteamIpPrewarm(
            shouldPrewarm = true,
            dataSource = SteamWorkshopDataSource.COMMUNITY_HTML,
            steamAccessRepository = steamAccess,
            failureMessage = "prewarm failed",
        )
        browseRequests += 1

        assertEquals(2, steamAccess.prewarmRequests)
        assertEquals(1, browseRequests)
    }
}

private class FakeSteamAccessRepository(
    private val prewarm: suspend (SteamWorkshopDataSource) -> Boolean,
) : SteamAccessRepository {
    override val state = MutableStateFlow(SteamAccessState())
    var prewarmRequests = 0

    override suspend fun prewarmSteamIp(dataSource: SteamWorkshopDataSource): Boolean {
        prewarmRequests += 1
        return prewarm(dataSource)
    }

    override fun refresh() = Unit
}
