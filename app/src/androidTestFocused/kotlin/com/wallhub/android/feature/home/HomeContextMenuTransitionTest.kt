package com.wallhub.android.feature.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsProperties
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.testhost.WallHubTestActivity
import org.junit.Rule
import org.junit.Test

class HomeContextMenuTransitionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WallHubTestActivity>()

    @Test
    fun longPressOpensSelectedWallpaperPanel_andBackdropTapClosesIt() {
        var menuActive by mutableStateOf(false)
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                HomeScreen(
                    state =
                        HomeUiState(
                            items =
                                listOf(
                                    WorkshopSummary(
                                        id = 101L,
                                        title = "Shared transition wallpaper",
                                        author = "Test author",
                                        type = WorkshopType.SCENE,
                                        previewUrl = null,
                                        subscriptions = 120L,
                                        favorites = 25L,
                                        fileSizeBytes = 1_048_576L,
                                    ),
                                ),
                            isInitialLoading = false,
                        ),
                    onAction = {},
                    onContextMenuActiveChanged = { menuActive = it },
                )
            }
        }

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("home-workshop-101").performTouchInput { longClick() }
        composeRule.mainClock.advanceTimeBy(250)

        composeRule.onNodeWithText("场景").assertIsDisplayed()
        composeRule.onNodeWithText("场景").fetchSemanticsNode().boundsInRoot.let { bounds ->
            assert(bounds.height > 0f)
        }
        composeRule.onNodeWithText("Shared transition wallpaper").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.waitUntil(2_000) { menuActive }
        composeRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    "Wallpaper actions",
                ),
            ).assertIsDisplayed()
        composeRule.onNodeWithText("Shared transition wallpaper").assertIsDisplayed()
        composeRule.onNodeWithText("作者：Test author").assertIsDisplayed()
        composeRule.onNodeWithText("120").assertIsDisplayed()
        composeRule.onNodeWithText("101").assertIsDisplayed()

        composeRule.onRoot().performTouchInput { click(percentOffset(0.98f, 0.02f)) }
        composeRule.mainClock.advanceTimeBy(600)

        composeRule.waitUntil(2_000) { !menuActive }
        composeRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    "Wallpaper actions",
                ),
            ).assertIsNotDisplayed()
    }
}

