/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.settingslib

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

// Compatibility entry points for applications using the original settingslib package.
@Composable
fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    clearContentDescription: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector = Icons.Outlined.Search,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) =
    org.uwuaosp.compose.widget.editor.SettingsSearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = placeholder,
        clearContentDescription = clearContentDescription,
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingContent = trailingContent,
    )
