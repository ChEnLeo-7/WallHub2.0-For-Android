package com.wallhub.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManagementEdgeSwipeAccumulatorTest {
    @Test
    fun `outward drag from downloads navigates to previous top level once`() {
        val accumulator = ManagementEdgeSwipeAccumulator(thresholdPx = 60f)

        assertNull(accumulator.onDrag(24f, atFirstPage = true, atLastPage = false, horizontalDominant = true))
        assertEquals(
            ManagementBoundaryDirection.PREVIOUS,
            accumulator.onDrag(40f, atFirstPage = true, atLastPage = false, horizontalDominant = true),
        )
        assertNull(accumulator.onDrag(40f, atFirstPage = true, atLastPage = false, horizontalDominant = true))
    }

    @Test
    fun `outward drag from local navigates to next top level`() {
        val accumulator = ManagementEdgeSwipeAccumulator(thresholdPx = 60f)

        assertNull(accumulator.onDrag(-30f, atFirstPage = false, atLastPage = true, horizontalDominant = true))
        assertEquals(
            ManagementBoundaryDirection.NEXT,
            accumulator.onDrag(-31f, atFirstPage = false, atLastPage = true, horizontalDominant = true),
        )
    }

    @Test
    fun `vertical central and inward drags do not navigate`() {
        val accumulator = ManagementEdgeSwipeAccumulator(thresholdPx = 20f)

        assertNull(accumulator.onDrag(30f, atFirstPage = true, atLastPage = false, horizontalDominant = false))
        assertNull(accumulator.onDrag(-30f, atFirstPage = true, atLastPage = false, horizontalDominant = true))
        assertNull(accumulator.onDrag(30f, atFirstPage = false, atLastPage = false, horizontalDominant = true))
        assertNull(accumulator.onDrag(30f, atFirstPage = false, atLastPage = true, horizontalDominant = true))
    }

    @Test
    fun `reset allows a later edge gesture to navigate again`() {
        val accumulator = ManagementEdgeSwipeAccumulator(thresholdPx = 20f)

        assertEquals(
            ManagementBoundaryDirection.NEXT,
            accumulator.onDrag(-24f, atFirstPage = false, atLastPage = true, horizontalDominant = true),
        )
        accumulator.reset()
        assertEquals(
            ManagementBoundaryDirection.NEXT,
            accumulator.onDrag(-24f, atFirstPage = false, atLastPage = true, horizontalDominant = true),
        )
    }
}
