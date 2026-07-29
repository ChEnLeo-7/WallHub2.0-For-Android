package com.wallhub.android.core.designsystem

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallHubDesignTokensTest {
    @Test
    fun `spacing tokens follow the shared eight point rhythm`() {
        assertEquals(4.dp, WallHubSpacing.xxs)
        assertEquals(8.dp, WallHubSpacing.xs)
        assertEquals(12.dp, WallHubSpacing.sm)
        assertEquals(16.dp, WallHubSpacing.md)
        assertEquals(24.dp, WallHubSpacing.lg)
        assertEquals(32.dp, WallHubSpacing.xl)
    }

    @Test
    fun `interactive controls retain the material minimum touch target`() {
        assertTrue(WallHubSizeTokens.minimumTouchTarget >= 48.dp)
    }

    @Test
    fun `material shapes expose the shared shape roles`() {
        val shapes = WallHubShapeTokens.material

        assertEquals(WallHubShapeTokens.medium, shapes.medium)
        assertEquals(WallHubShapeTokens.large, shapes.large)
        assertEquals(WallHubShapeTokens.extraLarge, shapes.extraLarge)
    }
}
