/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.settingslib

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

// Compatibility entry points for applications using the original settingslib package.
@Composable
fun MainSwitchPreference(
    title: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) =
    org.uwuaosp.compose.widget.preference.MainSwitchPreference(
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
    )

@Composable
fun SwitchPreferenceRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconContent: (@Composable () -> Unit)? = null,
    index: Int = 0,
    itemCount: Int = 1,
    showSummary: Boolean = true,
) =
    org.uwuaosp.compose.widget.preference.SwitchPreferenceRow(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        iconContent = iconContent,
        index = index,
        itemCount = itemCount,
        showSummary = showSummary,
    )

@Composable
fun PrimarySwitchPreferenceRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    switchEnabled: Boolean = enabled,
    icon: ImageVector? = null,
    iconContent: (@Composable () -> Unit)? = null,
    index: Int = 0,
    itemCount: Int = 1,
    showSummary: Boolean = true,
) =
    org.uwuaosp.compose.widget.preference.PrimarySwitchPreferenceRow(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onCheckedChange,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        switchEnabled = switchEnabled,
        icon = icon,
        iconContent = iconContent,
        index = index,
        itemCount = itemCount,
        showSummary = showSummary,
    )
