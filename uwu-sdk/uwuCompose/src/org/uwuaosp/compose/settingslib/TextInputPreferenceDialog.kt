/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.settingslib

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

// Compatibility entry points for applications using the original settingslib package.
@Composable
fun TextInputPreferenceDialog(
    title: String,
    value: String,
    confirmText: String,
    dismissText: String,
    onConfirm: (String) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    validator: (String) -> Boolean = { true },
    errorText: String? = null,
) =
    org.uwuaosp.compose.widget.editor.TextInputPreferenceDialog(
        title = title,
        value = value,
        confirmText = confirmText,
        dismissText = dismissText,
        onConfirm = onConfirm,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        singleLine = singleLine,
        validator = validator,
        errorText = errorText,
    )
