package com.wallhub.android.core.designsystem

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallHubWindowLayoutTest {
    @Test
    fun `layout follows material width boundaries`() {
        assertEquals(WallHubWindowLayout.COMPACT, wallHubWindowLayout(599.dp))
        assertEquals(WallHubWindowLayout.MEDIUM, wallHubWindowLayout(600.dp))
        assertEquals(WallHubWindowLayout.MEDIUM, wallHubWindowLayout(839.dp))
        assertEquals(WallHubWindowLayout.EXPANDED, wallHubWindowLayout(840.dp))
    }

    @Test
    fun `floating navigation clearance includes system navigation inset`() {
        // 50dp item + 2 x 4dp container padding + 2 x 8dp outer padding + 8dp content gap.
        assertEquals(82.dp, wallHubOverlayBottomClearance(0.dp))
        assertEquals(106.dp, wallHubOverlayBottomClearance(24.dp))
        assertEquals(82.dp, wallHubOverlayBottomClearance((-4).dp))
        assertEquals(0.dp, wallHubOverlayBottomClearance(24.dp, navigationVisible = false))
    }

    @Test
    fun `height layout identifies short landscape windows`() {
        assertEquals(WallHubWindowHeight.COMPACT, wallHubWindowHeight(479.dp))
        assertEquals(WallHubWindowHeight.MEDIUM, wallHubWindowHeight(480.dp))
        assertEquals(WallHubWindowHeight.MEDIUM, wallHubWindowHeight(899.dp))
        assertEquals(WallHubWindowHeight.EXPANDED, wallHubWindowHeight(900.dp))
        assertTrue(WallHubAdaptiveLayout(WallHubWindowLayout.EXPANDED, WallHubWindowHeight.COMPACT).isShort)
        assertFalse(WallHubAdaptiveLayout(WallHubWindowLayout.MEDIUM, WallHubWindowHeight.MEDIUM).isShort)
    }
}
