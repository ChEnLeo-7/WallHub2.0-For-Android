package com.wallhub.android.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WallHubThemeTest {
    @Test
    fun systemMonetRequiresBothTheUserSettingAndAndroidTwelve() {
        assertTrue(shouldUseSystemMonet(useSystemMonet = true, sdkInt = 31))
        assertFalse(shouldUseSystemMonet(useSystemMonet = false, sdkInt = 31))
        assertFalse(shouldUseSystemMonet(useSystemMonet = true, sdkInt = 30))
    }

    @Test
    fun staticLightAccentRetainsTheMaterialSurfaceHierarchy() {
        val scheme =
            staticAccentColorScheme(
                seedColor = Color(0xFF2F855A),
                dark = false,
            )

        assertNotEquals(scheme.background, scheme.surfaceContainerLow)
        assertNotEquals(scheme.surfaceContainerLow, scheme.surfaceContainer)
        assertNotEquals(scheme.surfaceContainer, scheme.surfaceContainerHigh)
    }

    @Test
    fun daylightMonetSurfaceContainersKeepAVisibleFlatHierarchy() {
        listOf(
            Color(0xFF5B7AA0),
            Color(0xFF2B6CB0),
            Color(0xFF2F855A),
            Color(0xFFC53030),
            Color(0xFF805AD5),
        ).forEach { seedColor ->
            val source = staticAccentColorScheme(seedColor = seedColor, dark = false)
            val deepened = source.withDeeperMonetSurfaceContainers(dark = false)
            val untintedLow = lerp(source.surfaceContainerLow, source.surfaceDim, 0.30f)

            assertTrue(source.background.luminance() - deepened.surfaceContainerLow.luminance() >= 0.04f)
            assertTrue(
                colorDistance(deepened.background, source.primaryContainer) <
                    colorDistance(source.background, source.primaryContainer),
            )
            assertNotEquals(untintedLow, deepened.surfaceContainerLow)
            assertTrue(
                colorDistance(deepened.surfaceContainerLow, source.primaryContainer) <
                    colorDistance(untintedLow, source.primaryContainer),
            )
            assertTrue(deepened.surfaceContainerLowest.luminance() > deepened.surfaceContainerLow.luminance())
            assertTrue(deepened.surfaceContainerLow.luminance() > deepened.surfaceContainer.luminance())
            assertTrue(deepened.surfaceContainer.luminance() > deepened.surfaceContainerHigh.luminance())
            assertTrue(deepened.surfaceContainerHigh.luminance() > deepened.surfaceContainerHighest.luminance())
        }
    }

    @Test
    fun darkMonetSurfaceContainersKeepTheirExistingDepthProfile() {
        val source =
            staticAccentColorScheme(
                seedColor = Color(0xFF2F855A),
                dark = true,
            )
        val deepened = source.withDeeperMonetSurfaceContainers(dark = true)

        assertEquals(lerp(source.surfaceContainerLowest, source.surfaceDim, 0.07f), deepened.surfaceContainerLowest)
        assertEquals(lerp(source.surfaceContainerLow, source.surfaceDim, 0.10f), deepened.surfaceContainerLow)
        assertEquals(lerp(source.surfaceContainer, source.surfaceDim, 0.105f), deepened.surfaceContainer)
        assertEquals(lerp(source.surfaceContainerHigh, source.surfaceDim, 0.11f), deepened.surfaceContainerHigh)
        assertEquals(lerp(source.surfaceContainerHighest, source.surfaceDim, 0.115f), deepened.surfaceContainerHighest)
    }

    @Test
    fun defaultStaticLightSchemeUsesTonalSurfacesOnAGrayCanvas() {
        val scheme = defaultStaticColorScheme(dark = false)

        assertEquals(Color(0xFFF5F6F8), scheme.background)
        assertEquals(Color(0xFF242424), scheme.primary)
        assertEquals(Color.White, scheme.onPrimary)
        assertEquals(Color(0xFFF9FAFB), scheme.surface)
        assertEquals(Color.White, scheme.surfaceContainerLowest)
        assertEquals(Color(0xFFF7F8FA), scheme.surfaceContainerLow)
        assertEquals(Color(0xFFF1F2F4), scheme.surfaceContainer)
        assertEquals(Color(0xFFEBECEF), scheme.surfaceContainerHigh)
        assertEquals(Color(0xFFE4E6E9), scheme.surfaceContainerHighest)
    }

    @Test
    fun defaultStaticDarkSchemeStaysNeutralWithoutAMonetAccent() {
        val scheme = defaultStaticColorScheme(dark = true)

        assertEquals(Color.White, scheme.primary)
        assertEquals(Color(0xFF181818), scheme.onPrimary)
        assertEquals(Color(0xFF121212), scheme.background)
        assertEquals(Color(0xFF1C1C1C), scheme.surface)
    }

    @Test
    fun distinctAccentSeedsProduceDistinctMaterialPrimaryRoles() {
        val green = staticAccentColorScheme(Color(0xFF2F855A), dark = false)
        val rose = staticAccentColorScheme(Color(0xFFC53030), dark = false)

        assertNotEquals(green.primary, rose.primary)
        assertNotEquals(green.primaryContainer, rose.primaryContainer)
    }

    private fun colorDistance(
        first: Color,
        second: Color,
    ): Float = abs(first.red - second.red) + abs(first.green - second.green) + abs(first.blue - second.blue)
}
