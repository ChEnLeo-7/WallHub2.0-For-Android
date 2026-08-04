@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wallhub.android.R
import org.uwuaosp.compose.settingslib.PreferencePosition
import org.uwuaosp.compose.settingslib.PreferenceRow
import org.uwuaosp.compose.settingslib.SwitchPreferenceRow
import org.uwuaosp.compose.settingslib.SettingsCategory as UwuSettingsCategory
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

@Composable
internal fun BasicSettingsContent(
    matureContentEnabled: Boolean,
    diagnosticExportState: DiagnosticExportUiState,
    onMatureContentEnabledChange: (Boolean) -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    UwuSettingsCategory(title = stringResource(R.string.settings_diagnostics_title))
    PreferenceRow(
        title = stringResource(R.string.settings_diagnostic_log),
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
}
