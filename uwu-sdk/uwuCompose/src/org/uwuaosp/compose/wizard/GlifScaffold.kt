/*
 * SPDX-FileCopyrightText: The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.uwuaosp.compose.wizard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class BottomAppBarButton(
    val text: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
fun GlifScaffold(
    imageVector: ImageVector,
    title: String,
    description: String = "",
    actionButton: BottomAppBarButton? = null,
    dismissButton: BottomAppBarButton? = null,
    onNavigateBack: (() -> Unit)? = null,
    navigateBackContentDescription: String = "",
    contentScrollable: Boolean = true,
    content: @Composable () -> Unit,
) {
    ActivityTitle(title)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        BoxWithConstraints(
            Modifier
                .padding(innerPadding)
                .padding(top = 8.dp),
        ) {
            if (maxWidth < maxHeight) {
                Column {
                    Column(
                        Modifier
                            .weight(1f)
                            .then(
                                if (contentScrollable) {
                                    Modifier.verticalScroll(rememberScrollState())
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        GlifHeader(
                            imageVector = imageVector,
                            title = title,
                            description = description,
                            onNavigateBack = onNavigateBack,
                            navigateBackContentDescription = navigateBackContentDescription,
                        )
                        content()
                    }
                    WizardBottomBar(actionButton, dismissButton)
                }
            } else {
                Column(Modifier.padding(horizontal = 8.dp)) {
                    Row(Modifier.weight(1f)) {
                        Box(Modifier.weight(1f)) {
                            GlifHeader(
                                imageVector = imageVector,
                                title = title,
                                description = description,
                                onNavigateBack = onNavigateBack,
                                navigateBackContentDescription = navigateBackContentDescription,
                            )
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .then(
                                    if (contentScrollable) {
                                        Modifier.verticalScroll(rememberScrollState())
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            content()
                        }
                    }
                    WizardBottomBar(actionButton, dismissButton)
                }
            }
        }
    }
}

@Composable
private fun GlifHeader(
    imageVector: ImageVector,
    title: String,
    description: String,
    onNavigateBack: (() -> Unit)?,
    navigateBackContentDescription: String,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box {
            onNavigateBack?.let { navigateBack ->
                FilledTonalIconButton(
                    onClick = navigateBack,
                    shape = IconButtonDefaults.smallRoundShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = navigateBackContentDescription,
                    )
                }
            }
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = imageVector,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (description.isNotEmpty()) {
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
internal fun WizardBottomBar(
    actionButton: BottomAppBarButton?,
    dismissButton: BottomAppBarButton?,
) {
    if (actionButton == null && dismissButton == null) return
    Row(
        modifier = Modifier.padding(
            start = 24.dp,
            top = 8.dp,
            end = 24.dp,
            bottom = 2.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val size = ButtonDefaults.MediumContainerHeight
        dismissButton?.let { button ->
            OutlinedButton(
                onClick = button.onClick,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(size),
                enabled = button.enabled,
                contentPadding = ButtonDefaults.contentPaddingFor(size),
            ) {
                ActionText(button.text)
            }
        }
        actionButton?.let { button ->
            Button(
                onClick = button.onClick,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(size),
                enabled = button.enabled,
                contentPadding = ButtonDefaults.contentPaddingFor(size),
            ) {
                ActionText(button.text)
            }
        }
    }
}

@Composable
private fun ActionText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun ActivityTitle(title: String) {
    val context = LocalContext.current
    LaunchedEffect(context, title) {
        context.findActivity()?.title = title
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
