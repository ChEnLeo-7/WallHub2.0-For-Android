package com.wallhub.android.feature.downloads

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.MpkgConversionOptions
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.core.model.DownloadTask
import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.testhost.WallHubTestActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BettboxUiMatrixTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WallHubTestActivity>()

    @Test
    fun darkThemeDownloadsKeepSelectedTabAndExpandableActions() {
        val activeTab = composeRule.activity.getString(R.string.downloads_tab_active)
        val expandActions = composeRule.activity.getString(R.string.downloads_expand_actions)
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.DARK,
                accent = AccentPreference.MONET,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                DownloadsScreen(state = DownloadsUiState(allTasks = listOf(task)), onAction = {})
            }
        }

        composeRule.onNodeWithText(activeTab).assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithContentDescription(expandActions).assertIsDisplayed()
    }

    @Test
    fun rtlLayoutKeepsTabSelectionSemanticsUsable() {
        val activeTab = composeRule.activity.getString(R.string.downloads_tab_active)
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WallHubTheme(
                    preference = ThemePreference.LIGHT,
                    accent = AccentPreference.MONET,
                    useSystemMonet = false,
                    visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
                ) {
                    DownloadsScreen(state = DownloadsUiState(allTasks = listOf(task)), onAction = {})
                }
            }
        }

        composeRule.onNodeWithText(activeTab).assertIsDisplayed().assertIsSelected()
    }

    @Test
    fun mpkgPresetsRemainVisibleAtLargeFontScale() {
        val labels =
            listOf(
                R.string.mpkg_preset_standard,
                R.string.mpkg_preset_compatibility,
                R.string.mpkg_preset_high_quality,
                R.string.mpkg_preset_extreme_quality,
            ).map { composeRule.activity.getString(it) }
        val baseDensity = Density(composeRule.density.density, fontScale = 1.5f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides baseDensity) {
                WallHubTheme(
                    preference = ThemePreference.LIGHT,
                    accent = AccentPreference.MONET,
                    useSystemMonet = false,
                    visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
                ) {
                    MpkgConversionOptions(
                        preset = MpkgConversionPreset.HIGH_QUALITY,
                        onPresetSelected = {},
                        preserveAudio = false,
                        onPreserveAudioChanged = {},
                    )
                }
            }
        }

        labels.forEach { label -> composeRule.onNodeWithText(label).assertIsDisplayed() }
    }

    @Test
    fun squareCardAtLargeFontScaleKeepsTitleAndStatusVisible() {
        val title = "Blue Archive-Arona 阿洛娜｜繁花似锦超长标题换行验证"
        val statusLabel = composeRule.activity.getString(R.string.downloads_status_downloading)
        val largeDensity = Density(composeRule.density.density, fontScale = 1.4f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides largeDensity) {
                WallHubTheme(
                    preference = ThemePreference.LIGHT,
                    accent = AccentPreference.MONET,
                    useSystemMonet = false,
                    visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
                ) {
                    BettboxDownloadList(
                        tasks = listOf(squareLongTitleTask(title)),
                        viewMode = DownloadViewMode.SQUARE,
                        onDownloadsAction = {},
                        onOpenDetail = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText(statusLabel).assertIsDisplayed()
    }

    private fun squareLongTitleTask(title: String) =
        DownloadTask(
            id = "matrix-long-title",
            workshopId = 41L,
            title = title,
            type = WorkshopType.VIDEO,
            status = DownloadStatus.DOWNLOADING,
            downloadedBytes = 0L,
            totalBytes = 0L,
        )

    private val task =
        DownloadTask(
            id = "matrix-task",
            workshopId = 11L,
            title = "雨夜列车",
            type = WorkshopType.VIDEO,
            status = DownloadStatus.DOWNLOADING,
            downloadedBytes = 10_000_000L,
            totalBytes = 50_000_000L,
        )
}

