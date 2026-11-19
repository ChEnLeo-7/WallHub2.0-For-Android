/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.settingslib

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Compatibility entry points for applications using the original settingslib package.
@Composable
fun SettingsTopIntro(text: String, modifier: Modifier = Modifier) =
    org.uwuaosp.compose.widget.ui.SettingsTopIntro(text = text, modifier = modifier)
