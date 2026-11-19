package com.wallhub.android.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier

/** Semantic role of one dialog action; the role owns colour and placement, not the caller. */
enum class WallHubDialogActionRole {
    Primary,
    Secondary,
    Destructive,
    Dismiss,
}

@Immutable
data class WallHubDialogAction(
    val label: String,
    val role: WallHubDialogActionRole,
    val onClick: () -> Unit,
)

/**
 * The shared dialog action contract.
 *
 * Dismiss actions live in Material's dismiss slot. Every other action is a full-width row inside the
 * content column so destructive and primary actions are visually distinct instead of three peer
 * TextButtons with no hierarchy.
 */
@Composable
fun WallHubAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    actions: List<WallHubDialogAction>,
    modifier: Modifier = Modifier,
    text: (@Composable () -> Unit)? = null,
) {
    val dismissAction = actions.firstOrNull { it.role == WallHubDialogActionRole.Dismiss }
    val contentActions = actions.filterNot { it.role == WallHubDialogActionRole.Dismiss }
    WallHubAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                text?.invoke()
                contentActions.forEach { action ->
                    WallHubDialogActionButton(action = action, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            dismissAction?.let { action ->
                WallHubDialogActionButton(action = action)
            }
        },
    )
}

@Composable
private fun WallHubDialogActionButton(
    action: WallHubDialogAction,
    modifier: Modifier = Modifier,
) {
    val colors =
        when (action.role) {
            WallHubDialogActionRole.Primary ->
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            WallHubDialogActionRole.Secondary ->
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
            WallHubDialogActionRole.Destructive ->
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            WallHubDialogActionRole.Dismiss ->
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    TextButton(
        onClick = action.onClick,
        modifier = modifier,
        colors = colors,
    ) {
        Text(action.label)
    }
}
