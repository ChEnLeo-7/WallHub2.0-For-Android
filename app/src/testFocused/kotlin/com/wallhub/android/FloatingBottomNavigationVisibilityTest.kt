package com.wallhub.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FloatingBottomNavigationVisibilityTest {
    @Test
    fun `enabled Home collapse hides floating navigation`() {
        assertFalse(
            shouldShowFloatingBottomNavigation(
                isTopLevelDestination = true,
                currentTopLevelDestination = TopLevelDestination.HOME,
                autoHideEnabled = true,
                homeBottomNavigationCollapsed = true,
            ),
        )
    }

    @Test
    fun `disabled preference keeps Home floating navigation visible`() {
        assertTrue(
            shouldShowFloatingBottomNavigation(
                isTopLevelDestination = true,
                currentTopLevelDestination = TopLevelDestination.HOME,
                autoHideEnabled = false,
                homeBottomNavigationCollapsed = true,
            ),
        )
    }

    @Test
    fun `stale Home collapse cannot hide another destination`() {
        assertTrue(
            shouldShowFloatingBottomNavigation(
                isTopLevelDestination = true,
                currentTopLevelDestination = TopLevelDestination.DOWNLOADS,
                autoHideEnabled = true,
                homeBottomNavigationCollapsed = true,
            ),
        )
    }

    @Test
    fun `secondary destination does not show floating navigation`() {
        assertFalse(
            shouldShowFloatingBottomNavigation(
                isTopLevelDestination = false,
                currentTopLevelDestination = null,
                autoHideEnabled = true,
                homeBottomNavigationCollapsed = false,
            ),
        )
    }
}
