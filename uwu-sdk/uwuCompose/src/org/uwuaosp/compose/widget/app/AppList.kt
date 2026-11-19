/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.uwuaosp.compose.widget.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.uwuaosp.compose.framework.layout.LocalSettingsLayoutMetrics
import org.uwuaosp.compose.framework.theme.SettingsDimension
import org.uwuaosp.compose.widget.preference.CustomPreferenceRow
import org.uwuaosp.compose.widget.scaffold.SettingsPageScaffold
import org.uwuaosp.compose.widget.ui.SettingsContent

@Composable
fun AppListSearchBar(
    query: String,
    searchPlaceholder: String,
    clearSearchContentDescription: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        SearchBarDefaults.InputField(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = {},
            expanded = false,
            onExpandedChange = {},
            placeholder = { Text(searchPlaceholder) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = clearSearchContentDescription)
                    }
                }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

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
) {
    SettingsPageScaffold(
        title = title,
        modifier = modifier,
        showBackButton = true,
        onNavigateUp = onNavigateUp,
        actions = actions,
        expandedToolbarHeight = 176.dp,
        header = {
            val layout = LocalSettingsLayoutMetrics.current
            SettingsContent(maxWidth = layout.chromeMaxWidth) {
                AppListSearchBar(
                    query = searchQuery,
                    searchPlaceholder = searchPlaceholder,
                    clearSearchContentDescription = clearSearchContentDescription,
                    onQueryChange = onSearchQueryChange,
                    modifier =
                        Modifier.padding(
                            horizontal = layout.horizontalPadding,
                            vertical = 8.dp,
                        ),
                )
            }
        },
    ) { padding ->
        val layout = LocalSettingsLayoutMetrics.current
        SettingsContent(maxWidth = layout.chromeMaxWidth) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentPadding =
                    PaddingValues(
                        start = layout.horizontalPadding,
                        end = layout.horizontalPadding,
                        bottom = SettingsDimension.pageBottomPadding,
                    ),
                content = content,
            )
        }
    }
}

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
) {
    CustomPreferenceRow(
        modifier = modifier,
        enabled = enabled,
        index = index,
        itemCount = itemCount,
        onClick = onClick,
    ) {
        Image(
            bitmap = icon,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = packageName,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        trailingContent()
    }
}

@Composable
fun AppListLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().heightIn(min = 160.dp),
        contentAlignment = Alignment.Center,
    ) {
        LoadingIndicator()
    }
}

@Composable
fun AppListEmpty(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AppListError(
    text: String,
    retryText: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().heightIn(min = 160.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(44.dp))
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text(retryText) }
    }
}
