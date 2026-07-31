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
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.text
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

@Composable
internal fun BasicSettingsContent(
    language: AppLanguage,
    matureContentEnabled: Boolean,
    diagnosticExportState: DiagnosticExportUiState,
    appUpdateState: AppUpdateUiState,
    onMatureContentEnabledChange: (Boolean) -> Unit,
    onCheckForAppUpdate: () -> Unit,
    onDownloadLatestRelease: () -> Unit,
    onCancelAppUpdateDownload: () -> Unit,
    onInstallDownloadedRelease: (String) -> Unit,
    onExportDiagnostics: () -> Unit,
    onOpenExternalUri: (String, String) -> Unit,
) {
    val installed = appUpdateState.installed

    SettingsSection(
        title = "WallHub For Android",
        icon = Icons.Outlined.Info,
    ) {
        AboutWallHubContent(
            language = language,
            installed = installed,
            appUpdateState = appUpdateState,
            onCheckForAppUpdate = onCheckForAppUpdate,
            onDownloadLatestRelease = onDownloadLatestRelease,
            onCancelAppUpdateDownload = onCancelAppUpdateDownload,
            onInstallDownloadedRelease = onInstallDownloadedRelease,
            onOpenExternalUri = onOpenExternalUri,
        )
    }

    SettingsSection(
        title = language.text("诊断与支持", "Diagnostics & support"),
        supportingText =
            language.text(
                "导出经过脱敏处理的运行信息",
                "Export redacted runtime information",
            ),
        icon = Icons.Outlined.FolderOpen,
    ) {
        SettingsListItem(
            headlineContent = { Text(language.text("诊断日志", "Diagnostic log")) },
            supportingContent = {
                Text(
                    language.text(
                        "包含业务日志与崩溃调用栈，不包含登录凭据",
                        "Includes app logs and crash traces without sign-in credentials",
                    ),
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
                            language.text("正在导出…", "Exporting…")
                        } else {
                            language.text("导出诊断日志", "Export diagnostic log")
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
        title = language.text("内容访问", "Content access"),
        icon = Icons.Outlined.Visibility,
    ) {
        SettingsSwitchRow(
            title = language.text("NSFW 内容", "NSFW content"),
            supportingText =
                language.text(
                    "控制发现页是否显示成人内容",
                    "Control whether mature content appears in Discover",
                ),
            checked = matureContentEnabled,
            onCheckedChange = onMatureContentEnabledChange,
        )
    }
}
