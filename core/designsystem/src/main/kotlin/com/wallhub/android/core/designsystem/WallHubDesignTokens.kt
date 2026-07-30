package com.wallhub.android.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared layout values for Compose components and feature screens. */
object WallHubSpacing {
    val none: Dp = 0.dp
    val hairline: Dp = 1.dp
    val xxxs: Dp = 2.dp
    val xxs: Dp = 4.dp
    val dense: Dp = 6.dp
    val xs: Dp = 8.dp
    val compact: Dp = 10.dp
    val sm: Dp = 12.dp
    val controlInset: Dp = 14.dp
    val md: Dp = 16.dp
    val content: Dp = 20.dp
    val lg: Dp = 24.dp
    val section: Dp = 28.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 48.dp
}

object WallHubShapeTokens {
    val extraSmall = RoundedCornerShape(4.dp)
    val thumbnail = RoundedCornerShape(6.dp)
    val small = RoundedCornerShape(8.dp)
    val badge = RoundedCornerShape(10.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(16.dp)
    val extraLarge = RoundedCornerShape(28.dp)
    val mediaControl = RoundedCornerShape(32.dp)

    val material: Shapes =
        Shapes(
            extraSmall = extraSmall,
            small = small,
            medium = medium,
            large = large,
            extraLarge = extraLarge,
        )
}

object WallHubSizeTokens {
    val minimumTouchTarget: Dp = 48.dp
    val icon: Dp = 24.dp
    val compactIcon: Dp = 18.dp
    val smallIcon: Dp = 20.dp
    val compactActionHeight: Dp = 40.dp
    val compactIconButton: Dp = 36.dp
    val listItemMinimumHeight: Dp = 56.dp
    val cardTitleHeight: Dp = 44.dp
    val bottomNavigationClearance: Dp = 80.dp
    val modalContentMaxWidth: Dp = 920.dp
    val expandedNavigationDrawerWidth: Dp = 240.dp
    val readableContentMaxWidth: Dp = 1080.dp
}
