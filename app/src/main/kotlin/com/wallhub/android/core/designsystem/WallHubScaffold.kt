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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wallhub.android.R
import org.uwuaosp.compose.settingslib.SettingsAppBarScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallHubPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    showAppBar: Boolean = true,
    showBackButton: Boolean = false,
    onNavigateUp: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
    contentTopPaddingAdjustment: Dp = 0.dp,
    containerColor: Color = Color.Unspecified,
    content: @Composable (PaddingValues) -> Unit,
) {
    val style = wallHubStyle()
    val resolvedContainerColor =
        if (containerColor == Color.Unspecified) {
            style.surfaces.page
        } else {
            containerColor
        }
    if (showAppBar) {
        if (style.capabilities.bettboxSurfaces) {
            SettingsAppBarScaffold(
                title = title,
                modifier = modifier,
                showBackButton = showBackButton,
                onNavigateUp = onNavigateUp,
                actions = actions,
                titleContent = titleContent,
                statusBarInsets = WindowInsets(0, 0, 0, 0),
                contentTopPaddingAdjustment = contentTopPaddingAdjustment,
                containerColor = resolvedContainerColor,
                content = content,
            )
        } else {
            Scaffold(
                modifier = modifier,
                containerColor = resolvedContainerColor,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = {
                            if (titleContent != null) {
                                Box(Modifier.fillMaxWidth()) { titleContent() }
                            } else {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = resolvedContainerColor,
                            ),
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        navigationIcon = {
                            if (showBackButton) {
                                IconButton(onClick = onNavigateUp) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = stringResource(R.string.settings_action_back),
                                    )
                                }
                            }
                        },
                        actions = actions,
                    )
                },
            ) { padding ->
                val direction = LocalLayoutDirection.current
                content(
                    PaddingValues(
                        start = padding.calculateStartPadding(direction),
                        top = padding.calculateTopPadding() + contentTopPaddingAdjustment,
                        end = padding.calculateEndPadding(direction),
                        bottom = padding.calculateBottomPadding(),
                    ),
                )
            }
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            content(PaddingValues())
        }
    }
}

@Composable
fun WallHubToolbarSearchTitle(
    title: String,
    query: String,
    expanded: Boolean,
    placeholder: String,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    collapseContentDescription: String? = null,
    clearContentDescription: String? = null,
    enabled: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(expanded, enabled) {
        if (expanded && enabled) {
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboardController?.show()
        } else if (!expanded) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }
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
                color = wallHubStyle().field.container,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    enabled = enabled,
                    modifier = Modifier.fillMaxSize().focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit() }),
                    decorationBox = { innerTextField ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onCollapse) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = collapseContentDescription,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Icon(Icons.Outlined.Search, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (query.isBlank()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                innerTextField()
                            }
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChanged("") }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Cancel,
                                        contentDescription = clearContentDescription,
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.width(16.dp))
                            }
                        }
                    },
                )
            }
        }
    }
}
