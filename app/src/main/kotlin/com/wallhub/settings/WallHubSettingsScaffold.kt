@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.wallHubStyle
import org.uwuaosp.compose.settingslib.SettingsScaffold

/**
 * Settings chrome seam: Material 3 uses an official TopAppBar/Scaffold, while the expressive style
 * keeps the uwu-sdk collapsing settings scaffold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WallHubSettingsScaffold(
    title: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    useCollapsingToolbar: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentMaxWidth: Dp = SETTINGS_CONTENT_MAX_WIDTH,
    fillViewport: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val expressive = wallHubStyle().capabilities.bettboxSurfaces
    if (expressive) {
        SettingsScaffold(
            title = title,
            modifier = modifier,
            showBackButton = showBackButton,
            onNavigateUp = onNavigateUp,
            actions = actions,
            useCollapsingToolbar = useCollapsingToolbar,
            statusBarInsets = WindowInsets(0, 0, 0, 0),
            containerColor = containerColor,
            content = content,
        )
        return
    }
    val settingsScheme = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = settingsScheme.copy(surfaceBright = settingsScheme.surfaceContainer),
    ) {
        Scaffold(
            modifier = modifier,
            containerColor = containerColor,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = containerColor),
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
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .then(
                            if (fillViewport) {
                                Modifier
                            } else {
                                Modifier.verticalScroll(rememberScrollState())
                            },
                        ),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(if (fillViewport) Modifier.fillMaxHeight() else Modifier),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier =
                            Modifier
                                .widthIn(max = contentMaxWidth)
                                .fillMaxWidth()
                                .then(if (fillViewport) Modifier.fillMaxHeight() else Modifier)
                                .padding(
                                    horizontal = WallHubSpacing.content,
                                    vertical = WallHubSpacing.md,
                                ),
                        content = content,
                    )
                }
            }
        }
    }
}
