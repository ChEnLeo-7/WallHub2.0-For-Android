package com.wallhub.android.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun <T> WallHubCapsuleFilter(
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: @Composable (T) -> Unit,
    visibleOptionCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(64.8.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val optionWidth = maxWidth / visibleOptionCount.coerceAtLeast(1)
            val contentWidth = optionWidth * options.size
            val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
            val indicatorOffset by animateDpAsState(
                targetValue = optionWidth * selectedIndex,
                animationSpec = tween(CAPSULE_FILTER_ANIMATION_DURATION_MS),
                label = "CapsuleFilterIndicator",
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .horizontalScroll(rememberScrollState())
                        .semantics { selectableGroup() },
            ) {
                Box(modifier = Modifier.width(contentWidth).fillMaxHeight()) {
                    Surface(
                        modifier =
                            Modifier
                                .offset(x = indicatorOffset)
                                .width(optionWidth)
                                .fillMaxHeight(),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        content = {},
                    )
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        options.forEach { option ->
                            val isSelected = option == selected
                            val contentColor by animateColorAsState(
                                targetValue =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                animationSpec = tween(CAPSULE_FILTER_ANIMATION_DURATION_MS),
                                label = "CapsuleFilterContent",
                            )
                            Box(
                                modifier =
                                    Modifier
                                        .width(optionWidth)
                                        .fillMaxHeight()
                                        .clip(CircleShape)
                                        .selectable(
                                            selected = isSelected,
                                            role = Role.RadioButton,
                                            onClick = { onSelected(option) },
                                        ),
                                contentAlignment = Alignment.Center,
                            ) {
                                CompositionLocalProvider(LocalContentColor provides contentColor) {
                                    label(option)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val CAPSULE_FILTER_ANIMATION_DURATION_MS = 400
