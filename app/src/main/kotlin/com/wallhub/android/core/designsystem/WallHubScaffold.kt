@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.uwuaosp.compose.settingslib.SettingsAppBarScaffold
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallHubPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    topBarContent: @Composable (() -> Unit)? = null,
    useUwuToolbar: Boolean = false,
    titleContent: (@Composable () -> Unit)? = null,
    onNavigateUp: () -> Unit = {},
    contentTopPaddingAdjustment: Dp = 0.dp,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (useUwuToolbar && topBarContent == null) {
        SettingsAppBarScaffold(
            title = title,
            modifier = modifier,
            showBackButton = navigationIcon != null,
            onNavigateUp = onNavigateUp,
            actions = actions,
            titleContent = titleContent,
            contentTopPaddingAdjustment = contentTopPaddingAdjustment,
            content = content,
        )
    } else {
        Scaffold(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            topBar = {
                if (topBarContent != null) {
                    topBarContent()
                } else {
                    TopAppBar(
                        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
                        actions = actions,
                        navigationIcon = { navigationIcon?.invoke() },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                                titleContentColor = MaterialTheme.colorScheme.onBackground,
                                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    )
                }
            },
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallHubToolbarSearchTitle(
    title: String,
    query: String,
    expanded: Boolean,
    placeholder: String,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AnimatedVisibility(
            visible = !expanded,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            IconButton(onClick = onExpand) {
                Icon(Icons.Outlined.Search, contentDescription = placeholder)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            modifier = Modifier.fillMaxWidth(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(100.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    enabled = enabled,
                    modifier = Modifier.fillMaxSize(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit() }),
                    decorationBox = { innerTextField ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onCollapse) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Icon(Icons.Outlined.Search, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                if (query.isBlank()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                innerTextField()
                            }
                        }
                    },
                )
            }
        }
    }
}
