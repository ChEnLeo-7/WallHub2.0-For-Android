package com.wallhub.android.feature.detail

import androidx.compose.ui.unit.dp
import com.wallhub.android.core.designsystem.WallHubAdaptiveLayout
import com.wallhub.android.core.designsystem.WallHubWindowHeight
import com.wallhub.android.core.designsystem.WallHubWindowLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkshopDetailResponsiveLayoutTest {
    @Test
    fun `large detail workspace requires Monet width and height`() {
        assertEquals(
            true,
            useLargeMonetDetailWorkspace(
                bettboxSurfaces = true,
                availableWidth = 1280.dp,
                availableHeight = 720.dp,
            ),
        )
        assertEquals(
            false,
            useLargeMonetDetailWorkspace(
                bettboxSurfaces = false,
                availableWidth = 1600.dp,
                availableHeight = 900.dp,
            ),
        )
        assertEquals(
            false,
            useLargeMonetDetailWorkspace(
                bettboxSurfaces = true,
                availableWidth = 1279.dp,
                availableHeight = 720.dp,
            ),
        )
        assertEquals(
            false,
            useLargeMonetDetailWorkspace(
                bettboxSurfaces = true,
                availableWidth = 1280.dp,
                availableHeight = 719.dp,
            ),
        )
    }

    @Test
    fun `only expanded width uses dedicated Pad detail pane`() {
        assertEquals(
            true,
            useExpandedDetailPane(
                WallHubAdaptiveLayout(
                    width = WallHubWindowLayout.EXPANDED,
                    height = WallHubWindowHeight.MEDIUM,
                ),
            ),
        )
        assertEquals(
            false,
            useExpandedDetailPane(
                WallHubAdaptiveLayout(
                    width = WallHubWindowLayout.MEDIUM,
                    height = WallHubWindowHeight.EXPANDED,
                ),
            ),
        )
        assertEquals(
            false,
            useExpandedDetailPane(
                WallHubAdaptiveLayout(
                    width = WallHubWindowLayout.COMPACT,
                    height = WallHubWindowHeight.MEDIUM,
                ),
            ),
        )
    }

    @Test
    fun `expanded medium-height tablet uses compact detail media`() {
        assertEquals(
            true,
            useShortHeightDetailLayout(
                WallHubAdaptiveLayout(
                    width = WallHubWindowLayout.EXPANDED,
                    height = WallHubWindowHeight.MEDIUM,
                ),
            ),
        )
        assertEquals(
            false,
            useShortHeightDetailLayout(
                WallHubAdaptiveLayout(
                    width = WallHubWindowLayout.MEDIUM,
                    height = WallHubWindowHeight.EXPANDED,
                ),
            ),
        )
    }

    @Test
    fun `short landscape limits classic detail media width`() {
        assertEquals(840.dp, workshopDetailContentMaxWidth(bettboxSurfaces = false, shortHeight = true))
        assertEquals(1080.dp, workshopDetailContentMaxWidth(bettboxSurfaces = false, shortHeight = false))
    }

    @Test
    fun `short landscape caps detail cover height so tabs remain reachable`() {
        assertEquals(200.dp, workshopDetailCoverMaxHeight(shortHeight = true))
        assertEquals(null, workshopDetailCoverMaxHeight(shortHeight = false))
    }

    @Test
    fun `bettbox detail keeps its narrower reading width`() {
        assertEquals(720.dp, workshopDetailContentMaxWidth(bettboxSurfaces = true, shortHeight = true))
        assertEquals(720.dp, workshopDetailContentMaxWidth(bettboxSurfaces = true, shortHeight = false))
    }
}
