@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
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
    val layoutDirection = LocalLayoutDirection.current

    val dialogTitle = stringResource(R.string.pagination_go_to_page)
    val inputLabel = stringResource(R.string.pagination_page_number)
    val pageInputHint = stringResource(R.string.pagination_known_last_page_hint, knownTotalPages)
    val invalidPageLabel = stringResource(R.string.pagination_invalid_page)
    val cancelLabel = stringResource(R.string.pagination_cancel)
    val confirmLabel = stringResource(R.string.pagination_go)
    val targetPage = resolvePaginationPageInput(pageDraft.text)
    val inputHasError = pageDraft.text.isNotBlank() && targetPage == null

    fun dismissJumpDialog() {
        showJumpDialog = false
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
    }

    fun openJumpDialog() {
        val currentText = safeCurrentPage.toString()
        pageDraft =
            TextFieldValue(
                text = currentText,
                selection = TextRange(0, currentText.length),
            )
        showJumpDialog = true
    }

    fun submitPage() {
        val target = targetPage ?: return
        dismissJumpDialog()
        if (target != safeCurrentPage) onPageSelected(target)
    }

    LaunchedEffect(safeCurrentPage, safeTotalPages, showJumpDialog) {
        if (!showJumpDialog) {
            pageDraft = TextFieldValue(safeCurrentPage.toString())
        }
    }
    LaunchedEffect(showJumpDialog) {
        if (showJumpDialog) {
            withFrameNanos { }
            focusRequester.requestFocus()
            withFrameNanos { }
            keyboardController?.show()
        }
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val window =
            PaginationWindow(
                currentPage = safeCurrentPage,
                totalPages = safeTotalPages,
            )

        AnimatedContent(
            targetState = window,
            transitionSpec = {
                paginationPageChangeTransition(
                    forward = targetState.currentPage > initialState.currentPage,
                    layoutDirection = layoutDirection,
                )
            },
            contentAlignment = Alignment.Center,
            label = "PaginationWindow",
        ) { displayedWindow ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PaginationTextButton(
                    label = stringResource(R.string.pagination_first),
                    text = "1",
                    enabled = !isLoading,
                    selected = false,
                    contentDescription = stringResource(R.string.pagination_go_to_first_page),
                    onClick = {
                        if (displayedWindow.currentPage != 1) onPageSelected(1)
                    },
                    modifier = Modifier.weight(1f),
                )
                PaginationTextButton(
                    label = stringResource(R.string.pagination_current),
                    text = displayedWindow.currentPage.toString(),
                    enabled = !isLoading,
                    selected = true,
                    contentDescription = currentContentDescription,
                    onClick = ::openJumpDialog,
                    modifier = Modifier.weight(1.15f),
                )
                PaginationTextButton(
                    label = stringResource(R.string.pagination_last),
                    text = displayedWindow.totalPages.toString(),
                    enabled = !isLoading,
                    selected = false,
                    contentDescription = stringResource(R.string.pagination_go_to_page_number, displayedWindow.totalPages),
                    onClick = {
                        if (displayedWindow.currentPage != displayedWindow.totalPages) {
                            onPageSelected(displayedWindow.totalPages)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                PaginationTextButton(
                    label = stringResource(R.string.pagination_jump),
                    text = null,
                    enabled = !isLoading,
                    selected = false,
                    contentDescription = stringResource(R.string.pagination_enter_custom_page),
                    onClick = ::openJumpDialog,
                    modifier = Modifier.weight(0.9f),
                )
            }
        }
    }

    if (showJumpDialog) {
        AlertDialog(
            onDismissRequest = ::dismissJumpDialog,
            title = { Text(dialogTitle) },
            text = {
                OutlinedTextField(
                    value = pageDraft,
                    onValueChange = { value ->
                        val sanitized = sanitizePaginationPageInput(value.text)
                        pageDraft =
                            TextFieldValue(
                                text = sanitized,
                                selection = TextRange(sanitized.length),
                            )
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    label = { Text(inputLabel) },
                    supportingText = {
                        Text(if (inputHasError) invalidPageLabel else pageInputHint)
                    },
                    isError = inputHasError,
                    enabled = !isLoading,
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Go,
                        ),
                    keyboardActions = KeyboardActions(onGo = { submitPage() }),
                )
            },
            dismissButton = {
                TextButton(onClick = ::dismissJumpDialog) {
                    Text(cancelLabel)
                }
            },
            confirmButton = {
                Button(
                    onClick = ::submitPage,
                    enabled = targetPage != null && !isLoading,
                ) {
                    Text(confirmLabel)
                }
            },
        )
    }
}

@Composable
private fun PaginationTextButton(
    label: String,
    text: String?,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isSelected = selected == true
    val usePrimaryContainer = MaterialTheme.colorScheme.primary.luminance() >= 0.95f
    val targetContainerColor =
        if (isSelected) {
            if (usePrimaryContainer) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.primary
            }
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val selectedContentColor =
        if (usePrimaryContainer) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onPrimary
        }
    val unselectedContentColor = MaterialTheme.colorScheme.onSurface
    val targetContentColor =
        when {
            isSelected && enabled -> selectedContentColor
            isSelected -> selectedContentColor.copy(alpha = PAGINATION_DISABLED_SELECTED_ALPHA)
            enabled -> unselectedContentColor
            else -> unselectedContentColor.copy(alpha = PAGINATION_DISABLED_ALPHA)
        }
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = tween(PAGINATION_SELECTION_MOTION_MS),
        label = "PaginationContainerColor",
    )
    val contentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = tween(PAGINATION_SELECTION_MOTION_MS),
        label = "PaginationContentColor",
    )
    Box(
        modifier =
            modifier
                .defaultMinSize(
                    minWidth = PAGINATION_TOUCH_TARGET_SIZE,
                    minHeight = PAGINATION_TOUCH_TARGET_SIZE,
                ).paginationPressScale(interactionSource)
                .semantics(mergeDescendants = true) {
                    this.contentDescription = contentDescription
                    role = Role.Button
                    selected?.let { this.selected = it }
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).padding(PAGINATION_TOUCH_INSET),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Box(
                modifier =
                    Modifier
                        .height(PAGINATION_VISUAL_HEIGHT)
                        .widthIn(min = PAGINATION_VISUAL_MIN_WIDTH)
                        .padding(horizontal = PAGINATION_PAGE_HORIZONTAL_PADDING),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.78f),
                        maxLines = 1,
                    )
                    if (text == null) {
                        Icon(
                            imageVector = WallHubIcons.Outlined.Edit,
                            contentDescription = null,
                            modifier = Modifier.height(18.dp),
                        )
                    } else {
                        Text(
                            text = text,
                            style =
                                if (text.length >= PAGINATION_SMALL_LABEL_DIGITS) {
                                    MaterialTheme.typography.labelSmall
                                } else {
                                    MaterialTheme.typography.labelMedium
                                },
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.paginationPressScale(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PAGINATION_PRESSED_SCALE else 1f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "PaginationPressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

private fun paginationPageChangeTransition(
    forward: Boolean,
    layoutDirection: LayoutDirection,
) = (
    fadeIn(tween(PAGINATION_PAGE_FADE_MS)) +
        slideInHorizontally(
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            initialOffsetX = { width ->
                val direction = if (forward == (layoutDirection == LayoutDirection.Ltr)) 1 else -1
                direction * width / PAGINATION_SLIDE_DIVISOR
            },
        )
) togetherWith
    (
        fadeOut(tween(PAGINATION_PAGE_FADE_MS)) +
            slideOutHorizontally(
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                targetOffsetX = { width ->
                    val direction = if (forward == (layoutDirection == LayoutDirection.Ltr)) -1 else 1
                    direction * width / PAGINATION_SLIDE_DIVISOR
                },
            )
    )

internal fun sanitizePaginationPageInput(raw: String): String {
    val digits = raw.filter { it in '0'..'9' }
    if (digits.isEmpty()) return ""
    return digits.trimStart('0').ifEmpty { "0" }
}

internal fun resolvePaginationPageInput(input: String): Int? = input.toIntOrNull()?.takeIf { it > 0 }

private data class PaginationWindow(
    val currentPage: Int,
    val totalPages: Int,
)

private val PAGINATION_TOUCH_TARGET_SIZE = WallHubSizeTokens.minimumTouchTarget
private val PAGINATION_TOUCH_INSET = 4.dp
private val PAGINATION_VISUAL_HEIGHT = 52.dp
private val PAGINATION_VISUAL_MIN_WIDTH = 40.dp
private val PAGINATION_PAGE_HORIZONTAL_PADDING = 4.dp
private const val PAGINATION_SMALL_LABEL_DIGITS = 6
private const val PAGINATION_PRESSED_SCALE = 0.94f
private const val PAGINATION_DISABLED_ALPHA = 0.38f
private const val PAGINATION_DISABLED_SELECTED_ALPHA = 0.62f
private const val PAGINATION_SELECTION_MOTION_MS = 160
private const val PAGINATION_PAGE_FADE_MS = 160
private const val PAGINATION_SLIDE_DIVISOR = 6
