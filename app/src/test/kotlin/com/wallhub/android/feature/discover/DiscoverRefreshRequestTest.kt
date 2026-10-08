package com.wallhub.android.feature.discover

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoverRefreshRequestTest {
    @Test
    fun `first composition observes the request without reloading the initial generation`() {
        val requests = DiscoverRefreshRequestTracker()

        assertFalse(requests.consume(3))
    }

    @Test
    fun `explicit bottom navigation request is consumed once`() {
        val requests = DiscoverRefreshRequestTracker()
        requests.consume(3)

        assertTrue(requests.consume(4))
        assertFalse(requests.consume(4))
    }

    @Test
    fun `route recreation for child back keeps the handled request in the ViewModel`() {
        val requests = DiscoverRefreshRequestTracker()
        requests.consume(3)

        assertFalse(requests.consume(3))
    }
}
