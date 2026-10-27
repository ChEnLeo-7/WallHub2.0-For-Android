package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local ordering and overlay state for confirmed account collection mutations.
 *
 * The ledger owns no network or persistence. Callers take a [ReadToken] immediately before a remote read,
 * apply a confirmed write through [confirmedMutation], and pass the remote answer to [completeRead] before
 * publishing or caching it. This keeps a completed write visible while Steam's following read catches up and
 * fences reads that started before that write from replacing a newer cache value.
 */
@Singleton
internal class SteamAccountCollectionMutationLedger @Inject constructor() {
    private val mutex = Mutex()
    private var revision = 0L
    private val mutations = mutableMapOf<AccountCollectionItemKey, PendingMutation>()
    private val scopeRevisions = mutableMapOf<AccountCollectionScopeKey, Long>()
    private val mutableChanges =
        MutableSharedFlow<AccountCollectionMutationChange>(
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Hot process-local invalidation signal for Library and Profile observers. */
    val changes: Flow<AccountCollectionMutationChange> = mutableChanges.asSharedFlow()

    suspend fun beginRead(): ReadToken = mutex.withLock { ReadToken(revision) }

    suspend fun confirmedMutation(
        accountId: Long,
        collection: AccountWorkshopCollection,
        itemId: Long,
        present: Boolean,
        summary: WorkshopSummary?,
        cachedPage: WorkshopPage?,
        publishChange: Boolean = true,
    ): ConfirmedAccountCollectionMutation {
        require(accountId > 0L) { "Invalid Steam account ID" }
        require(itemId > 0L) { "Invalid Workshop item ID" }
        require(summary == null || summary.id == itemId) { "Mutation summary does not match its Workshop item ID" }

        return mutex.withLock {
            revision += 1L
            val key = AccountCollectionItemKey(accountId, collection, itemId)
            val previous = mutations[key]
            val knownSummary = (summary ?: previous?.summary)?.withCollectionState(collection, present)
            val change = AccountCollectionMutationChange(key, present, revision)
            mutations[key] = PendingMutation(change, knownSummary)
            scopeRevisions[key.scope()] = revision
            if (publishChange) mutableChanges.tryEmit(change)
            ConfirmedAccountCollectionMutation(
                change = change,
                patchedPage = cachedPage?.applyMutation(itemId, present, knownSummary),
            )
        }
    }

    fun publish(change: AccountCollectionMutationChange) {
        mutableChanges.tryEmit(change)
    }

    suspend fun overlay(
        accountId: Long,
        collection: AccountWorkshopCollection,
        page: WorkshopPage,
        includeSummary: (WorkshopSummary) -> Boolean = { true },
        allowInsertion: Boolean = false,
        maxItems: Int = Int.MAX_VALUE,
    ): WorkshopPage =
        mutex.withLock {
            mutations.entries
                .filter { (key, _) -> key.accountId == accountId && key.collection == collection }
                .sortedBy { (_, mutation) -> mutation.change.revision }
                .fold(page) { current, (key, mutation) ->
                    current.applyMutation(
                        itemId = key.itemId,
                        present = mutation.change.present,
                        summary = mutation.summary?.takeIf(includeSummary),
                        allowInsertion = allowInsertion,
                        maxItems = maxItems,
                    )
                }
        }

    suspend fun completeRead(
        token: ReadToken,
        accountId: Long,
        collection: AccountWorkshopCollection,
        remotePage: WorkshopPage,
        completeCollection: Boolean = true,
        includeSummary: (WorkshopSummary) -> Boolean = { true },
        allowInsertion: Boolean = false,
        maxItems: Int = Int.MAX_VALUE,
    ): CompletedAccountCollectionRead {
        require(accountId > 0L) { "Invalid Steam account ID" }

        return mutex.withLock {
            val scope = AccountCollectionScopeKey(accountId, collection)
            val scopeRevision = scopeRevisions[scope] ?: 0L
            var page = remotePage
            var confirmedRevision: Long? = null
            val scopedMutations =
                mutations.entries
                    .filter { (key, _) -> key.accountId == accountId && key.collection == collection }
                    .sortedBy { (_, mutation) -> mutation.change.revision }

            scopedMutations.forEach { (key, mutation) ->
                val remoteContainsItem = remotePage.items.any { it.id == key.itemId }
                val mayConfirm = token.revision >= mutation.change.revision
                val remoteCanConfirmAbsence = completeCollection
                if (
                    mayConfirm &&
                    (remoteContainsItem == mutation.change.present) &&
                    (mutation.change.present || remoteCanConfirmAbsence)
                ) {
                    mutations.remove(key)
                    confirmedRevision = maxOf(confirmedRevision ?: 0L, mutation.change.revision)
                } else {
                    page =
                        page.applyMutation(
                            itemId = key.itemId,
                            present = mutation.change.present,
                            summary = mutation.summary?.takeIf(includeSummary),
                            allowInsertion = allowInsertion,
                            maxItems = maxItems,
                        )
                }
            }

            CompletedAccountCollectionRead(
                page = page,
                cacheWriteAllowed = token.revision >= scopeRevision,
                confirmedRevision = confirmedRevision,
            )
        }
    }

    internal suspend fun pendingMutationCount(): Int = mutex.withLock { mutations.size }

    private fun WorkshopSummary.withCollectionState(
        collection: AccountWorkshopCollection,
        present: Boolean,
    ): WorkshopSummary =
        when (collection) {
            AccountWorkshopCollection.SUBSCRIPTIONS ->
                copy(
                    subscriptionState =
                        if (present) SubscriptionState.SUBSCRIBED else SubscriptionState.NOT_SUBSCRIBED,
                )
            AccountWorkshopCollection.FAVORITES ->
                copy(
                    favoriteState = if (present) FavoriteState.FAVORITED else FavoriteState.NOT_FAVORITED,
                )
        }

    private fun WorkshopPage.applyMutation(
        itemId: Long,
        present: Boolean,
        summary: WorkshopSummary?,
        allowInsertion: Boolean = false,
        maxItems: Int = Int.MAX_VALUE,
    ): WorkshopPage {
        val existingIndex = items.indexOfFirst { it.id == itemId }
        val patchedItems =
            when {
                !present && existingIndex >= 0 -> items.filterNot { it.id == itemId }
                !present -> items
                existingIndex >= 0 && summary != null ->
                    items.toMutableList().also { it[existingIndex] = summary }
                existingIndex >= 0 -> items
                present && summary != null && allowInsertion ->
                    (listOf(summary) + items).distinctBy(WorkshopSummary::id).take(maxItems.coerceAtLeast(1))
                // The ledger cannot infer Steam ordering or page capacity without query context.
                else -> items
            }
        return if (patchedItems === items) this else copy(items = patchedItems)
    }

    private data class PendingMutation(
        val change: AccountCollectionMutationChange,
        val summary: WorkshopSummary?,
    )
}

internal data class AccountCollectionItemKey(
    val accountId: Long,
    val collection: AccountWorkshopCollection,
    val itemId: Long,
) {
    internal fun scope(): AccountCollectionScopeKey = AccountCollectionScopeKey(accountId, collection)
}

internal data class AccountCollectionScopeKey(
    val accountId: Long,
    val collection: AccountWorkshopCollection,
)

internal data class AccountCollectionMutationChange(
    val key: AccountCollectionItemKey,
    val present: Boolean,
    val revision: Long,
)

@JvmInline
internal value class ReadToken internal constructor(
    val revision: Long,
)

internal data class ConfirmedAccountCollectionMutation(
    val change: AccountCollectionMutationChange,
    val patchedPage: WorkshopPage?,
)

internal data class CompletedAccountCollectionRead(
    val page: WorkshopPage,
    val cacheWriteAllowed: Boolean,
    val confirmedRevision: Long?,
)
