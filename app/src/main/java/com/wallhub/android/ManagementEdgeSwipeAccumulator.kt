package com.wallhub.android

import kotlin.math.abs

internal enum class ManagementBoundaryDirection {
    PREVIOUS,
    NEXT,
}

internal class ManagementEdgeSwipeAccumulator(
    private val thresholdPx: Float,
) {
    private var activeDirection: ManagementBoundaryDirection? = null
    private var distancePx = 0f
    private var dispatched = false

    fun onDrag(
        deltaX: Float,
        atFirstPage: Boolean,
        atLastPage: Boolean,
        horizontalDominant: Boolean,
    ): ManagementBoundaryDirection? {
        val direction =
            when {
                !horizontalDominant -> null
                atFirstPage && deltaX > 0f -> ManagementBoundaryDirection.PREVIOUS
                atLastPage && deltaX < 0f -> ManagementBoundaryDirection.NEXT
                else -> null
            }
        if (direction == null) {
            reset()
            return null
        }
        if (direction != activeDirection) {
            activeDirection = direction
            distancePx = 0f
            dispatched = false
        }
        distancePx += abs(deltaX)
        if (!dispatched && distancePx >= thresholdPx) {
            dispatched = true
            return direction
        }
        return null
    }

    fun reset() {
        activeDirection = null
        distancePx = 0f
        dispatched = false
    }
}
