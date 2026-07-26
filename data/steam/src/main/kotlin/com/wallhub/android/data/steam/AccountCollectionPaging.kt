package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.WorkshopSummary

internal data class AccountCollectionPageSelection<T>(
    val items: List<T>,
    val hasNextPage: Boolean,
)

/**
 * Slices already-matched Steam collection entries into the page requested by the UI. The source
 * can remain unfiltered because GetUserFiles does not reliably honor required tags for all lists.
 */
internal fun <T> selectAccountCollectionPage(
    matches: List<T>,
    page: Int,
    pageSize: Int,
    sourceExhausted: Boolean,
): AccountCollectionPageSelection<T> {
    val normalizedPage = page.coerceAtLeast(1)
    val normalizedPageSize = pageSize.coerceAtLeast(1)
    val startIndex = (normalizedPage.toLong() - 1L) * normalizedPageSize.toLong()
    val endIndex = startIndex + normalizedPageSize.toLong()
    return AccountCollectionPageSelection(
        items = if (startIndex >= matches.size.toLong()) {
            emptyList()
        } else {
            matches.drop(startIndex.toInt()).take(normalizedPageSize)
        },
        hasNextPage = matches.size.toLong() > endIndex || !sourceExhausted,
    )
}

internal fun AccountWorkshopQuery.matchesAccountCollectionItem(summary: WorkshopSummary): Boolean {
    if (type != null && summary.type != type) return false
    if (tags.isNotEmpty()) {
        val sourceTags = summary.tags.map(String::lowercase).toSet()
        if (!tags.all { tag -> tag.lowercase() in sourceTags }) return false
    }
    val needle = searchText.trim().lowercase()
    if (needle.isEmpty()) return true
    return needle in summary.title.lowercase() ||
        needle in summary.author.lowercase() ||
        needle in summary.id.toString() ||
        summary.creatorId?.lowercase()?.contains(needle) == true ||
        summary.tags.any { tag -> needle in tag.lowercase() }
}
