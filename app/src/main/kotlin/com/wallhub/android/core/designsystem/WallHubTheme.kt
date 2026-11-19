@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.core.designsystem

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.google.android.material.color.utilities.DynamicColor
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeContent
import com.google.android.material.color.utilities.SchemeExpressive
import com.google.android.material.color.utilities.SchemeMonochrome
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.SchemeVibrant
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.ThemeContrastLevel
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.ThemeSchemeStyle
import com.wallhub.android.core.model.VisualStyle
import org.uwuaosp.compose.settingslib.rememberSettingsTypography

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WallHubTheme(
    preference: ThemePreference,
    accent: AccentPreference = AccentPreference.MONET,
    customAccentColor: String = DEFAULT_CUSTOM_MONET_SEED_HEX,
    useSystemMonet: Boolean = true,
    visualStyle: VisualStyle = VisualStyle.MATERIAL3,
    schemeStyle: ThemeSchemeStyle = ThemeSchemeStyle.TONAL_SPOT,
    contrastLevel: ThemeContrastLevel = ThemeContrastLevel.STANDARD,
    pureBlackInDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val useDarkTheme =
        when (preference) {
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
        }
    val context = LocalContext.current
    val baseColorScheme =
        remember(
            accent,
            customAccentColor,
            useDarkTheme,
            useSystemMonet,
            schemeStyle,
            contrastLevel,
            pureBlackInDark,
        ) {
            resolveMonetColorScheme(
                context = context,
                accent = accent,
                customAccentColor = customAccentColor,
                dark = useDarkTheme,
                useSystemMonet = useSystemMonet,
                schemeStyle = schemeStyle,
                contrastLevel = contrastLevel,
                pureBlackInDark = pureBlackInDark,
            )
        }
    val style =
        remember(baseColorScheme, visualStyle, useDarkTheme, pureBlackInDark) {
            buildWallHubStyle(
                baseScheme = baseColorScheme,
                visualStyle = visualStyle,
                isDark = useDarkTheme,
                pureBlackInDark = pureBlackInDark,
            )
        }
    // Apply a complete palette in one composition pass so fixed-color components and
    // system bars cannot briefly display a different theme phase from the page content.
    val colorScheme = style.colorScheme
    WallHubSystemBars(
        colorScheme = colorScheme,
        useDarkTheme = useDarkTheme,
    )
    val contentTypography =
        if (visualStyle == VisualStyle.MATERIAL3) {
            Typography()
        } else {
            rememberSettingsTypography()
        }
    val motionScheme =
        if (visualStyle == VisualStyle.MATERIAL3) {
            MotionScheme.standard()
        } else {
            MotionScheme.expressive()
        }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = contentTypography,
        shapes = style.materialShapes,
        motionScheme = motionScheme,
    ) {
        CompositionLocalProvider(
            LocalWallHubStyle provides style,
            content = content,
        )
    }
}

@Composable
@Suppress("DEPRECATION")
private fun WallHubSystemBars(
    colorScheme: ColorScheme,
    useDarkTheme: Boolean,
) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !useDarkTheme
            isAppearanceLightNavigationBars = !useDarkTheme
        }
    }
}

private fun resolveMonetColorScheme(
    context: Context,
    accent: AccentPreference,
    customAccentColor: String,
    dark: Boolean,
    useSystemMonet: Boolean,
    schemeStyle: ThemeSchemeStyle,
    contrastLevel: ThemeContrastLevel,
    pureBlackInDark: Boolean,
): ColorScheme {
    val useSystemScheme = shouldUseSystemMonet(useSystemMonet, Build.VERSION.SDK_INT)
    val resolved =
        if (!useSystemScheme && (accent == AccentPreference.DEFAULT || accent == AccentPreference.MONET)) {
            defaultStaticColorScheme(dark, contrastLevel = contrastLevel)
        } else if (useSystemScheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        } else {
            staticAccentColorScheme(
                seedColor = accent.monetSeedColor(customAccentColor),
                dark = dark,
                schemeStyle = schemeStyle,
                contrastLevel = contrastLevel.contrast,
            )
        }
    return resolved.withPureBlack(pureBlackInDark && dark)
}

internal fun defaultStaticColorScheme(
    dark: Boolean,
    contrastLevel: ThemeContrastLevel = ThemeContrastLevel.STANDARD,
    pureBlackInDark: Boolean = false,
): ColorScheme {
    val base =
        staticAccentColorScheme(
            seedColor = MONOCHROME_SEED,
            dark = dark,
            schemeStyle = ThemeSchemeStyle.MONOCHROME,
            contrastLevel = contrastLevel.contrast,
        )
    return when {
        pureBlackInDark && dark -> base.withPureBlack(true)
        dark -> base.withNonPureBlackDarkSurfaces()
        else -> base
    }
}

/**
 * SchemeMonochrome's dark neutral surfaces are extremely close to black. Keep
 * the default dark theme visibly distinct from the opt-in OLED palette.
 */
private fun ColorScheme.withNonPureBlackDarkSurfaces(): ColorScheme =
    copy(
        background = Color(0xFF0F0F0F),
        surface = Color(0xFF131313),
        surfaceContainerLowest = Color(0xFF0D0D0D),
        surfaceContainerLow = Color(0xFF111111),
        surfaceContainer = Color(0xFF171717),
        surfaceContainerHigh = Color(0xFF1C1C1C),
        surfaceContainerHighest = Color(0xFF222222),
        surfaceDim = Color(0xFF0A0A0A),
        surfaceBright = Color(0xFF2A2A2A),
    )

private fun ColorScheme.withPureBlack(enabled: Boolean): ColorScheme {
    if (!enabled) return this
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF070707),
        surfaceContainer = Color(0xFF0D0D0D),
        surfaceContainerHigh = Color(0xFF141414),
        surfaceContainerHighest = Color(0xFF1B1B1B),
        surfaceDim = Color.Black,
        surfaceBright = Color(0xFF1F1F1F),
    )
}

/**
 * A Color-Match style brand scheme for the static fallback.
 *
 * TonalSpot intentionally desaturates the neutral surfaces and container roles. This scheme keeps
 * the brand blue in the button containers and uses blue-tinted neutral surfaces, so the static
 * theme does not fall back to a black/white/gray UI.
 */
internal fun brandColorMatchScheme(dark: Boolean): ColorScheme {
    val base = staticAccentColorScheme(MONET_FALLBACK_SEED, dark, schemeStyle = ThemeSchemeStyle.CONTENT)
    return if (dark) {
        base.copy(
            primary = Color(0xFFA9C7FF),
            onPrimary = Color(0xFF0A2F5A),
            primaryContainer = Color(0xFF134A78),
            onPrimaryContainer = Color(0xFFD6E4FF),
            inversePrimary = Color(0xFF2B6CB0),
            secondary = Color(0xFFB4C7E7),
            onSecondary = Color(0xFF1A2F4A),
            secondaryContainer = Color(0xFF1F3A5C),
            onSecondaryContainer = Color(0xFFDCE7FB),
            tertiary = Color(0xFFD3BCF6),
            onTertiary = Color(0xFF38265A),
            tertiaryContainer = Color(0xFF4F3B73),
            onTertiaryContainer = Color(0xFFEDDCFF),
            background = Color(0xFF111820),
            onBackground = Color(0xFFE4E8F0),
            surface = Color(0xFF111820),
            onSurface = Color(0xFFE4E8F0),
            surfaceVariant = Color(0xFF414B5A),
            onSurfaceVariant = Color(0xFFC1C9D8),
            surfaceTint = Color(0xFFA9C7FF),
            inverseSurface = Color(0xFFE2E8F2),
            inverseOnSurface = Color(0xFF2A313B),
            outline = Color(0xFF8B96A7),
            outlineVariant = Color(0xFF3A4656),
            surfaceBright = Color(0xFF353F4D),
            surfaceDim = Color(0xFF0D131A),
            surfaceContainer = Color(0xFF1B2531),
            surfaceContainerHigh = Color(0xFF25303D),
            surfaceContainerHighest = Color(0xFF303C4A),
            surfaceContainerLow = Color(0xFF171F2A),
            surfaceContainerLowest = Color(0xFF0B1017),
        )
    } else {
        base.copy(
            primary = Color(0xFF2B6CB0),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD3E3FF),
            onPrimaryContainer = Color(0xFF082B55),
            inversePrimary = Color(0xFFA9C7FF),
            secondary = Color(0xFF3F6396),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFC9DDFF),
            onSecondaryContainer = Color(0xFF082B55),
            tertiary = Color(0xFF6D4C91),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFEADDFF),
            onTertiaryContainer = Color(0xFF2A1A44),
            background = Color(0xFFF7F9FF),
            onBackground = Color(0xFF171C26),
            surface = Color(0xFFF7F9FF),
            onSurface = Color(0xFF171C26),
            surfaceVariant = Color(0xFFE0E7F2),
            onSurfaceVariant = Color(0xFF3F495A),
            surfaceTint = Color(0xFF2B6CB0),
            inverseSurface = Color(0xFF2E3540),
            inverseOnSurface = Color(0xFFEFF2F8),
            outline = Color(0xFF6F7A8A),
            outlineVariant = Color(0xFFC0CCDD),
            surfaceBright = Color(0xFFFBFDFF),
            surfaceDim = Color(0xFFD8E2F2),
            surfaceContainer = Color(0xFFEAF0FA),
            surfaceContainerHigh = Color(0xFFE2EAF7),
            surfaceContainerHighest = Color(0xFFD9E4F4),
            surfaceContainerLow = Color(0xFFF2F6FE),
            surfaceContainerLowest = Color(0xFFFFFFFF),
        )
    }
}

internal fun shouldUseSystemMonet(
    useSystemMonet: Boolean,
    sdkInt: Int,
): Boolean = useSystemMonet && sdkInt >= Build.VERSION_CODES.S

private fun AccentPreference.monetSeedColor(customAccentColor: String): Color =
    when (this) {
        AccentPreference.DEFAULT,
        AccentPreference.MONET,
        -> MONET_FALLBACK_SEED

        AccentPreference.BLUE -> MONET_BLUE_SEED
        AccentPreference.GREEN -> MONET_GREEN_SEED
        AccentPreference.ROSE -> MONET_RED_SEED
        AccentPreference.VIOLET -> MONET_PURPLE_SEED
        AccentPreference.CUSTOM -> parseColor(customAccentColor) ?: MONET_FALLBACK_SEED
    }

/** Returns the stable swatch color used by the settings accent picker. */
fun AccentPreference.wallHubPreviewColor(
    customColor: String,
    systemMonetColor: Color,
): Color =
    when (this) {
        AccentPreference.DEFAULT -> MONOCHROME_SEED
        AccentPreference.MONET -> systemMonetColor
        AccentPreference.BLUE -> MONET_BLUE_SEED
        AccentPreference.GREEN -> MONET_GREEN_SEED
        AccentPreference.ROSE -> MONET_RED_SEED
        AccentPreference.VIOLET -> MONET_PURPLE_SEED
        AccentPreference.CUSTOM -> parseColor(customColor) ?: MONET_FALLBACK_SEED
    }

@SuppressLint("RestrictedApi")
internal fun staticAccentColorScheme(
    seedColor: Color,
    dark: Boolean,
    schemeStyle: ThemeSchemeStyle = ThemeSchemeStyle.TONAL_SPOT,
    contrastLevel: Double = 0.0,
): ColorScheme {
    val hct = Hct.fromInt(seedColor.toArgb())
    val scheme =
        when (schemeStyle) {
            ThemeSchemeStyle.TONAL_SPOT -> SchemeTonalSpot(hct, dark, contrastLevel)
            ThemeSchemeStyle.CONTENT -> SchemeContent(hct, dark, contrastLevel)
            ThemeSchemeStyle.VIBRANT -> SchemeVibrant(hct, dark, contrastLevel)
            ThemeSchemeStyle.EXPRESSIVE -> SchemeExpressive(hct, dark, contrastLevel)
            ThemeSchemeStyle.MONOCHROME -> SchemeMonochrome(hct, dark, contrastLevel)
        }
    val colors = MaterialDynamicColors()
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = colors.primary().resolve(scheme),
        onPrimary = colors.onPrimary().resolve(scheme),
        primaryContainer = colors.primaryContainer().resolve(scheme),
        onPrimaryContainer = colors.onPrimaryContainer().resolve(scheme),
        inversePrimary = colors.inversePrimary().resolve(scheme),
        secondary = colors.secondary().resolve(scheme),
        onSecondary = colors.onSecondary().resolve(scheme),
        secondaryContainer = colors.secondaryContainer().resolve(scheme),
        onSecondaryContainer = colors.onSecondaryContainer().resolve(scheme),
        tertiary = colors.tertiary().resolve(scheme),
        onTertiary = colors.onTertiary().resolve(scheme),
        tertiaryContainer = colors.tertiaryContainer().resolve(scheme),
        onTertiaryContainer = colors.onTertiaryContainer().resolve(scheme),
        background = colors.background().resolve(scheme),
        onBackground = colors.onBackground().resolve(scheme),
        surface = colors.surface().resolve(scheme),
        onSurface = colors.onSurface().resolve(scheme),
        surfaceVariant = colors.surfaceVariant().resolve(scheme),
        onSurfaceVariant = colors.onSurfaceVariant().resolve(scheme),
        surfaceTint = colors.surfaceTint().resolve(scheme),
        inverseSurface = colors.inverseSurface().resolve(scheme),
        inverseOnSurface = colors.inverseOnSurface().resolve(scheme),
        error = colors.error().resolve(scheme),
        onError = colors.onError().resolve(scheme),
        errorContainer = colors.errorContainer().resolve(scheme),
        onErrorContainer = colors.onErrorContainer().resolve(scheme),
        outline = colors.outline().resolve(scheme),
        outlineVariant = colors.outlineVariant().resolve(scheme),
        scrim = colors.scrim().resolve(scheme),
        surfaceBright = colors.surfaceBright().resolve(scheme),
        surfaceDim = colors.surfaceDim().resolve(scheme),
        surfaceContainer = colors.surfaceContainer().resolve(scheme),
        surfaceContainerHigh = colors.surfaceContainerHigh().resolve(scheme),
        surfaceContainerHighest = colors.surfaceContainerHighest().resolve(scheme),
        surfaceContainerLow = colors.surfaceContainerLow().resolve(scheme),
        surfaceContainerLowest = colors.surfaceContainerLowest().resolve(scheme),
    )
}

@SuppressLint("RestrictedApi")
private fun DynamicColor.resolve(scheme: DynamicScheme): Color = Color(getArgb(scheme))

private fun parseColor(value: String): Color? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6) return null
    val rgb = hex.toLongOrNull(16) ?: return null
    return Color((0xFF000000L or rgb).toInt())
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private const val MONET_CONTRAST_LEVEL = 0.0
private const val DEFAULT_CUSTOM_MONET_SEED_HEX = "#5B5B5B"
private val MONOCHROME_SEED = Color(0xFF5B5B5B)
private val MONET_FALLBACK_SEED = Color(0xFF2B6CB0)
private val MONET_BLUE_SEED = Color(0xFF2B6CB0)
private val MONET_GREEN_SEED = Color(0xFF2F855A)
private val MONET_RED_SEED = Color(0xFFC53030)
private val MONET_PURPLE_SEED = Color(0xFF805AD5)
