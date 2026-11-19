@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun WallHubAppSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val style = wallHubStyle()
    Surface(
        modifier = modifier.fillMaxSize(),
        color = style.surfaces.page,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        content()
    }
}

@Composable
fun WallHubSurfaceCard(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    shape: Shape? = null,
    elevation: Dp? = null,
    content: @Composable () -> Unit,
) {
    val style = wallHubStyle()
    Surface(
        modifier = modifier,
        shape = shape ?: style.shapes.card,
        color = if (containerColor == Color.Unspecified) style.surfaces.content else containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = WallHubSpacing.none,
        shadowElevation = elevation ?: style.elevations.card,
    ) { content() }
}

@Composable
fun WallHubEmptyState(
    icon: ImageVector,
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    tertiaryActionLabel: String? = null,
    onTertiaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = WallHubSpacing.lg, vertical = WallHubSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            WallHubSecondaryButton(onClick = onAction) { Text(actionLabel) }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            TextButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
        }
        if (tertiaryActionLabel != null && onTertiaryAction != null) {
            TextButton(onClick = onTertiaryAction) { Text(tertiaryActionLabel) }
        }
    }
}

@Composable
fun WallHubSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(),
        content = content,
    )
}

val WallHubFabDefaultElevation = 3.dp
val WallHubFabActiveElevation = 4.dp
