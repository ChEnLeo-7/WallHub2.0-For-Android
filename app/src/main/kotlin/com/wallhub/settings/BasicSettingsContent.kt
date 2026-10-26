@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wallhub.android.R
import com.wallhub.android.core.model.AppLanguage
import org.uwuaosp.compose.settingslib.PreferenceGroupSpacer
import org.uwuaosp.compose.settingslib.PreferencePosition
import org.uwuaosp.compose.settingslib.PreferenceRow
import org.uwuaosp.compose.settingslib.SwitchPreferenceRow
import org.uwuaosp.compose.settingslib.SettingsCategory as UwuSettingsCategory

@Composable
internal fun BasicSettingsContent(
    appLanguage: AppLanguage,
    matureContentEnabled: Boolean,
    diagnosticExportState: DiagnosticExportUiState,
    notificationsGranted: Boolean,
    onLanguageChange: (AppLanguage) -> Unit,
    onMatureContentEnabledChange: (Boolean) -> Unit,
    onExportDiagnostics: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    var showLanguageDialog by remember { mutableStateOf(false) }
    UwuSettingsCategory(title = stringResource(R.string.settings_language_category))
    PreferenceRow(
        title = stringResource(R.string.settings_language),
        summary = stringResource(appLanguage.labelResource()),
        position = PreferencePosition.Single,
        iconContent = { SettingsPreferenceIcon(Icons.Outlined.Language) },
        onClick = { showLanguageDialog = true },
    )
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    AppLanguage.entries.forEach { language ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showLanguageDialog = false
                                        onLanguageChange(language)
                                    }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = language == appLanguage,
                                onClick = null,
                            )
                            Text(stringResource(language.labelResource()))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
            },
        )
    }

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
        position =
            if (diagnosticExportState.message == null) {
                PreferencePosition.Single
            } else {
                PreferencePosition.Top
            },
        iconContent = { SettingsPreferenceIcon(Icons.Outlined.FileUpload) },
        onClick = onExportDiagnostics,
    )
    diagnosticExportState.message?.let { message ->
        PreferenceGroupSpacer()
        SettingsStatusMessage(
            position = PreferencePosition.Bottom,
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

private fun AppLanguage.labelResource(): Int =
    when (this) {
        AppLanguage.SYSTEM -> R.string.settings_language_system
        AppLanguage.SIMPLIFIED_CHINESE -> R.string.settings_language_simplified_chinese
        AppLanguage.ENGLISH -> R.string.settings_language_english
    }
