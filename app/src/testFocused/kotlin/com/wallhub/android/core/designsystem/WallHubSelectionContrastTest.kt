package com.wallhub.android.core.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.wallhub.android.core.model.VisualStyle
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Selection cards must consume one complete colour pair. These assertions pin the final composited
 * contrast rather than token names so a future role mix-up fails here.
 */
class WallHubSelectionContrastTest {
    private val bettboxLight =
        lightColorScheme(
            primary = Color(0xFF0C665B),
            primaryContainer = Color(0xFFCFE9E4),
            onPrimaryContainer = Color(0xFF00201C),
            secondaryContainer = Color(0xFFCBE8E2),
            onSecondaryContainer = Color(0xFF05201C),
            surface = Color(0xFFF1F5F2),
            surfaceContainer = Color(0xFFEAEFEB),
            surfaceContainerLow = Color(0xFFEEF2EF),
            surfaceContainerHigh = Color(0xFFE3E9E5),
            surfaceContainerHighest = Color(0xFFDCE3DE),
            outlineVariant = Color(0xFFC2CEC6),
        )

    private val bettboxDark =
        darkColorScheme(
            primary = Color(0xFF8BD8CB),
            primaryContainer = Color(0xFF0E4C43),
            onPrimaryContainer = Color(0xFFA6F2E4),
            secondaryContainer = Color(0xFF334B46),
            onSecondaryContainer = Color(0xFFCDE8E1),
            surface = Color(0xFF121915),
            surfaceContainer = Color(0xFF161D1A),
            surfaceContainerLow = Color(0xFF1B2320),
            surfaceContainerHigh = Color(0xFF27312C),
            surfaceContainerHighest = Color(0xFF323D37),
            outlineVariant = Color(0xFF404B45),
        )

    @Test
    fun `bettbox selection text reaches body contrast in light and dark`() {
        val light = buildWallHubStyle(bettboxLight, VisualStyle.MATERIAL3_EXPRESSIVE, isDark = false).selection
        val dark = buildWallHubStyle(bettboxDark, VisualStyle.MATERIAL3_EXPRESSIVE, isDark = true).selection

        assertTrue(
            contrastRatio(light.content, light.container) >= 4.5,
            "light content contrast was ${contrastRatio(light.content, light.container)}",
        )
        assertTrue(
            contrastRatio(light.supportingContent, light.container) >= 4.5,
            "light supporting contrast was ${contrastRatio(light.supportingContent, light.container)}",
        )
        assertTrue(
            contrastRatio(dark.content, dark.container) >= 4.5,
            "dark content contrast was ${contrastRatio(dark.content, dark.container)}",
        )
        assertTrue(
            contrastRatio(dark.supportingContent, dark.container) >= 4.5,
            "dark supporting contrast was ${contrastRatio(dark.supportingContent, dark.container)}",
        )
    }

    @Test
    fun `classic selection keeps its original primary container pair`() {
        val light = buildWallHubStyle(lightColorScheme(), VisualStyle.MATERIAL3, isDark = false).selection
        val dark = buildWallHubStyle(darkColorScheme(), VisualStyle.MATERIAL3, isDark = true).selection

        assertTrue(
            contrastRatio(light.content, light.container) >= 4.5,
            "classic light content contrast was ${contrastRatio(light.content, light.container)}",
        )
        assertTrue(
            contrastRatio(dark.content, dark.container) >= 4.5,
            "classic dark content contrast was ${contrastRatio(dark.content, dark.container)}",
        )
    }

    private fun contrastRatio(
        foreground: Color,
        background: Color,
    ): Double {
        val lighter = maxOf(foreground.luminance(), background.luminance()).toDouble()
        val darker = minOf(foreground.luminance(), background.luminance()).toDouble()
        return (lighter + 0.05) / (darker + 0.05)
    }
}

