package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.matchesSteamWallpaper

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
        items =
            if (startIndex >= matches.size.toLong()) {
                emptyList()
            } else {
                matches.drop(startIndex.toInt()).take(normalizedPageSize)
            },
        hasNextPage = matches.size.toLong() > endIndex || !sourceExhausted,
    )
}

internal fun AccountWorkshopQuery.matchesAccountCollectionItem(summary: WorkshopSummary): Boolean {
    val filterQuery =
        WorkshopBrowseQuery(
            searchText = searchText,
            exactPhrase = exactPhrase,
            type = type,
            types = types,
            tags = tags,
            ratings = ratings.ifEmpty { setOf(com.wallhub.android.core.model.WorkshopRating.ALL) },
            genres = genres,
            officialTags = officialTags,
            excludedOfficialTags = excludedOfficialTags,
            resolutions = resolutions,
            allowNsfw = true,
        )
    if (!filterQuery.matchesSteamWallpaper(summary)) return false
    val needle = searchText.trim().lowercase()
    if (needle.isEmpty()) return true
    val searchable =
        buildList {
            add(summary.title)
            add(summary.author)
            add(summary.id.toString())
            summary.creatorId?.let(::add)
            addAll(summary.tags)
        }.map(String::lowercase)
    return if (exactPhrase) {
        searchable.any { value -> needle in value }
    } else {
        needle.split(Regex("\\s+")).filter(String::isNotBlank).all { token ->
            searchable.any { value -> token in value }
        }
    }
}
