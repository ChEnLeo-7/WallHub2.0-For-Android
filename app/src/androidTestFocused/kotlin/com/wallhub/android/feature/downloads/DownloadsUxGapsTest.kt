package com.wallhub.android.feature.downloads

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadsUxGapsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WallHubTestActivity>()

    @Test
    fun completedVideoTaskOffersPlayNextStep() {
        var playedTaskId: String? = null
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                accent = AccentPreference.MONET,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                BettboxDownloadList(
                    tasks = listOf(completedVideo),
                    viewMode = DownloadViewMode.SQUARE,
                    onDownloadsAction = {},
                    onOpenDetail = {},
                    onPlayVideo = { playedTaskId = it },
                )
            }
        }

        val playLabel = composeRule.activity.getString(R.string.downloads_play_video)
        composeRule.onNodeWithContentDescription(playLabel).assertIsDisplayed().performClick()
        assertEquals(completedVideo.id, playedTaskId)
    }

    @Test
    fun failedTaskShowsSanitisedSummaryAndKeepsRecoveryActions() {
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                accent = AccentPreference.MONET,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                BettboxDownloadList(
                    tasks = listOf(failedTask),
                    viewMode = DownloadViewMode.SQUARE,
                    onDownloadsAction = {},
                    onOpenDetail = {},
                )
            }
        }

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.downloads_failure_summary))
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.downloads_action_retry))
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.downloads_action_delete))
            .assertIsDisplayed()
    }

    @Test
    fun emptyStateOffersBrowseAction() {
        var browsed = false
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                accent = AccentPreference.MONET,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                DownloadsScreen(
                    state = DownloadsUiState(allTasks = emptyList()),
                    onAction = {},
                    onBrowse = { browsed = true },
                )
            }
        }

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.downloads_empty_browse))
            .assertIsDisplayed()
            .performClick()
        assertTrue(browsed)
    }

    private val completedVideo =
        DownloadTask(
            id = "completed-video",
            workshopId = 51L,
            title = "完成的视频",
            type = WorkshopType.VIDEO,
            status = DownloadStatus.COMPLETED,
            stagingDirectory = "/staging/51",
            downloadedBytes = 100L,
            totalBytes = 100L,
        )

    private val failedTask =
        DownloadTask(
            id = "failed-task",
            workshopId = 52L,
            title = "失败的场景",
            type = WorkshopType.SCENE,
            status = DownloadStatus.FAILED,
            downloadedBytes = 10L,
            totalBytes = 100L,
            message = "HTTP 500 https://example.invalid/secret",
        )
}

