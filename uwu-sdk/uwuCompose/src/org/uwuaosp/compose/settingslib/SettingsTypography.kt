/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.settingslib

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable

// Compatibility entry points for applications using the original settingslib package.
@Composable
fun rememberSettingsTypography(): Typography =
    org.uwuaosp.compose.framework.theme.rememberSettingsTypography()
