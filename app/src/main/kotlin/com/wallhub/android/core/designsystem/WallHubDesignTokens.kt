package com.wallhub.android.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
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

    /** Approved 2dp separation between closed settings rows / stacked cards. */
    val cardGap: Dp = xxxs

    /** Approved 6dp gap between a card's metadata rows. */
    val metaGap: Dp = dense
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

    /** Secondary icon actions (subscribe/favourite) share one squircle, never the CTA pill. */
    val secondaryAction: Shape = RoundedCornerShape(16.dp)

    /** Pill shape reserved for primary CTAs and segmented controls. */
    val cta: Shape = RoundedCornerShape(percent = 50)

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
    val compactIcon: Dp = 18.dp
    val smallIcon: Dp = 20.dp
    val compactActionHeight: Dp = 40.dp
    val compactIconButton: Dp = 36.dp
    val listItemMinimumHeight: Dp = 56.dp
    val cardTitleHeight: Dp = 44.dp
    val modalContentMaxWidth: Dp = 920.dp
    val expandedNavigationDrawerWidth: Dp = 240.dp
    val collapsedNavigationDrawerWidth: Dp = 80.dp
    val readableContentMaxWidth: Dp = 1080.dp

    /** Height of the floating bottom navigation's item row. */
    val floatingNavigationItemHeight: Dp = 50.dp

    /** Inner padding between the floating bar's item row and its surface edge. */
    val floatingNavigationContainerPadding: Dp = 4.dp

    /** Outer padding between the floating bar's surface and the page or system inset edge. */
    val floatingNavigationOuterPadding: Dp = 8.dp

    /** Gap overlay content (search FAB, snackbar, list end) keeps above the floating bar. */
    val floatingNavigationContentGap: Dp = WallHubSpacing.xs

    /** Visible surface height of the floating bottom navigation bar. */
    val floatingNavigationSurfaceHeight: Dp =
        floatingNavigationItemHeight + floatingNavigationContainerPadding * 2

    /**
     * Space the floating bar reserves above the system inset, including the gap overlay content
     * must keep from it. Screens add the device's bottom system inset (navigation bar or gesture
     * strip) on top, so the value follows each device instead of assuming one inset height.
     */
    val floatingNavigationClearance: Dp =
        floatingNavigationSurfaceHeight + floatingNavigationOuterPadding * 2 + floatingNavigationContentGap

    /** Full detail width is reduced in compact-height landscape so media leaves room for tabs/actions. */
    val shortHeightDetailMaxWidth: Dp = 840.dp

    /** Short-height detail media cap that keeps tabs and primary actions visible. */
    val shortHeightDetailCoverHeight: Dp = 200.dp

    /** Long-form detail copy stays readable instead of spanning a tablet edge to edge. */
    val detailReadableMaxWidth: Dp = 720.dp

    /** Full-width primary call-to-action height (detail download, setup steps). */
    val primaryActionHeight: Dp = 56.dp

    /** Spacious secondary action height. */
    val secondaryActionHeight: Dp = 56.dp

    val iconLarge: Dp = 24.dp

    /** Circular action inside the 16:9 download card. */
    val wideCardAction: Dp = 44.dp
}
