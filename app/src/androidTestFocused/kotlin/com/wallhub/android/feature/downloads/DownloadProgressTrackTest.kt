package com.wallhub.android.feature.downloads

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wallhub.android.core.designsystem.WallHubStyle
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.designsystem.wallHubStyle
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
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class DownloadProgressTrackTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WallHubTestActivity>()

    @Test
    fun partialDownloadRendersTheWholeTrackBehindTheFilledPart() {
        var style: WallHubStyle? = null
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                accent = AccentPreference.MONET,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                style = wallHubStyle()
                BettboxDownloadList(
                    tasks = listOf(partialTask),
                    viewMode = DownloadViewMode.SQUARE,
                    onDownloadsAction = {},
                    onOpenDetail = {},
                )
            }
        }

        val progressNode =
            composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true)
        progressNode.assertExists()
        assertEquals(
            ProgressBarRangeInfo(0.2f, 0f..1f),
            progressNode.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo],
        )

        val image = progressNode.captureToImage().asAndroidBitmap()
        val expectedTrack = style!!.surfaces.progressTrack
        val expectedFill = style!!.colorScheme.primary

        val midY = image.height / 2
        val fillPixel = image.getPixel((image.width * 0.1f).toInt(), midY)
        val trackPixel = image.getPixel((image.width * 0.85f).toInt(), midY)

        assertTrue("fill pixel was #${Integer.toHexString(fillPixel)}", matches(fillPixel, expectedFill))
        assertTrue("track pixel was #${Integer.toHexString(trackPixel)}", matches(trackPixel, expectedTrack))
    }

    private fun matches(
        pixel: Int,
        color: Color,
    ): Boolean =
        abs(AndroidColor.red(pixel) - (color.red * 255).toInt()) <= 12 &&
            abs(AndroidColor.green(pixel) - (color.green * 255).toInt()) <= 12 &&
            abs(AndroidColor.blue(pixel) - (color.blue * 255).toInt()) <= 12

    private val partialTask =
        DownloadTask(
            id = "progress-track-task",
            workshopId = 21L,
            title = "雨夜列车",
            type = WorkshopType.VIDEO,
            status = DownloadStatus.DOWNLOADING,
            downloadedBytes = 10_000_000L,
            totalBytes = 50_000_000L,
        )
}

