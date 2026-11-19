package com.wallhub.android.feature.downloads

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class DownloadsWideCardLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WallHubTestActivity>()

    @Test
    fun wideCardActionsAreHorizontalAtTheBottomRight() {
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                accent = AccentPreference.MONET,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                BettboxDownloadList(
                    tasks = listOf(task),
                    viewMode = DownloadViewMode.WIDE,
                    onDownloadsAction = {},
                    onOpenDetail = {},
                )
            }
        }

        val pauseLabel = composeRule.activity.getString(R.string.downloads_action_pause)
        val cancelLabel = composeRule.activity.getString(R.string.downloads_action_cancel)
        val primary = composeRule.onNodeWithContentDescription(pauseLabel)
        val secondary = composeRule.onNodeWithContentDescription(cancelLabel)
        primary.assertExists()
        secondary.assertExists()

        val primaryBounds = primary.getUnclippedBoundsInRoot()
        val secondaryBounds = secondary.getUnclippedBoundsInRoot()
        val density = composeRule.density
        val primaryCenterY = (primaryBounds.top + primaryBounds.bottom) / 2
        val secondaryCenterY = (secondaryBounds.top + secondaryBounds.bottom) / 2
        val sameRow = with(density) { abs((primaryCenterY - secondaryCenterY).toPx()) }
        assertTrue("actions should share one row, vertical delta=$sameRow", sameRow < 4f)
        assertTrue("secondary action should sit to the right of the primary action", secondaryBounds.left > primaryBounds.right)

        val progress =
            composeRule.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                useUnmergedTree = true,
            )
        val progressBounds = progress.getUnclippedBoundsInRoot()
        assertTrue("actions should sit below the progress bar", primaryBounds.top > progressBounds.bottom)

        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        val rootCenterX = (rootBounds.left + rootBounds.right) / 2
        assertTrue("actions should be anchored to the right half", primaryBounds.left > rootCenterX)
    }

    private val task =
        DownloadTask(
            id = "wide-layout-task",
            workshopId = 31L,
            title = "Blue Archive-Arona",
            type = WorkshopType.VIDEO,
            status = DownloadStatus.DOWNLOADING,
            downloadedBytes = 17_900_000L,
            totalBytes = 181_000_000L,
        )
}

