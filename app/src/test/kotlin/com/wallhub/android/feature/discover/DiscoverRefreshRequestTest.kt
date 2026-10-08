package com.wallhub.android.feature.discover

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoverRefreshRequestTest {
    @Test
    fun `changed request always refreshes and resets scroll`() {
        val action = discoverRefreshAction(handledRequest = 3, refreshRequest = 4)

        assertTrue(action.refresh)
        assertTrue(action.resetScroll)
    }

    @Test
    fun `unchanged request does nothing`() {
        val action = discoverRefreshAction(handledRequest = 4, refreshRequest = 4)

        assertFalse(action.refresh)
        assertFalse(action.resetScroll)
    }
}
