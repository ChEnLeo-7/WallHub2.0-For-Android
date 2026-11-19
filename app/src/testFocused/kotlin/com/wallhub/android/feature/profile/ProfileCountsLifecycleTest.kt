package com.wallhub.android.feature.profile

import com.wallhub.android.core.model.AccountWorkshopCounts
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.SteamAppPlaytime
import com.wallhub.android.core.model.SteamPlaytimeRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileCountsLifecycleTest {
    @Test
    fun `opening and reopening profile does not request collection counts`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val sessions = FakeProfileSessionRepository(signedIn("account-a"))
                val account = CountingAccountWorkshopRepository()
                val viewModel = ProfileViewModel(sessions, account, FakeSteamPlaytimeRepository())

                viewModel.setVisible(true)
                advanceUntilIdle()
                viewModel.setVisible(false)
                viewModel.setVisible(true)
                advanceUntilIdle()

                assertEquals(0, account.browseRequests)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `sign out and account switch never show counts from the previous account`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val sessions = FakeProfileSessionRepository(signedIn("account-a"))
                val account = CountingAccountWorkshopRepository()
                val viewModel = ProfileViewModel(sessions, account, FakeSteamPlaytimeRepository())
                account.mutableCounts.value =
                    AccountWorkshopCounts(
                        accountName = "account-a",
                        subscriptions = 11,
                        favorites = 12,
                        isValid = true,
                    )
                advanceUntilIdle()
                assertEquals(11, assertIs<ProfileCountState.Loaded>(viewModel.counts.value.subscriptions).count)

                sessions.mutableSession.value = SteamSessionState(phase = SteamSessionPhase.SIGNED_OUT)
                advanceUntilIdle()
                assertIs<ProfileCountState.SignInRequired>(viewModel.counts.value.subscriptions)
                account.mutableCounts.value =
                    AccountWorkshopCounts(
                        accountName = "account-a",
                        subscriptions = 99,
                        favorites = 99,
                        isValid = true,
                    )
                sessions.mutableSession.value = signedIn("account-b")
                advanceUntilIdle()
                assertIs<ProfileCountState.Loading>(viewModel.counts.value.subscriptions)

                account.mutableCounts.value =
                    AccountWorkshopCounts(
                        accountName = "account-b",
                        subscriptions = 21,
                        favorites = 22,
                        isValid = true,
                    )
                advanceUntilIdle()
                assertEquals(21, assertIs<ProfileCountState.Loaded>(viewModel.counts.value.subscriptions).count)
                assertEquals(22, assertIs<ProfileCountState.Loaded>(viewModel.counts.value.favorites).count)
                assertEquals(0, account.browseRequests)
            } finally {
                Dispatchers.resetMain()
            }
        }

    private fun signedIn(accountName: String) =
        SteamSessionState(
            phase = SteamSessionPhase.SIGNED_IN,
            accountName = accountName,
            hasStoredSession = true,
            isConnected = true,
        )
}

private class FakeProfileSessionRepository(initial: SteamSessionState) : SteamSessionRepository {
    val mutableSession = MutableStateFlow(initial)
    override val session = mutableSession
    override fun restorePersistedSession() = Unit
    override fun login(accountName: String, password: String) = Unit
    override fun submitSteamGuardCode(code: String) = Unit
    override fun useManualSteamGuardFallback() = Unit
    override fun logout() = Unit
}

private class CountingAccountWorkshopRepository : AccountWorkshopRepository {
    val mutableCounts = MutableStateFlow(AccountWorkshopCounts())
    override val collectionCounts = mutableCounts
    var browseRequests = 0

    override suspend fun browseCollection(query: AccountWorkshopQuery): WorkshopPage {
        browseRequests += 1
        return WorkshopPage(emptyList(), query.page, false, totalCount = 0)
    }

    override suspend fun resolveAuthorDisplayName(workshopId: Long): String? = null
    override suspend fun getInteraction(workshopId: Long) = WorkshopInteraction()
    override suspend fun setSubscribed(workshopId: Long, subscribed: Boolean, summary: WorkshopSummary?) = WorkshopInteraction()
    override suspend fun setFavorited(workshopId: Long, favorited: Boolean, summary: WorkshopSummary?) = WorkshopInteraction()
    override suspend fun postComment(workshopId: Long, ownerId: String, text: String) = Unit
}

private class FakeSteamPlaytimeRepository : SteamPlaytimeRepository {
    override suspend fun getAppPlaytime(appId: Int): SteamAppPlaytime? = null
}
