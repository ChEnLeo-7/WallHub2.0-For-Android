package com.wallhub.android.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WallHubTokenScaleTest {
    @Test
    fun typographyExposesSemanticCompactTiers() {
        assertEquals(14.sp, WallHubTypography.titleSmall.fontSize)
        assertEquals(12.sp, WallHubTypography.labelMedium.fontSize)
        assertEquals(12.sp, WallHubTypography.labelSmall.fontSize)
        assertEquals(12.sp, WallHubTypography.bodySmall.fontSize)
        assertTrue(WallHubTypography.labelSmall.fontSize.value >= 12f)
        assertTrue(WallHubTypography.bodySmall.fontSize.value >= 12f)
    }

    @Test
    fun mediaOverlayRolesKeepContrastOnWorstCaseImages() {
        val whiteImage = Color.White
        val scrimmed = WallHubColorTokens.mediaOverlayScrim.compositeOver(whiteImage)
        assertTrue(contrast(WallHubColorTokens.mediaOverlayContent, scrimmed) >= 4.5f)
        assertTrue(contrast(WallHubColorTokens.mediaOverlaySupportingContent, scrimmed) >= 4.5f)
        assertEquals(1f, WallHubColorTokens.mediaOverlayContent.alpha)
        assertEquals(1f, WallHubColorTokens.mediaOverlaySupportingContent.alpha)
    }

    private fun contrast(
        foreground: Color,
        background: Color,
    ): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    @Test
    fun spacingKeepsApprovedGapsAsNamedTokens() {
        assertEquals(2.dp, WallHubSpacing.cardGap)
        assertEquals(6.dp, WallHubSpacing.metaGap)
        assertEquals(4.dp, WallHubSpacing.xxs)
        assertEquals(8.dp, WallHubSpacing.xs)
        assertEquals(12.dp, WallHubSpacing.sm)
        assertEquals(16.dp, WallHubSpacing.md)
        assertEquals(24.dp, WallHubSpacing.lg)
    }

    @Test
    fun actionAndShapeTokensCarrySemanticSizes() {
        assertEquals(56.dp, WallHubSizeTokens.primaryActionHeight)
        assertEquals(56.dp, WallHubSizeTokens.secondaryActionHeight)
        assertEquals(44.dp, WallHubSizeTokens.wideCardAction)
        assertEquals(24.dp, WallHubSizeTokens.iconLarge)
        assertEquals(720.dp, WallHubSizeTokens.detailReadableMaxWidth)
        assertTrue(WallHubShapeTokens.cta is RoundedCornerShape)
        assertTrue(WallHubShapeTokens.secondaryAction is RoundedCornerShape)
        assertTrue(WallHubShapeTokens.cta != WallHubShapeTokens.secondaryAction)
    }
}
