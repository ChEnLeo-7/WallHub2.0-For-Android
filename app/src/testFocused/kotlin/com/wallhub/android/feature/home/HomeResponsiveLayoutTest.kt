package com.wallhub.android.feature.home

import androidx.compose.ui.unit.dp
import com.wallhub.android.core.designsystem.WallHubWindowLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeResponsiveLayoutTest {
    @Test
    fun `automatic card count defaults differ between phone and Pad`() {
        assertEquals(2, displayedHomeCardsPerRow(0, WallHubWindowLayout.COMPACT))
        assertEquals(2, displayedHomeCardsPerRow(0, WallHubWindowLayout.MEDIUM))
        assertEquals(4, displayedHomeCardsPerRow(0, WallHubWindowLayout.EXPANDED))
    }

    @Test
    fun `explicit cards per row supports one through eight`() {
        assertEquals(3, displayedHomeCardsPerRow(3, WallHubWindowLayout.COMPACT))
        assertEquals(5, displayedHomeCardsPerRow(5, WallHubWindowLayout.EXPANDED))
        assertEquals(8, displayedHomeCardsPerRow(8, WallHubWindowLayout.EXPANDED))
    }

    @Test
    fun `automatic expanded density is not capped at five columns`() {
        assertEquals(
            4,
            effectiveHomeGridColumns(
                availableWidth = 817.dp,
                configuredColumns = 0,
                windowLayout = WallHubWindowLayout.EXPANDED,
            ),
        )
        assertEquals(
            5,
            effectiveHomeGridColumns(
                availableWidth = 1009.dp,
                configuredColumns = 0,
                windowLayout = WallHubWindowLayout.EXPANDED,
            ),
        )
        assertEquals(
            6,
            effectiveHomeGridColumns(
                availableWidth = 1200.dp,
                configuredColumns = 0,
                windowLayout = WallHubWindowLayout.EXPANDED,
            ),
        )
        assertEquals(
            7,
            effectiveHomeGridColumns(
                availableWidth = 1400.dp,
                configuredColumns = 0,
                windowLayout = WallHubWindowLayout.EXPANDED,
            ),
        )
    }

    @Test
    fun `Pad drawer width changes automatic column count`() {
        assertEquals(
            6,
            effectiveHomeGridColumns(
                availableWidth = 1240.dp,
                configuredColumns = 0,
                windowLayout = WallHubWindowLayout.EXPANDED,
            ),
        )
        assertEquals(
            7,
            effectiveHomeGridColumns(
                availableWidth = 1400.dp,
                configuredColumns = 0,
                windowLayout = WallHubWindowLayout.EXPANDED,
            ),
        )
    }

    @Test
    fun `compact layouts grow from portrait to landscape without changing navigation`() {
        assertEquals(
            2,
            effectiveHomeGridColumns(
                availableWidth = 360.dp,
                configuredColumns = 0,
                windowLayout = WallHubWindowLayout.COMPACT,
            ),
        )
        assertEquals(
            4,
            effectiveHomeGridColumns(
                availableWidth = 720.dp,
                configuredColumns = 0,
                windowLayout = WallHubWindowLayout.COMPACT,
            ),
        )
    }

    @Test
    fun `explicit count is respected when space is available and reduced when needed`() {
        assertEquals(
            8,
            effectiveHomeGridColumns(
                availableWidth = 1600.dp,
                configuredColumns = 8,
                windowLayout = WallHubWindowLayout.EXPANDED,
            ),
        )
        assertEquals(
            5,
            effectiveHomeGridColumns(
                availableWidth = 1009.dp,
                configuredColumns = 8,
                windowLayout = WallHubWindowLayout.EXPANDED,
            ),
        )
    }

    @Test
    fun `small windows reduce requested density to a usable count`() {
        assertEquals(
            3,
            effectiveHomeGridColumns(
                availableWidth = 520.dp,
                configuredColumns = 5,
                windowLayout = WallHubWindowLayout.COMPACT,
            ),
        )
        assertEquals(
            1,
            effectiveHomeGridColumns(
                availableWidth = 120.dp,
                configuredColumns = 8,
                windowLayout = WallHubWindowLayout.COMPACT,
            ),
        )
    }
}
