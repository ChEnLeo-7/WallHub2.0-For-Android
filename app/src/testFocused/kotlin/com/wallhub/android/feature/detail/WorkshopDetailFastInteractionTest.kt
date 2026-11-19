package com.wallhub.android.feature.detail

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import com.wallhub.android.R
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.DownloadRequest
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopDownloads
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopVideoOpenRequest
import com.wallhub.android.core.model.WorkshopVideoStreamRepository
import com.wallhub.android.core.model.WorkshopVideoStreamSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkshopDetailFastInteractionTest {
    @Test
    fun `connected unknown interaction starts subscribe before read completes and ignores stale read`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fixture = fixture()
            runCurrent()
            kotlinx.coroutines.withTimeout(5_000L) { fixture.account.readStarted.await() }
            runCurrent()
            assertEquals(SubscriptionState.UNKNOWN, fixture.viewModel.uiState.value.interaction.subscriptionState)

            fixture.viewModel.toggleSubscription()
            runCurrent()

            assertEquals(listOf(true), fixture.account.subscriptionRequests)
            assertEquals("steam-user", fixture.account.mutationAccountNames.single())
            assertTrue(fixture.viewModel.uiState.value.isUpdatingInteraction)
            assertEquals(SubscriptionState.SUBSCRIBED, fixture.viewModel.uiState.value.interaction.subscriptionState)

            fixture.account.mutation.complete(WorkshopInteraction(subscriptionState = SubscriptionState.SUBSCRIBED))
            runCurrent()
            fixture.account.read.complete(WorkshopInteraction(subscriptionState = SubscriptionState.NOT_SUBSCRIBED))
            advanceUntilIdle()

            assertEquals(SubscriptionState.SUBSCRIBED, fixture.viewModel.uiState.value.interaction.subscriptionState)
            assertEquals(R.string.detail_subscribe_submitted, (fixture.viewModel.uiState.value.interactionMessage as DetailUiText.Resource).resourceId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `connected unknown interaction starts favorite before read completes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fixture = fixture()
            runCurrent()

            fixture.viewModel.toggleFavorite()
            runCurrent()

            assertEquals(listOf(true), fixture.account.favoriteRequests)
            assertEquals("steam-user", fixture.account.mutationAccountNames.single())
            assertTrue(fixture.viewModel.uiState.value.isUpdatingInteraction)
            assertEquals(FavoriteState.FAVORITED, fixture.viewModel.uiState.value.interaction.favoriteState)

            fixture.account.mutation.complete(WorkshopInteraction(favoriteState = FavoriteState.FAVORITED))
            advanceUntilIdle()
            assertEquals(FavoriteState.FAVORITED, fixture.viewModel.uiState.value.interaction.favoriteState)
            assertEquals(R.string.detail_favorite_submitted, (fixture.viewModel.uiState.value.interactionMessage as DetailUiText.Resource).resourceId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed fast subscription rolls back optimistic state without success message`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fixture = fixture()
            runCurrent()
            fixture.viewModel.toggleSubscription()
            runCurrent()
            assertTrue(fixture.viewModel.uiState.value.isUpdatingInteraction)

            fixture.account.mutation.completeExceptionally(IllegalStateException("mutation failed"))
            advanceUntilIdle()

            assertEquals(SubscriptionState.UNKNOWN, fixture.viewModel.uiState.value.interaction.subscriptionState)
            assertFalse(fixture.viewModel.uiState.value.isUpdatingInteraction)
            assertNull(fixture.viewModel.uiState.value.interactionMessage?.takeIf {
                it == DetailUiText.Resource(R.string.detail_subscribe_submitted)
            })
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(): Fixture {
        val account = ControlledAccountRepository()
        val viewModel = WorkshopDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("workshopId" to WORKSHOP_ID)),
            applicationContext = TestContext,
            workshopRepository = EmptyWorkshopRepository,
            accountWorkshopRepository = account,
            steamSessionRepository = ConnectedSteamSessionRepository,
            workshopDownloads = EmptyWorkshopDownloads,
            settingsRepository = EmptySettingsRepository,
            videoStreamRepository = EmptyVideoRepository,
        )
        return Fixture(account, viewModel)
    }

    private data class Fixture(
        val account: ControlledAccountRepository,
        val viewModel: WorkshopDetailViewModel,
    )

    private companion object {
        const val WORKSHOP_ID = 73L
        val TestContext: Context = ContextWrapper(null)
    }
}

private class ControlledAccountRepository : AccountWorkshopRepository {
    override val collectionCounts = MutableStateFlow(com.wallhub.android.core.model.AccountWorkshopCounts())
    override fun activeAccountId() = 123L
    override fun activeAccountName() = "steam-user"
    val readStarted = CompletableDeferred<Unit>()
    val read = CompletableDeferred<WorkshopInteraction>()
    val mutation = CompletableDeferred<WorkshopInteraction>()
    val subscriptionRequests = mutableListOf<Boolean>()
    val favoriteRequests = mutableListOf<Boolean>()
    val mutationAccountNames = mutableListOf<String>()

    override suspend fun getInteraction(workshopId: Long): WorkshopInteraction {
        readStarted.complete(Unit)
        return withContext(NonCancellable) { read.await() }
    }

    override suspend fun setSubscribedForCurrentAccount(
        expectedAccountName: String,
        workshopId: Long,
        subscribed: Boolean,
        summary: WorkshopSummary?,
    ): WorkshopInteraction {
        mutationAccountNames += expectedAccountName
        subscriptionRequests += subscribed
        return mutation.await()
    }

    override suspend fun setFavoritedForCurrentAccount(
        expectedAccountName: String,
        workshopId: Long,
        favorited: Boolean,
        summary: WorkshopSummary?,
    ): WorkshopInteraction {
        mutationAccountNames += expectedAccountName
        favoriteRequests += favorited
        return mutation.await()
    }

    override suspend fun browseCollection(query: AccountWorkshopQuery) = error("not used")
    override suspend fun resolveAuthorDisplayName(workshopId: Long): String? = null
    override suspend fun setSubscribed(workshopId: Long, subscribed: Boolean, summary: WorkshopSummary?) = error("unexpected legacy subscription API")
    override suspend fun setFavorited(workshopId: Long, favorited: Boolean, summary: WorkshopSummary?) = error("unexpected legacy favorite API")
    override suspend fun postComment(workshopId: Long, ownerId: String, text: String) = Unit
}

private object EmptyWorkshopRepository : WorkshopRepository {
    override suspend fun browse(query: com.wallhub.android.core.model.WorkshopBrowseQuery) = WorkshopPage(emptyList(), query.page, false)
    override suspend fun getDetail(workshopId: Long) = error("not used")
    override suspend fun getComments(workshopId: Long, start: Int, count: Int, ownerId: String?) = WorkshopCommentPage(emptyList(), start, count, start, hasMore = false)
}

private object ConnectedSteamSessionRepository : SteamSessionRepository {
    override val session = MutableStateFlow(SteamSessionState(phase = SteamSessionPhase.SIGNED_IN, accountName = "steam-user", isConnected = true))
    override fun restorePersistedSession() = Unit
    override fun login(accountName: String, password: String) = Unit
    override fun submitSteamGuardCode(code: String) = Unit
    override fun useManualSteamGuardFallback() = Unit
    override fun logout() = Unit
}

private object EmptyWorkshopDownloads : WorkshopDownloads {
    override val tasks: Flow<List<DownloadTask>> = MutableStateFlow(emptyList())
    override suspend fun find(taskId: String): DownloadTask? = null
    override suspend fun enqueue(request: DownloadRequest) = error("not used")
    override suspend fun persistConversionPlan(taskId: String, planJson: String?, planVersion: Int) = Unit
    override suspend fun persistConversionReport(taskId: String, reportJson: String?) = Unit
    override suspend fun prewarm(workshopId: Long) = Unit
    override suspend fun requestAction(taskId: String, action: com.wallhub.android.core.model.DownloadAction) = Unit
    override suspend fun requestExport(taskId: String, format: com.wallhub.android.core.model.ExportFormat) = Unit
    override suspend fun requestReconversion(taskId: String, preset: com.wallhub.android.core.model.MpkgConversionPreset, preserveAudio: Boolean) = Unit
    override suspend fun reorder(taskIds: List<String>) = Unit
    override suspend fun clearFinishedHistory(): Int = 0
    override suspend fun clearCompletedHistory(): Int = 0
    override suspend fun retryFailedTasks(): Int = 0
}

private object EmptySettingsRepository : SettingsRepository {
    override val preferences = MutableStateFlow(AppPreferences())
    override suspend fun setTheme(theme: com.wallhub.android.core.model.ThemePreference) = Unit
    override suspend fun setAccent(accent: com.wallhub.android.core.model.AccentPreference, customColor: String?) = Unit
    override suspend fun setHomePreferences(pageSize: Int, columns: Int, multiSelect: Boolean, cardAction: com.wallhub.android.core.model.HomeCardAction, matureContentEnabled: Boolean) = Unit
    override suspend fun setDownloadPreferences(maxConcurrentDownloads: Int, chunkDownloadConcurrency: Int, proxyUrl: String, mediaCacheLimitMb: Int) = Unit
    override suspend fun setOutputDirectory(treeUri: String, label: String) = Unit
    override suspend fun clearOutputDirectory() = Unit
}

private object EmptyVideoRepository : WorkshopVideoStreamRepository {
    override suspend fun open(request: WorkshopVideoOpenRequest): WorkshopVideoStreamSession = error("not used")
}
