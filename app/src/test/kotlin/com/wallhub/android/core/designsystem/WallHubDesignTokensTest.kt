package com.wallhub.android.core.designsystem

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallHubDesignTokensTest {
    @Test
    fun `spacing tokens expose the shared layout scale`() {
        assertEquals(0.dp, WallHubSpacing.none)
        assertEquals(1.dp, WallHubSpacing.hairline)
        assertEquals(2.dp, WallHubSpacing.xxxs)
        assertEquals(4.dp, WallHubSpacing.xxs)
        assertEquals(6.dp, WallHubSpacing.dense)
        assertEquals(8.dp, WallHubSpacing.xs)
        assertEquals(10.dp, WallHubSpacing.compact)
        assertEquals(12.dp, WallHubSpacing.sm)
        assertEquals(16.dp, WallHubSpacing.md)
        assertEquals(24.dp, WallHubSpacing.lg)
        assertEquals(32.dp, WallHubSpacing.xl)
    }

    @Test
    fun `interactive controls retain the material minimum touch target`() {
        assertTrue(WallHubSizeTokens.minimumTouchTarget >= 48.dp)
        assertEquals(20.dp, WallHubSizeTokens.smallIcon)
        assertEquals(40.dp, WallHubSizeTokens.compactActionHeight)
        assertEquals(36.dp, WallHubSizeTokens.compactIconButton)
        assertEquals(56.dp, WallHubSizeTokens.listItemMinimumHeight)
    }

    @Test
    fun `expanded navigation and content widths remain bounded`() {
        assertEquals(240.dp, WallHubSizeTokens.expandedNavigationDrawerWidth)
        assertTrue(WallHubSizeTokens.readableContentMaxWidth >= 840.dp)
        assertEquals(920.dp, WallHubSizeTokens.modalContentMaxWidth)
    }

    @Test
    fun `material shapes expose the shared shape roles`() {
        val shapes = WallHubShapeTokens.material

        assertEquals(WallHubShapeTokens.medium, shapes.medium)
        assertEquals(WallHubShapeTokens.large, shapes.large)
        assertEquals(WallHubShapeTokens.extraLarge, shapes.extraLarge)
    }
}
