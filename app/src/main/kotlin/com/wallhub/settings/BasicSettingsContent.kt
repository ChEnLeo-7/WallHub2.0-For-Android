@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wallhub.android.R
import org.uwuaosp.compose.settingslib.PreferencePosition
import org.uwuaosp.compose.settingslib.PreferenceRow
import org.uwuaosp.compose.settingslib.SwitchPreferenceRow
import org.uwuaosp.compose.settingslib.SettingsCategory as UwuSettingsCategory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Visibility

@Composable
internal fun BasicSettingsContent(
    matureContentEnabled: Boolean,
    diagnosticExportState: DiagnosticExportUiState,
    notificationsGranted: Boolean,
    onMatureContentEnabledChange: (Boolean) -> Unit,
    onExportDiagnostics: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    UwuSettingsCategory(title = stringResource(R.string.settings_diagnostics_title))
    PreferenceRow(
        title = stringResource(R.string.settings_action_export_diagnostic_log),
        summary =
            if (diagnosticExportState.isExporting) {
                stringResource(R.string.settings_diagnostic_exporting)
            } else {
                diagnosticExportState.message
                    ?: stringResource(R.string.settings_diagnostic_log_description)
            },
        enabled = !diagnosticExportState.isExporting,
        position = PreferencePosition.Single,
        iconContent = { SettingsPreferenceIcon(Icons.Outlined.FileUpload) },
        onClick = onExportDiagnostics,
    )
    diagnosticExportState.message?.let { message ->
        SettingsStatusMessage(
            message = message,
            isFailure = diagnosticExportState.isFailure,
        )
    }

    UwuSettingsCategory(title = stringResource(R.string.settings_content_access_title))
    SwitchPreferenceRow(
        title = stringResource(R.string.settings_nsfw_content),
        summary = stringResource(R.string.settings_nsfw_content_description),
        checked = matureContentEnabled,
        onCheckedChange = onMatureContentEnabledChange,
        position = PreferencePosition.Single,
        iconContent = { SettingsPreferenceIcon(Icons.Outlined.Visibility) },
    )

    UwuSettingsCategory(title = stringResource(R.string.settings_system_permissions_title))
    PreferenceRow(
        title = stringResource(R.string.settings_background_task_notifications),
        summary = stringResource(
            if (notificationsGranted) {
                R.string.settings_background_task_notifications_granted
            } else {
                R.string.settings_background_task_notifications_description
            },
        ),
        position = PreferencePosition.Single,
        iconContent = { SettingsPreferenceIcon(Icons.Outlined.Notifications) },
        onClick = onRequestNotifications,
    )
}
