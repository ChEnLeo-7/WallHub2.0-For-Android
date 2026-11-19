package com.wallhub.android.feature.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wallhub.android.R
import com.wallhub.android.core.model.AccountWorkshopSort
import com.wallhub.android.feature.home.FilterSection
import com.wallhub.android.feature.home.TwoColumnFilterOptions

@Composable
internal fun LibrarySortSection(
    collection: LibraryCollectionTab,
    sort: AccountWorkshopSort,
    onSelect: (AccountWorkshopSort) -> Unit,
) {
    FilterSection(
        title = stringResource(R.string.library_filter_sort),
        summary = sort.label(collection),
        icon = Icons.Outlined.FilterList,
        initiallyExpanded = true,
    ) {
        TwoColumnFilterOptions(
            options = AccountWorkshopSort.entries,
            selected = { it == sort },
            label = { it.label(collection) },
            onClick = onSelect,
            enabled = { collection == LibraryCollectionTab.SUBSCRIPTIONS || it != AccountWorkshopSort.RELATIONSHIP_DATE_DESC },
        )
    }
}

@Composable
private fun AccountWorkshopSort.label(collection: LibraryCollectionTab): String =
    stringResource(
        when (this) {
            AccountWorkshopSort.RELATIONSHIP_DATE_DESC ->
                if (collection == LibraryCollectionTab.SUBSCRIPTIONS) {
                    R.string.library_sort_subscription_date
                } else {
                    R.string.library_sort_favorite_date
                }
            AccountWorkshopSort.TITLE_ASC -> R.string.library_sort_title
            AccountWorkshopSort.UPDATED_DESC -> R.string.library_sort_updated
            AccountWorkshopSort.CREATED_DESC -> R.string.library_sort_created
        },
    )
