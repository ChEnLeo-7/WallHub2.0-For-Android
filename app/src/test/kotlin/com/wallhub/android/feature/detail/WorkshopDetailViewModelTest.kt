package com.wallhub.android.feature.detail

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.DownloadAction
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.DownloadTaskRepository
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopVideoStreamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class WorkshopDetailViewModelTest {
    @Test
    fun `detail load failure clears loading and reload retries successfully`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val repository = RetryableDetailRepository(failRequests = 1)
                val viewModel =
                    WorkshopDetailViewModel(
                        savedStateHandle = SavedStateHandle(mapOf("workshopId" to WORKSHOP_ID)),
                        applicationContext = TestContext,
                        workshopRepository = repository,
                        accountWorkshopRepository = EmptyAccountWorkshopRepository,
                        steamSessionRepository = EmptySteamSessionRepository,
                        downloadTaskRepository = EmptyDownloadTaskRepository,
                        settingsRepository = EmptySettingsRepository,
                        videoStreamRepository = EmptyVideoStreamRepository,
                    )

                advanceUntilIdle()

                assertFalse(viewModel.uiState.value.isLoading)
                assertEquals("offline", viewModel.uiState.value.error)
                assertEquals(1, repository.detailRequests)

                viewModel.reload()
                advanceUntilIdle()

                assertFalse(viewModel.uiState.value.isLoading)
                assertNull(viewModel.uiState.value.error)
                assertEquals(
                    WORKSHOP_ID,
                    viewModel.uiState.value.detail
                        ?.summary
                        ?.id,
                )
                assertEquals(2, repository.detailRequests)
            } finally {
                Dispatchers.resetMain()
            }
        }

    private companion object {
        const val WORKSHOP_ID = 42L
        val TestContext: Context = ContextWrapper(null)
    }
}

private class RetryableDetailRepository(
    private var failRequests: Int,
) : WorkshopRepository {
    var detailRequests = 0

    override suspend fun browse(query: com.wallhub.android.core.model.WorkshopBrowseQuery): WorkshopPage =
        WorkshopPage(items = emptyList(), page = query.page, hasNextPage = false)

    override suspend fun getDetail(workshopId: Long): WorkshopDetail {
        detailRequests += 1
        if (failRequests > 0) {
            failRequests -= 1
            error("offline")
        }
        return WorkshopDetail(
            summary = WorkshopSummary(id = workshopId, title = "Recovered", author = "creator"),
        )
    }

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

private object EmptyAccountWorkshopRepository : AccountWorkshopRepository {
    override suspend fun browseCollection(query: AccountWorkshopQuery): WorkshopPage =
        WorkshopPage(items = emptyList(), page = query.page, hasNextPage = false)

    override suspend fun resolveAuthorDisplayName(workshopId: Long): String? = null

    override suspend fun getInteraction(workshopId: Long): WorkshopInteraction = WorkshopInteraction()

    override suspend fun setSubscribed(
        workshopId: Long,
        subscribed: Boolean,
    ): WorkshopInteraction = WorkshopInteraction()

    override suspend fun setFavorited(
        workshopId: Long,
        favorited: Boolean,
    ): WorkshopInteraction = WorkshopInteraction()

    override suspend fun postComment(
        workshopId: Long,
        ownerId: String,
        text: String,
    ) = Unit
}

private object EmptySteamSessionRepository : SteamSessionRepository {
    override val session = MutableStateFlow(SteamSessionState())

    override fun restorePersistedSession() = Unit

    override fun login(
        accountName: String,
        password: String,
    ) = Unit

    override fun submitSteamGuardCode(code: String) = Unit

    override fun useManualSteamGuardFallback() = Unit

    override fun logout() = Unit
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

private object EmptyVideoStreamRepository : WorkshopVideoStreamRepository {
    override suspend fun open(workshopId: Long) = error("not expected")
}
