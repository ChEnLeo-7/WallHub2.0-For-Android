package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import kotlinx.coroutines.sync.Mutex

/** Identifies the signed-in account session whose personal collection is being traversed. */
internal data class AccountCollectionScope(
    val accountId: Long,
    val sessionGeneration: Long,
)

internal data class AccountCollectionPagerLimits(
    val maxCursors: Int = DEFAULT_MAX_ACCOUNT_COLLECTION_CURSORS,
    val maxSourcePagesPerCursor: Int = MAX_ACCOUNT_COLLECTION_FILTER_SOURCE_PAGES,
    val sourcePageSize: Int = MAX_ACCOUNT_WORKSHOP_PAGE_SIZE,
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
    private val mutex = Mutex()
    private val cursors =
        object : LinkedHashMap<CursorKey, Cursor>(limits.maxCursors + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CursorKey, Cursor>?): Boolean =
                size > limits.maxCursors
        }

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
        mutex.lock()
        try {
            val cursor = cursors.getOrPut(key, ::Cursor)
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
            return WorkshopPage(
                items = selection.items,
                page = normalized.page,
                hasNextPage = selection.hasNextPage,
            )
        } finally {
            mutex.unlock()
        }
    }

    suspend fun invalidateAll() = mutex.withLock { cursors.clear() }

    private data class CursorKey(
        val scope: AccountCollectionScope,
        val collection: AccountWorkshopCollection,
        val filter: FilterFingerprint,
    )

    private data class Cursor(
        val matches: MutableList<WorkshopSummary> = mutableListOf(),
        var loadedSourcePages: Int = 0,
        var sourceExhausted: Boolean = false,
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
