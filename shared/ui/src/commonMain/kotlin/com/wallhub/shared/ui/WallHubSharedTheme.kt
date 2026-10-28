package com.wallhub.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.wallhub.shared.ui.generated.resources.Res
import com.wallhub.shared.ui.generated.resources.noto_sans_sc_wallhub
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.Font

@Composable
@OptIn(ExperimentalResourceApi::class)
fun WallHubSharedTheme(
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val fontFamily =
        FontFamily(
            Font(Res.font.noto_sans_sc_wallhub, FontWeight.Normal),
            Font(Res.font.noto_sans_sc_wallhub, FontWeight.Medium),
            Font(Res.font.noto_sans_sc_wallhub, FontWeight.SemiBold),
            Font(Res.font.noto_sans_sc_wallhub, FontWeight.Bold),
        )
    MaterialTheme(
        colorScheme = wallHubStaticColorScheme(dark),
        typography = wallHubTypography(fontFamily),
        shapes = WallHubShapeTokens.material,
        content = content,
    )
}
