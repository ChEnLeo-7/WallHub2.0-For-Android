package com.wallhub.android.core.designsystem

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@Composable
fun WallHubDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(WALLHUB_DROPDOWN_WIDTH).then(modifier),
        offset = offset,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(WALLHUB_DROPDOWN_CORNER_RADIUS),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        shadowElevation = WALLHUB_DROPDOWN_SHADOW_ELEVATION,
        content = content,
    )
}

@Composable
fun WallHubDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    DropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = Modifier.height(WALLHUB_DROPDOWN_ITEM_HEIGHT).then(modifier),
        leadingIcon = leadingIcon,
    )
}

val WALLHUB_DROPDOWN_WIDTH = 132.dp
val WALLHUB_DROPDOWN_CORNER_RADIUS = 15.dp
val WALLHUB_DROPDOWN_ITEM_HEIGHT = 48.dp
private val WALLHUB_DROPDOWN_SHADOW_ELEVATION = 6.dp
