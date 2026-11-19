package com.wallhub.android.feature.discover

import com.wallhub.android.TopLevelDestination
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.nextDiscoverRefreshRequest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoverRefreshBehaviorTest {
    @Test
    fun `only reselecting the already visible Discover destination requests refresh`() {
        assertEquals(
            7,
            nextDiscoverRefreshRequest(7, TopLevelDestination.HOME, TopLevelDestination.DISCOVER),
        )
        assertEquals(
            8,
            nextDiscoverRefreshRequest(7, TopLevelDestination.DISCOVER, TopLevelDestination.DISCOVER),
        )
        assertEquals(
            7,
            nextDiscoverRefreshRequest(7, TopLevelDestination.DISCOVER, TopLevelDestination.HOME),
        )
    }

    @Test
    fun `explicit Discover refresh clears committed cards immediately and begins next generation`() {
        val previous = DiscoverFeedState(
            rails = listOf(DiscoverRailState(spec(), loadState = DiscoverRailLoadState.READY)),
            generation = 4L,
            isRefreshing = false,
        )

        val refreshing = previous.beginDiscoverRefresh(preserveCommittedRows = false)

        assertTrue(refreshing.rails.isEmpty())
        assertTrue(refreshing.isPreparing)
        assertFalse(refreshing.isRefreshing)
        assertEquals(5L, refreshing.generation)
    }

    @Test
    fun `late rail result from an old Discover generation cannot replace current refresh`() {
        val refreshing = DiscoverFeedState(
            rails = listOf(DiscoverRailState(spec(), loadState = DiscoverRailLoadState.QUEUED)),
            generation = 5L,
        )

        val afterLateResult = refreshing.updateRailIfCurrent(4L, "rail") {
            it.copy(loadState = DiscoverRailLoadState.READY)
        }

        assertEquals(DiscoverRailLoadState.QUEUED, afterLateResult.rails.single().loadState)
    }

    @Test
    fun `current generation failure exits page loading while stale failure is ignored`() {
        val refreshing = DiscoverFeedState(
            rails = emptyList(),
            generation = 5L,
            isPreparing = true,
        )

        val stale = refreshing.finishDiscoverRefreshFailure(4L, "stale")
        val current = refreshing.finishDiscoverRefreshFailure(5L, "network")

        assertEquals(refreshing, stale)
        assertFalse(current.isPreparing)
        assertFalse(current.isRefreshing)
        assertFalse(current.hasMore)
        assertEquals("network", current.error)
    }
}

private fun spec() =
    DiscoverRailSpec(
        id = "rail",
        category = DiscoverCategory.WALLPAPER,
        semantic = "rail",
        titleKind = DiscoverTitleKind.RECENT_APPROVED,
        queryPlan = DiscoverQueryPlan(
            descriptorId = "rail",
            semantic = "rail",
            query = WorkshopBrowseQuery(),
            fidelity = DiscoverQueryFidelity.EXACT,
        ),
        priority = 1f,
        weight = 1,
        sticky = true,
        source = DiscoverSpecSource.GENERIC_FALLBACK,
        drillDown = DiscoverDrillDown.FULL_QUERY_RESULTS,
    )
