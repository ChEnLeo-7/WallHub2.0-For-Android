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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScaffold(
    title: String,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = false,
    onNavigateUp: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    useCollapsingToolbar: Boolean = true,
    startCollapsed: Boolean = true,
    contentTopPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val actionBarHeight = actionBarSize()
    val expandedToolbarHeight = 176.dp
    val collapseRangePx = with(density) {
        (expandedToolbarHeight - actionBarHeight).coerceAtLeast(0.dp).toPx()
    }
    var toolbarOffsetPx by remember(useCollapsingToolbar, startCollapsed, collapseRangePx) {
        mutableFloatStateOf(
            if (useCollapsingToolbar && startCollapsed) {
                collapseRangePx
            } else {
                0f
            }
        )
    }
    toolbarOffsetPx = toolbarOffsetPx.coerceIn(0f, collapseRangePx)

    val nestedScrollConnection = remember(useCollapsingToolbar, collapseRangePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!useCollapsingToolbar || available.y >= 0f) return Offset.Zero
                val previousOffset = toolbarOffsetPx
                toolbarOffsetPx = (toolbarOffsetPx - available.y).coerceIn(0f, collapseRangePx)
                return Offset(x = 0f, y = previousOffset - toolbarOffsetPx)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!useCollapsingToolbar || available.y <= 0f) return Offset.Zero
                val previousOffset = toolbarOffsetPx
                toolbarOffsetPx = (toolbarOffsetPx - available.y).coerceIn(0f, collapseRangePx)
                return Offset(x = 0f, y = previousOffset - toolbarOffsetPx)
            }
        }
    }
    val currentToolbarHeight = if (useCollapsingToolbar) {
        with(density) { (expandedToolbarHeight.toPx() - toolbarOffsetPx).toDp() }
    } else {
        actionBarHeight
    }
    val progress = if (collapseRangePx > 0f) {
        (toolbarOffsetPx / collapseRangePx).coerceIn(0f, 1f)
    } else {
        1f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .nestedScroll(nestedScrollConnection)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = statusBarPadding + currentToolbarHeight)
                .padding(
                    start = 16.dp,
                    top = contentTopPadding,
                    end = 16.dp,
                    bottom = 18.dp + navigationBarPadding,
                )
        ) {
            content()
        }
        SettingsToolbar(
            title = title,
            showBackButton = showBackButton,
            useCollapsingToolbar = useCollapsingToolbar,
            progress = progress,
            toolbarHeight = currentToolbarHeight,
            statusBarPadding = statusBarPadding,
            onNavigateUp = onNavigateUp,
            actions = actions,
        )
    }
}
