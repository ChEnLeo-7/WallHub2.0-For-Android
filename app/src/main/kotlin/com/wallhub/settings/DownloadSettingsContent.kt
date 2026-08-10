@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.isSupportedDownloadProxyUrl
import org.uwuaosp.compose.settingslib.PreferencePosition
import org.uwuaosp.compose.settingslib.PreferenceRow
import org.uwuaosp.compose.settingslib.SwitchPreferenceRow
import org.uwuaosp.compose.settingslib.SettingsCategory as UwuSettingsCategory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune

@Composable
internal fun DownloadSettingsContent(
    preferences: AppPreferences,
    proxyUrl: String,
    onProxyUrlChanged: (String) -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onClearOutputDirectory: () -> Unit,
    onDownloadPreferencesChange: (Int, Int, String, Int) -> Unit,
    onDownloadProxyEnabledChange: (Boolean) -> Unit,
    onOnlineChunkPlaybackEnabledChange: (Boolean) -> Unit,
    onOnlineStreamCacheLimitChange: (Int) -> Unit,
) {
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

    UwuSettingsCategory(title = stringResource(R.string.settings_storage_location_title))
    PreferenceRow(
        title = stringResource(R.string.settings_current_export_directory),
        summary = preferences.outputDirectoryLabel ?: stringResource(R.string.settings_default_export_directory),
        position = PreferencePosition.Single,
        iconContent = { SettingsPreferenceIcon(Icons.Outlined.FolderOpen) },
        onClick = null,
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
                            stringResource(R.string.settings_action_choose_custom_directory)
                        } else {
                            stringResource(R.string.settings_action_change_custom_directory)
                        },
                    modifier = Modifier.padding(start = WallHubSpacing.xs),
                )
            }
            if (preferences.outputTreeUri != null) {
                TextButton(
                    onClick = onClearOutputDirectory,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_action_restore_default_directory))
                }
            }
    }

    UwuSettingsCategory(title = stringResource(R.string.settings_download_performance_title))
    SettingChoiceRow(
        title = stringResource(R.string.settings_concurrent_downloads),
        selectedValue = preferences.maxConcurrentDownloads,
        values = listOf(1, 2, 3, 4),
        label = { pluralStringResource(R.plurals.settings_download_task_count, it, it) },
        onSelected = { value -> saveDownloadPreferences(maxDownloads = value) },
        position = PreferencePosition.Top,
        icon = Icons.Outlined.Download,
    )
    org.uwuaosp.compose.settingslib.PreferenceGroupSpacer()
    SettingChoiceRow(
        title = stringResource(R.string.settings_chunks_per_download),
        selectedValue = preferences.chunkDownloadConcurrency,
        values = listOf(12, 16, 24, 32, 48),
        label = { value -> pluralStringResource(R.plurals.settings_chunk_count, value, value) },
        onSelected = { value -> saveDownloadPreferences(chunkConcurrency = value) },
        position = PreferencePosition.Bottom,
        icon = Icons.Outlined.Tune,
    )

    UwuSettingsCategory(title = stringResource(R.string.settings_online_playback_title))
    SwitchPreferenceRow(
        title = stringResource(R.string.settings_steamkit_chunk_streaming),
        summary = stringResource(R.string.settings_steamkit_chunk_streaming_description),
        checked = preferences.onlineChunkPlaybackEnabled,
        iconContent = { SettingsPreferenceIcon(Icons.Outlined.PlayArrow) },
        position = PreferencePosition.Top,
        onCheckedChange = onOnlineChunkPlaybackEnabledChange,
    )
    org.uwuaosp.compose.settingslib.PreferenceGroupSpacer()
    SteamStreamCacheSetting(
        cacheLimitMb = preferences.mediaCacheLimitMb,
        onCacheLimitChange = onOnlineStreamCacheLimitChange,
    )

    UwuSettingsCategory(title = stringResource(R.string.settings_network_proxy_title))
        if (preferences.downloadProxyRequiresConfirmation) {
            SettingsNotice(
                title = stringResource(R.string.settings_legacy_proxy_confirmation_title),
                message = stringResource(R.string.settings_legacy_proxy_confirmation_description),
            )
        }
        SwitchPreferenceRow(
            title = stringResource(R.string.settings_use_network_proxy),
            summary =
                if (proxyUrl != preferences.downloadProxyUrl) {
                    stringResource(R.string.settings_proxy_unsaved_changes)
                } else {
                    stringResource(R.string.settings_use_network_proxy_description)
                },
            checked = preferences.downloadProxyEnabled,
            enabled = isSupportedDownloadProxyUrl(preferences.downloadProxyUrl),
            onCheckedChange = onDownloadProxyEnabledChange,
            position = PreferencePosition.Single,
            iconContent = { SettingsPreferenceIcon(Icons.Outlined.Tune) },
        )
        Column(
            modifier = Modifier.padding(WallHubSpacing.md),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            SettingsFilledTextField(
                value = proxyUrl,
                onValueChange = onProxyUrlChanged,
                label = { Text(stringResource(R.string.settings_proxy_field_label)) },
                placeholder = { Text("socks5://127.0.0.1:1080") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { saveDownloadPreferences(nextProxyUrl = proxyUrl) },
                enabled = proxyUrl != preferences.downloadProxyUrl,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_action_save_proxy_settings))
            }
        }
}
