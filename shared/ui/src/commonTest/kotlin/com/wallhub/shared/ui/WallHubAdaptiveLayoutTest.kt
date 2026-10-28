package com.wallhub.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class WallHubAdaptiveLayoutTest {
    @Test
    fun `window width boundaries select the agreed navigation mode`() {
        assertEquals(WallHubWindowSizeClass.Compact, wallHubWindowSizeClass(599f))
        assertEquals(WallHubWindowSizeClass.Medium, wallHubWindowSizeClass(600f))
        assertEquals(WallHubWindowSizeClass.Medium, wallHubWindowSizeClass(839f))
        assertEquals(WallHubWindowSizeClass.Expanded, wallHubWindowSizeClass(840f))
    }
}
