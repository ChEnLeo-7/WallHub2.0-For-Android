package com.wallhub.android.core.designsystem

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.google.android.material.color.utilities.DynamicColor
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.ThemePreference

@Composable
fun WallHubTheme(
    preference: ThemePreference,
    language: AppLanguage = AppLanguage.ZH,
    accent: AccentPreference = AccentPreference.MONET,
    customAccentColor: String = DEFAULT_CUSTOM_MONET_SEED_HEX,
    useSystemMonet: Boolean = true,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val context = LocalContext.current
    val targetColorScheme = remember(accent, customAccentColor, useDarkTheme, useSystemMonet) {
        resolveMonetColorScheme(
            context = context,
            accent = accent,
            customAccentColor = customAccentColor,
            dark = useDarkTheme,
            useSystemMonet = useSystemMonet,
        )
    }
    // Apply a complete palette in one composition pass so fixed-color components and
    // system bars cannot briefly display a different theme phase from the page content.
    val colorScheme = targetColorScheme
    WallHubSystemBars(
        colorScheme = colorScheme,
        useDarkTheme = useDarkTheme,
    )
    CompositionLocalProvider(LocalWallHubLanguage provides language) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WallHubTypography,
            shapes = WallHubShapeTokens.material,
            content = content,
        )
    }
}

val LocalWallHubLanguage = staticCompositionLocalOf { AppLanguage.ZH }

@Composable
fun wallHubText(zh: String, en: String): String =
    if (LocalWallHubLanguage.current == AppLanguage.EN) en else zh

/**
 * Selects the Chinese or English literal for an explicit [AppLanguage].
 *
 * Use this outside composition, or wherever the language is already resolved.
 * Prefer [wallHubText] inside composables that can read [LocalWallHubLanguage].
 */
fun AppLanguage.text(zh: String, en: String): String = if (this == AppLanguage.EN) en else zh

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
        window.statusBarColor = colorScheme.background.toArgb()
        window.navigationBarColor = colorScheme.surfaceContainerLow.toArgb()
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
): ColorScheme {
    val useSystemScheme = shouldUseSystemMonet(useSystemMonet, Build.VERSION.SDK_INT)
    if (!useSystemScheme && accent == AccentPreference.DEFAULT) {
        return defaultStaticColorScheme(dark)
    }
    val scheme = if (useSystemScheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        }
    } else {
        staticAccentColorScheme(
            seedColor = accent.monetSeedColor(customAccentColor),
            dark = dark,
        )
    }
    return scheme.withDeeperMonetSurfaceContainers(dark)
}

internal fun defaultStaticColorScheme(dark: Boolean): ColorScheme {
    return if (dark) {
        darkColorScheme(
            primary = Color.White,
            onPrimary = DEFAULT_DARK_ACCENT,
            primaryContainer = DEFAULT_DARK_SELECTED_CONTAINER,
            onPrimaryContainer = Color.White,
            inversePrimary = DEFAULT_DARK_ACCENT,
            secondary = DEFAULT_DARK_SECONDARY,
            onSecondary = DEFAULT_DARK_ACCENT,
            secondaryContainer = DEFAULT_DARK_SECONDARY_CONTAINER,
            onSecondaryContainer = Color.White,
            tertiary = DEFAULT_DARK_SECONDARY,
            onTertiary = DEFAULT_DARK_ACCENT,
            tertiaryContainer = DEFAULT_DARK_SECONDARY_CONTAINER,
            onTertiaryContainer = Color.White,
            background = DEFAULT_DARK_CANVAS,
            onBackground = Color.White,
            surface = DEFAULT_DARK_SURFACE,
            onSurface = Color.White,
            surfaceVariant = DEFAULT_DARK_SURFACE,
            onSurfaceVariant = DEFAULT_DARK_ON_SURFACE_VARIANT,
            surfaceTint = Color.White,
            inverseSurface = Color.White,
            inverseOnSurface = DEFAULT_DARK_ACCENT,
            outline = DEFAULT_DARK_OUTLINE,
            outlineVariant = DEFAULT_DARK_OUTLINE_VARIANT,
            scrim = Color.Black,
            surfaceBright = DEFAULT_DARK_SURFACE_BRIGHT,
            surfaceDim = DEFAULT_DARK_CANVAS,
            surfaceContainerLowest = DEFAULT_DARK_SURFACE_LOWEST,
            surfaceContainerLow = DEFAULT_DARK_SURFACE,
            surfaceContainer = DEFAULT_DARK_SURFACE,
            surfaceContainerHigh = DEFAULT_DARK_SURFACE_HIGH,
            surfaceContainerHighest = DEFAULT_DARK_SURFACE_HIGHEST,
        )
    } else {
        lightColorScheme(
            primary = DEFAULT_LIGHT_ACCENT,
            onPrimary = Color.White,
            primaryContainer = DEFAULT_LIGHT_SELECTED_CONTAINER,
            onPrimaryContainer = DEFAULT_LIGHT_ACCENT,
            inversePrimary = Color.White,
            secondary = DEFAULT_LIGHT_SECONDARY,
            onSecondary = Color.White,
            secondaryContainer = DEFAULT_LIGHT_SELECTED_CONTAINER,
            onSecondaryContainer = DEFAULT_LIGHT_ACCENT,
            tertiary = DEFAULT_LIGHT_SECONDARY,
            onTertiary = Color.White,
            tertiaryContainer = DEFAULT_LIGHT_SELECTED_CONTAINER,
            onTertiaryContainer = DEFAULT_LIGHT_ACCENT,
            background = DEFAULT_LIGHT_CANVAS,
            onBackground = DEFAULT_LIGHT_ON_SURFACE,
            surface = DEFAULT_LIGHT_SURFACE,
            onSurface = DEFAULT_LIGHT_ON_SURFACE,
            surfaceVariant = DEFAULT_LIGHT_SURFACE_CONTAINER,
            onSurfaceVariant = DEFAULT_LIGHT_ON_SURFACE_VARIANT,
            surfaceTint = DEFAULT_LIGHT_ACCENT,
            inverseSurface = DEFAULT_DARK_SURFACE,
            inverseOnSurface = Color.White,
            outline = DEFAULT_LIGHT_OUTLINE,
            outlineVariant = DEFAULT_LIGHT_OUTLINE_VARIANT,
            scrim = Color.Black,
            surfaceBright = DEFAULT_LIGHT_SURFACE_BRIGHT,
            surfaceDim = DEFAULT_LIGHT_CANVAS,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = DEFAULT_LIGHT_SURFACE_LOW,
            surfaceContainer = DEFAULT_LIGHT_SURFACE_CONTAINER,
            surfaceContainerHigh = DEFAULT_LIGHT_SURFACE_HIGH,
            surfaceContainerHighest = DEFAULT_LIGHT_SURFACE_HIGHEST,
        )
    }
}

internal fun ColorScheme.withDeeperMonetSurfaceContainers(dark: Boolean): ColorScheme {
    if (dark) {
        return copy(
            surfaceContainerLowest = surfaceContainerLowest.deepenToward(surfaceDim, MONET_DARK_SURFACE_DEPTH * 0.7f),
            surfaceContainerLow = surfaceContainerLow.deepenToward(surfaceDim, MONET_DARK_SURFACE_DEPTH),
            surfaceContainer = surfaceContainer.deepenToward(surfaceDim, MONET_DARK_SURFACE_DEPTH * 1.05f),
            surfaceContainerHigh = surfaceContainerHigh.deepenToward(surfaceDim, MONET_DARK_SURFACE_DEPTH * 1.1f),
            surfaceContainerHighest = surfaceContainerHighest.deepenToward(surfaceDim, MONET_DARK_SURFACE_DEPTH * 1.15f),
        )
    }
    return copy(
        background = background.tintToward(primaryContainer, MONET_LIGHT_BACKGROUND_TINT),
        surfaceContainerLowest = surfaceContainerLowest
            .deepenToward(surfaceDim, MONET_LIGHT_SURFACE_LOWEST_DEPTH)
            .tintToward(primaryContainer, MONET_LIGHT_SURFACE_LOWEST_TINT),
        surfaceContainerLow = surfaceContainerLow
            .deepenToward(surfaceDim, MONET_LIGHT_SURFACE_LOW_DEPTH)
            .tintToward(primaryContainer, MONET_LIGHT_SURFACE_LOW_TINT),
        surfaceContainer = surfaceContainer
            .deepenToward(surfaceDim, MONET_LIGHT_SURFACE_DEPTH)
            .tintToward(primaryContainer, MONET_LIGHT_SURFACE_TINT),
        surfaceContainerHigh = surfaceContainerHigh
            .deepenToward(surfaceDim, MONET_LIGHT_SURFACE_HIGH_DEPTH)
            .tintToward(primaryContainer, MONET_LIGHT_SURFACE_HIGH_TINT),
        surfaceContainerHighest = surfaceContainerHighest
            .deepenToward(surfaceDim, MONET_LIGHT_SURFACE_HIGHEST_DEPTH)
            .tintToward(primaryContainer, MONET_LIGHT_SURFACE_HIGHEST_TINT),
    )
}

private fun Color.deepenToward(reference: Color, amount: Float): Color =
    lerp(start = this, stop = reference, fraction = amount)

private fun Color.tintToward(reference: Color, amount: Float): Color =
    lerp(start = this, stop = reference, fraction = amount)

internal fun shouldUseSystemMonet(
    useSystemMonet: Boolean,
    sdkInt: Int,
): Boolean = useSystemMonet && sdkInt >= Build.VERSION_CODES.S

private fun AccentPreference.monetSeedColor(customAccentColor: String): Color = when (this) {
    AccentPreference.DEFAULT,
    AccentPreference.MONET,
    -> MONET_FALLBACK_SEED

    AccentPreference.BLUE -> MONET_BLUE_SEED
    AccentPreference.GREEN -> MONET_GREEN_SEED
    AccentPreference.ROSE -> MONET_RED_SEED
    AccentPreference.VIOLET -> MONET_PURPLE_SEED
    AccentPreference.CUSTOM -> parseColor(customAccentColor) ?: MONET_FALLBACK_SEED
}

@SuppressLint("RestrictedApi")
internal fun staticAccentColorScheme(
    seedColor: Color,
    dark: Boolean,
): ColorScheme {
    val scheme = SchemeTonalSpot(
        Hct.fromInt(seedColor.toArgb()),
        dark,
        MONET_CONTRAST_LEVEL,
    )
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

@Composable
private fun animateWallHubColorScheme(target: ColorScheme): ColorScheme {
    val transition = updateTransition(targetState = target, label = "WallHubThemeColors")
    val spec = tween<Color>(
        durationMillis = THEME_COLOR_TRANSITION_DURATION_MS,
        easing = FastOutSlowInEasing,
    )
    val primary by transition.animateColor({ spec }, label = "primary") { it.primary }
    val onPrimary by transition.animateColor({ spec }, label = "onPrimary") { it.onPrimary }
    val primaryContainer by transition.animateColor({ spec }, label = "primaryContainer") { it.primaryContainer }
    val onPrimaryContainer by transition.animateColor({ spec }, label = "onPrimaryContainer") { it.onPrimaryContainer }
    val inversePrimary by transition.animateColor({ spec }, label = "inversePrimary") { it.inversePrimary }
    val secondary by transition.animateColor({ spec }, label = "secondary") { it.secondary }
    val onSecondary by transition.animateColor({ spec }, label = "onSecondary") { it.onSecondary }
    val secondaryContainer by transition.animateColor({ spec }, label = "secondaryContainer") { it.secondaryContainer }
    val onSecondaryContainer by transition.animateColor({ spec }, label = "onSecondaryContainer") { it.onSecondaryContainer }
    val tertiary by transition.animateColor({ spec }, label = "tertiary") { it.tertiary }
    val onTertiary by transition.animateColor({ spec }, label = "onTertiary") { it.onTertiary }
    val tertiaryContainer by transition.animateColor({ spec }, label = "tertiaryContainer") { it.tertiaryContainer }
    val onTertiaryContainer by transition.animateColor({ spec }, label = "onTertiaryContainer") { it.onTertiaryContainer }
    val background by transition.animateColor({ spec }, label = "background") { it.background }
    val onBackground by transition.animateColor({ spec }, label = "onBackground") { it.onBackground }
    val surface by transition.animateColor({ spec }, label = "surface") { it.surface }
    val onSurface by transition.animateColor({ spec }, label = "onSurface") { it.onSurface }
    val surfaceVariant by transition.animateColor({ spec }, label = "surfaceVariant") { it.surfaceVariant }
    val onSurfaceVariant by transition.animateColor({ spec }, label = "onSurfaceVariant") { it.onSurfaceVariant }
    val surfaceTint by transition.animateColor({ spec }, label = "surfaceTint") { it.surfaceTint }
    val inverseSurface by transition.animateColor({ spec }, label = "inverseSurface") { it.inverseSurface }
    val inverseOnSurface by transition.animateColor({ spec }, label = "inverseOnSurface") { it.inverseOnSurface }
    val error by transition.animateColor({ spec }, label = "error") { it.error }
    val onError by transition.animateColor({ spec }, label = "onError") { it.onError }
    val errorContainer by transition.animateColor({ spec }, label = "errorContainer") { it.errorContainer }
    val onErrorContainer by transition.animateColor({ spec }, label = "onErrorContainer") { it.onErrorContainer }
    val outline by transition.animateColor({ spec }, label = "outline") { it.outline }
    val outlineVariant by transition.animateColor({ spec }, label = "outlineVariant") { it.outlineVariant }
    val scrim by transition.animateColor({ spec }, label = "scrim") { it.scrim }
    val surfaceBright by transition.animateColor({ spec }, label = "surfaceBright") { it.surfaceBright }
    val surfaceDim by transition.animateColor({ spec }, label = "surfaceDim") { it.surfaceDim }
    val surfaceContainer by transition.animateColor({ spec }, label = "surfaceContainer") { it.surfaceContainer }
    val surfaceContainerHigh by transition.animateColor({ spec }, label = "surfaceContainerHigh") { it.surfaceContainerHigh }
    val surfaceContainerHighest by transition.animateColor({ spec }, label = "surfaceContainerHighest") { it.surfaceContainerHighest }
    val surfaceContainerLow by transition.animateColor({ spec }, label = "surfaceContainerLow") { it.surfaceContainerLow }
    val surfaceContainerLowest by transition.animateColor({ spec }, label = "surfaceContainerLowest") { it.surfaceContainerLowest }
    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = surfaceTint,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        surfaceBright = surfaceBright,
        surfaceDim = surfaceDim,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
    )
}

private fun parseColor(value: String): Color? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6) return null
    val rgb = hex.toLongOrNull(16) ?: return null
    return Color((0xFF000000L or rgb).toInt())
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val THEME_COLOR_TRANSITION_DURATION_MS = 360
private const val MONET_CONTRAST_LEVEL = 0.0
private const val MONET_LIGHT_SURFACE_LOWEST_DEPTH = 0.20f
private const val MONET_LIGHT_SURFACE_LOW_DEPTH = 0.30f
private const val MONET_LIGHT_SURFACE_DEPTH = 0.34f
private const val MONET_LIGHT_SURFACE_HIGH_DEPTH = 0.38f
private const val MONET_LIGHT_SURFACE_HIGHEST_DEPTH = 0.42f
private const val MONET_LIGHT_BACKGROUND_TINT = 0.06f
private const val MONET_LIGHT_SURFACE_LOWEST_TINT = 0.10f
private const val MONET_LIGHT_SURFACE_LOW_TINT = 0.20f
private const val MONET_LIGHT_SURFACE_TINT = 0.18f
private const val MONET_LIGHT_SURFACE_HIGH_TINT = 0.16f
private const val MONET_LIGHT_SURFACE_HIGHEST_TINT = 0.14f
private const val MONET_DARK_SURFACE_DEPTH = 0.10f
private val DEFAULT_LIGHT_CANVAS = Color(0xFFF5F6F8)
private val DEFAULT_LIGHT_SURFACE = Color(0xFFF9FAFB)
private val DEFAULT_LIGHT_SURFACE_LOW = Color(0xFFF7F8FA)
private val DEFAULT_LIGHT_SURFACE_CONTAINER = Color(0xFFF1F2F4)
private val DEFAULT_LIGHT_SURFACE_HIGH = Color(0xFFEBECEF)
private val DEFAULT_LIGHT_SURFACE_HIGHEST = Color(0xFFE4E6E9)
private val DEFAULT_LIGHT_SURFACE_BRIGHT = Color(0xFFFCFCFD)
private val DEFAULT_LIGHT_ACCENT = Color(0xFF242424)
private val DEFAULT_LIGHT_ON_SURFACE = Color(0xFF191919)
private val DEFAULT_LIGHT_ON_SURFACE_VARIANT = Color(0xFF5F5F5F)
private val DEFAULT_LIGHT_SECONDARY = Color(0xFF424242)
private val DEFAULT_LIGHT_SELECTED_CONTAINER = Color(0xFFE4E4E4)
private val DEFAULT_LIGHT_OUTLINE = Color(0xFF777777)
private val DEFAULT_LIGHT_OUTLINE_VARIANT = Color(0xFFC6C6C6)
private val DEFAULT_DARK_CANVAS = Color(0xFF121212)
private val DEFAULT_DARK_ACCENT = Color(0xFF181818)
private val DEFAULT_DARK_SURFACE_LOWEST = Color(0xFF101010)
private val DEFAULT_DARK_SURFACE = Color(0xFF1C1C1C)
private val DEFAULT_DARK_SURFACE_HIGH = Color(0xFF242424)
private val DEFAULT_DARK_SURFACE_HIGHEST = Color(0xFF2C2C2C)
private val DEFAULT_DARK_SURFACE_BRIGHT = Color(0xFF303030)
private val DEFAULT_DARK_SECONDARY = Color(0xFFD4D4D4)
private val DEFAULT_DARK_SECONDARY_CONTAINER = Color(0xFF3A3A3A)
private val DEFAULT_DARK_SELECTED_CONTAINER = Color(0xFF3A3A3A)
private val DEFAULT_DARK_ON_SURFACE_VARIANT = Color(0xFFC8C8C8)
private val DEFAULT_DARK_OUTLINE = Color(0xFFA8A8A8)
private val DEFAULT_DARK_OUTLINE_VARIANT = Color(0xFF4C4C4C)
private const val DEFAULT_CUSTOM_MONET_SEED_HEX = "#5B7AA0"
private val MONET_FALLBACK_SEED = Color(0xFF5B7AA0)
private val MONET_BLUE_SEED = Color(0xFF2B6CB0)
private val MONET_GREEN_SEED = Color(0xFF2F855A)
private val MONET_RED_SEED = Color(0xFFC53030)
private val MONET_PURPLE_SEED = Color(0xFF805AD5)
