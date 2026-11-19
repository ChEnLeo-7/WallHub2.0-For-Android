package com.wallhub.android.core.designsystem

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.testhost.WallHubTestActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WallHubDialogActionsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WallHubTestActivity>()

    @Test
    fun destructiveAndDismissActionsAreDistinctAndDispatchTheirOwnCallbacks() {
        var clicked = ""
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                accent = AccentPreference.MONET,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                WallHubAlertDialog(
                    onDismissRequest = { clicked = "dismiss-request" },
                    title = "下载",
                    actions =
                        listOf(
                            WallHubDialogAction("重试所有失败下载", WallHubDialogActionRole.Primary) { clicked = "retry" },
                            WallHubDialogAction("清空结束的下载", WallHubDialogActionRole.Destructive) { clicked = "clear" },
                            WallHubDialogAction("取消", WallHubDialogActionRole.Dismiss) { clicked = "cancel" },
                        ),
                )
            }
        }

        composeRule.onNodeWithText("重试所有失败下载").assertIsDisplayed()
        composeRule.onNodeWithText("清空结束的下载").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()

        composeRule.onNodeWithText("清空结束的下载").performClick()
        assertEquals("clear", clicked)
    }
}

