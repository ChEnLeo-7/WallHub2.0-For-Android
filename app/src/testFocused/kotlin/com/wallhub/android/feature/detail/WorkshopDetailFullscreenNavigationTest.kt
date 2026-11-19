package com.wallhub.android.feature.detail

import com.wallhub.android.shouldShowAdaptiveNavigation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopDetailFullscreenNavigationTest {
    @Test
    fun fullscreenPlaybackHidesNavigationForExpandedWindow() {
        assertFalse(shouldShowAdaptiveNavigation(isTopLevelDestination = false, fullscreenPlayback = true))
        assertTrue(shouldShowAdaptiveNavigation(isTopLevelDestination = true, fullscreenPlayback = false))
        assertFalse(shouldShowAdaptiveNavigation(isTopLevelDestination = true, fullscreenPlayback = true))
    }
}
