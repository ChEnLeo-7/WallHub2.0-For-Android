package com.wallhub.android.feature.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryScreenInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tapping_a_library_result_opens_its_detail() {
        var openedWorkshopId: Long? = null
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                language = AppLanguage.EN,
                useSystemMonet = false,
            ) {
                LibraryScreen(
                    state =
                        LibraryUiState(
                            session = SteamSessionState(phase = SteamSessionPhase.SIGNED_IN),
                            items = listOf(libraryResult),
                        ),
                    onAction = { action ->
                        if (action is LibraryAction.OpenDetail) openedWorkshopId = action.workshopId
                    },
                )
            }
        }

        composeRule.onNodeWithTag("library-workshop-${libraryResult.id}").performClick()

        composeRule.runOnIdle { assertEquals(libraryResult.id, openedWorkshopId) }
    }
}

private val libraryResult =
    WorkshopSummary(
        id = 7002L,
        title = "Library interaction wallpaper",
        author = "WallHub Test",
        type = WorkshopType.SCENE,
    )
