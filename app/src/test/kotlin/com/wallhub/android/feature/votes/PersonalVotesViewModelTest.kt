package com.wallhub.android.feature.votes

import com.wallhub.android.core.model.*
import com.wallhub.android.testutil.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalVotesViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun `no account RPC or scan before explicit sync and up is default`() = runTest {
        val fixture = Fixture()
        assertEquals(0, fixture.account.identityReads)
        assertTrue(fixture.account.voteReads.isEmpty())
        assertFalse(fixture.vm.state.value.readerMounted)
        assertEquals(1, fixture.vm.state.value.selectedVote)
        fixture.vm.selectVote(2)
        assertEquals(2, fixture.vm.state.value.selectedVote)
        fixture.vm.nextPage()
        assertEquals(0, fixture.vm.state.value.requestCounter)
    }

    @Test fun `known WE current CM directions count and none and other apps disappear`() = runTest {
        val f = Fixture()
        (1L..4L).forEach { f.public.results[it] = available(it, if (it == 4L) 999L else 431960L) }
        f.account.votes.putAll(mapOf(1L to 1, 2L to 2, 3L to 0))
        f.sync()
        f.page(listOf(1, 2, 3, 4), false)
        assertEquals(listOf(1L, 2L), f.vm.state.value.items.map { it.summary.id })
        assertEquals(listOf(1L, 2L, 3L), f.account.voteReads)
        assertTrue(f.vm.state.value.complete)
        assertEquals(VoteCounts(1, 1, true), f.store.countsByAccountName.value["alice"])
        assertEquals(1, f.public.batches.size)
    }

    @Test fun `unknown missing and vote failures remain unresolved but successful siblings survive`() = runTest {
        val f = Fixture()
        f.public.results[1] = available(1)
        f.public.results[2] = available(2, null)
        f.public.results[3] = CmPublicDetailResult.NotFound
        f.public.results[4] = CmPublicDetailResult.Unavailable("offline")
        f.public.results[5] = available(5)
        f.account.votes[1] = 1
        f.sync()
        f.page(listOf(1, 2, 3, 4, 5), false)
        assertEquals(listOf(1L), f.vm.state.value.items.map { it.summary.id })
        assertEquals(setOf(2L, 3L, 4L, 5L), f.vm.state.value.unresolved)
        assertFalse(f.vm.state.value.complete)
        assertEquals(VoteCounts(1, 0, false), f.store.counts.value[STEAM_ID])
    }

    @Test fun `chunk sixteen and advance only on user next`() = runTest {
        val f = Fixture()
        (1L..17L).forEach { f.public.results[it] = available(it, 999) }
        f.sync()
        f.page((1..17).toList(), true)
        assertEquals(listOf(16, 1), f.public.batches.map { it.size })
        assertEquals(0, f.vm.state.value.requestCounter)
        assertFalse(f.vm.state.value.readerVisible)
        assertTrue(f.vm.state.value.readerMounted)
        assertFalse(f.vm.state.value.complete)
        f.vm.nextPage()
        assertEquals(1, f.vm.state.value.requestCounter)
        f.vm.nextPage()
        assertEquals(1, f.vm.state.value.requestCounter)
    }

    @Test fun `wrong reader or CM account clears results and cache`() = runTest {
        val f = Fixture()
        f.public.results[1] = available(1)
        f.account.votes[1] = 1
        f.sync()
        f.page(listOf(1), true)
        f.vm.nextPage()
        f.account.steamId = "76561198000000002"
        f.page(listOf(2), false)
        assertEquals(PersonalVotesProblem.ACCOUNT_MISMATCH, f.vm.state.value.problem)
        assertTrue(f.vm.state.value.items.isEmpty())
        assertTrue(f.store.counts.value.isEmpty())
        assertFalse(f.vm.state.value.readerMounted)
    }

    @Test fun `account change fences suspended old work and clears process observations`() = runTest {
        val f = Fixture()
        f.public.results[1] = available(1)
        f.account.votes[1] = 1
        val gate = CompletableDeferred<Unit>()
        f.public.gate = gate
        f.sync()
        val oldGeneration = f.vm.state.value.generation
        f.page(listOf(1), false)
        f.sessions.session.value = SteamSessionState(accountName = "bob", hasStoredSession = true)
        gate.complete(Unit)
        f.vm.onPage(oldGeneration, VotesReadPage(STEAM_ID, listOf(VoteRawRow(1, true, false)), false))
        assertEquals("bob", f.vm.state.value.accountName)
        assertTrue(f.vm.state.value.items.isEmpty())
        assertTrue(f.store.counts.value.isEmpty())
        assertFalse(f.vm.state.value.readerMounted)
    }

    @Test fun `reader mismatch cannot retain cached counts and other errors never imply complete`() = runTest {
        val f = Fixture()
        f.sync()
        f.vm.onReaderError(f.vm.state.value.generation, VotesReaderError.NETWORK)
        assertFalse(f.vm.state.value.complete)
        assertEquals(PersonalVotesProblem.READ, f.vm.state.value.problem)
        f.vm.onReaderError(f.vm.state.value.generation, VotesReaderError.ACCOUNT_MISMATCH)
        assertEquals(PersonalVotesProblem.ACCOUNT_MISMATCH, f.vm.state.value.problem)
        assertTrue(f.store.counts.value.isEmpty())
    }

    private class Fixture {
        val sessions = Sessions()
        val account = Account()
        val public = Public()
        val store = VoteCountsStore()
        val vm = PersonalVotesViewModel(sessions, account, public, store)
        fun sync() = vm.sync()
        fun page(ids: List<Int>, hasNext: Boolean) = vm.onPage(
            vm.state.value.generation,
            VotesReadPage(STEAM_ID, ids.map { VoteRawRow(it.toLong(), false, true) }, hasNext),
        )
    }

    private class Sessions : SteamSessionRepository {
        override val session = MutableStateFlow(SteamSessionState(accountName = "alice", hasStoredSession = true))
        override fun restorePersistedSession() = error("No auto restores")
        override fun login(accountName: String, password: String) = error("Unexpected")
        override fun submitSteamGuardCode(code: String) = error("Unexpected")
        override fun useManualSteamGuardFallback() = error("Unexpected")
        override fun logout() = error("Unexpected")
    }

    private class Account : AccountWorkshopRepository {
        var steamId = STEAM_ID
        var identityReads = 0
        val voteReads = mutableListOf<Long>()
        val votes = mutableMapOf<Long, Int>()
        override suspend fun currentAccountSteamId(): String { identityReads++; return steamId }
        override suspend fun getUserVote(workshopId: Long): Int {
            voteReads += workshopId
            return votes[workshopId] ?: error("Unavailable")
        }
        override suspend fun browseCollection(query: AccountWorkshopQuery): WorkshopPage = error("Unexpected")
        override suspend fun resolveAuthorDisplayName(workshopId: Long): String? = error("Unexpected")
        override suspend fun getInteraction(workshopId: Long): WorkshopInteraction = error("Unexpected")
        override suspend fun setSubscribed(workshopId: Long, subscribed: Boolean): WorkshopInteraction = error("Unexpected")
        override suspend fun setFavorited(workshopId: Long, favorited: Boolean): WorkshopInteraction = error("Unexpected")
        override suspend fun postComment(workshopId: Long, ownerId: String, text: String) = error("Unexpected")
    }

    private class Public : SteamUnifiedWorkshopRepository {
        val results = mutableMapOf<Long, CmPublicDetailResult>()
        val batches = mutableListOf<List<Long>>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun getPublicDetailsResult(workshopIds: List<Long>): CmPublicDetailsBatch {
            batches += workshopIds
            gate?.await()
            return CmPublicDetailsBatch(workshopIds.map { it to (results[it] ?: CmPublicDetailResult.Unavailable()) })
        }
        override suspend fun browsePublic(query: WorkshopBrowseQuery): WorkshopPage? = error("Unexpected")
        override suspend fun getPublicDetail(workshopId: Long): WorkshopDetail? = error("Unexpected")
        override suspend fun getAuthenticatedComments(workshopId: Long, start: Int, count: Int, ownerId: String): WorkshopCommentPage? = error("Unexpected")
    }

    companion object {
        private const val STEAM_ID = "76561198000000001"
        private fun available(id: Long, appId: Long? = 431960L) = CmPublicDetailResult.Available(
            WorkshopDetail(
                summary = WorkshopSummary(id = id, title = "Item $id", author = "Author"),
                contentIdentity = WorkshopContentIdentity(consumerAppId = appId),
            ),
        )
    }
}
