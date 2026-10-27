package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class SteamAccountCollectionMutationLedgerTest {
    @Test
    fun `confirmed mutations are keyed and publish monotonic changes`() = runTest {
        val ledger = SteamAccountCollectionMutationLedger()
        val changes = mutableListOf<AccountCollectionMutationChange>()
        val collection =
            backgroundScope.launch {
                ledger.changes.take(2).toList(changes)
            }
        runCurrent()

        val first =
            ledger.confirmedMutation(
                accountId = 10L,
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                itemId = 100L,
                present = true,
                summary = summary(100L),
                cachedPage = null,
            )
        val second =
            ledger.confirmedMutation(
                accountId = 10L,
                collection = AccountWorkshopCollection.FAVORITES,
                itemId = 200L,
                present = false,
                summary = null,
                cachedPage = null,
            )
        runCurrent()

        assertEquals(1L, first.change.revision)
        assertEquals(2L, second.change.revision)
        assertEquals(listOf(first.change, second.change), changes)
        assertEquals(
            AccountCollectionItemKey(10L, AccountWorkshopCollection.SUBSCRIPTIONS, 100L),
            changes.first().key,
        )
        collection.cancel()
    }

    @Test
    fun `concurrent confirmed mutations still receive unique monotonic revisions`() = runTest {
        val ledger = SteamAccountCollectionMutationLedger()

        val revisions =
            (1L..64L)
                .map { itemId ->
                    async {
                        ledger.confirmedMutation(
                            accountId = 10L,
                            collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                            itemId = itemId,
                            present = true,
                            summary = summary(itemId),
                            cachedPage = null,
                        ).change.revision
                    }
                }.awaitAll()

        assertEquals((1L..64L).toList(), revisions.sorted())
        assertEquals(64, ledger.pendingMutationCount())
    }

    @Test
    fun `confirmed mutation immediately removes or retains cached rows and adds only known summaries`() = runTest {
        val ledger = SteamAccountCollectionMutationLedger()
        val first = summary(100L)
        val original = page(first, summary(200L))

        val removed =
            ledger.confirmedMutation(
                accountId = 10L,
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                itemId = first.id,
                present = false,
                summary = null,
                cachedPage = original,
            ).patchedPage
        assertEquals(listOf(200L), removed?.items?.map(WorkshopSummary::id))

        val unknownAddition =
            ledger.confirmedMutation(
                accountId = 10L,
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                itemId = 300L,
                present = true,
                summary = null,
                cachedPage = removed,
            ).patchedPage
        assertEquals(listOf(200L), unknownAddition?.items?.map(WorkshopSummary::id))

        val knownAddition =
            ledger.confirmedMutation(
                accountId = 10L,
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                itemId = 300L,
                present = true,
                summary = summary(300L),
                cachedPage = unknownAddition,
            ).patchedPage
        assertEquals(listOf(200L), knownAddition?.items?.map(WorkshopSummary::id))

        val retained =
            ledger.confirmedMutation(
                accountId = 10L,
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                itemId = 200L,
                present = true,
                summary = null,
                cachedPage = knownAddition,
            ).patchedPage
        assertEquals(listOf(200L), retained?.items?.map(WorkshopSummary::id))
    }

    @Test
    fun `known additions are not fabricated into arbitrary pages`() = runTest {
        val ledger = SteamAccountCollectionMutationLedger()
        val added = summary(300L)
        ledger.confirmedMutation(
            accountId = 10L,
            collection = AccountWorkshopCollection.FAVORITES,
            itemId = added.id,
            present = true,
            summary = added,
            cachedPage = null,
        )

        val secondPage = page(summary(200L)).copy(page = 2, hasNextPage = true)
        val overlaid = ledger.overlay(10L, AccountWorkshopCollection.FAVORITES, secondPage)

        assertEquals(listOf(200L), overlaid.items.map(WorkshopSummary::id))
    }

    @Test
    fun `eligible first page insertion stays within its capacity`() = runTest {
        val ledger = SteamAccountCollectionMutationLedger()
        val added = summary(300L)
        ledger.confirmedMutation(
            accountId = 10L,
            collection = AccountWorkshopCollection.FAVORITES,
            itemId = added.id,
            present = true,
            summary = added,
            cachedPage = null,
        )

        val overlaid =
            ledger.overlay(
                accountId = 10L,
                collection = AccountWorkshopCollection.FAVORITES,
                page = page(summary(100L), summary(200L)),
                allowInsertion = true,
                maxItems = 2,
            )

        assertEquals(listOf(300L, 100L), overlaid.items.map(WorkshopSummary::id))
    }

    @Test
    fun `last page after page one cannot confirm collection wide absence`() = runTest {
        val ledger = SteamAccountCollectionMutationLedger()
        ledger.confirmedMutation(
            accountId = 10L,
            collection = AccountWorkshopCollection.SUBSCRIPTIONS,
            itemId = 100L,
            present = false,
            summary = null,
            cachedPage = null,
        )

        ledger.completeRead(
            token = ledger.beginRead(),
            accountId = 10L,
            collection = AccountWorkshopCollection.SUBSCRIPTIONS,
            remotePage = page(summary(200L)).copy(page = 3, hasNextPage = false),
            completeCollection = false,
        )

        assertEquals(1, ledger.pendingMutationCount())
    }

    @Test
    fun `read started before mutation cannot replace cache and receives the overlay`() = runTest {
        val ledger = SteamAccountCollectionMutationLedger()
        val oldRead = ledger.beginRead()
        ledger.confirmedMutation(
            accountId = 10L,
            collection = AccountWorkshopCollection.FAVORITES,
            itemId = 100L,
            present = false,
            summary = null,
            cachedPage = page(summary(100L)),
        )

        val completed =
            ledger.completeRead(
                token = oldRead,
                accountId = 10L,
                collection = AccountWorkshopCollection.FAVORITES,
                remotePage = page(summary(100L)),
            )

        assertTrue(completed.page.items.isEmpty())
        assertFalse(completed.cacheWriteAllowed)
        assertEquals(1, ledger.pendingMutationCount())
    }

    @Test
    fun `new consistent remote result confirms and clears the mutation`() = runTest {
        val ledger = SteamAccountCollectionMutationLedger()
        ledger.confirmedMutation(
            accountId = 10L,
            collection = AccountWorkshopCollection.FAVORITES,
            itemId = 100L,
            present = false,
            summary = null,
            cachedPage = page(summary(100L)),
        )
        val newRead = ledger.beginRead()

        val completed =
            ledger.completeRead(
                token = newRead,
                accountId = 10L,
                collection = AccountWorkshopCollection.FAVORITES,
                remotePage = page(),
            )

        assertTrue(completed.cacheWriteAllowed)
        assertTrue(completed.page.items.isEmpty())
        assertEquals(0, ledger.pendingMutationCount())
    }

    @Test
    fun `new inconsistent result stays overlaid until a later read confirms it`() = runTest {
        val ledger = SteamAccountCollectionMutationLedger()
        val known = summary(100L)
        ledger.confirmedMutation(
            accountId = 10L,
            collection = AccountWorkshopCollection.SUBSCRIPTIONS,
            itemId = known.id,
            present = true,
            summary = known,
            cachedPage = page(),
        )

        val inconsistent =
            ledger.completeRead(
                token = ledger.beginRead(),
                accountId = 10L,
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                remotePage = page(),
                allowInsertion = true,
                maxItems = 24,
            )
        assertEquals(listOf(100L), inconsistent.page.items.map(WorkshopSummary::id))
        assertTrue(inconsistent.cacheWriteAllowed)
        assertEquals(1, ledger.pendingMutationCount())

        val consistent =
            ledger.completeRead(
                token = ledger.beginRead(),
                accountId = 10L,
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                remotePage = page(known),
            )
        assertEquals(listOf(100L), consistent.page.items.map(WorkshopSummary::id))
        assertEquals(0, ledger.pendingMutationCount())
    }

    @Test
    fun `overlay does not cross account collection or item keys`() = runTest {
        val ledger = SteamAccountCollectionMutationLedger()
        ledger.confirmedMutation(
            accountId = 10L,
            collection = AccountWorkshopCollection.SUBSCRIPTIONS,
            itemId = 100L,
            present = false,
            summary = null,
            cachedPage = null,
        )

        val otherAccount =
            ledger.completeRead(
                token = ledger.beginRead(),
                accountId = 11L,
                collection = AccountWorkshopCollection.SUBSCRIPTIONS,
                remotePage = page(summary(100L)),
            )
        val otherCollection =
            ledger.completeRead(
                token = ledger.beginRead(),
                accountId = 10L,
                collection = AccountWorkshopCollection.FAVORITES,
                remotePage = page(summary(100L)),
            )

        assertEquals(listOf(100L), otherAccount.page.items.map(WorkshopSummary::id))
        assertEquals(listOf(100L), otherCollection.page.items.map(WorkshopSummary::id))
        assertNull(otherAccount.confirmedRevision)
        assertNull(otherCollection.confirmedRevision)
    }

    private fun summary(id: Long) = WorkshopSummary(id = id, title = "item-$id", author = "author")

    private fun page(vararg items: WorkshopSummary) =
        WorkshopPage(items = items.toList(), page = 1, hasNextPage = false)
}
