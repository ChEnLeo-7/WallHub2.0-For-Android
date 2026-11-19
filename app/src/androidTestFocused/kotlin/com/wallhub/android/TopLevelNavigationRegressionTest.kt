package com.wallhub.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wallhub.android.testhost.WallHubTestActivity
import androidx.test.espresso.Espresso.pressBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TopLevelNavigationRegressionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WallHubTestActivity>()

    private lateinit var navController: NavHostController

    @Test
    fun downloadsTab_isBettboxOnlyAndClassicKeepsItsExistingDestinations() {
        assertEquals(
            listOf(TopLevelDestination.HOME, TopLevelDestination.DISCOVER, TopLevelDestination.PROFILE),
            topLevelDestinations(includeDownloads = false),
        )
        assertEquals(TopLevelDestination.entries, topLevelDestinations(includeDownloads = true))
    }

    @Test
    fun downloadsTransition_isSecondaryInClassicAndTopLevelInBettbox() {
        setNavigationContent()
        val home = navController.currentDestination ?: error("Missing Home destination")
        navigateTo(TopLevelDestination.DOWNLOADS)
        val downloads = navController.currentDestination ?: error("Missing Downloads destination")

        val classic =
            wallHubEnterTransition(
                initialDestination = home,
                targetDestination = downloads,
                animateTopLevelTransitions = false,
                includeDownloadsAsTopLevel = false,
                popping = false,
            )
        val bettbox =
            wallHubEnterTransition(
                initialDestination = home,
                targetDestination = downloads,
                animateTopLevelTransitions = false,
                includeDownloadsAsTopLevel = true,
                popping = false,
            )

        assertTrue(classic != EnterTransition.None)
        assertEquals(EnterTransition.None, bettbox)
    }

    @Test
    fun downloadsBrowseThenProfile_usesOneHomeAndStaysOnProfile() {
        setNavigationContent()

        navigateTo(TopLevelDestination.DOWNLOADS)
        navigateTo(TopLevelDestination.HOME) // Downloads empty-state CTA.
        navigateTo(TopLevelDestination.PROFILE)

        assertCurrent(TopLevelDestination.PROFILE)
        assertEquals(1, topLevelBackStack().count { it == TopLevelDestination.HOME })
        assertEquals(1, topLevelBackStack().count { it == TopLevelDestination.PROFILE })
    }

    @Test
    fun everyTopLevelDestination_remainsReachableAfterDownloadsBrowse() {
        setNavigationContent()

        navigateTo(TopLevelDestination.DOWNLOADS)
        navigateTo(TopLevelDestination.HOME)
        listOf(
            TopLevelDestination.DISCOVER,
            TopLevelDestination.DOWNLOADS,
            TopLevelDestination.PROFILE,
            TopLevelDestination.HOME,
        ).forEach { destination ->
            navigateTo(destination)
            assertCurrent(destination)
        }
    }

    @Test
    fun repeatedAndRapidTopLevelClicks_doNotDuplicateOrReplayHome() {
        setNavigationContent()

        navigateTo(TopLevelDestination.HOME)
        navigateTo(TopLevelDestination.HOME)
        composeRule.runOnIdle {
            navController.navigateToTopLevelDestination(TopLevelDestination.DISCOVER)
            navController.navigateToTopLevelDestination(TopLevelDestination.DOWNLOADS)
            navController.navigateToTopLevelDestination(TopLevelDestination.PROFILE)
        }

        assertCurrent(TopLevelDestination.PROFILE)
        assertEquals(1, topLevelBackStack().count { it == TopLevelDestination.PROFILE })
    }

    @Test
    fun systemBackFromAnotherTopLevelDestination_returnsToHome() {
        setNavigationContent()
        navigateTo(TopLevelDestination.DOWNLOADS)

        pressBack()
        composeRule.waitForIdle()

        assertCurrent(TopLevelDestination.HOME)
    }

    @Test
    fun restoredComposition_doesNotReplayDownloadsBrowseNavigation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            navController = rememberNavController()
            NavigationHarness(navController)
        }
        composeRule.waitForIdle()

        navigateTo(TopLevelDestination.DOWNLOADS)
        navigateTo(TopLevelDestination.HOME)
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertCurrent(TopLevelDestination.HOME)
        navigateTo(TopLevelDestination.PROFILE)
        assertCurrent(TopLevelDestination.PROFILE)
    }

    private fun setNavigationContent() {
        composeRule.setContent {
            navController = rememberNavController()
            NavigationHarness(navController)
        }
        composeRule.waitForIdle()
    }

    private fun navigateTo(destination: TopLevelDestination) {
        composeRule.runOnIdle { navController.navigateToTopLevelDestination(destination) }
        composeRule.waitForIdle()
    }

    private fun assertCurrent(expected: TopLevelDestination) {
        composeRule.runOnIdle {
            val current = navController.currentDestination
            checkNotNull(current)
            assertEquals(true, current.hasRoute(expected.target::class))
        }
    }

    private fun topLevelBackStack(): List<TopLevelDestination> =
        navController.currentBackStack.value.mapNotNull { entry ->
            TopLevelDestination.entries.firstOrNull { entry.destination.hasRoute(it.target::class) }
        }
}

@Composable
private fun NavigationHarness(navController: NavHostController) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentTopLevel = currentEntry?.destination?.topLevelDestinationOrNull()
    BackHandler(enabled = currentTopLevel != null && currentTopLevel != TopLevelDestination.HOME) {
        navController.navigateToTopLevelDestination(TopLevelDestination.HOME)
    }
    NavHost(navController = navController, startDestination = HomeDestination) {
        composable<HomeDestination> { DestinationLabel("home") }
        composable<DiscoverDestination> { DestinationLabel("discover") }
        composable<DownloadsDestination> { DestinationLabel("downloads") }
        composable<ProfileDestination> { DestinationLabel("profile") }
    }
}

@Composable
private fun DestinationLabel(label: String) {
    Box(modifier = Modifier) { Text(label) }
}
