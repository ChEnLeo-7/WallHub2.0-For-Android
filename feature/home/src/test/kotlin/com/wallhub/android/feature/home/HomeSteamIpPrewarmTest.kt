package com.wallhub.android.feature.home

import androidx.lifecycle.SavedStateHandle
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.DownloadTaskRepository
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamAccessRepository
import com.wallhub.android.core.model.SteamAccessState
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `initial browse remains blocked until prewarm completes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prewarm = CompletableDeferred<Boolean>()
        try {
            val steamAccess = FakeSteamAccessRepository { prewarm.await() }
            val workshop = FakeWorkshopRepository()
            val viewModel = homeViewModel(workshop, steamAccess)

            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isSteamIpPrewarming)
            assertEquals(0, workshop.browseRequests)

            prewarm.complete(true)
            advanceUntilIdle()

            assertEquals(1, workshop.browseRequests)
            assertFalse(viewModel.uiState.value.isSteamIpPrewarming)
        } finally {
            prewarm.complete(true)
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed prewarm blocks browse and retry starts a new prewarm`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var prewarmReady = false
            val steamAccess = FakeSteamAccessRepository { prewarmReady }
            val workshop = FakeWorkshopRepository()
            val viewModel = homeViewModel(workshop, steamAccess)
            advanceUntilIdle()

            assertEquals(1, steamAccess.prewarmRequests)
            assertEquals(0, workshop.browseRequests)
            assertTrue(viewModel.uiState.value.error?.contains("Steam IP") == true)

            prewarmReady = true
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(2, steamAccess.prewarmRequests)
            assertEquals(1, workshop.browseRequests)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun homeViewModel(
        workshop: WorkshopRepository,
        steamAccess: SteamAccessRepository,
    ) = HomeViewModel(
        workshopRepository = workshop,
        settingsRepository = FakeSettingsRepository(),
        steamAccessRepository = steamAccess,
        downloadTaskRepository = FakeDownloadTaskRepository(),
        savedStateHandle = SavedStateHandle(),
    )
}

private class FakeSettingsRepository : SettingsRepository {
    override val preferences = flowOf(AppPreferences(steamAccessEnabled = true))

    override suspend fun setTheme(theme: ThemePreference) = Unit

    override suspend fun setLanguage(language: AppLanguage) = Unit

    override suspend fun setAccent(accent: AccentPreference, customColor: String?) = Unit

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

    override suspend fun setOutputDirectory(treeUri: String, label: String) = Unit

    override suspend fun clearOutputDirectory() = Unit
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

private class FakeWorkshopRepository : WorkshopRepository {
    var browseRequests = 0

    override suspend fun browse(query: WorkshopBrowseQuery): WorkshopPage {
        browseRequests += 1
        return WorkshopPage(
            items = emptyList(),
            page = query.page,
            hasNextPage = false,
        )
    }

    override suspend fun getDetail(workshopId: Long): WorkshopDetail = error("Not used")

    override suspend fun getComments(
        workshopId: Long,
        start: Int,
        count: Int,
        ownerId: String?,
    ): WorkshopCommentPage = error("Not used")
}

private class FakeDownloadTaskRepository : DownloadTaskRepository {
    override val tasks = flowOf(emptyList<DownloadTask>())

    override suspend fun find(taskId: String): DownloadTask? = null

    override suspend fun upsert(task: DownloadTask) = Unit

    override suspend fun enqueue(request: DownloadRequest): DownloadTask = error("Not used")

    override suspend fun requestAction(taskId: String, action: DownloadAction) = Unit

    override suspend fun reorder(taskIds: List<String>) = Unit

    override suspend fun clearFinishedHistory(): Int = 0
}
