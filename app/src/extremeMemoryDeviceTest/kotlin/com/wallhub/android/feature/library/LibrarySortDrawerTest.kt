package com.wallhub.android.feature.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wallhub.android.core.model.AccountWorkshopSort
import com.wallhub.android.testhost.WallHubTestActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibrarySortDrawerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WallHubTestActivity>()

    @Test
    fun titleOrderCanBeSelectedWithoutApplyingOtherFilters() {
        var selected: AccountWorkshopSort? = null
        composeRule.setContent {
            MaterialTheme {
                LibrarySortSection(
                    collection = LibraryCollectionTab.SUBSCRIPTIONS,
                    sort = AccountWorkshopSort.RELATIONSHIP_DATE_DESC,
                    onSelect = { selected = it },
                )
            }
        }
        val titleLabel = composeRule.activity.getString(com.wallhub.android.R.string.library_sort_title)
        composeRule.onNodeWithText(titleLabel).performClick()
        composeRule.runOnIdle { assertEquals(AccountWorkshopSort.TITLE_ASC, selected) }
    }

    @Test
    fun unverifiedFavoriteDateCannotBeSelected() {
        composeRule.setContent {
            MaterialTheme {
                LibrarySortSection(
                    collection = LibraryCollectionTab.FAVORITES,
                    sort = AccountWorkshopSort.UPDATED_DESC,
                    onSelect = { error("Disabled date must not dispatch") },
                )
            }
        }
        val label = composeRule.activity.getString(com.wallhub.android.R.string.library_sort_favorite_date)
        composeRule.onNodeWithText(label).assertIsNotEnabled()
    }
}
