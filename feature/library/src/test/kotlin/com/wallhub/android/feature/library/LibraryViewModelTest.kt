package com.wallhub.android.feature.library

import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopPage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @Test
    fun `retryable session refresh restores then loads after sign in`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val sessions = FakeSteamSessionRepository(
                SteamSessionState(
                    phase = SteamSessionPhase.RESTORABLE,
                    accountName = "test-account",
                    hasStoredSession = true,
                ),
            )
            val workshop = FakeAccountWorkshopRepository()
            val viewModel = LibraryViewModel(sessions, workshop)
            advanceUntilIdle()

            viewModel.refresh()
            assertEquals(1, sessions.restoreRequests)
            assertEquals(0, workshop.browseRequests)

            sessions.mutableSession.value = SteamSessionState(
                phase = SteamSessionPhase.SIGNED_IN,
                accountName = "test-account",
                hasStoredSession = true,
            )
            advanceUntilIdle()

            assertEquals(1, workshop.browseRequests)
            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(SteamSessionPhase.SIGNED_IN, viewModel.uiState.value.session.phase)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `session loss cancels library loading flags`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val sessions = FakeSteamSessionRepository(
                SteamSessionState(
                    phase = SteamSessionPhase.SIGNED_IN,
                    accountName = "test-account",
                    hasStoredSession = true,
                ),
            )
            val browseStarted = CompletableDeferred<Unit>()
            val neverCompletes = CompletableDeferred<WorkshopPage>()
            val workshop = FakeAccountWorkshopRepository {
                browseStarted.complete(Unit)
                neverCompletes.await()
            }
            val viewModel = LibraryViewModel(sessions, workshop)
            runCurrent()
            browseStarted.await()
            assertTrue(viewModel.uiState.value.isLoading)

            sessions.mutableSession.value = SteamSessionState(
                phase = SteamSessionPhase.RESTORABLE,
                accountName = "test-account",
                hasStoredSession = true,
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertFalse(state.isRefreshing)
            assertFalse(state.isLoadingMore)
            assertEquals(SteamSessionPhase.RESTORABLE, state.session.phase)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class FakeSteamSessionRepository(
    initialState: SteamSessionState,
) : SteamSessionRepository {
    val mutableSession = MutableStateFlow(initialState)
    var restoreRequests = 0

    override val session = mutableSession

    override fun restorePersistedSession() {
        restoreRequests += 1
    }

    override fun login(accountName: String, password: String) = Unit

    override fun submitSteamGuardCode(code: String) = Unit

    override fun useManualSteamGuardFallback() = Unit

    override fun logout() = Unit
}

private class FakeAccountWorkshopRepository(
    private val browse: suspend (AccountWorkshopQuery) -> WorkshopPage = { query ->
        WorkshopPage(
            items = emptyList(),
            page = query.page,
            hasNextPage = false,
        )
    },
) : AccountWorkshopRepository {
    var browseRequests = 0

    override suspend fun browseCollection(query: AccountWorkshopQuery): WorkshopPage {
        browseRequests += 1
        return browse(query)
    }

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

    override suspend fun postComment(workshopId: Long, ownerId: String, text: String) = Unit
}
