package com.wallhub.android.core.designsystem

import androidx.compose.ui.graphics.Color

/** Non-theme colors with a stable semantic role across feature screens. */
object WallHubColorTokens {
    val mediaCanvas: Color = Color.Black
    val mediaOverlayScrim: Color = Color.Black.copy(alpha = 0.58f)
    val mediaControlScrim: Color = Color.Black.copy(alpha = 0.38f)
    val mediaOverlayContent: Color = Color.White
    val customAccentPreviewOnLight: Color = Color(0xFF171C19)
    val customAccentPreviewOnDark: Color = Color.White
}
