/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.framework.theme

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens

/** Retains Material's type scale while resolving font families from the current system overlay. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun rememberSettingsTypography(): Typography {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        val brand =
            context.systemFontFamily("config_headlineFontFamily", "config_headlineFontFamilyMedium")
        val plain = context.systemFontFamily("config_bodyFontFamily", "config_bodyFontFamilyMedium")
        val defaults = Typography()
        defaults.copy(
            displayLarge = defaults.displayLarge.withSystemFont(brand),
            displayMedium = defaults.displayMedium.withSystemFont(brand),
            displaySmall = defaults.displaySmall.withSystemFont(brand),
            headlineLarge = defaults.headlineLarge.withSystemFont(brand),
            headlineMedium = defaults.headlineMedium.withSystemFont(brand),
            headlineSmall = defaults.headlineSmall.withSystemFont(brand),
            titleLarge = defaults.titleLarge.withSystemFont(brand),
            titleMedium = defaults.titleMedium.withSystemFont(brand),
            titleSmall = defaults.titleSmall.withSystemFont(brand),
            bodyLarge = defaults.bodyLarge.withSystemFont(plain),
            bodyMedium = defaults.bodyMedium.withSystemFont(plain),
            bodySmall = defaults.bodySmall.withSystemFont(plain),
            labelLarge = defaults.labelLarge.withSystemFont(plain),
            labelMedium = defaults.labelMedium.withSystemFont(plain),
            labelSmall = defaults.labelSmall.withSystemFont(plain),
            displayLargeEmphasized = defaults.displayLargeEmphasized.withSystemFont(brand),
            displayMediumEmphasized = defaults.displayMediumEmphasized.withSystemFont(brand),
            displaySmallEmphasized = defaults.displaySmallEmphasized.withSystemFont(brand),
            headlineLargeEmphasized = defaults.headlineLargeEmphasized.withSystemFont(brand),
            headlineMediumEmphasized = defaults.headlineMediumEmphasized.withSystemFont(brand),
            headlineSmallEmphasized = defaults.headlineSmallEmphasized.withSystemFont(brand),
            titleLargeEmphasized = defaults.titleLargeEmphasized.withSystemFont(brand),
            titleMediumEmphasized = defaults.titleMediumEmphasized.withSystemFont(brand),
            titleSmallEmphasized = defaults.titleSmallEmphasized.withSystemFont(brand),
            bodyLargeEmphasized = defaults.bodyLargeEmphasized.withSystemFont(plain),
            bodyMediumEmphasized = defaults.bodyMediumEmphasized.withSystemFont(plain),
            bodySmallEmphasized = defaults.bodySmallEmphasized.withSystemFont(plain),
            labelLargeEmphasized = defaults.labelLargeEmphasized.withSystemFont(plain),
            labelMediumEmphasized = defaults.labelMediumEmphasized.withSystemFont(plain),
            labelSmallEmphasized = defaults.labelSmallEmphasized.withSystemFont(plain),
        )
    }
}

private fun TextStyle.withSystemFont(family: FontFamily) =
    copy(fontFamily = family, hyphens = Hyphens.Auto)

private fun Context.systemFontFamily(normalConfig: String, mediumConfig: String): FontFamily {
    val normal = androidFontConfig(normalConfig)
    val medium = androidFontConfig(mediumConfig)
    if (normal.isBlank() || medium.isBlank()) return FontFamily.Default
    return FontFamily(
        Font(DeviceFontFamilyName(normal), FontWeight.Normal),
        Font(DeviceFontFamilyName(medium), FontWeight.Medium),
    )
}

private fun Context.androidFontConfig(name: String): String {
    @SuppressLint("DiscouragedApi") val id = resources.getIdentifier(name, "string", "android")
    return if (id == 0) "" else resources.getString(id)
}
