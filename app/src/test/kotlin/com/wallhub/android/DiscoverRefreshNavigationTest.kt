package com.wallhub.android

import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoverRefreshNavigationTest {
    @Test
    fun `entering Discover from another top-level tab requests refresh`() {
        assertEquals(
            8,
            nextDiscoverRefreshRequest(
                currentRequest = 7,
                currentDestination = TopLevelDestination.HOME,
                selectedDestination = TopLevelDestination.DISCOVER,
            ),
        )
    }

    @Test
    fun `reselecting Discover requests refresh`() {
        assertEquals(
            8,
            nextDiscoverRefreshRequest(
                currentRequest = 7,
                currentDestination = TopLevelDestination.DISCOVER,
                selectedDestination = TopLevelDestination.DISCOVER,
            ),
        )
    }

    @Test
    fun `returning from a child destination does not request refresh`() {
        assertEquals(
            7,
            nextDiscoverRefreshRequest(
                currentRequest = 7,
                currentDestination = null,
                selectedDestination = null,
            ),
        )
    }

    @Test
    fun `selecting another top-level tab does not request Discover refresh`() {
        assertEquals(
            7,
            nextDiscoverRefreshRequest(
                currentRequest = 7,
                currentDestination = TopLevelDestination.DISCOVER,
                selectedDestination = TopLevelDestination.PROFILE,
            ),
        )
    }
}
