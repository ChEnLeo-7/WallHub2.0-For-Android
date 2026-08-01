/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.uwuaosp.compose.settingslib

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

internal class SettingsFontFamilies(
    val brand: FontFamily,
    val plain: FontFamily,
    val displaySmallEmphasized: FontFamily,
    val titleLargeEmphasized: FontFamily,
    val titleMediumEmphasized: FontFamily,
    val bodyLargeEmphasized: FontFamily,
)

@Composable
internal fun rememberSettingsFontFamilies(): SettingsFontFamilies {
    val context = LocalContext.current
    return remember(context) {
        SettingsFontFamilies(
            brand = context.getFontFamily(
                configFontFamilyNormal = "config_headlineFontFamily",
                configFontFamilyMedium = "config_headlineFontFamilyMedium",
            ),
            plain = context.getFontFamily(
                configFontFamilyNormal = "config_bodyFontFamily",
                configFontFamilyMedium = "config_bodyFontFamilyMedium",
            ),
            displaySmallEmphasized = fontFamily("variable-display-small-emphasized"),
            titleLargeEmphasized = fontFamily("variable-title-large-emphasized"),
            titleMediumEmphasized = fontFamily("variable-title-medium-emphasized"),
            bodyLargeEmphasized = fontFamily("variable-body-large-emphasized"),
        )
    }
}

@Composable
fun rememberSettingsTypography(): Typography {
    val fontFamilies = rememberSettingsFontFamilies()
    return remember(fontFamilies) {
        Typography(
            displaySmall = TextStyle(
                fontFamily = fontFamilies.brand,
                fontWeight = FontWeight.Normal,
                fontSize = 36.sp,
                lineHeight = 44.sp,
                letterSpacing = 0.sp,
                hyphens = Hyphens.Auto,
            ),
            headlineSmall = TextStyle(
                fontFamily = fontFamilies.brand,
                fontWeight = FontWeight.Normal,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                letterSpacing = 0.sp,
                hyphens = Hyphens.Auto,
            ),
            titleLarge = TextStyle(
                fontFamily = fontFamilies.brand,
                fontWeight = FontWeight.Normal,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.02.em,
                hyphens = Hyphens.Auto,
            ),
            titleMedium = TextStyle(
                fontFamily = fontFamilies.brand,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.02.em,
                hyphens = Hyphens.Auto,
            ),
            titleSmall = TextStyle(
                fontFamily = fontFamilies.brand,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.02.em,
                hyphens = Hyphens.Auto,
            ),
            bodyLarge = TextStyle(
                fontFamily = fontFamilies.plain,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.01.em,
                hyphens = Hyphens.Auto,
            ),
            bodyMedium = TextStyle(
                fontFamily = fontFamilies.plain,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.01.em,
                hyphens = Hyphens.Auto,
            ),
            bodySmall = TextStyle(
                fontFamily = fontFamilies.plain,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.01.em,
                hyphens = Hyphens.Auto,
            ),
            labelLarge = TextStyle(
                fontFamily = fontFamilies.plain,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.01.em,
                hyphens = Hyphens.Auto,
            ),
            labelMedium = TextStyle(
                fontFamily = fontFamilies.plain,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.01.em,
                hyphens = Hyphens.Auto,
            ),
            labelSmall = TextStyle(
                fontFamily = fontFamilies.plain,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.01.em,
                hyphens = Hyphens.Auto,
            ),
        )
    }
}

internal fun TextStyle.emphasized(
    fontFamily: FontFamily,
    fontWeight: FontWeight = FontWeight.SemiBold,
): TextStyle {
    return copy(
        fontFamily = fontFamily,
        fontWeight = fontWeight,
    )
}

private fun fontFamily(name: String): FontFamily {
    return FontFamily(Font(DeviceFontFamilyName(name)))
}

private fun Context.getFontFamily(
    configFontFamilyNormal: String,
    configFontFamilyMedium: String,
): FontFamily {
    val fontFamilyNormal = getAndroidConfig(configFontFamilyNormal)
    val fontFamilyMedium = getAndroidConfig(configFontFamilyMedium)
    if (fontFamilyNormal.isEmpty() || fontFamilyMedium.isEmpty()) return FontFamily.Default
    return FontFamily(
        Font(DeviceFontFamilyName(fontFamilyNormal), FontWeight.Normal),
        Font(DeviceFontFamilyName(fontFamilyMedium), FontWeight.Medium),
    )
}

private fun Context.getAndroidConfig(configName: String): String {
    @SuppressLint("DiscouragedApi")
    val configId = resources.getIdentifier(configName, "string", "android")
    if (configId == 0) return ""
    return resources.getString(configId)
}
