/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.settingslib

import androidx.annotation.DrawableRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

// Compatibility entry points for applications using the original settingslib package.
@Composable
fun SettingsHomepageIcon(
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    foregroundColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) =
    org.uwuaosp.compose.widget.ui.SettingsHomepageIcon(
        iconRes = iconRes,
        modifier = modifier,
        backgroundColor = backgroundColor,
        foregroundColor = foregroundColor,
    )

@Composable
fun SettingsHomepageIcon(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    foregroundColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) =
    org.uwuaosp.compose.widget.ui.SettingsHomepageIcon(
        imageVector = imageVector,
        modifier = modifier,
        backgroundColor = backgroundColor,
        foregroundColor = foregroundColor,
    )
