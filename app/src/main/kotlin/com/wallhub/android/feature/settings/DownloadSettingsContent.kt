@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.text
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.isSupportedDownloadProxyUrl
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

@Composable
internal fun DownloadSettingsContent(
    preferences: AppPreferences,
    proxyUrl: String,
    onProxyUrlChanged: (String) -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onClearOutputDirectory: () -> Unit,
    onDownloadPreferencesChange: (Int, Int, String, Int) -> Unit,
    onDownloadProxyEnabledChange: (Boolean) -> Unit,
) {
    fun text(
        zh: String,
        en: String,
    ): String = if (preferences.language == AppLanguage.EN) en else zh

    fun saveDownloadPreferences(
        maxDownloads: Int = preferences.maxConcurrentDownloads,
        chunkConcurrency: Int = preferences.chunkDownloadConcurrency,
        nextProxyUrl: String = preferences.downloadProxyUrl,
    ) {
        onDownloadPreferencesChange(
            maxDownloads,
            chunkConcurrency,
            nextProxyUrl,
            preferences.mediaCacheLimitMb,
        )
    }

    SettingsSection(
        title = text("存储位置", "Storage location"),
        supportingText =
            text(
                "选择转换完成后的文件导出位置",
                "Choose where converted files are exported",
            ),
        icon = Icons.Outlined.FolderOpen,
    ) {
        SettingsListItem(
            headlineContent = { Text(text("当前导出目录", "Current export directory")) },
            supportingContent = {
                Text(
                    preferences.outputDirectoryLabel ?: text(
                        "默认：Download/WallHub",
                        "Default: Download/WallHub",
                    ),
                )
            },
        )
        SettingsActionArea {
            FilledTonalButton(
                onClick = onSelectOutputDirectory,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                )
                Text(
                    text =
                        if (preferences.outputTreeUri == null) {
                            text("选择自定义目录", "Choose custom directory")
                        } else {
                            text("更改自定义目录", "Change custom directory")
                        },
                    modifier = Modifier.padding(start = WallHubSpacing.xs),
                )
            }
            if (preferences.outputTreeUri != null) {
                TextButton(
                    onClick = onClearOutputDirectory,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text("恢复默认目录", "Restore default directory"))
                }
            }
        }
    }

    SettingsSection(
        title = text("下载性能", "Download performance"),
        supportingText =
            text(
                "调整任务数量与单任务分块并发",
                "Adjust task count and per-download chunk concurrency",
            ),
        icon = Icons.Outlined.Download,
    ) {
        SettingChoiceRow(
            title = text("同时下载项目数", "Concurrent downloads"),
            selectedValue = preferences.maxConcurrentDownloads,
            values = listOf(1, 2, 3, 4),
            label = { text("$it 项", "$it tasks") },
            onSelected = { value -> saveDownloadPreferences(maxDownloads = value) },
        )
        SettingsItemDivider()
        SettingChoiceRow(
            title = text("单项目分块并发", "Chunks per download"),
            selectedValue = preferences.chunkDownloadConcurrency,
            values = listOf(12, 16, 24, 32, 48),
            label = { value -> text("$value 个", "$value chunks") },
            onSelected = { value -> saveDownloadPreferences(chunkConcurrency = value) },
        )
    }

    SettingsSection(
        title = text("网络代理", "Network proxy"),
        supportingText =
            text(
                "仅用于下载和在线播放，不影响 Steam 社区内置访问线路",
                "Used only by downloads and online playback; independent from built-in Steam service access",
            ),
        icon = Icons.Outlined.Tune,
    ) {
        if (preferences.downloadProxyRequiresConfirmation) {
            SettingsNotice(
                title = text("旧版代理需要确认", "Legacy proxy needs confirmation"),
                message =
                    text(
                        "已保留旧版代理地址，但不会自动启用。请确认地址后再开启代理。",
                        "The saved legacy address was kept but is not enabled automatically. Confirm it before enabling the proxy.",
                    ),
            )
        }
        SettingsSwitchRow(
            title = text("使用网络代理", "Use network proxy"),
            supportingText =
                text(
                    "仅下载客户端使用此地址；失败时不会切换其他代理",
                    "Only download clients use this address; failures do not switch to another proxy",
                ),
            checked = preferences.downloadProxyEnabled,
            enabled = isSupportedDownloadProxyUrl(preferences.downloadProxyUrl),
            onCheckedChange = onDownloadProxyEnabledChange,
        )
        SettingsItemDivider()
        Column(
            modifier = Modifier.padding(WallHubSpacing.md),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            SettingsFilledTextField(
                value = proxyUrl,
                onValueChange = onProxyUrlChanged,
                label = { Text(text("HTTP(S) / SOCKS5 代理", "HTTP(S) / SOCKS5 proxy")) },
                placeholder = { Text("socks5://127.0.0.1:1080") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { saveDownloadPreferences(nextProxyUrl = proxyUrl) },
                enabled = proxyUrl != preferences.downloadProxyUrl,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text("保存代理设置", "Save proxy settings"))
            }
        }
    }
}
