@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

@Composable
internal fun BasicSettingsContent(
    matureContentEnabled: Boolean,
    diagnosticExportState: DiagnosticExportUiState,
    onMatureContentEnabledChange: (Boolean) -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    SettingsSection(
        title = stringResource(R.string.settings_diagnostics_title),
        supportingText = stringResource(R.string.settings_diagnostics_description),
        icon = Icons.Outlined.FolderOpen,
    ) {
        SettingsListItem(
            headlineContent = { Text(stringResource(R.string.settings_diagnostic_log)) },
            supportingContent = {
                Text(
                    stringResource(R.string.settings_diagnostic_log_description),
                )
            },
        )
        SettingsActionArea {
            Button(
                onClick = onExportDiagnostics,
                enabled = !diagnosticExportState.isExporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(imageVector = Icons.Outlined.FileUpload, contentDescription = null)
                Text(
                    text =
                        if (diagnosticExportState.isExporting) {
                            stringResource(R.string.settings_diagnostic_exporting)
                        } else {
                            stringResource(R.string.settings_action_export_diagnostic_log)
                        },
                    modifier = Modifier.padding(start = WallHubSpacing.xs),
                )
            }
            diagnosticExportState.message?.let { message ->
                SettingsStatusMessage(
                    message = message,
                    isFailure = diagnosticExportState.isFailure,
                )
            }
        }
    }

    SettingsSection(
        title = stringResource(R.string.settings_content_access_title),
        icon = Icons.Outlined.Visibility,
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_nsfw_content),
            supportingText = stringResource(R.string.settings_nsfw_content_description),
            checked = matureContentEnabled,
            onCheckedChange = onMatureContentEnabledChange,
        )
    }
}
