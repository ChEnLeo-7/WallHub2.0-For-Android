package com.wallhub.android.settings

import com.wallhub.android.testhost.WallHubTestActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wallhub.android.R
import com.wallhub.settings.WallHubSingleChoiceDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WallHubSingleChoiceDialogTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WallHubTestActivity>()

    @Test
    fun dialogShowsEveryOptionAndSelectionInvokesCallbackAndCloses() {
        var selected by mutableStateOf("B")
        var visible by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                if (visible) {
                    WallHubSingleChoiceDialog(
                        title = "Language",
                        selectedValue = selected,
                        values = listOf("A", "B", "C"),
                        label = { it },
                        onDismissRequest = { visible = false },
                        onSelected = {
                            selected = it
                            visible = false
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithText("A").assertIsDisplayed()
        composeRule.onNodeWithText("B").assertIsSelected()
        composeRule.onNodeWithText("C").performClick()

        composeRule.runOnIdle {
            assertEquals("C", selected)
            assertFalse(visible)
        }
    }

    @Test
    fun selectedRowUsesContainerCheckIconAndRadioGroupSemantics() {
        val selectedContainer = Color(0xFFFF00FF)
        val selectedForeground = Color(0xFF00FF00)
        val unselectedIconColor = Color(0xFF0000FF)
        var clickedValue: String? = null
        composeRule.setContent {
            MaterialTheme(
                colorScheme =
                    lightColorScheme(
                        primaryContainer = selectedContainer,
                        onPrimaryContainer = selectedForeground,
                        onSurfaceVariant = unselectedIconColor,
                    ),
            ) {
                WallHubSingleChoiceDialog(
                    title = "Language",
                    selectedValue = "B",
                    values = listOf("A", "B", "C"),
                    label = { it },
                    onDismissRequest = {},
                    onSelected = { clickedValue = it },
                )
            }
        }

        val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
            .fetchSemanticsNode()
        composeRule.onAllNodes(isSelectable() and radioRole).assertCountEquals(3)
        composeRule.onAllNodes(isSelectable() and isSelected()).assertCountEquals(1)
        composeRule.onNodeWithText("B").assertIsSelected()
        composeRule.onNodeWithText("A").assertIsNotSelected()
        composeRule.onAllNodes(hasTestTag("wallhub-choice-selected-icon"), useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodes(hasTestTag("wallhub-choice-unselected-icon"), useUnmergedTree = true).assertCountEquals(2)
        composeRule.onNode(hasTestTag("wallhub-choice-selected-icon"), useUnmergedTree = true).assertHasNoClickAction()
        composeRule.onAllNodes(hasTestTag("wallhub-choice-unselected-icon"), useUnmergedTree = true)[0].assertHasNoClickAction()
        composeRule.onAllNodes(hasTestTag("wallhub-choice-unselected-icon"), useUnmergedTree = true)[1].assertHasNoClickAction()

        val selectedImage = composeRule.onNode(hasTestTag("wallhub-choice-row-1")).captureToImage()
        val unselectedImage = composeRule.onNode(hasTestTag("wallhub-choice-row-0")).captureToImage()
        val selectedPixel = selectedImage.toPixelMap()[selectedImage.width - 2, selectedImage.height / 2].toArgb()
        val unselectedPixel = unselectedImage.toPixelMap()[unselectedImage.width - 2, unselectedImage.height / 2].toArgb()
        assertEquals(selectedContainer.toArgb(), selectedPixel)
        assertNotEquals(selectedContainer.toArgb(), unselectedPixel)

        val selectedIconImage =
            composeRule
                .onNode(hasTestTag("wallhub-choice-selected-icon"), useUnmergedTree = true)
                .captureToImage()
        val unselectedIconImage =
            composeRule
                .onAllNodes(hasTestTag("wallhub-choice-unselected-icon"), useUnmergedTree = true)[0]
                .captureToImage()
        val selectedInterior =
            selectedIconImage.toPixelMap()[selectedIconImage.width / 2, selectedIconImage.height / 4].toArgb()
        val unselectedInterior =
            unselectedIconImage.toPixelMap()[unselectedIconImage.width / 2, unselectedIconImage.height / 4].toArgb()
        assertEquals(selectedForeground.toArgb(), selectedInterior)
        assertNotEquals(unselectedIconColor.toArgb(), unselectedInterior)

        val density = composeRule.activity.resources.displayMetrics.density
        val selectedRowBounds = composeRule.onNode(hasTestTag("wallhub-choice-row-1")).fetchSemanticsNode().boundsInRoot
        val selectedIconBounds =
            composeRule
                .onNode(hasTestTag("wallhub-choice-selected-icon"), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val selectedTextBounds =
            composeRule.onNodeWithText("B", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val firstRowBounds = composeRule.onNode(hasTestTag("wallhub-choice-row-0")).fetchSemanticsNode().boundsInRoot
        val rowHeightDp = selectedRowBounds.height / density
        val iconSizeDp = selectedIconBounds.width / density
        val iconTextGapDp = (selectedTextBounds.left - selectedIconBounds.right) / density
        val interRowGapDp = (selectedRowBounds.top - firstRowBounds.bottom) / density
        assertTrue("row height was $rowHeightDp dp", rowHeightDp in 48f..56f)
        assertTrue("icon size was $iconSizeDp dp", iconSizeDp in 23.5f..24.5f)
        assertTrue("icon/text gap was $iconTextGapDp dp", iconTextGapDp in 11.5f..12.5f)
        assertTrue("inter-row gap was $interRowGapDp dp", interRowGapDp in 3.5f..4.5f)

        composeRule.onNode(hasTestTag("wallhub-choice-row-0")).performTouchInput {
            click(Offset(width - 4f, height / 2f))
        }
        composeRule.runOnIdle { assertEquals("A", clickedValue) }
    }

    @Test
    fun rtlLongAndMultilingualLabelsRemainReadableRadioOptions() {
        val values =
            listOf(
                "فارسی — استفاده از تنظیمات پیش‌فرض سیستم برای نمایش متن طولانی",
                "繁體中文",
                "日本語",
                "한국어",
                "Русский язык",
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    WallHubSingleChoiceDialog(
                        title = "زبان",
                        selectedValue = values.first(),
                        values = values,
                        label = { it },
                        onDismissRequest = {},
                        onSelected = {},
                    )
                }
            }
        }

        val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        composeRule.onAllNodes(isSelectable() and radioRole).assertCountEquals(values.size)
        composeRule.onNodeWithText(values.first()).assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithText("繁體中文").assertIsDisplayed().assertIsNotSelected()
    }

    @Test
    fun descriptionsAreExposedWithTheOptionAndDoNotChangeRadioSemantics() {
        composeRule.setContent {
            MaterialTheme {
                WallHubSingleChoiceDialog(
                    title = "Appearance style",
                    selectedValue = "classic",
                    values = listOf("classic", "monet"),
                    label = { if (it == "classic") "Classic WallHub" else "Monet Bright" },
                    description = {
                        if (it == "classic") {
                            "Keeps the existing hierarchy"
                        } else {
                            "Tinted page and compact groups"
                        }
                    },
                    onDismissRequest = {},
                    onSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("Classic WallHub").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithText("Keeps the existing hierarchy").assertIsDisplayed()
        composeRule.onNodeWithText("Monet Bright").assertIsDisplayed().assertIsNotSelected()
        composeRule.onNodeWithText("Tinted page and compact groups").assertIsDisplayed()
    }

    @Test
    fun cancelClosesWithoutChangingValue() {
        var selected by mutableStateOf("B")
        var visible by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                if (visible) {
                    WallHubSingleChoiceDialog(
                        title = "Language",
                        selectedValue = selected,
                        values = listOf("A", "B", "C"),
                        label = { it },
                        onDismissRequest = { visible = false },
                        onSelected = { selected = it },
                    )
                }
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.settings_action_cancel)).performClick()
        composeRule.runOnIdle {
            assertEquals("B", selected)
            assertFalse(visible)
        }
    }

    @Test
    fun systemBackClosesWithoutChangingValue() {
        var selected by mutableStateOf("B")
        var visible by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                if (visible) {
                    WallHubSingleChoiceDialog(
                        title = "Language",
                        selectedValue = selected,
                        values = listOf("A", "B", "C"),
                        label = { it },
                        onDismissRequest = { visible = false },
                        onSelected = { selected = it },
                    )
                }
            }
        }

        pressBack()
        composeRule.runOnIdle {
            assertEquals("B", selected)
            assertFalse(visible)
        }
    }

    @Test
    fun longListOpensWithCurrentSelectionVisible() {
        composeRule.setContent {
            MaterialTheme {
                WallHubSingleChoiceDialog(
                    title = "Chunks",
                    selectedValue = 18,
                    values = (1..20).toList(),
                    label = { "Option $it" },
                    onDismissRequest = {},
                    onSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("Option 18").assertIsDisplayed().assertIsSelected()
    }

    @Test
    fun outsideScrimTapDismissesWithoutChangingValue() {
        var selected by mutableStateOf("B")
        var visible by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                if (visible) {
                    WallHubSingleChoiceDialog(
                        title = "Language",
                        selectedValue = selected,
                        values = listOf("A", "B", "C"),
                        label = { it },
                        onDismissRequest = { visible = false },
                        onSelected = { selected = it },
                    )
                }
            }
        }

        injectWindowTap(x = 10f, y = 640f)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals("B", selected)
            assertFalse(visible)
        }
    }

    @Test
    fun modalScrimKeepsBackgroundUnavailable() {
        var backgroundClicks = 0
        composeRule.setContent {
            MaterialTheme {
                androidx.compose.material3.Button(onClick = { backgroundClicks += 1 }) {
                    androidx.compose.material3.Text("Background action")
                }
                WallHubSingleChoiceDialog(
                    title = "Language",
                    selectedValue = "B",
                    values = listOf("A", "B", "C"),
                    label = { it },
                    onDismissRequest = {},
                    onSelected = {},
                )
            }
        }

        injectWindowTap(x = 50f, y = 50f)
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(0, backgroundClicks) }
    }
}

private fun injectWindowTap(x: Float, y: Float) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val downTime = SystemClock.uptimeMillis()
    fun event(action: Int) =
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
    event(MotionEvent.ACTION_DOWN).also { down ->
        try {
            automation.injectInputEvent(down, true)
        } finally {
            down.recycle()
        }
    }
    event(MotionEvent.ACTION_UP).also { up ->
        try {
            automation.injectInputEvent(up, true)
        } finally {
            up.recycle()
        }
    }
}
