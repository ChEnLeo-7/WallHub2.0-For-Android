package com.wallhub.android.core.designsystem

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class WallHubWindowSizeClassTest {
    @Test
    fun `width classes use stable material boundaries`() {
        assertEquals(WallHubWindowSizeClass.COMPACT, wallHubWindowSizeClass(599.dp))
        assertEquals(WallHubWindowSizeClass.MEDIUM, wallHubWindowSizeClass(600.dp))
        assertEquals(WallHubWindowSizeClass.MEDIUM, wallHubWindowSizeClass(839.dp))
        assertEquals(WallHubWindowSizeClass.EXPANDED, wallHubWindowSizeClass(840.dp))
    }
}
