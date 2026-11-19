package com.wallhub.android

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.wallhub.android.testhost.WallHubTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WorkshopDetailNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WallHubTestActivity>()

    private lateinit var navController: NavHostController
    private lateinit var homeEntry: NavBackStackEntry

    @Test
    fun rapidClicksOnSameAndDifferentCardsPushOnlyFirstDetail() {
        setContent()
        composeRule.runOnIdle {
            assertEquals(Lifecycle.State.RESUMED, homeEntry.lifecycle.currentState)
            navController.navigateToWorkshopDetail(homeEntry, 42L)
            repeat(5) {
                navController.navigateToWorkshopDetail(homeEntry, 42L)
                navController.navigateToWorkshopDetail(homeEntry, 99L)
            }
            val details = navController.currentBackStack.value.filter {
                it.destination.hasRoute<WorkshopDetailDestination>()
            }
            assertEquals(1, details.size)
            assertEquals(42L, details.single().toRoute<WorkshopDetailDestination>().workshopId)
        }
    }

    @Test
    fun returningToHomeAllowsAnotherDeliberateNavigation() {
        setContent()
        composeRule.runOnIdle { navController.navigateToWorkshopDetail(homeEntry, 42L) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertTrue(navController.popBackStack()) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(Lifecycle.State.RESUMED, homeEntry.lifecycle.currentState)
            navController.navigateToWorkshopDetail(homeEntry, 99L)
            assertEquals(99L, navController.currentBackStackEntry!!.toRoute<WorkshopDetailDestination>().workshopId)
        }
    }

    private fun setContent() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = HomeDestination) {
                composable<HomeDestination> { entry ->
                    homeEntry = entry
                    Text("home")
                }
                composable<WorkshopDetailDestination> { Text("detail") }
            }
        }
        composeRule.waitForIdle()
    }
}
