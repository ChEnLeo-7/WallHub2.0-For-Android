package com.wallhub.android.core.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Material 3-aligned width classes shared by the app shell and feature layouts. */
enum class WallHubWindowSizeClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

fun wallHubWindowSizeClass(width: Dp): WallHubWindowSizeClass =
    when {
        width < WallHubWindowBreakpoints.medium -> WallHubWindowSizeClass.COMPACT
        width < WallHubWindowBreakpoints.expanded -> WallHubWindowSizeClass.MEDIUM
        else -> WallHubWindowSizeClass.EXPANDED
    }

object WallHubWindowBreakpoints {
    val medium: Dp = 600.dp
    val expanded: Dp = 840.dp
}
