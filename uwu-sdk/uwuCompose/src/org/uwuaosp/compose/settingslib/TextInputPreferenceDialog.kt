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

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

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
) {
    val focusRequester = remember { FocusRequester() }
    var inputValue by rememberTextFieldValue(value)
    val isValid = validator(inputValue.text)

    fun confirmIfValid() {
        if (isValid) {
            onConfirm(inputValue.text)
        }
    }

    AlertDialog(
        modifier = modifier.width(settingsDialogWidth()),
        onDismissRequest = onDismissRequest,
        title = {
            val fontFamilies = rememberSettingsFontFamilies()
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.emphasized(
                    fontFamilies.titleLargeEmphasized
                ),
            )
        },
        text = {
            FilledLineTextInput(
                value = inputValue,
                onValueChange = { inputValue = it },
                singleLine = singleLine,
                isError = !isValid,
                errorText = errorText,
                focusRequester = focusRequester,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirmIfValid() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                enabled = isValid,
                onClick = ::confirmIfValid,
                modifier = Modifier.height(DialogButtonHeight),
            ) {
                DialogButtonText(text = confirmText)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismissRequest,
                modifier = Modifier.height(DialogButtonHeight),
            ) {
                DialogButtonText(
                    text = dismissText,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
private fun rememberTextFieldValue(value: String) = remember(value) {
    mutableStateOf(
        TextFieldValue(
            text = value,
            selection = TextRange(value.length),
        )
    )
}

@Composable
private fun FilledLineTextInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    isError: Boolean = false,
    errorText: String? = null,
    focusRequester: FocusRequester,
) {
    var focused by remember { mutableStateOf(false) }
    val indicatorColor = when {
        isError -> MaterialTheme.colorScheme.error
        focused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(InputPadding),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (focused || isError) 2.dp else 1.dp)
                .background(indicatorColor),
        )
        if (isError && errorText != null) {
            Text(
                text = errorText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun DialogButtonText(
    text: String,
    color: Color = Color.Unspecified,
) {
    Text(
        text = text,
        color = color,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun settingsDialogWidth(): Dp {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp.dp *
        when (configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> 0.65f
            else -> 0.85f
        }
}

private val DialogButtonHeight = 45.dp
private val InputPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
