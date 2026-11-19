package com.wallhub.android.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.wallhub.android.core.model.VisualStyle
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WallHubVisualStyleTest {
    private val classicLight = lightColorScheme()
    private val classicDark = darkColorScheme()
    private val dynamicLight =
        lightColorScheme(
            primary = Color(0xFF0C665B),
            primaryContainer = Color(0xFFCFE9E4),
            surface = Color(0xFFF1F5F2),
            surfaceContainerLowest = Color(0xFFF8FAF9),
            surfaceContainerLow = Color(0xFFEEF2EF),
            surfaceContainer = Color(0xFFEAEFEB),
            surfaceContainerHigh = Color(0xFFE3E9E5),
            surfaceContainerHighest = Color(0xFFDCE3DE),
            outlineVariant = Color(0xFFC2CEC6),
        )
    private val dynamicDark =
        darkColorScheme(
            primary = Color(0xFF8BD8CB),
            primaryContainer = Color(0xFF0E4C43),
            surface = Color(0xFF121915),
            surfaceContainerLowest = Color(0xFF0C120F),
            surfaceContainerLow = Color(0xFF1B2320),
            surfaceContainer = Color(0xFF161D1A),
            surfaceContainerHigh = Color(0xFF27312C),
            surfaceContainerHighest = Color(0xFF323D37),
            outlineVariant = Color(0xFF404B45),
        )

    @Test
    fun `classic style is an exact surface and component token identity`() {
        val style = buildWallHubStyle(classicLight, VisualStyle.MATERIAL3, isDark = false)

        assertEquals(classicLight, style.colorScheme)
        assertEquals(classicLight.surface, style.surfaces.page)
        assertEquals(classicLight.surfaceContainer, style.surfaces.content)
        assertEquals(classicLight.surfaceContainerHigh, style.surfaces.control)
        assertEquals(classicLight.surfaceContainerHighest, style.surfaces.controlStrong)
        assertEquals(classicLight.surfaceContainer, style.surfaces.browseCard)
        assertEquals(classicLight.surfaceContainer, style.surfaces.settingsRow)
        assertEquals(classicLight.surfaceContainerHigh, style.dialog.container)
        assertEquals(classicLight.surfaceContainerLow, style.surfaces.sheet)
        assertEquals(classicLight.surfaceContainerHighest, style.field.container)
        assertEquals(classicLight.surfaceContainer, style.navigation.container)
        assertEquals(classicLight.primaryContainer, style.selection.container)
        assertEquals(classicLight.secondaryContainer, style.navigation.selectedContainer)
        assertEquals(classicLight.onSecondaryContainer, style.navigation.selectedContent)
        assertEquals(classicLight.onSecondaryContainer, style.navigation.drawerSelectedContent)
        assertEquals(0.dp, style.elevations.card)
        assertEquals(0.dp, style.navigation.elevation)
        assertEquals(0.dp, style.dialog.elevation)
        assertEquals(2.dp, style.lists.commentSpacing)
        assertEquals(20.dp, style.shapes.detailHeroRadius)
        assertEquals(20.dp, style.shapes.cardRadius)
        assertEquals(classicLight.outlineVariant, style.dividers.normal)
        assertEquals(0.32f, style.dividers.subtle.alpha, 0.01f)
        assertEquals(1.dp, style.dividers.thickness)
        assertTrue(style.dividers.showTabDivider)
        assertEquals(classicLight.surfaceContainerHighest, style.surfaces.progressTrack)
        assertFalse(style.capabilities.bettboxSurfaces)
        assertFalse(style.capabilities.compactSettings)
        assertFalse(style.capabilities.accentSelection)
        assertFalse(style.navigation.floating)
    }

    @Test
    fun `classic style matches the current light and dark surface hierarchy`() {
        val light = buildWallHubStyle(classicLight, VisualStyle.MATERIAL3, isDark = false)
        val dark = buildWallHubStyle(classicDark, VisualStyle.MATERIAL3, isDark = true)

        assertEquals(classicLight.surface, light.surfaces.page)
        assertEquals(classicLight.surfaceContainer, light.surfaces.content)
        assertEquals(classicDark.surfaceContainerHigh, dark.dialog.container)
        assertEquals(classicDark.surfaceContainer, dark.surfaces.content)
    }

    @Test
    fun `monet bright light page is tinted and foreground is lighter but not pure white`() {
        val style = buildWallHubStyle(dynamicLight, VisualStyle.MATERIAL3_EXPRESSIVE, isDark = false)

        assertEquals(dynamicLight.surfaceContainer, style.surfaces.page)
        assertEquals(
            lerp(dynamicLight.surface, dynamicLight.surfaceContainerLowest, 0.5f),
            style.surfaces.content,
        )
        assertTrue(style.surfaces.content.luminance() > style.surfaces.page.luminance())
        assertNotEquals(Color.White, style.surfaces.content)
        assertTrue(style.dialog.container.luminance() > style.surfaces.content.luminance())
        assertTrue(style.field.container.luminance() > style.surfaces.content.luminance())
        assertNotEquals(style.surfaces.content, style.surfaces.page)
        assertEquals(style.surfaces.content, style.surfaces.control)
        assertEquals(style.surfaces.content, style.surfaces.controlStrong)
        assertEquals(style.surfaces.content, style.surfaces.browseCard)
        assertEquals(0.dp, style.elevations.card)
        assertEquals(2.dp, style.lists.commentSpacing)
        assertTrue(style.navigation.floating)
        assertEquals(0.dp, style.navigation.elevation)
        assertEquals(6.dp, style.dialog.elevation)
        assertEquals(style.elevations.card, style.navigation.elevation)
        assertTrue(style.navigation.elevation < style.dialog.elevation)
        assertEquals(12.dp, style.navigation.bottomInset)
        assertEquals(17.dp, style.navigation.horizontalInset)
        assertEquals(RoundedCornerShape(30.dp), style.navigation.shape)
        assertEquals(dynamicLight.secondaryContainer, style.selection.container)
        assertEquals(dynamicLight.primary, style.navigation.selectedContent)
        assertEquals(dynamicLight.primary, style.navigation.drawerSelectedContent)
    }

    @Test
    fun `monet bright dark uses container page and high foreground without simply inverting`() {
        val style = buildWallHubStyle(dynamicDark, VisualStyle.MATERIAL3_EXPRESSIVE, isDark = true)

        assertEquals(dynamicDark.surfaceContainer, style.surfaces.page)
        assertEquals(dynamicDark.surfaceContainerHigh, style.surfaces.content)
        assertEquals(dynamicDark.surfaceContainerHigh, style.dialog.container)
        assertEquals(dynamicDark.surfaceContainerLow, style.surfaces.sheet)
        assertEquals(20.dp, style.shapes.detailHeroRadius)
        assertEquals(0.40f, style.dividers.normal.alpha, 0.01f)
        assertEquals(0.32f, style.dividers.subtle.alpha, 0.01f)
        assertFalse(style.dividers.showTabDivider)
        assertNotEquals(style.surfaces.content, style.surfaces.progressTrack)
        assertTrue(
            colorDistance(style.surfaces.content, style.surfaces.progressTrack) > 0.05f,
        )
        assertTrue(style.capabilities.bettboxSurfaces)
        assertTrue(style.capabilities.compactSettings)
        assertTrue(style.capabilities.accentSelection)
        assertTrue(style.surfaces.content.luminance() > style.surfaces.page.luminance())
        assertTrue(style.navigation.floating)
    }

    @Test
    fun `pure black dark page is preserved in both material styles`() {
        val pureBlackScheme =
            darkColorScheme(
                background = Color.Black,
                surface = Color.Black,
                surfaceDim = Color.Black,
                surfaceContainer = Color(0xFF0D0D0D),
                surfaceContainerHigh = Color(0xFF141414),
                surfaceContainerHighest = Color(0xFF1B1B1B),
            )

        listOf(VisualStyle.MATERIAL3, VisualStyle.MATERIAL3_EXPRESSIVE).forEach { visualStyle ->
            val style =
                buildWallHubStyle(
                    baseScheme = pureBlackScheme,
                    visualStyle = visualStyle,
                    isDark = true,
                    pureBlackInDark = true,
                )

            assertEquals(Color.Black, style.surfaces.page)
            if (visualStyle == VisualStyle.MATERIAL3_EXPRESSIVE) {
                assertEquals(Color.Black, style.colorScheme.background)
                assertEquals(Color.Black, style.colorScheme.surfaceDim)
            }
        }
    }

    @Test
    fun `pure black preference does not affect light page`() {
        val style =
            buildWallHubStyle(
                baseScheme = classicLight,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
                isDark = false,
                pureBlackInDark = true,
            )

        assertEquals(classicLight.surfaceContainer, style.surfaces.page)
    }

    @Test
    fun `monet bright maps material surface roles for unmigrated material components`() {
        val style = buildWallHubStyle(dynamicLight, VisualStyle.MATERIAL3_EXPRESSIVE, isDark = false)
        val scheme = style.colorScheme

        assertEquals(style.surfaces.page, scheme.surfaceContainer)
        assertEquals(style.surfaces.content, scheme.surfaceContainerLowest)
        assertEquals(style.surfaces.content, scheme.surfaceContainerLow)
        assertEquals(style.surfaces.content, scheme.surfaceContainerHigh)
        assertEquals(style.field.container, scheme.surfaceContainerHighest)
        assertEquals(style.surfaces.page, scheme.background)
    }
    private fun colorDistance(first: Color, second: Color): Float =
        kotlin.math.abs(first.red - second.red) + kotlin.math.abs(first.green - second.green) + kotlin.math.abs(first.blue - second.blue)
}
