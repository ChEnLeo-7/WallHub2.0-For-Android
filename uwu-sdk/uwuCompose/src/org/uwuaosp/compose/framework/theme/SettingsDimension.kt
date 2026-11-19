/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.framework.theme

import androidx.compose.ui.unit.dp
import org.uwuaosp.compose.framework.layout.SettingsLayoutDefaults

/** Shared uwu settings dimensions. Material components retain their own default tokens. */
object SettingsDimension {
    val pagePadding = 16.dp
    val pageBottomPadding = 18.dp
    val contentMaxWidth = SettingsLayoutDefaults.contentMaxWidth
    val preferenceMinHeight = 72.dp
    val preferencePadding = 20.dp
    val preferenceVerticalPadding = 12.dp
    val itemIconSize = 24.dp
    val itemIconSpacing = 24.dp
    val groupSpacing = 2.dp
    val groupOuterRadius = 20.dp
    val groupInnerRadius = 4.dp
    val sectionSpacing = 8.dp
    val disabledAlpha = 0.38f
}
