@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.label
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.MAX_CONCURRENT_DOWNLOAD_OPTIONS
import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.android.core.model.isSupportedDownloadProxyUrl
import org.uwuaosp.compose.settingslib.CustomPreferenceRow
import org.uwuaosp.compose.settingslib.PreferenceGroupSpacer
import org.uwuaosp.compose.settingslib.PreferencePosition
import org.uwuaosp.compose.settingslib.PreferenceRow
import org.uwuaosp.compose.settingslib.SwitchPreferenceRow
import org.uwuaosp.compose.settingslib.SettingsCategory as UwuSettingsCategory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Tune

@Composable
internal fun DownloadSettingsContent(
    preferences: AppPreferences,
    proxyUrl: String,
    onProxyUrlChanged: (String) -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onClearOutputDirectory: () -> Unit,
    onDownloadPreferencesChange: (Int, Int, String, Int) -> Unit,
    onMpkgConversionPresetChange: (MpkgConversionPreset) -> Unit,
    onDownloadProxyEnabledChange: (Boolean) -> Unit,
    onOnlineChunkPlaybackEnabledChange: (Boolean) -> Unit,
    onOnlineStreamCacheLimitChange: (Int) -> Unit,
    onClearOnlineStreamCache: () -> Unit,
    workshopSourceCacheState: WorkshopSourceCacheUiState,
    onWorkshopSourceCacheEnabledChange: (Boolean) -> Unit,
    onWorkshopSourceCacheBudgetChange: (Int) -> Unit,
    onClearWorkshopSourceCache: () -> Unit,
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
        position = PreferencePosition.Top,
        iconContent = { SettingsPreferenceIcon(Icons.Outlined.FolderOpen) },
        onClick = null,
    )
    PreferenceGroupSpacer()
    SettingsActionPreference(
        title =
            if (preferences.outputTreeUri == null) {
                stringResource(R.string.settings_action_choose_custom_directory)
            } else {
                stringResource(R.string.settings_action_change_custom_directory)
            },
        icon = Icons.Outlined.FolderOpen,
        onClick = onSelectOutputDirectory,
        position =
            if (preferences.outputTreeUri == null) {
                PreferencePosition.Bottom
            } else {
                PreferencePosition.Middle
            },
    )
    if (preferences.outputTreeUri != null) {
        PreferenceGroupSpacer()
        SettingsActionPreference(
            title = stringResource(R.string.settings_action_restore_default_directory),
            icon = Icons.Outlined.DeleteSweep,
            onClick = onClearOutputDirectory,
            position = PreferencePosition.Bottom,
        )
    }

    UwuSettingsCategory(title = stringResource(R.string.settings_mpkg_conversion_title))
    SettingChoiceRow(
        title = stringResource(R.string.settings_mpkg_conversion_quality),
        selectedValue = preferences.mpkgConversionPreset,
        values = MpkgConversionPreset.entries,
        label = { preset -> preset.label() },
        onSelected = onMpkgConversionPresetChange,
        position = PreferencePosition.Single,
        icon = Icons.Outlined.Tune,
    )

    WorkshopSourceCacheSettings(
        enabled = preferences.workshopSourceCacheEnabled,
        budgetGiB = preferences.workshopSourceCacheBudgetGiB,
        state = workshopSourceCacheState,
        onEnabledChange = onWorkshopSourceCacheEnabledChange,
        onBudgetChange = onWorkshopSourceCacheBudgetChange,
        onClear = onClearWorkshopSourceCache,
    )

    UwuSettingsCategory(title = stringResource(R.string.settings_download_performance_title))
    SettingChoiceRow(
        title = stringResource(R.string.settings_concurrent_downloads),
        selectedValue = preferences.maxConcurrentDownloads,
        values = MAX_CONCURRENT_DOWNLOAD_OPTIONS,
        label = { pluralStringResource(R.plurals.settings_download_task_count, it, it) },
        onSelected = { value -> saveDownloadPreferences(maxDownloads = value) },
        position = PreferencePosition.Top,
        icon = Icons.Outlined.Download,
    )
    PreferenceGroupSpacer()
    SettingChoiceRow(
        title = stringResource(R.string.settings_chunks_per_download),
        selectedValue = preferences.chunkDownloadConcurrency,
        // Low values are offered on purpose: a link that cannot take a burst of connections needs a way to
        // say so, and the diagnostic difference between one chunk at a time and twelve is worth a setting.
        values = listOf(1, 2, 4, 8, 12, 16, 24, 32, 48),
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
    PreferenceGroupSpacer()
    SteamStreamCacheSetting(
        cacheLimitMb = preferences.mediaCacheLimitMb,
        onCacheLimitChange = onOnlineStreamCacheLimitChange,
        position = PreferencePosition.Middle,
    )
    PreferenceGroupSpacer()
    SettingsActionPreference(
        title = stringResource(R.string.settings_clear_streaming_cache),
        icon = Icons.Outlined.DeleteSweep,
        onClick = onClearOnlineStreamCache,
        position = PreferencePosition.Bottom,
    )

    UwuSettingsCategory(title = stringResource(R.string.settings_network_proxy_title))
        if (preferences.downloadProxyRequiresConfirmation) {
            SettingsNotice(
                title = stringResource(R.string.settings_legacy_proxy_confirmation_title),
                message = stringResource(R.string.settings_legacy_proxy_confirmation_description),
                position = PreferencePosition.Top,
            )
            PreferenceGroupSpacer()
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
            position =
                if (preferences.downloadProxyRequiresConfirmation) {
                    PreferencePosition.Middle
                } else {
                    PreferencePosition.Top
                },
            iconContent = { SettingsPreferenceIcon(Icons.Outlined.Tune) },
        )
        PreferenceGroupSpacer()
        CustomPreferenceRow(position = PreferencePosition.Bottom) {
            Column(
                modifier = Modifier.weight(1f).padding(vertical = WallHubSpacing.sm),
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
}

@Composable
private fun WorkshopSourceCacheSettings(
    enabled: Boolean,
    budgetGiB: Int,
    state: WorkshopSourceCacheUiState,
    onEnabledChange: (Boolean) -> Unit,
    onBudgetChange: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    var customBudget by remember(budgetGiB) { mutableStateOf(budgetGiB.toString()) }
    var showCustomDialog by rememberSaveable { mutableStateOf(false) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    val selectedPreset = workshopSourceCacheBudgetPreset(budgetGiB)

    UwuSettingsCategory(title = stringResource(R.string.settings_source_cache_title))
    SwitchPreferenceRow(
        title = stringResource(R.string.settings_source_cache_enabled),
        summary = stringResource(R.string.settings_source_cache_enabled_description),
        checked = enabled,
        iconContent = { SettingsPreferenceIcon(Icons.Outlined.FolderOpen) },
        position = PreferencePosition.Top,
        onCheckedChange = onEnabledChange,
    )
    PreferenceGroupSpacer()
    SettingChoiceRow(
        title = stringResource(R.string.settings_source_cache_budget),
        selectedValue = selectedPreset,
        values = WorkshopSourceCacheBudgetPreset.entries,
        label = { preset ->
            preset.budgetGiB?.let { stringResource(R.string.settings_source_cache_budget_gib, it) }
                ?: stringResource(R.string.settings_custom_value, "$budgetGiB GiB")
        },
        onSelected = { preset ->
            preset.budgetGiB?.let(onBudgetChange) ?: run { showCustomDialog = true }
        },
        position = PreferencePosition.Middle,
        icon = Icons.Outlined.Tune,
    )
    PreferenceGroupSpacer()
    PreferenceRow(
        title = stringResource(R.string.settings_source_cache_retained),
        summary = Formatter.formatFileSize(context, state.retainedBytes),
        position = PreferencePosition.Middle,
        iconContent = { SettingsPreferenceIcon(Icons.Outlined.Download) },
        onClick = null,
    )
    PreferenceGroupSpacer()
    SettingsNotice(
        title = stringResource(R.string.settings_source_cache_effective_limit_title),
        message = stringResource(R.string.settings_source_cache_effective_limit_description),
        position = PreferencePosition.Middle,
    )
    PreferenceGroupSpacer()
    SettingsActionPreference(
        title = stringResource(R.string.settings_source_cache_clear_now),
        icon = Icons.Outlined.DeleteSweep,
        onClick = { showClearDialog = true },
        enabled = !state.isClearing,
        position = PreferencePosition.Bottom,
    )

    if (showCustomDialog) {
        val parsedBudget = parsePositiveGiB(customBudget)
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text(stringResource(R.string.settings_source_cache_custom_budget)) },
            text = {
                SettingsFilledTextField(
                    value = customBudget,
                    onValueChange = { customBudget = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.settings_source_cache_budget_gib_label)) },
                    supportingText = {
                        if (customBudget.isNotEmpty() && parsedBudget == null) {
                            Text(stringResource(R.string.settings_source_cache_budget_error))
                        }
                    },
                    isError = customBudget.isNotEmpty() && parsedBudget == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = parsedBudget != null,
                    onClick = {
                        parsedBudget?.let(onBudgetChange)
                        showCustomDialog = false
                    },
                ) { Text(stringResource(R.string.settings_action_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.settings_source_cache_clear_confirm_title)) },
            text = { Text(stringResource(R.string.settings_source_cache_clear_confirm_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClear()
                    },
                ) { Text(stringResource(R.string.settings_source_cache_clear_now)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
