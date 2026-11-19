/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.settingslib

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Compatibility entry points for applications using the original settingslib package.
@Composable
fun CustomPreferenceRow(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    index: Int = 0,
    itemCount: Int = 1,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) =
    org.uwuaosp.compose.widget.preference.CustomPreferenceRow(
        modifier = modifier,
        enabled = enabled,
        index = index,
        itemCount = itemCount,
        onClick = onClick,
        content = content,
    )
