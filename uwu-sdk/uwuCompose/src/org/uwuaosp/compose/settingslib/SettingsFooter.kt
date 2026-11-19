/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.settingslib

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

// Compatibility entry points for applications using the original settingslib package.
@Composable
fun SettingsFooter(text: String) = org.uwuaosp.compose.widget.ui.SettingsFooter(text = text)

@Composable
fun SettingsFooter(
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Info,
    content: @Composable () -> Unit,
) =
    org.uwuaosp.compose.widget.ui.SettingsFooter(
        modifier = modifier,
        icon = icon,
        content = content,
    )
