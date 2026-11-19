/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.framework.layout

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class SettingsWidthClass {
    Compact,
    Medium,
    Expanded,
}

/** Layout values resolved from the width allocated to a settings pane. */
@Immutable
data class SettingsLayoutMetrics(
    val widthClass: SettingsWidthClass,
    val horizontalPadding: Dp,
    val contentMaxWidth: Dp,
) {
    val chromeMaxWidth: Dp
        get() = contentMaxWidth + horizontalPadding * 2
}

object SettingsLayoutDefaults {
    val contentMaxWidth = 760.dp

    fun metrics(availableWidth: Dp): SettingsLayoutMetrics =
        SettingsLayoutMetrics(
            widthClass =
                when {
                    availableWidth < 600.dp -> SettingsWidthClass.Compact
                    availableWidth < 840.dp -> SettingsWidthClass.Medium
                    else -> SettingsWidthClass.Expanded
                },
            horizontalPadding = if (availableWidth < 600.dp) 16.dp else 24.dp,
            contentMaxWidth = contentMaxWidth,
        )
}

val LocalSettingsLayoutMetrics =
    staticCompositionLocalOf {
        SettingsLayoutDefaults.metrics(0.dp)
    }
