@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> WallHubSingleChoiceSegmentedControl(
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = selected == option,
                onCheckedChange = { checked -> if (checked) onSelected(option) },
                modifier = Modifier.weight(1f),
                shapes =
                    when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
            ) { label(option) }
        }
    }
}

@Composable
fun rememberWallHubDirectionalCollapseConnection(
    collapsed: Boolean,
    onCollapsedChanged: (Boolean) -> Unit,
    collapseDistance: Dp = 48.dp,
    expandDistance: Dp = 24.dp,
): NestedScrollConnection {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val collapseDistancePx = with(density) { collapseDistance.toPx() }
    val expandDistancePx = with(density) { expandDistance.toPx() }
    val connection =
        remember(collapseDistancePx, expandDistancePx) {
            WallHubDirectionalCollapseConnection(
                collapsed = collapsed,
                collapseDistancePx = collapseDistancePx,
                expandDistancePx = expandDistancePx,
                onCollapsedChanged = onCollapsedChanged,
            )
        }
    SideEffect { connection.update(collapsed, onCollapsedChanged) }
    return connection
}

private class WallHubDirectionalCollapseConnection(
    collapsed: Boolean,
    private val collapseDistancePx: Float,
    private val expandDistancePx: Float,
    onCollapsedChanged: (Boolean) -> Unit,
) : NestedScrollConnection {
    private var collapsed = collapsed
    private var onCollapsedChanged = onCollapsedChanged
    private var collapseTravel = 0f
    private var expandTravel = 0f

    fun update(collapsed: Boolean, onCollapsedChanged: (Boolean) -> Unit) {
        this.collapsed = collapsed
        this.onCollapsedChanged = onCollapsedChanged
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        when {
            available.y < 0f -> {
                expandTravel = 0f
                if (!collapsed) {
                    collapseTravel += -available.y
                    if (collapseTravel >= collapseDistancePx) requestCollapsed(true)
                }
            }
            available.y > 0f -> {
                collapseTravel = 0f
                if (collapsed) {
                    expandTravel += available.y
                    if (expandTravel >= expandDistancePx) requestCollapsed(false)
                }
            }
        }
        return Offset.Zero
    }

    private fun requestCollapsed(value: Boolean) {
        if (collapsed == value) return
        collapsed = value
        collapseTravel = 0f
        expandTravel = 0f
        onCollapsedChanged(value)
    }
}

@Composable
fun WallHubAnimatedSelectionCheck(
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = FILTER_CHIP_ICON_SIZE,
) {
    val visibleState = remember { MutableTransitionState(selected) }
    visibleState.targetState = selected
    AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter =
            fadeIn(tween(160)) +
                scaleIn(initialScale = 0.72f, animationSpec = tween(220, easing = FastOutSlowInEasing)),
        exit =
            fadeOut(tween(100)) +
                scaleOut(targetScale = 0.8f, animationSpec = tween(120, easing = FastOutSlowInEasing)),
    ) {
        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(size))
    }
}

private val FILTER_CHIP_ICON_SIZE = 18.dp
