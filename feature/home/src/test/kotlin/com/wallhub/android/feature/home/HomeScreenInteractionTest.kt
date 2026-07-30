package com.wallhub.android.feature.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AppLanguage
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
class HomeScreenInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tapping_a_home_result_opens_its_detail() {
        var openedWorkshopId: Long? = null
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                language = AppLanguage.EN,
                useSystemMonet = false,
            ) {
                HomeScreen(
                    state =
                        HomeUiState(
                            items = listOf(homeResult),
                            isInitialLoading = false,
                        ),
                    onAction = { action ->
                        if (action is HomeAction.OpenDetail) openedWorkshopId = action.workshopId
                    },
                )
            }
        }

        composeRule.onNodeWithTag("home-workshop-${homeResult.id}").performClick()

        composeRule.runOnIdle { assertEquals(homeResult.id, openedWorkshopId) }
    }
}

private val homeResult =
    WorkshopSummary(
        id = 7001L,
        title = "Home interaction wallpaper",
        author = "WallHub Test",
        type = WorkshopType.SCENE,
    )
