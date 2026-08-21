@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LastPage
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FirstPage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wallhub.android.R

@Composable
fun WallHubPaginationControl(
    currentPage: Int,
    totalPages: Int,
    isLoading: Boolean,
    currentContentDescription: String,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val knownTotalPages = totalPages.coerceAtLeast(1)
    val safeCurrentPage = currentPage.coerceAtLeast(1)
    val safeTotalPages = maxOf(knownTotalPages, safeCurrentPage)
    var showJumpDialog by rememberSaveable { mutableStateOf(false) }
    var pageDraft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(safeCurrentPage.toString()))
    }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val targetPage = resolvePaginationPageInput(pageDraft.text)
    val inputHasError = pageDraft.text.isNotBlank() && targetPage == null

    fun dismissJumpDialog() {
        showJumpDialog = false
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
    }

    fun openJumpDialog() {
        val currentText = safeCurrentPage.toString()
        pageDraft = TextFieldValue(currentText, TextRange(0, currentText.length))
        showJumpDialog = true
    }

    fun submitPage() {
        val target = targetPage ?: return
        dismissJumpDialog()
        if (target != safeCurrentPage) onPageSelected(target)
    }

    LaunchedEffect(safeCurrentPage, safeTotalPages, showJumpDialog) {
        if (!showJumpDialog) pageDraft = TextFieldValue(safeCurrentPage.toString())
    }
    LaunchedEffect(showJumpDialog) {
        if (showJumpDialog) {
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = WallHubSpacing.md),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(WallHubSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaginationIconButton(
                icon = Icons.Outlined.FirstPage,
                contentDescription = stringResource(R.string.pagination_go_to_first_page),
                enabled = !isLoading && safeCurrentPage > 1,
                onClick = { onPageSelected(1) },
            )
            PaginationIconButton(
                icon = Icons.Outlined.ChevronLeft,
                contentDescription = stringResource(R.string.pagination_previous_page),
                enabled = !isLoading && safeCurrentPage > 1,
                onClick = { onPageSelected(safeCurrentPage - 1) },
            )
            Surface(
                onClick = ::openJumpDialog,
                enabled = !isLoading,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = WallHubSizeTokens.minimumTouchTarget)
                        .semantics { contentDescription = currentContentDescription },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = WallHubSpacing.sm),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        text = "$safeCurrentPage / $safeTotalPages",
                        modifier = if (isLoading) Modifier.padding(start = WallHubSpacing.xs) else Modifier,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            PaginationIconButton(
                icon = Icons.Outlined.ChevronRight,
                contentDescription = stringResource(R.string.pagination_next_page),
                enabled = !isLoading && safeCurrentPage < safeTotalPages,
                onClick = { onPageSelected(safeCurrentPage + 1) },
            )
            PaginationIconButton(
                icon = Icons.AutoMirrored.Outlined.LastPage,
                contentDescription = stringResource(R.string.pagination_go_to_page_number, safeTotalPages),
                enabled = !isLoading && safeCurrentPage < safeTotalPages,
                onClick = { onPageSelected(safeTotalPages) },
            )
        }
    }

    if (showJumpDialog) {
        AlertDialog(
            onDismissRequest = ::dismissJumpDialog,
            title = { Text(stringResource(R.string.pagination_go_to_page)) },
            text = {
                OutlinedTextField(
                    value = pageDraft,
                    onValueChange = { value ->
                        val sanitized = sanitizePaginationPageInput(value.text)
                        pageDraft = TextFieldValue(sanitized, TextRange(sanitized.length))
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    label = { Text(stringResource(R.string.pagination_page_number)) },
                    supportingText = {
                        Text(
                            if (inputHasError) {
                                stringResource(R.string.pagination_invalid_page)
                            } else {
                                stringResource(R.string.pagination_known_last_page_hint, knownTotalPages)
                            },
                        )
                    },
                    isError = inputHasError,
                    enabled = !isLoading,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submitPage() }),
                )
            },
            dismissButton = {
                TextButton(onClick = ::dismissJumpDialog) {
                    Text(stringResource(R.string.pagination_cancel))
                }
            },
            confirmButton = {
                Button(onClick = ::submitPage, enabled = targetPage != null && !isLoading) {
                    Text(stringResource(R.string.pagination_go))
                }
            },
        )
    }
}

@Composable
private fun PaginationIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(WallHubSizeTokens.minimumTouchTarget),
    ) {
        Icon(icon, contentDescription, Modifier.size(22.dp))
    }
}

internal fun sanitizePaginationPageInput(raw: String): String {
    val digits = raw.filter { it in '0'..'9' }
    if (digits.isEmpty()) return ""
    return digits.trimStart('0').ifEmpty { "0" }
}

internal fun resolvePaginationPageInput(input: String): Int? = input.toIntOrNull()?.takeIf { it > 0 }

internal fun buildPaginationItems(
    currentPage: Int,
    totalPages: Int,
): List<Int> {
    val safeTotalPages = totalPages.coerceAtLeast(1)
    val safeCurrentPage = currentPage.coerceIn(1, safeTotalPages)
    return linkedSetOf(1, safeCurrentPage, safeTotalPages).toList()
}
