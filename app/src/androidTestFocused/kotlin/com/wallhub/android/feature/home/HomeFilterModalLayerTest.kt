package com.wallhub.android.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wallhub.android.R
import com.wallhub.android.WallHubFloatingNavigationLayer
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.testhost.WallHubTestActivity
import androidx.test.espresso.Espresso.pressBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeFilterModalLayerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WallHubTestActivity>()

    @Test
    fun openDrawer_coversFloatingNavigationAndBlocksItsTouchTarget() {
        val harness = setModalContent()
        val navNode = composeRule.onNodeWithTag(FLOATING_NAV_TAG, useUnmergedTree = true)
        val navBounds = navNode.fetchSemanticsNode().boundsInRoot
        val navCenter = navBounds.center
        openDrawer()
        navNode.assertIsDisplayed()
        assertEquals(navBounds, navNode.fetchSemanticsNode().boundsInRoot)
        composeRule.onNodeWithTag(FLOATING_NAV_TAG).assertDoesNotExist()

        composeRule.onRoot().performTouchInput { click(navCenter) }

        composeRule.runOnIdle { assertEquals(0, harness.navigationClicks) }
        composeRule.waitUntil { !harness.modalActive }
        composeRule.onNode(paneMatcher()).assertIsNotDisplayed()
    }

    @Test
    fun drawerClose_keepsOneFloatingNavigationComposition() {
        val harness = setModalContent()
        composeRule.runOnIdle {
            assertEquals(1, harness.navigationCompositions)
            assertEquals(1, harness.activeNavigationCompositions)
        }

        openDrawer()
        val navNode = composeRule.onNodeWithTag(FLOATING_NAV_TAG, useUnmergedTree = true)
        val navBounds = navNode.fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle {
            assertEquals(1, harness.navigationCompositions)
            assertEquals(1, harness.activeNavigationCompositions)
        }

        composeRule.mainClock.autoAdvance = false
        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.home_filter_close))
            .performClick()
        repeat(45) {
            composeRule.mainClock.advanceTimeByFrame()
            assertEquals(navBounds, navNode.fetchSemanticsNode().boundsInRoot)
            composeRule.runOnIdle {
                assertEquals(1, harness.navigationCompositions)
                assertEquals(1, harness.activeNavigationCompositions)
                assertEquals(0, harness.navigationDisposals)
            }
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitUntil { !harness.modalActive }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(1, harness.navigationCompositions)
            assertEquals(1, harness.activeNavigationCompositions)
            assertEquals(0, harness.navigationDisposals)
        }
    }

    @Test
    fun fiveDrawerClosures_doNotRestartFloatingNavigation() {
        val harness = setModalContent()
        repeat(5) {
            openDrawer()
            composeRule
                .onNodeWithContentDescription(composeRule.activity.getString(R.string.home_filter_close))
                .performClick()
            composeRule.waitUntil { !harness.modalActive }
        }

        composeRule.runOnIdle {
            assertEquals(1, harness.navigationCompositions)
            assertEquals(1, harness.activeNavigationCompositions)
            assertEquals(0, harness.navigationDisposals)
        }
    }

    @Test
    fun closeThenImmediatelyReopen_keepsFloatingNavigationStable() {
        val harness = setModalContent()
        openDrawer()
        composeRule.mainClock.autoAdvance = false
        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.home_filter_close))
            .performClick()
        repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.home_open_all_filters))
            .performClick()
        repeat(45) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitUntil { harness.modalActive }
        composeRule.onNode(paneMatcher()).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, harness.navigationCompositions)
            assertEquals(1, harness.activeNavigationCompositions)
            assertEquals(0, harness.navigationDisposals)
        }
    }

    @Test
    fun downwardHomeBrowsing_hidesFloatingSearchAndReverseBrowsingRestoresIt() {
        var collapsed by mutableStateOf(false)
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                HomeScreen(
                    state =
                        HomeUiState(
                            isInitialLoading = false,
                            homeSearchFab = true,
                            homeBottomBarAutoHide = true,
                            items =
                                List(30) { index ->
                                    WorkshopSummary(
                                        id = index.toLong() + 1,
                                        title = "Wallpaper $index",
                                        author = "Author",
                                        type = WorkshopType.VIDEO,
                                    )
                                },
                        ),
                    onAction = {},
                    onBottomNavigationCollapsedChanged = { collapsed = it },
                    floatingBottomBarAutoHideActive = true,
                )
            }
        }
        val search = composeRule.activity.getString(R.string.home_search)
        composeRule.onNodeWithContentDescription(search).assertIsDisplayed()

        composeRule.onRoot().performTouchInput {
            swipe(
                start = percentOffset(0.5f, 0.7f),
                end = percentOffset(0.5f, 0.2f),
                durationMillis = 500,
            )
        }
        composeRule.waitUntil(3_000) { collapsed }
        composeRule.onNodeWithContentDescription(search).assertDoesNotExist()

        composeRule.onRoot().performTouchInput {
            swipe(
                start = percentOffset(0.5f, 0.3f),
                end = percentOffset(0.5f, 0.7f),
                durationMillis = 400,
            )
        }
        composeRule.waitUntil(3_000) { !collapsed }
        composeRule.onNodeWithContentDescription(search).assertIsDisplayed()
    }

    @Test
    fun applyButton_isReachableAndAppliesOnlyOnConfirmation() {
        val harness = setModalContent(fontScale = 1.5f, compactHeight = true)
        openDrawer()

        composeRule.runOnIdle { assertEquals(0, harness.appliedFilters) }
        val applyNode = composeRule.onNodeWithText(composeRule.activity.getString(R.string.home_filter_apply))
        applyNode.assertIsDisplayed()
        val applyBounds = applyNode.getBoundsInRoot()
        val paneBounds = composeRule.onNode(paneMatcher()).getBoundsInRoot()
        assertTrue(paneBounds.bottom - applyBounds.bottom >= 8.dp)
        applyNode.performClick()

        composeRule.runOnIdle { assertEquals(1, harness.appliedFilters) }
        composeRule.waitUntil { !harness.modalActive }
        composeRule.onNode(paneMatcher()).assertIsNotDisplayed()
    }

    @Test
    fun filterOptionsRemainScrollableAndApplyTheSelectedDraft() {
        val harness = setModalContent()
        openDrawer()

        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.home_7_days))
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(0, harness.appliedFilters) }
        composeRule
            .onNodeWithText(composeRule.activity.getString(R.string.home_filter_apply))
            .performClick()

        composeRule.runOnIdle { assertEquals(1, harness.appliedFilters) }
    }

    @Test
    fun backAndScrim_cancelDraftWithoutApplying() {
        val harness = setModalContent()
        openDrawer()
        composeRule.waitUntil { harness.modalActive }
        pressBack()
        composeRule.waitForIdle()

        composeRule.waitUntil { !harness.modalActive }
        composeRule.onNode(paneMatcher()).assertIsNotDisplayed()
        composeRule.runOnIdle { assertEquals(0, harness.appliedFilters) }

        openDrawer()
        composeRule.onRoot().performTouchInput { click(percentOffset(0.99f, 0.5f)) }
        composeRule.waitUntil { !harness.modalActive }
        composeRule.onNode(paneMatcher()).assertIsNotDisplayed()
        composeRule.runOnIdle {
            assertEquals(0, harness.appliedFilters)
            assertEquals(1, harness.navigationCompositions)
            assertEquals(1, harness.activeNavigationCompositions)
            assertEquals(0, harness.navigationDisposals)
        }
    }

    @Test
    fun darkBettbox_keepsDrawerNavigationLifecycleStable() {
        assertThemeKeepsDrawerNavigationStable(themePreference = ThemePreference.DARK)
    }

    @Test
    fun dynamicColorBettbox_keepsDrawerNavigationLifecycleStable() {
        assertThemeKeepsDrawerNavigationStable(useSystemMonet = true)
    }

    @Test
    fun classicWallHub_keepsExistingDrawerNavigationLifecycleStable() {
        assertThemeKeepsDrawerNavigationStable(visualStyle = VisualStyle.MATERIAL3)
    }

    @Test
    fun rtl_drawerUsesStartEdgeAndKeepsApplyReachable() {
        setModalContent(layoutDirection = LayoutDirection.Rtl)
        openDrawer()

        val paneBounds = composeRule.onNode(paneMatcher()).getBoundsInRoot()
        val rootBounds = composeRule.onRoot().getBoundsInRoot()
        assertTrue(paneBounds.right == rootBounds.right)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.home_filter_apply)).assertIsDisplayed()
    }

    private fun assertThemeKeepsDrawerNavigationStable(
        themePreference: ThemePreference = ThemePreference.LIGHT,
        useSystemMonet: Boolean = false,
        visualStyle: VisualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
    ) {
        val harness =
            setModalContent(
                themePreference = themePreference,
                useSystemMonet = useSystemMonet,
                visualStyle = visualStyle,
            )
        openDrawer()
        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.home_filter_close))
            .performClick()
        composeRule.waitUntil { !harness.modalActive }
        composeRule.runOnIdle {
            assertEquals(1, harness.navigationCompositions)
            assertEquals(1, harness.activeNavigationCompositions)
            assertEquals(0, harness.navigationDisposals)
        }
    }

    private fun setModalContent(
        fontScale: Float = 1f,
        compactHeight: Boolean = false,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        themePreference: ThemePreference = ThemePreference.LIGHT,
        useSystemMonet: Boolean = false,
        visualStyle: VisualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
    ): ModalHarness {
        val harness = ModalHarness()
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WallHubTheme(
                    preference = themePreference,
                    useSystemMonet = useSystemMonet,
                    visualStyle = visualStyle,
                ) {
                    Box(modifier = if (compactHeight) Modifier.size(360.dp, 420.dp) else Modifier.fillMaxSize()) {
                        WallHubFloatingNavigationLayer(
                            modalContentActive = harness.modalActive,
                            content = {
                                HomeScreen(
                                    state =
                                        HomeUiState(
                                            isInitialLoading = false,
                                            homeSearchFab = false,
                                        ),
                                    onAction = { if (it is HomeAction.ApplyFilters) harness.appliedFilters += 1 },
                                    onContextMenuActiveChanged = { harness.modalActive = it },
                                    floatingNavigation = {},
                                )
                            },
                            navigation = {
                                TrackedFloatingNavigation(harness)
                            },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return harness
    }

    private fun openDrawer() {
        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.home_open_all_filters))
            .performClick()
        composeRule.waitUntil { composeRule.onNode(paneMatcher()).isDisplayed() }
        composeRule.onNode(paneMatcher()).assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.isDisplayed(): Boolean =
        runCatching { assertIsDisplayed() }.isSuccess

    @Composable
    private fun BoxScope.TrackedFloatingNavigation(harness: ModalHarness) {
        DisposableEffect(Unit) {
            harness.navigationCompositions += 1
            harness.activeNavigationCompositions += 1
            onDispose {
                harness.activeNavigationCompositions -= 1
                harness.navigationDisposals += 1
            }
        }
        Button(
            onClick = { harness.navigationClicks += 1 },
            modifier = Modifier.align(Alignment.BottomEnd).testTag(FLOATING_NAV_TAG),
        ) {
            Text("Underlying navigation")
        }
    }

    private fun paneMatcher(): SemanticsMatcher =
        SemanticsMatcher.expectValue(
            SemanticsProperties.PaneTitle,
            composeRule.activity.getString(R.string.home_filter_drawer_title),
        )

    private class ModalHarness {
        var modalActive by mutableStateOf(false)
        var navigationClicks = 0
        var navigationCompositions = 0
        var activeNavigationCompositions = 0
        var navigationDisposals = 0
        var appliedFilters = 0
    }

    private companion object {
        const val FLOATING_NAV_TAG = "underlying-floating-navigation"
    }
}

