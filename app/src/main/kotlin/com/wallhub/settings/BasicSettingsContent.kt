@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.wallhub.android.R
import com.wallhub.android.core.model.AppLanguage
import org.uwuaosp.compose.settingslib.PreferenceRow
import org.uwuaosp.compose.settingslib.SwitchPreferenceRow

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
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    SettingsSection(title = stringResource(R.string.settings_language_category)) {
        item {
            PreferenceRow(
                title = stringResource(R.string.settings_language),
                summary = stringResource(appLanguage.labelResource()),
                iconContent = { SettingsPreferenceIcon(Icons.Outlined.Language) },
                onClick = { showLanguageDialog = true },
            )
        }
    }
    if (showLanguageDialog) {
        WallHubSingleChoiceDialog(
            title = stringResource(R.string.settings_language),
            selectedValue = appLanguage,
            values = AppLanguage.entries,
            label = { language -> stringResource(language.labelResource()) },
            onDismissRequest = { showLanguageDialog = false },
            onSelected = { language ->
                onLanguageChange(language)
                showLanguageDialog = false
            },
        )
    }

    SettingsSection(title = stringResource(R.string.settings_diagnostics_title)) {
        item {
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
                iconContent = { SettingsPreferenceIcon(Icons.Outlined.FileUpload) },
                onClick = onExportDiagnostics,
            )
        }
        diagnosticExportState.message?.let { message ->
            item {
                SettingsStatusMessage(
                    message = message,
                    isFailure = diagnosticExportState.isFailure,
                )
            }
        }
    }

    SettingsSection(title = stringResource(R.string.settings_content_access_title)) {
        item {
            SwitchPreferenceRow(
                title = stringResource(R.string.settings_nsfw_content),
                summary = stringResource(R.string.settings_nsfw_content_description),
                checked = matureContentEnabled,
                onCheckedChange = onMatureContentEnabledChange,
                iconContent = { SettingsPreferenceIcon(Icons.Outlined.Visibility) },
            )
        }
    }

    SettingsSection(title = stringResource(R.string.settings_system_permissions_title)) {
        item {
            PreferenceRow(
                title = stringResource(R.string.settings_background_task_notifications),
                summary = stringResource(
                    if (notificationsGranted) {
                        R.string.settings_background_task_notifications_granted
                    } else {
                        R.string.settings_background_task_notifications_description
                    },
                ),
                iconContent = { SettingsPreferenceIcon(Icons.Outlined.Notifications) },
                onClick = onRequestNotifications,
            )
        }
    }
}

private fun AppLanguage.labelResource(): Int =
    when (this) {
        AppLanguage.SYSTEM -> R.string.settings_language_system
        AppLanguage.SIMPLIFIED_CHINESE -> R.string.settings_language_simplified_chinese
        AppLanguage.ENGLISH -> R.string.settings_language_english
    }
