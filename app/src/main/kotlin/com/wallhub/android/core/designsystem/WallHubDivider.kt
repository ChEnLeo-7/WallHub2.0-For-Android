package com.wallhub.android.core.designsystem

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class WallHubDividerVariant {
    Normal,
    Subtle,
}

/** Shared horizontal divider; every semantic separator reads the same colour and thickness token. */
@Composable
fun WallHubHorizontalDivider(
    modifier: Modifier = Modifier,
    variant: WallHubDividerVariant = WallHubDividerVariant.Normal,
) {
    val dividers = wallHubStyle().dividers
    HorizontalDivider(
        modifier = modifier,
        thickness = dividers.thickness,
        color = if (variant == WallHubDividerVariant.Subtle) dividers.subtle else dividers.normal,
    )
}

/** Shared vertical divider for inline action groups and statistic rows. */
@Composable
fun WallHubVerticalDivider(
    modifier: Modifier = Modifier,
    variant: WallHubDividerVariant = WallHubDividerVariant.Normal,
) {
    val dividers = wallHubStyle().dividers
    VerticalDivider(
        modifier = modifier.width(dividers.thickness).height(WallHubSpacing.compact),
        thickness = dividers.thickness,
        color = if (variant == WallHubDividerVariant.Subtle) dividers.subtle else dividers.normal,
    )
}
