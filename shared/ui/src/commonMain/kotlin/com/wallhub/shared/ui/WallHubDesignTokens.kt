package com.wallhub.shared.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    val material =
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
    val bottomNavigationClearance: Dp = 80.dp
    val modalContentMaxWidth: Dp = 920.dp
    val mediumNavigationRailWidth: Dp = 80.dp
    val expandedNavigationDrawerWidth: Dp = 240.dp
    val readableContentMaxWidth: Dp = 1080.dp
}

internal fun wallHubTypography(fontFamily: FontFamily): Typography =
    Typography(
        headlineLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
        headlineMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
        headlineSmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
        titleLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
        bodyLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    )

internal fun wallHubStaticColorScheme(dark: Boolean) =
    if (dark) {
        darkColorScheme(
            primary = Color.White,
            onPrimary = Color(0xFF181818),
            primaryContainer = Color(0xFF3A3A3A),
            onPrimaryContainer = Color.White,
            inversePrimary = Color(0xFF181818),
            secondary = Color(0xFFD4D4D4),
            onSecondary = Color(0xFF181818),
            secondaryContainer = Color(0xFF3A3A3A),
            onSecondaryContainer = Color.White,
            tertiary = Color(0xFFD4D4D4),
            onTertiary = Color(0xFF181818),
            tertiaryContainer = Color(0xFF3A3A3A),
            onTertiaryContainer = Color.White,
            background = Color(0xFF121212),
            onBackground = Color.White,
            surface = Color(0xFF1C1C1C),
            onSurface = Color.White,
            surfaceVariant = Color(0xFF1C1C1C),
            onSurfaceVariant = Color(0xFFC8C8C8),
            surfaceTint = Color.White,
            inverseSurface = Color.White,
            inverseOnSurface = Color(0xFF181818),
            outline = Color(0xFFA8A8A8),
            outlineVariant = Color(0xFF4C4C4C),
            surfaceBright = Color(0xFF303030),
            surfaceDim = Color(0xFF121212),
            surfaceContainerLowest = Color(0xFF101010),
            surfaceContainerLow = Color(0xFF1C1C1C),
            surfaceContainer = Color(0xFF1C1C1C),
            surfaceContainerHigh = Color(0xFF242424),
            surfaceContainerHighest = Color(0xFF2C2C2C),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF242424),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE4E4E4),
            onPrimaryContainer = Color(0xFF242424),
            inversePrimary = Color.White,
            secondary = Color(0xFF424242),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE4E4E4),
            onSecondaryContainer = Color(0xFF242424),
            tertiary = Color(0xFF424242),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFE4E4E4),
            onTertiaryContainer = Color(0xFF242424),
            background = Color(0xFFF5F6F8),
            onBackground = Color(0xFF191919),
            surface = Color(0xFFF9FAFB),
            onSurface = Color(0xFF191919),
            surfaceVariant = Color(0xFFF1F2F4),
            onSurfaceVariant = Color(0xFF5F5F5F),
            surfaceTint = Color(0xFF242424),
            inverseSurface = Color(0xFF1C1C1C),
            inverseOnSurface = Color.White,
            outline = Color(0xFF777777),
            outlineVariant = Color(0xFFC6C6C6),
            surfaceBright = Color(0xFFFCFCFD),
            surfaceDim = Color(0xFFF5F6F8),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF7F8FA),
            surfaceContainer = Color(0xFFF1F2F4),
            surfaceContainerHigh = Color(0xFFEBECEF),
            surfaceContainerHighest = Color(0xFFE4E6E9),
        )
    }
