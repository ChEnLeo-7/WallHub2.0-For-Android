package com.wallhub.android

import com.wallhub.android.core.model.AccountWorkshopCounts
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AccountCollectionCountCoordinatorTest {
    @Test
    fun `cold start and account changes refresh counts only for distinct valid sessions`() =
        runTest {
            val sessions = MutableStateFlow(signedIn("account-a"))
            val repository = CountCoordinatorRepository()
            val collector = launch { collectAccountCollectionCounts(sessions, repository) }
            runCurrent()

            sessions.value = signedIn("account-a")
            runCurrent()
            sessions.value = SteamSessionState(phase = SteamSessionPhase.EXPIRED, accountName = "account-a")
            runCurrent()
            sessions.value = signedIn("account-b")
            runCurrent()

            assertEquals(listOf("account-a", "account-b"), repository.refreshAccounts)
            assertEquals(3, repository.invalidations)
            collector.cancel()
        }

    @Test
    fun `account switch invalidates before a cancellation resistant old refresh returns`() =
        runTest {
            val sessions = MutableStateFlow(signedIn("account-a"))
            val repository = CountCoordinatorRepository(blockFirstRefresh = true)
            val collector = launch { collectAccountCollectionCounts(sessions, repository) }
            runCurrent()
            assertEquals(1, repository.invalidations)

            sessions.value = signedIn("account-b")
            runCurrent()
            assertEquals(2, repository.invalidations)
            assertEquals(listOf("account-a"), repository.refreshAccounts)

            repository.releaseFirstRefresh.complete(Unit)
            runCurrent()
            assertEquals(listOf("account-a", "account-b"), repository.refreshAccounts)
            collector.cancel()
        }

    private fun signedIn(accountName: String) =
        SteamSessionState(
            phase = SteamSessionPhase.SIGNED_IN,
            accountName = accountName,
            hasStoredSession = true,
            isConnected = true,
        )
}

private class CountCoordinatorRepository(
    private val blockFirstRefresh: Boolean = false,
) : AccountWorkshopRepository {
    override val collectionCounts = MutableStateFlow(AccountWorkshopCounts())
    val refreshAccounts = mutableListOf<String>()
    var invalidations = 0
    val releaseFirstRefresh = CompletableDeferred<Unit>()

    override suspend fun refreshCollectionCounts(accountName: String) {
        refreshAccounts += accountName
        if (blockFirstRefresh && refreshAccounts.size == 1) {
            withContext(NonCancellable) { releaseFirstRefresh.await() }
        }
    }

    override fun invalidateCollectionCounts() {
        invalidations += 1
        collectionCounts.value = AccountWorkshopCounts()
    }

    override suspend fun browseCollection(query: AccountWorkshopQuery) = WorkshopPage(emptyList(), 1, false)
    override suspend fun resolveAuthorDisplayName(workshopId: Long): String? = null
    override suspend fun getInteraction(workshopId: Long) = WorkshopInteraction()
    override suspend fun setSubscribed(workshopId: Long, subscribed: Boolean, summary: WorkshopSummary?) = WorkshopInteraction()
    override suspend fun setFavorited(workshopId: Long, favorited: Boolean, summary: WorkshopSummary?) = WorkshopInteraction()
    override suspend fun postComment(workshopId: Long, ownerId: String, text: String) = Unit
}
