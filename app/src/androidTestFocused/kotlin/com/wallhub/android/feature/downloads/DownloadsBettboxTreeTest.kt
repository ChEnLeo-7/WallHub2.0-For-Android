package com.wallhub.android.feature.downloads

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.testhost.WallHubTestActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadsBettboxTreeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WallHubTestActivity>()

    @Test
    fun bettboxTreeExposesSelectedTabAndExpandableActions() {
        val activeTab = composeRule.activity.getString(R.string.downloads_tab_active)
        val expandActions = composeRule.activity.getString(R.string.downloads_expand_actions)
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                accent = AccentPreference.MONET,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                DownloadsScreen(
                    state =
                        DownloadsUiState(
                            allTasks = listOf(downloadingTask),
                        ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText(activeTab).assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithContentDescription(expandActions).performClick()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.downloads_pause_all))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun pausedDownloadsExposeStartAllInsteadOfPauseAll() {
        val expandActions = composeRule.activity.getString(R.string.downloads_expand_actions)
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                accent = AccentPreference.MONET,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                DownloadsScreen(
                    state = DownloadsUiState(allTasks = listOf(downloadingTask.copy(status = DownloadStatus.PAUSED))),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(expandActions).performClick()
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.downloads_resume_all))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }

    private val downloadingTask =
        DownloadTask(
            id = "download-semantics",
            workshopId = 7L,
            title = "雨夜列车",
            type = WorkshopType.VIDEO,
            status = DownloadStatus.DOWNLOADING,
            downloadedBytes = 120_000_000L,
            totalBytes = 400_000_000L,
        )
}

