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
import com.wallhub.android.core.model.WorkshopSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelEffectTest {
    @Test
    fun `initial load failure clears loading and refresh retries successfully`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val repository = RetryableWorkshopRepository(failRequests = 1)
                val viewModel =
                    HomeViewModel(
                        workshopRepository = repository,
                        settingsRepository = EmptySettingsRepository,
                        steamAccessRepository = EmptySteamAccessRepository,
                        downloadTaskRepository = EmptyDownloadTaskRepository,
                        savedStateHandle = SavedStateHandle(),
                    )

                awaitLoadCompletion(viewModel)

                assertFalse(viewModel.uiState.value.isInitialLoading)
                assertEquals("offline", viewModel.uiState.value.error)
                assertEquals(1, repository.browseRequests)

                viewModel.onAction(HomeAction.Refresh)
                awaitLoadCompletion(viewModel)

                assertFalse(viewModel.uiState.value.isInitialLoading)
                assertNull(viewModel.uiState.value.error)
                assertEquals(
                    listOf(42L),
                    viewModel.uiState.value.items
                        .map(WorkshopSummary::id),
                )
                assertEquals(2, repository.browseRequests)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `denied storage permission emits one message without storing it in ui state`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val viewModel =
                    HomeViewModel(
                        workshopRepository = EmptyWorkshopRepository,
                        settingsRepository = EmptySettingsRepository,
                        steamAccessRepository = EmptySteamAccessRepository,
                        downloadTaskRepository = EmptyDownloadTaskRepository,
                        savedStateHandle = SavedStateHandle(),
                    )
                val effects = mutableListOf<HomeEffect>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.effects.collect(effects::add)
                }
                val item = WorkshopSummary(id = 42L, title = "test", author = "creator")

                viewModel.onAction(
                    HomeAction.LegacyStoragePermissionResult(item, granted = false),
                )
                advanceUntilIdle()

                assertEquals(1, effects.size)
                assertEquals(
                    "未授予存储权限，无法导出到 Download/WallHub",
                    assertIs<HomeEffect.ShowMessage>(effects.single()).message,
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    private fun TestScope.awaitLoadCompletion(viewModel: HomeViewModel) {
        repeat(MAX_LOAD_AWAIT_ATTEMPTS) {
            advanceUntilIdle()
            if (!viewModel.uiState.value.isInitialLoading) return
            Thread.sleep(LOAD_AWAIT_INTERVAL_MILLIS)
        }
        error("Home load did not complete")
    }

    private companion object {
        const val MAX_LOAD_AWAIT_ATTEMPTS = 100
        const val LOAD_AWAIT_INTERVAL_MILLIS = 5L
    }
}

private class RetryableWorkshopRepository(
    private var failRequests: Int,
) : WorkshopRepository {
    var browseRequests = 0

    override suspend fun browse(query: WorkshopBrowseQuery): WorkshopPage {
        browseRequests += 1
        if (failRequests > 0) {
            failRequests -= 1
            error("offline")
        }
        return WorkshopPage(
            items = listOf(WorkshopSummary(id = 42L, title = "Recovered", author = "creator")),
            page = query.page,
            hasNextPage = false,
        )
    }

    override suspend fun getDetail(workshopId: Long): WorkshopDetail =
        WorkshopDetail(
            WorkshopSummary(workshopId, "test", "creator"),
        )

    override suspend fun getComments(
        workshopId: Long,
        start: Int,
        count: Int,
        ownerId: String?,
    ): WorkshopCommentPage =
        WorkshopCommentPage(
            comments = emptyList(),
            start = start,
            count = count,
            nextStart = start,
            hasMore = false,
        )
}

private object EmptyWorkshopRepository : WorkshopRepository {
    override suspend fun browse(query: WorkshopBrowseQuery): WorkshopPage =
        WorkshopPage(items = emptyList(), page = query.page, hasNextPage = false)

    override suspend fun getDetail(workshopId: Long): WorkshopDetail = WorkshopDetail(WorkshopSummary(workshopId, "test", "creator"))

    override suspend fun getComments(
        workshopId: Long,
        start: Int,
        count: Int,
        ownerId: String?,
    ): WorkshopCommentPage =
        WorkshopCommentPage(
            comments = emptyList(),
            start = start,
            count = count,
            nextStart = start,
            hasMore = false,
        )
}

private object EmptySettingsRepository : SettingsRepository {
    override val preferences: Flow<AppPreferences> = MutableStateFlow(AppPreferences())

    override suspend fun setTheme(theme: ThemePreference) = Unit

    override suspend fun setLanguage(language: AppLanguage) = Unit

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

private object EmptySteamAccessRepository : SteamAccessRepository {
    override val state = MutableStateFlow(SteamAccessState())

    override suspend fun prewarmSteamIp(dataSource: SteamWorkshopDataSource): Boolean = true

    override fun refresh() = Unit
}

private object EmptyDownloadTaskRepository : DownloadTaskRepository {
    override val tasks: Flow<List<DownloadTask>> = MutableStateFlow(emptyList())

    override suspend fun find(taskId: String): DownloadTask? = null

    override suspend fun upsert(task: DownloadTask) = Unit

    override suspend fun enqueue(request: DownloadRequest): DownloadTask = error("not expected")

    override suspend fun requestAction(
        taskId: String,
        action: DownloadAction,
    ) = Unit

    override suspend fun reorder(taskIds: List<String>) = Unit

    override suspend fun clearFinishedHistory(): Int = 0
}
