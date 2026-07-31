package com.wallhub.android.feature.local

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.LocalWallpaperFormat
import com.wallhub.android.core.model.LocalWallpaperResource
import com.wallhub.android.core.model.LocalWallpaperScanSnapshot
import com.wallhub.android.core.model.ThemePreference
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalWallpaperListInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tapping_a_local_list_item_selects_its_resource() {
        var selectedResourceId: String? = null
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                language = AppLanguage.EN,
                useSystemMonet = false,
            ) {
                LocalWallpaperList(
                    resources = listOf(localResource),
                    state =
                        LocalWallpaperUiState(
                            scan = LocalWallpaperScanSnapshot(resources = listOf(localResource)),
                        ),
                    language = AppLanguage.EN,
                    onSelectResource = { resourceId -> selectedResourceId = resourceId },
                    onStartSelection = {},
                    onToggleSelection = {},
                )
            }
        }

        composeRule.onNodeWithTag("local-wallpaper-${localResource.id}").performClick()

        composeRule.runOnIdle { assertEquals(localResource.id, selectedResourceId) }
    }
}

private val localResource =
    LocalWallpaperResource(
        id = "local-interaction-resource",
        contentUri = "content://wallhub/local-interaction-resource",
        displayName = "local-interaction.mpkg",
        title = "Local interaction wallpaper",
        format = LocalWallpaperFormat.MPKG,
        sourceId = "downloads",
        sourceLabel = "Downloads",
        relativePath = "WallHub/local-interaction.mpkg",
        detectionReason = "MPKG archive",
    )
