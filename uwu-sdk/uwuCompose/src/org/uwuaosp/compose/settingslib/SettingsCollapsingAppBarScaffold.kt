/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.settingslib

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Compatibility entry points for applications using the original settingslib package.
@Composable
fun SettingsCollapsingAppBarScaffold(
    title: String,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = false,
    onNavigateUp: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
    startCollapsed: Boolean = false,
    expandedToolbarHeight: Dp = 200.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable (PaddingValues) -> Unit,
) =
    org.uwuaosp.compose.widget.scaffold.SettingsCollapsingAppBarScaffold(
        title = title,
        modifier = modifier,
        showBackButton = showBackButton,
        onNavigateUp = onNavigateUp,
        actions = actions,
        titleContent = titleContent,
        startCollapsed = startCollapsed,
        expandedToolbarHeight = expandedToolbarHeight,
        containerColor = containerColor,
        content = content,
    )
