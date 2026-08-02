/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.uwuaosp.compose.settingslib

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hosts an always-collapsing settings toolbar without imposing a scroll container on [content].
 *
 * Lazy lists, pagers, grids, and custom scrollable content can participate through Compose's
 * nested-scroll chain while retaining their own state and measurement constraints.
 */
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
) {
    val density = LocalDensity.current
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val collapsedToolbarHeight = actionBarSize()
    val collapseRangePx = with(density) {
        (expandedToolbarHeight - collapsedToolbarHeight).coerceAtLeast(0.dp).toPx()
    }
    var toolbarOffsetPx by remember(startCollapsed, collapseRangePx) {
        mutableFloatStateOf(if (startCollapsed) collapseRangePx else 0f)
    }
    toolbarOffsetPx = toolbarOffsetPx.coerceIn(0f, collapseRangePx)

    val nestedScrollConnection = remember(collapseRangePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f) return Offset.Zero
                val previousOffset = toolbarOffsetPx
                toolbarOffsetPx = (toolbarOffsetPx - available.y).coerceIn(0f, collapseRangePx)
                return Offset(x = 0f, y = previousOffset - toolbarOffsetPx)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y <= 0f) return Offset.Zero
                val previousOffset = toolbarOffsetPx
                toolbarOffsetPx = (toolbarOffsetPx - available.y).coerceIn(0f, collapseRangePx)
                return Offset(x = 0f, y = previousOffset - toolbarOffsetPx)
            }
        }
    }
    val toolbarHeight = with(density) {
        (expandedToolbarHeight.toPx() - toolbarOffsetPx).toDp()
    }
    val collapseProgress = if (collapseRangePx > 0f) {
        (toolbarOffsetPx / collapseRangePx).coerceIn(0f, 1f)
    } else {
        1f
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(containerColor)
                .nestedScroll(nestedScrollConnection),
    ) {
        content(PaddingValues(top = statusBarPadding + toolbarHeight))
        SettingsToolbar(
            title = title,
            showBackButton = showBackButton,
            useCollapsingToolbar = true,
            progress = collapseProgress,
            toolbarHeight = toolbarHeight,
            statusBarPadding = statusBarPadding,
            onNavigateUp = onNavigateUp,
            actions = actions,
            titleContent = titleContent,
            useWeightedTitleLayout = true,
            containerColor = containerColor,
        )
    }
}
