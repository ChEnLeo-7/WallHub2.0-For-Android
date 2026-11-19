@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wallhub.android.core.model.VisualStyle

/**
 * The visual-style seam.
 *
 * Callers ask for a semantic role (page, card, dialog, settings row, navigation) and do not know
 * whether it came from the classic surface hierarchy or the Monet Bright interpolation.
 */
@Immutable
data class WallHubStyle(
    val isDark: Boolean,
    val colorScheme: ColorScheme,
    val materialShapes: Shapes,
    val surfaces: WallHubSurfaceTokens,
    val selection: WallHubSelectionTokens,
    val dividers: WallHubDividerTokens,
    val capabilities: WallHubVisualCapabilities,
    val shapes: WallHubComponentShapes,
    val elevations: WallHubElevationTokens,
    val dialog: WallHubDialogTokens,
    val field: WallHubFieldTokens,
    val navigation: WallHubNavigationTokens,
    val lists: WallHubListTokens,
)

@Immutable
data class WallHubSurfaceTokens(
    val page: Color,
    val content: Color,
    val sheet: Color,
    val menu: Color,
    val control: Color,
    val controlStrong: Color,
    val browseCard: Color,
    val settingsRow: Color,
    val tag: Color,
    val divider: Color,
    val progressTrack: Color,
    val mediaPlaceholder: Color,
    val searchFab: Color,
    val searchFabContent: Color,
)

@Immutable
data class WallHubVisualCapabilities(
    val bettboxSurfaces: Boolean,
    val compactSettings: Boolean,
    val accentSelection: Boolean,
)

@Immutable
data class WallHubDividerTokens(
    val normal: Color,
    val subtle: Color,
    val thickness: Dp,
    val showTabDivider: Boolean,
)

@Immutable
data class WallHubSelectionTokens(
    val container: Color,
    val content: Color,
    val supportingContent: Color,
    val icon: Color,
    val shape: Shape,
)

@Immutable
data class WallHubComponentShapes(
    val card: Shape,
    val detailHero: Shape,
    val detailHeroRadius: Dp,
    val carousel: Shape,
    val sheet: Shape,
    val navigation: Shape,
    val cardRadius: Dp,
)

@Immutable
data class WallHubElevationTokens(
    val card: Dp,
)

@Immutable
data class WallHubDialogTokens(
    val container: Color,
    val shape: Shape,
    val elevation: Dp,
)

@Immutable
data class WallHubFieldTokens(
    val container: Color,
    val focusedContainer: Color,
    val unfocusedContainer: Color,
    val disabledContainer: Color,
    val errorContainer: Color,
    val shape: Shape,
)

@Immutable
data class WallHubListTokens(
    val commentSpacing: Dp,
)

@Immutable
data class WallHubNavigationTokens(
    val floating: Boolean,
    val container: Color,
    val selectedContainer: Color,
    val selectedContent: Color,
    val drawerSelectedContent: Color,
    val content: Color,
    val shape: Shape,
    val height: Dp,
    val horizontalInset: Dp,
    val bottomInset: Dp,
    val elevation: Dp,
    val railContainer: Color,
    val drawerContainer: Color,
)

internal val LocalWallHubStyle = compositionLocalOf<WallHubStyle?> { null }

/** Resolves the active style, falling back to Classic when a legacy caller skips WallHubTheme. */
@Composable
fun wallHubStyle(): WallHubStyle {
    LocalWallHubStyle.current?.let { return it }
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.surface.luminance() < 0.5f
    return remember(scheme, isDark) {
        buildWallHubStyle(
            baseScheme = scheme,
            visualStyle = VisualStyle.MATERIAL3,
            isDark = isDark,
        )
    }
}

@Suppress("CyclomaticComplexMethod")
internal fun buildWallHubStyle(
    baseScheme: ColorScheme,
    visualStyle: VisualStyle,
    isDark: Boolean,
    pureBlackInDark: Boolean = false,
): WallHubStyle {
    val classic = visualStyle == VisualStyle.MATERIAL3
    val pureBlackPage = pureBlackInDark && isDark
    val content =
        if (classic) {
            baseScheme.surfaceContainer
        } else if (isDark) {
            baseScheme.surfaceContainerHigh
        } else {
            lerp(baseScheme.surface, baseScheme.surfaceContainerLowest, 0.5f)
        }
    val page =
        when {
            pureBlackPage -> Color.Black
            classic -> baseScheme.surface
            else -> baseScheme.surfaceContainer
        }
    val field =
        if (classic) {
            baseScheme.surfaceContainerHighest
        } else if (isDark) {
            baseScheme.surfaceContainerHighest
        } else {
            lerp(content, baseScheme.surfaceContainerLowest, 0.72f)
        }
    val dialogContainer =
        if (classic) {
            baseScheme.surfaceContainerHigh
        } else if (isDark) {
            baseScheme.surfaceContainerHigh
        } else {
            lerp(content, baseScheme.surfaceContainerLowest, 0.62f)
        }
    val divider =
        if (classic) {
            baseScheme.outlineVariant
        } else {
            baseScheme.outlineVariant.copy(alpha = 0.40f)
        }
    val mappedScheme =
        if (classic) {
            baseScheme
        } else {
            baseScheme.copy(
                background = page,
                surface = content,
                surfaceVariant = field,
                surfaceBright = content,
                surfaceDim = page,
                surfaceContainerLowest = content,
                surfaceContainerLow = content,
                surfaceContainer = page,
                surfaceContainerHigh = content,
                surfaceContainerHighest = field,
            )
        }
    val selectedContainer =
        if (classic) {
            baseScheme.primaryContainer
        } else {
            baseScheme.secondaryContainer
        }
    val selectedContent =
        if (classic) {
            baseScheme.onPrimaryContainer
        } else {
            baseScheme.onSecondaryContainer
        }
    val cardShape = RoundedCornerShape(20.dp)
    val detailShape = RoundedCornerShape(20.dp)
    val carouselShape = RoundedCornerShape(20.dp)
    val dialogShape = RoundedCornerShape(if (classic) 20.dp else 28.dp)
    val sheetShape =
        RoundedCornerShape(
            topStart = if (classic) 20.dp else 28.dp,
            topEnd = if (classic) 20.dp else 28.dp,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        )
    val fieldShape = RoundedCornerShape(20.dp)
    val navigationShape = if (classic) RoundedCornerShape(0.dp) else RoundedCornerShape(30.dp)
    val navContainer =
        if (classic) {
            baseScheme.surfaceContainer
        } else {
            content
        }
    val navSelected =
        if (classic) {
            baseScheme.secondaryContainer
        } else {
            baseScheme.primary.copy(alpha = if (isDark) 0.20f else 0.12f)
        }
    val railContainer = if (classic) baseScheme.surface else content
    val drawerContainer = if (classic) baseScheme.surfaceContainerLow else content

    return WallHubStyle(
        isDark = isDark,
        colorScheme = mappedScheme,
        materialShapes =
            if (classic) {
                Shapes(
                    extraSmall = RoundedCornerShape(4.dp),
                    small = RoundedCornerShape(8.dp),
                    medium = RoundedCornerShape(12.dp),
                    large = RoundedCornerShape(16.dp),
                    extraLarge = RoundedCornerShape(28.dp),
                )
            } else {
                Shapes(
                    extraSmall = RoundedCornerShape(4.dp),
                    small = RoundedCornerShape(8.dp),
                    medium = RoundedCornerShape(20.dp),
                    large = RoundedCornerShape(20.dp),
                    extraLarge = RoundedCornerShape(28.dp),
                )
            },
        surfaces =
            WallHubSurfaceTokens(
                page = page,
                content = content,
                sheet = baseScheme.surfaceContainerLow,
                menu = if (classic) baseScheme.surfaceContainer else content,
                control = if (classic) baseScheme.surfaceContainerHigh else content,
                controlStrong = if (classic) baseScheme.surfaceContainerHighest else content,
                browseCard = if (classic) baseScheme.surfaceContainer else content,
                settingsRow = if (classic) baseScheme.surfaceContainer else content,
                tag = baseScheme.secondaryContainer,
                divider = divider,
                progressTrack =
                    if (classic) {
                        baseScheme.surfaceContainerHighest
                    } else {
                        lerp(baseScheme.outlineVariant, content, 0.35f)
                    },
                mediaPlaceholder = baseScheme.surfaceContainerHigh,
                searchFab = if (classic) baseScheme.primary else content,
                searchFabContent = if (classic) baseScheme.onPrimary else baseScheme.primary,
            ),
        selection =
            WallHubSelectionTokens(
                container = selectedContainer,
                content = selectedContent,
                supportingContent = selectedContent,
                icon = selectedContent,
                shape = if (classic) RoundedCornerShape(20.dp) else RoundedCornerShape(14.dp),
            ),
        dividers =
            WallHubDividerTokens(
                normal = if (classic) baseScheme.outlineVariant else baseScheme.outlineVariant.copy(alpha = 0.40f),
                subtle = baseScheme.outlineVariant.copy(alpha = 0.32f),
                thickness = 1.dp,
                showTabDivider = classic,
            ),
        capabilities =
            WallHubVisualCapabilities(
                bettboxSurfaces = !classic,
                compactSettings = !classic,
                accentSelection = !classic,
            ),
        shapes =
            WallHubComponentShapes(
                card = cardShape,
                detailHero = detailShape,
                detailHeroRadius = 20.dp,
                carousel = carouselShape,
                sheet = sheetShape,
                navigation = navigationShape,
                cardRadius = 20.dp,
            ),
        elevations =
            WallHubElevationTokens(
                card = 0.dp,
            ),
        dialog =
            WallHubDialogTokens(
                container = dialogContainer,
                shape = dialogShape,
                elevation = if (classic) 0.dp else 6.dp,
            ),
        field =
            WallHubFieldTokens(
                container = field,
                focusedContainer =
                    if (classic) baseScheme.surfaceContainerHighest else field,
                unfocusedContainer =
                    if (classic) baseScheme.surfaceContainerHigh else field,
                disabledContainer =
                    if (classic) baseScheme.surfaceContainerHigh else field,
                errorContainer =
                    if (classic) baseScheme.surfaceContainerHighest else field,
                shape = fieldShape,
            ),
        navigation =
            WallHubNavigationTokens(
                floating = !classic,
                container = navContainer,
                selectedContainer = navSelected,
                selectedContent = if (classic) baseScheme.onSecondaryContainer else baseScheme.primary,
                drawerSelectedContent = if (classic) baseScheme.onSecondaryContainer else baseScheme.primary,
                content = baseScheme.onSurfaceVariant,
                shape = navigationShape,
                height = if (classic) 80.dp else 60.dp,
                horizontalInset = if (classic) 0.dp else 17.dp,
                bottomInset = if (classic) 0.dp else 12.dp,
                elevation = 0.dp,
                railContainer = railContainer,
                drawerContainer = drawerContainer,
            ),
        lists =
            WallHubListTokens(
                commentSpacing = 2.dp,
            ),
    )
}
