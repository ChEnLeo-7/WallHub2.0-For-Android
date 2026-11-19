package com.wallhub.android.core.designsystem

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Semantic width groups shared by the app shell and page-level responsive layouts. */
enum class WallHubWindowLayout {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

enum class WallHubWindowHeight {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

@Immutable
data class WallHubAdaptiveLayout(
    val width: WallHubWindowLayout,
    val height: WallHubWindowHeight,
) {
    val isShort: Boolean
        get() = height == WallHubWindowHeight.COMPACT
}

fun wallHubWindowLayout(width: Dp): WallHubWindowLayout =
    when {
        width < 600.dp -> WallHubWindowLayout.COMPACT
        width < 840.dp -> WallHubWindowLayout.MEDIUM
        else -> WallHubWindowLayout.EXPANDED
    }

fun wallHubWindowHeight(height: Dp): WallHubWindowHeight =
    when {
        height < 480.dp -> WallHubWindowHeight.COMPACT
        height < 900.dp -> WallHubWindowHeight.MEDIUM
        else -> WallHubWindowHeight.EXPANDED
    }

val LocalWallHubAdaptiveLayout =
    staticCompositionLocalOf {
        WallHubAdaptiveLayout(
            width = WallHubWindowLayout.COMPACT,
            height = WallHubWindowHeight.MEDIUM,
        )
    }

fun wallHubOverlayBottomClearance(
    navigationInset: Dp,
    navigationVisible: Boolean = true,
): Dp =
    if (navigationVisible) {
        navigationInset.coerceAtLeast(0.dp) + WallHubSizeTokens.floatingNavigationClearance
    } else {
        0.dp
    }

/**
 * Bottom system inset a floating navigation surface must stay above.
 *
 * Gesture navigation reports a zero-height `navigationBars` inset on many devices while still
 * reserving a gesture strip for the system handle, so the navigation-bar inset alone would let the
 * floating bar and the search FAB sit on top of the gesture area. Take the larger of the two; on
 * three-button devices the navigation bar is the larger one, on gesture devices the gesture strip.
 *
 * The gesture strip is much taller than the visible handle, so it is capped at one handle's worth
 * of clearance: the bar keeps the reference's low float while still staying off the system handle.
 */
@Composable
fun floatingNavigationSystemInset(): Dp {
    val navigationBars = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val gestureHandle =
        WindowInsets.systemGestures.asPaddingValues().calculateBottomPadding()
            .coerceAtMost(WallHubSpacing.md)
    return maxOf(navigationBars, gestureHandle)
}

/** Bottom space occupied by an overlaid navigation surface. Zero for rail/drawer layouts. */
val LocalWallHubOverlayBottomClearance = staticCompositionLocalOf<Dp> { 0.dp }
