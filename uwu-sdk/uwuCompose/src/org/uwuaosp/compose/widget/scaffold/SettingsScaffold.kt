/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.uwuaosp.compose.widget.scaffold

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.uwuaosp.compose.framework.layout.LocalSettingsLayoutMetrics
import org.uwuaosp.compose.framework.layout.SettingsLayoutDefaults
import org.uwuaosp.compose.framework.theme.SettingsDimension
import org.uwuaosp.compose.widget.ui.SettingsContent

/** Hosts content that owns its scrolling and applies the supplied window padding. */
@Composable
fun SettingsAppBarScaffold(
    title: String,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = false,
    onNavigateUp: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
    statusBarInsets: WindowInsets = WindowInsets.statusBars,
    contentTopPaddingAdjustment: Dp = 0.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable (PaddingValues) -> Unit,
) {
    SettingsPageScaffold(
        title = title,
        modifier = modifier,
        showBackButton = showBackButton,
        onNavigateUp = onNavigateUp,
        actions = actions,
        titleContent = titleContent,
        collapsing = false,
        statusBarInsets = statusBarInsets,
        containerColor = containerColor,
    ) { padding ->
        val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
        content(
            PaddingValues(
                start = padding.calculateStartPadding(direction),
                top = padding.calculateTopPadding() + contentTopPaddingAdjustment,
                end = padding.calculateEndPadding(direction),
                bottom = padding.calculateBottomPadding(),
            )
        )
    }
}

@Composable
fun SettingsScaffold(
    title: String,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = false,
    onNavigateUp: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    useCollapsingToolbar: Boolean = true,
    startCollapsed: Boolean = true,
    statusBarInsets: WindowInsets = WindowInsets.statusBars,
    contentTopPadding: Dp = 0.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsPageScaffold(
        title = title,
        modifier = modifier,
        showBackButton = showBackButton,
        onNavigateUp = onNavigateUp,
        actions = actions,
        collapsing = useCollapsingToolbar,
        startCollapsed = startCollapsed,
        expandedToolbarHeight = 179.dp,
        statusBarInsets = statusBarInsets,
        containerColor = containerColor,
    ) { padding ->
        val layout = LocalSettingsLayoutMetrics.current
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsContent(
                Modifier.padding(
                    start = layout.horizontalPadding,
                    top = contentTopPadding,
                    end = layout.horizontalPadding,
                    bottom = SettingsDimension.pageBottomPadding,
                ),
                maxWidth = layout.contentMaxWidth,
                content = content,
            )
        }
    }
}

/** Shares the custom collapsing app-bar behavior and inset policy across settings pages. */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = false,
    onNavigateUp: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
    collapsing: Boolean = true,
    startCollapsed: Boolean = false,
    expandedToolbarHeight: Dp = 200.dp,
    statusBarInsets: WindowInsets = WindowInsets.statusBars,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    header: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    LaunchedEffect(context, title) { context.findSettingsActivity()?.title = title }
    val statusBarPadding = statusBarInsets.asPaddingValues().calculateTopPadding()
    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val collapsedHeight = if (collapsing) actionBarSize() else 64.dp
    val collapseRange =
        with(density) { (expandedToolbarHeight - collapsedHeight).coerceAtLeast(0.dp).toPx() }
    var toolbarOffsetPx by remember(collapsing, startCollapsed, collapseRange) {
        mutableFloatStateOf(if (collapsing && startCollapsed) collapseRange else 0f)
    }
    toolbarOffsetPx = toolbarOffsetPx.coerceIn(0f, collapseRange)
    val nestedConnection = remember(collapsing, collapseRange) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!collapsing || available.y >= 0f) return Offset.Zero
                val previous = toolbarOffsetPx
                toolbarOffsetPx = (toolbarOffsetPx - available.y).coerceIn(0f, collapseRange)
                return Offset(0f, previous - toolbarOffsetPx)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!collapsing || available.y <= 0f) return Offset.Zero
                val previous = toolbarOffsetPx
                toolbarOffsetPx = (toolbarOffsetPx - available.y).coerceIn(0f, collapseRange)
                return Offset(0f, previous - toolbarOffsetPx)
            }
        }
    }
    val toolbarHeight = if (collapsing) {
        with(density) { (expandedToolbarHeight.toPx() - toolbarOffsetPx).toDp() }
    } else {
        collapsedHeight
    }
    val progress =
        if (!collapsing || collapseRange <= 0f) 1f
        else (toolbarOffsetPx / collapseRange).coerceIn(0f, 1f)
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val headerHeight = with(density) { headerHeightPx.toDp() }
    val direction = LocalLayoutDirection.current
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val contentPadding =
        PaddingValues(
            start = safeDrawingPadding.calculateStartPadding(direction),
            top = statusBarPadding + toolbarHeight + headerHeight,
            end = safeDrawingPadding.calculateEndPadding(direction),
            bottom = navigationBarPadding,
        )
    BoxWithConstraints(
        modifier.fillMaxSize().background(containerColor)
            .then(if (collapsing) Modifier.nestedScroll(nestedConnection) else Modifier),
    ) {
        val layout = SettingsLayoutDefaults.metrics(maxWidth)
        CompositionLocalProvider(LocalSettingsLayoutMetrics provides layout) {
            content(contentPadding)
            Column {
                SettingsToolbar(
                    title = title,
                    showBackButton = showBackButton,
                    useCollapsingToolbar = collapsing,
                    progress = progress,
                    toolbarHeight = toolbarHeight,
                    statusBarPadding = statusBarPadding,
                    onNavigateUp = onNavigateUp,
                    actions = actions,
                    titleContent = titleContent,
                    useWeightedTitleLayout = true,
                    containerColor = containerColor,
                )
                Box(Modifier.fillMaxWidth().onSizeChanged { headerHeightPx = it.height }) { header() }
            }
        }
    }
}

private tailrec fun Context.findSettingsActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> if (baseContext !== this) baseContext.findSettingsActivity() else null
        else -> null
    }

