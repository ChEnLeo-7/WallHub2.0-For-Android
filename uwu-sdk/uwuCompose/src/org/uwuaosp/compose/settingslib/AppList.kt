/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.settingslib

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap

// Compatibility entry points for applications using the original settingslib package.
@Composable
fun AppListSearchBar(
    query: String,
    searchPlaceholder: String,
    clearSearchContentDescription: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) =
    org.uwuaosp.compose.widget.app.AppListSearchBar(
        query = query,
        searchPlaceholder = searchPlaceholder,
        clearSearchContentDescription = clearSearchContentDescription,
        onQueryChange = onQueryChange,
        modifier = modifier,
    )

@Composable
fun AppListScaffold(
    title: String,
    searchQuery: String,
    searchPlaceholder: String,
    clearSearchContentDescription: String,
    onSearchQueryChange: (String) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) =
    org.uwuaosp.compose.widget.app.AppListScaffold(
        title = title,
        searchQuery = searchQuery,
        searchPlaceholder = searchPlaceholder,
        clearSearchContentDescription = clearSearchContentDescription,
        onSearchQueryChange = onSearchQueryChange,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
        actions = actions,
        content = content,
    )

@Composable
fun AppListItem(
    label: String,
    packageName: String,
    icon: ImageBitmap,
    index: Int = 0,
    itemCount: Int = 1,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingContent: @Composable RowScope.() -> Unit = {},
) =
    org.uwuaosp.compose.widget.app.AppListItem(
        label = label,
        packageName = packageName,
        icon = icon,
        index = index,
        itemCount = itemCount,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        trailingContent = trailingContent,
    )

@Composable
fun AppListLoading(modifier: Modifier = Modifier) =
    org.uwuaosp.compose.widget.app.AppListLoading(modifier = modifier)

@Composable
fun AppListEmpty(text: String, modifier: Modifier = Modifier) =
    org.uwuaosp.compose.widget.app.AppListEmpty(text = text, modifier = modifier)

@Composable
fun AppListError(
    text: String,
    retryText: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) =
    org.uwuaosp.compose.widget.app.AppListError(
        text = text,
        retryText = retryText,
        onRetry = onRetry,
        modifier = modifier,
    )
