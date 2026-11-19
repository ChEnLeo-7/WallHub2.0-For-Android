package com.wallhub.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubAlertDialog
import com.wallhub.android.core.designsystem.wallHubStyle

/** Modal second-level selector used by every true single-choice setting. */
@Composable
fun <T> WallHubSingleChoiceDialog(
    title: String,
    selectedValue: T,
    values: List<T>,
    label: @Composable (T) -> String,
    onDismissRequest: () -> Unit,
    onSelected: (T) -> Unit,
    description: (@Composable (T) -> String?)? = null,
) {
    val selectedIndex = values.indexOf(selectedValue).coerceAtLeast(0)
    val listState = rememberLazyListState()
    val style = wallHubStyle()
    LaunchedEffect(selectedIndex, values.size) {
        if (values.isNotEmpty()) listState.scrollToItem(selectedIndex)
    }
    WallHubAlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .selectableGroup(),
                contentPadding = PaddingValues(vertical = WallHubSpacing.xxs),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
            ) {
                itemsIndexed(values) { index, value ->
                    val selected = value == selectedValue
                    val contentColor =
                        if (selected) {
                            style.selection.content
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    val descriptionText = description?.invoke(value)
                    Surface(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("wallhub-choice-row-$index")
                                .clip(style.selection.shape)
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelected(value) },
                                ),
                        shape = style.selection.shape,
                        color =
                            if (selected) {
                                style.selection.container
                            } else {
                                Color.Transparent
                            },
                        contentColor = contentColor,
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = WallHubSizeTokens.minimumTouchTarget)
                                    .padding(
                                        horizontal = WallHubSpacing.sm,
                                        vertical = if (descriptionText == null) WallHubSpacing.xxs else WallHubSpacing.xs,
                                    ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector =
                                    if (selected) {
                                        Icons.Filled.CheckCircle
                                    } else {
                                        Icons.Outlined.RadioButtonUnchecked
                                    },
                                contentDescription = null,
                                modifier =
                                    Modifier
                                        .size(24.dp)
                                        .testTag(
                                            if (selected) {
                                                "wallhub-choice-selected-icon"
                                            } else {
                                                "wallhub-choice-unselected-icon"
                                            },
                                        ),
                                tint =
                                    if (selected) {
                                        style.selection.icon
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                            Spacer(Modifier.width(WallHubSpacing.sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = label(value),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = contentColor,
                                )
                                if (descriptionText != null) {
                                    Text(
                                        text = descriptionText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color =
                                            if (selected) {
                                                contentColor.copy(alpha = 0.78f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.settings_action_cancel))
            }
        },
    )
}
