/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.settingslib

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

// Compatibility entry points for applications using the original settingslib package.
@Composable
fun SettingsFooterLegacy(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Info,
) =
    org.uwuaosp.compose.widget.ui.SettingsFooterLegacy(
        text = text,
        modifier = modifier,
        icon = icon,
    )

@Composable
fun SettingsFooterLegacy(
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Info,
    content: @Composable RowScope.() -> Unit,
) =
    org.uwuaosp.compose.widget.ui.SettingsFooterLegacy(
        modifier = modifier,
        icon = icon,
        content = content,
    )
