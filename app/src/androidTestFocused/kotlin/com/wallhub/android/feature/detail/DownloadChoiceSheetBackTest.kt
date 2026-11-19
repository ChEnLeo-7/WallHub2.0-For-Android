package com.wallhub.android.feature.detail

import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.WorkshopType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DownloadChoiceSheetBackTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MpkgSheetTestActivity>()

    @Test
    fun one_system_back_closes_quality_sheet_and_preserves_detail() {
        var dismissCount = 0
        var downloadCount = 0
        var detailBackCount = 0
        var selectedPreset = MpkgConversionPreset.HIGH_QUALITY
        var preserveAudio = true
        val downloadMpkgLabel = composeRule.activity.getString(R.string.detail_download_mpkg)
        val extremeQualityLabel = composeRule.activity.getString(R.string.mpkg_preset_extreme_quality)
        val preserveAudioLabel = composeRule.activity.getString(R.string.detail_preserve_audio)
        val qualityLabel = setContentAndOpenQualitySheet(
            onDismiss = { dismissCount += 1 },
            onDownload = { downloadCount += 1 },
            onDetailBack = { detailBackCount += 1 },
            showReopenButton = true,
            onPresetSelected = { selectedPreset = it },
            onPreserveAudioChanged = { preserveAudio = it },
        )

        composeRule.onNodeWithText(extremeQualityLabel).performClick()
        composeRule.onNodeWithText(preserveAudioLabel).performClick()
        composeRule.runOnIdle {
            assertEquals(MpkgConversionPreset.EXTREME_QUALITY, selectedPreset)
            assertEquals(false, preserveAudio)
        }

        pressBack()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(qualityLabel).assertCountEquals(0)
        composeRule.onNodeWithText("Wallpaper detail").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, dismissCount)
            assertEquals(0, detailBackCount)
            assertEquals(0, downloadCount)
        }

        pressBack()
        composeRule.runOnIdle { assertEquals(1, detailBackCount) }

        composeRule.onNodeWithText("Open download options").performClick()
        composeRule.onNodeWithText(downloadMpkgLabel).performClick()
        composeRule.onNodeWithText(qualityLabel).assertIsDisplayed()
        composeRule.onNodeWithText(extremeQualityLabel).assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(MpkgConversionPreset.EXTREME_QUALITY, selectedPreset)
            assertEquals(false, preserveAudio)
        }
        pressBack()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(qualityLabel).assertCountEquals(0)
        composeRule.runOnIdle {
            assertEquals(2, dismissCount)
            assertEquals(1, detailBackCount)
            assertEquals(0, downloadCount)
        }
    }

    @Test
    fun tapping_scrim_closes_quality_sheet_without_downloading() {
        var dismissCount = 0
        var downloadCount = 0
        val qualityLabel = setContentAndOpenQualitySheet(
            onDismiss = { dismissCount += 1 },
            onDownload = { downloadCount += 1 },
        )

        onView(isRoot())
            .inRoot(isDialog())
            .perform(
                GeneralClickAction(
                    Tap.SINGLE,
                    fractionCoordinates(xFraction = 0.5f, yFraction = 0.2f),
                    Press.FINGER,
                    InputDevice.SOURCE_UNKNOWN,
                    MotionEvent.BUTTON_PRIMARY,
                ),
            )
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(qualityLabel).assertCountEquals(0)
        composeRule.onNodeWithText("Wallpaper detail").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, dismissCount)
            assertEquals(0, downloadCount)
        }
    }

    @Test
    fun swiping_down_closes_quality_sheet_without_downloading() {
        var dismissCount = 0
        var downloadCount = 0
        val qualityLabel = setContentAndOpenQualitySheet(
            onDismiss = { dismissCount += 1 },
            onDownload = { downloadCount += 1 },
        )

        onView(isRoot())
            .inRoot(isDialog())
            .perform(
                GeneralSwipeAction(
                    Swipe.FAST,
                    fractionCoordinates(xFraction = 0.5f, yFraction = 0.5f),
                    fractionCoordinates(xFraction = 0.5f, yFraction = 0.95f),
                    Press.FINGER,
                ),
            )
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(qualityLabel).assertCountEquals(0)
        composeRule.onNodeWithText("Wallpaper detail").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, dismissCount)
            assertEquals(0, downloadCount)
        }
    }

    private fun setContentAndOpenQualitySheet(
        onDismiss: () -> Unit,
        onDownload: () -> Unit,
        onDetailBack: (() -> Unit)? = null,
        showReopenButton: Boolean = false,
        onPresetSelected: (MpkgConversionPreset) -> Unit = {},
        onPreserveAudioChanged: (Boolean) -> Unit = {},
    ): String {
        val downloadMpkgLabel = composeRule.activity.getString(R.string.detail_download_mpkg)
        val qualityLabel = composeRule.activity.getString(R.string.detail_mpkg_conversion_quality)
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                useSystemMonet = false,
            ) {
                var sheetVisible by remember { mutableStateOf(true) }
                var preset by remember { mutableStateOf(MpkgConversionPreset.HIGH_QUALITY) }
                var preserveAudio by remember { mutableStateOf(true) }
                BackHandler(enabled = !sheetVisible && onDetailBack != null) {
                    onDetailBack?.invoke()
                }
                Box {
                    Column {
                        Text("Wallpaper detail")
                        if (showReopenButton) {
                            Button(onClick = { sheetVisible = true }) {
                                Text("Open download options")
                            }
                        }
                    }
                    if (sheetVisible) {
                        DownloadChoiceSheet(
                            type = WorkshopType.VIDEO,
                            exportFormats = listOf(ExportFormat.MPKG, ExportFormat.ZIP),
                            mpkgConversionPreset = preset,
                            onMpkgConversionPresetSelected = {
                                preset = it
                                onPresetSelected(it)
                            },
                            preserveAudio = preserveAudio,
                            onPreserveAudioChanged = {
                                preserveAudio = it
                                onPreserveAudioChanged(it)
                            },
                            onDismiss = {
                                onDismiss()
                                sheetVisible = false
                            },
                            onDownload = { onDownload() },
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText(downloadMpkgLabel).performClick()
        composeRule.onNodeWithText(qualityLabel).assertIsDisplayed()
        return qualityLabel
    }

    private fun fractionCoordinates(
        xFraction: Float,
        yFraction: Float,
    ) = CoordinatesProvider { view ->
        floatArrayOf(view.width * xFraction, view.height * yFraction)
    }
}
