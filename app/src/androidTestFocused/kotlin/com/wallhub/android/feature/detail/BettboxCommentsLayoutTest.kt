package com.wallhub.android.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wallhub.android.core.designsystem.WallHubAppSurface
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.designsystem.wallHubStyle
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.testhost.WallHubTestActivity
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BettboxCommentsLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WallHubTestActivity>()

    @Test
    fun commentsUseOldCommentGapWithEqualWidth() {
        var pageColor = 0
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                accent = AccentPreference.MONET,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                pageColor = wallHubStyle().surfaces.page.toArgb()
                WallHubAppSurface(modifier = Modifier.testTag("comments-root")) {
                    DetailCommentsPage(
                        comments = comments,
                        commentsHasMore = false,
                        isLoading = false,
                        isLoadingMore = false,
                        error = null,
                        canPostComment = false,
                        commentDraft = "",
                        isPostingComment = false,
                        commentPostError = null,
                        onRetry = {},
                        onLoadMore = {},
                        onCommentDraftChanged = {},
                        onSubmitComment = {},
                        onComposerHeightChanged = {},
                        isWallpaperHeaderCollapsed = false,
                        onReturnToWallpaperTop = {},
                    )
                }
            }
        }

        val density = composeRule.density
        val first = composeRule.onNodeWithTag("comment-0").getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithTag("comment-1").getUnclippedBoundsInRoot()
        val third = composeRule.onNodeWithTag("comment-2").getUnclippedBoundsInRoot()
        val firstWidth = first.right - first.left
        val secondWidth = second.right - second.left
        val thirdWidth = third.right - third.left
        assertEquals(firstWidth, secondWidth)
        assertEquals(secondWidth, thirdWidth)
        val gapPx = with(density) { (second.top - first.bottom).toPx() }
        assertEquals(2.dp.value * density.density, gapPx, 0.75f)
        assertNotEquals(first.bottom - first.top, second.bottom - second.top)

        val image = composeRule.onNodeWithTag("comments-root").captureToImage().toPixelMap()
        val gapY = with(density) { ((first.bottom + second.top) / 2f).toPx() }.toInt()
        assertEquals(pageColor, image[image.width / 2, gapY].toArgb())

        composeRule.onNodeWithText("short comment").assertIsDisplayed()
        composeRule.onNodeWithText("a long comment that wraps across several lines").assertIsDisplayed()
    }

    private val comments =
        listOf(
            WorkshopComment(author = "Alice", text = "short comment", dateLabel = "2026/10/30"),
            WorkshopComment(
                author = "Bob",
                text = "a long comment that wraps across several lines",
                dateLabel = "2026/10/29",
            ),
            WorkshopComment(author = "C", text = "third", dateLabel = "2026/10/28"),
        )
}

