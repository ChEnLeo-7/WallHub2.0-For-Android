/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.settingslib

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Compatibility entry points for applications using the original settingslib package.
@Composable
fun SettingsIllustrationHeader(
    @DrawableRes imageRes: Int,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
) =
    org.uwuaosp.compose.widget.ui.SettingsIllustrationHeader(
        imageRes = imageRes,
        modifier = modifier,
        height = height,
    )
