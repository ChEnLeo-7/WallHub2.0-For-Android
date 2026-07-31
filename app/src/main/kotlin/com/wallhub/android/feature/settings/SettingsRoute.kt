@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallhub.android.core.designsystem.LocalWallHubToastState
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsRoute(
    onOpenSteamLogin: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val diagnosticExportState by viewModel.diagnosticExportState.collectAsStateWithLifecycle()
    val appUpdateState by viewModel.appUpdateState.collectAsStateWithLifecycle()
    val steamAccessState by viewModel.steamAccessState.collectAsStateWithLifecycle()
    val toastState = LocalWallHubToastState.current
    val currentOnOpenSteamLogin by rememberUpdatedState(onOpenSteamLogin)
    val outputDirectoryLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { treeUri ->
            if (treeUri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
                    .onSuccess {
                        viewModel.onAction(
                            SettingsAction.OutputDirectorySelected(
                                treeUri = treeUri.toString(),
                                label =
                                    treeUri.lastPathSegment
                                        ?.substringAfterLast(':')
                                        ?.ifBlank { null }
                                        ?: "已选择导出目录",
                            ),
                        )
                    }.onFailure { error ->
                        viewModel.onAction(
                            SettingsAction.SystemActionFailed(
                                error.message ?: "无法授权导出目录",
                            ),
                        )
                    }
            }
        }
    val notificationLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { }
    val diagnosticExportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/plain"),
        ) { documentUri ->
            if (documentUri != null) {
                viewModel.onAction(SettingsAction.DiagnosticDocumentSelected(documentUri.toString()))
            }
        }
    var pendingUpdateApkPath by rememberSaveable { mutableStateOf<String?>(null) }
    val launchSystemInstaller: (String) -> Unit = { path ->
        runCatching {
            val apk = File(path)
            check(apk.isFile) { "已验证的安装包不存在" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            val intent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME_TYPE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
        }.onFailure { error ->
            viewModel.onAction(
                SettingsAction.InstallerFailed(error.message ?: "无法打开 Android 系统安装器"),
            )
        }
    }
    val unknownSourcesLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) {
            val path = pendingUpdateApkPath
            pendingUpdateApkPath = null
            if (
                path != null &&
                context.packageManager.canRequestPackageInstalls()
            ) {
                launchSystemInstaller(path)
            } else if (path != null) {
                viewModel.onAction(SettingsAction.InstallerFailed("需要允许 WallHub 安装未知应用后才能继续"))
            }
        }
    val requestReleaseInstall: (String) -> Unit = { path ->
        if (!context.packageManager.canRequestPackageInstalls()) {
            pendingUpdateApkPath = path
            val intent =
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                )
            runCatching { unknownSourcesLauncher.launch(intent) }
                .onFailure { error ->
                    pendingUpdateApkPath = null
                    viewModel.onAction(
                        SettingsAction.InstallerFailed(error.message ?: "无法打开安装未知应用设置"),
                    )
                }
        } else {
            launchSystemInstaller(path)
        }
    }
    val currentRequestReleaseInstall by rememberUpdatedState(requestReleaseInstall)
    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsEffect.SelectOutputDirectory -> outputDirectoryLauncher.launch(null)
                SettingsEffect.ExportDiagnostics -> {
                    diagnosticExportLauncher.launch(
                        "wallhub-diagnostics-${System.currentTimeMillis()}.txt",
                    )
                }
                SettingsEffect.RequestNotifications -> {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                SettingsEffect.OpenSteamLogin -> currentOnOpenSteamLogin()
                is SettingsEffect.InstallDownloadedRelease -> {
                    currentRequestReleaseInstall(effect.path)
                }
                is SettingsEffect.OpenExternalUri -> {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(effect.uri)).apply {
                                addCategory(Intent.CATEGORY_BROWSABLE)
                            },
                        )
                    }.onFailure {
                        viewModel.onAction(SettingsAction.SystemActionFailed(effect.failureMessage))
                    }
                }
                is SettingsEffect.ShowMessage -> toastState.show(effect.message)
            }
        }
    }
    SettingsScreen(
        preferences = preferences,
        steamAccessState = steamAccessState,
        session = session,
        diagnosticExportState = diagnosticExportState,
        appUpdateState = appUpdateState,
        onAction = viewModel::onAction,
    )
}
