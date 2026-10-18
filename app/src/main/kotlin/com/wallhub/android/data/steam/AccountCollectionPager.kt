package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Identifies the signed-in account session whose personal collection is being traversed. */
internal data class AccountCollectionScope(
    val accountId: Long,
    val sessionGeneration: Long,
)

internal const val DEFAULT_ACCOUNT_COLLECTION_SOURCE_PAGE_SIZE = 24

internal data class AccountCollectionPagerLimits(
    val maxCursors: Int = DEFAULT_MAX_ACCOUNT_COLLECTION_CURSORS,
    val maxSourcePagesPerCursor: Int = MAX_ACCOUNT_COLLECTION_FILTER_SOURCE_PAGES,
    val sourcePageSize: Int = DEFAULT_ACCOUNT_COLLECTION_SOURCE_PAGE_SIZE,
) {
    init {
        require(maxCursors > 0) { "maxCursors must be positive" }
        require(maxSourcePagesPerCursor > 0) { "maxSourcePagesPerCursor must be positive" }
        require(sourcePageSize > 0) { "sourcePageSize must be positive" }
    }
}

/**
 * Incrementally materializes filtered personal-collection matches.
 *
 * State is isolated by account/session generation, collection, and normalized filter fingerprint. UI page and
 * page size are deliberately not part of the key, so later pages continue the same source cursor. This module
 * handles filtered traversal only; callers retain the direct source-page path for unfiltered requests.
 */
internal class AccountCollectionPager(
    private val limits: AccountCollectionPagerLimits = AccountCollectionPagerLimits(),
) {
    private val cursorMapMutex = Mutex()
    private val cursors = LinkedHashMap<CursorKey, Cursor>(limits.maxCursors + 1, 0.75f, true)
    private var cursorReleased = CompletableDeferred<Unit>()

    suspend fun page(
        scope: AccountCollectionScope,
        query: AccountWorkshopQuery,
        loadSourcePage: suspend (page: Int, pageSize: Int) -> WorkshopPage,
    ): WorkshopPage {
        val normalized = query.normalized()
        require(normalized.hasInteractiveFilters()) {
            "AccountCollectionPager is only for filtered queries; use direct source paging otherwise"
        }
        val key = CursorKey(scope, normalized.collection, normalized.filterFingerprint())
        val cursor = acquireCursor(key)
        try {
            return cursor.mutex.withLock {
                val requiredMatches =
                    (normalized.page.toLong() * normalized.pageSize.toLong())
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                while (
                    cursor.matches.size.toLong() < requiredMatches &&
                        !cursor.sourceExhausted &&
                        cursor.loadedSourcePages < limits.maxSourcePagesPerCursor
                ) {
                    val sourcePage = cursor.loadedSourcePages + 1
                    // Do not mutate the cursor until the suspending read succeeds. Cancellation therefore leaves
                    // the same source page ready for a retry.
                    val source = loadSourcePage(sourcePage, limits.sourcePageSize)
                    cursor.matches +=
                        source.items
                            .asSequence()
                            .take(limits.sourcePageSize)
                            .filter(normalized::matchesAccountCollectionItem)
                            .toList()
                    cursor.loadedSourcePages = sourcePage
                    cursor.sourceExhausted = !source.hasNextPage
                }
                val capped = cursor.loadedSourcePages >= limits.maxSourcePagesPerCursor
                val selection =
                    selectAccountCollectionPage(
                        matches = cursor.matches,
                        page = normalized.page,
                        pageSize = normalized.pageSize,
                        sourceExhausted = cursor.sourceExhausted || capped,
                    )
                WorkshopPage(
                    items = selection.items,
                    page = normalized.page,
                    hasNextPage = selection.hasNextPage,
                )
            }
        } finally {
            withContext(NonCancellable) { releaseCursor(cursor) }
        }
    }

    suspend fun invalidateAll() = cursorMapMutex.withLock { cursors.clear() }

    private suspend fun acquireCursor(key: CursorKey): Cursor {
        while (true) {
            var acquired: Cursor? = null
            val waiter =
                cursorMapMutex.withLock {
                    cursors[key]?.let { cursor ->
                        cursor.leases += 1
                        acquired = cursor
                        return@withLock null
                    }
                    if (cursors.size >= limits.maxCursors) {
                        val evictable = cursors.entries.firstOrNull { (_, cursor) -> cursor.leases == 0 }
                        if (evictable == null) {
                            return@withLock cursorReleased
                        }
                        cursors.remove(evictable.key)
                    }
                    acquired = Cursor(leases = 1).also { cursor -> cursors[key] = cursor }
                    null
                }
            acquired?.let { return it }
            checkNotNull(waiter).await()
        }
    }

    private suspend fun releaseCursor(cursor: Cursor) {
        cursorMapMutex.withLock {
            cursor.leases -= 1
            check(cursor.leases >= 0) { "Account collection cursor lease underflow" }
            if (cursor.leases == 0) {
                cursorReleased.complete(Unit)
                cursorReleased = CompletableDeferred()
            }
        }
    }

    private data class CursorKey(
        val scope: AccountCollectionScope,
        val collection: AccountWorkshopCollection,
        val filter: FilterFingerprint,
    )

    private data class Cursor(
        val mutex: Mutex = Mutex(),
        val matches: MutableList<WorkshopSummary> = mutableListOf(),
        var loadedSourcePages: Int = 0,
        var sourceExhausted: Boolean = false,
        var leases: Int = 0,
    )
}

private data class FilterFingerprint(
    val searchText: String,
    val exactPhrase: Boolean,
    val type: WorkshopType?,
    val types: Set<WorkshopType>,
    val tags: Set<String>,
    val ratings: Set<WorkshopRating>,
    val genres: Set<String>,
    val officialTags: Set<String>,
    val excludedOfficialTags: Set<String>,
    val resolutions: Set<String>,
)

private fun AccountWorkshopQuery.filterFingerprint() =
    FilterFingerprint(
        searchText = searchText,
        exactPhrase = exactPhrase,
        type = type,
        types = types,
        tags = tags,
        ratings = ratings,
        genres = genres,
        officialTags = officialTags,
        excludedOfficialTags = excludedOfficialTags,
        resolutions = resolutions,
    )

internal const val DEFAULT_MAX_ACCOUNT_COLLECTION_CURSORS = 6
