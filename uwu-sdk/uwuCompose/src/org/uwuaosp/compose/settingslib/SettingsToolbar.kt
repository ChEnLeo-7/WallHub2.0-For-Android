/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.settingslib

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Compatibility entry points for applications using the original settingslib package.
@Composable
fun SettingsToolbarActionButton(
    imageVector: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    buttonSize: Dp = 56.dp,
    containerSize: Dp = 40.dp,
) =
    org.uwuaosp.compose.widget.scaffold.SettingsToolbarActionButton(
        imageVector = imageVector,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        iconSize = iconSize,
        buttonSize = buttonSize,
        containerSize = containerSize,
    )
