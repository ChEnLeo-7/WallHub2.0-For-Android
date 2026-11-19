/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.widget.scaffold

import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun actionBarSize(): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    val value = TypedValue()
    val sizePx = if (context.theme.resolveAttribute(android.R.attr.actionBarSize, value, true)) {
        TypedValue.complexToDimension(value.data, context.resources.displayMetrics)
    } else {
        56.dp.value * context.resources.displayMetrics.density
    }
    return with(density) { sizePx.toDp() }
}

internal class SettingsFontFamilies(
    val displaySmallEmphasized: FontFamily,
    val titleLargeEmphasized: FontFamily,
)

@Composable
internal fun rememberSettingsFontFamilies(): SettingsFontFamilies {
    val context = LocalContext.current
    return remember(context) {
        SettingsFontFamilies(
            displaySmallEmphasized = FontFamily(Font(DeviceFontFamilyName("variable-display-small-emphasized"))),
            titleLargeEmphasized = FontFamily(Font(DeviceFontFamilyName("variable-title-large-emphasized"))),
        )
    }
}

internal fun TextStyle.emphasized(fontFamily: FontFamily): TextStyle =
    copy(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold)
