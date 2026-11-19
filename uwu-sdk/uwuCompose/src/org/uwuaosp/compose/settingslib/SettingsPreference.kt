/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.settingslib

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

// Compatibility entry points for applications using the original settingslib package.
@Composable
fun PreferenceRow(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    index: Int = 0,
    itemCount: Int = 1,
    showSummary: Boolean = true,
    onClick: (() -> Unit)? = null,
) =
    org.uwuaosp.compose.widget.preference.PreferenceRow(
        title = title,
        summary = summary,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        iconContent = iconContent,
        trailingContent = trailingContent,
        index = index,
        itemCount = itemCount,
        showSummary = showSummary,
        onClick = onClick,
    )
