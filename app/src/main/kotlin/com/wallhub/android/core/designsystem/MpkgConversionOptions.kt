package com.wallhub.android.core.designsystem

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wallhub.android.R
import com.wallhub.android.core.model.MpkgConversionPreset

internal data class MpkgConversionOption(
    val preset: MpkgConversionPreset,
    @StringRes val labelRes: Int,
    @StringRes val compactDescriptionRes: Int,
    @StringRes val policyPreviewRes: Int,
)

private val MPKG_CONVERSION_OPTIONS =
    listOf(
        MpkgConversionOption(
            preset = MpkgConversionPreset.BALANCED,
            labelRes = R.string.mpkg_preset_standard,
            compactDescriptionRes = R.string.mpkg_preset_compact_balanced,
            policyPreviewRes = R.string.mpkg_preset_standard_preview,
        ),
        MpkgConversionOption(
            preset = MpkgConversionPreset.COMPATIBILITY,
            labelRes = R.string.mpkg_preset_compatibility,
            compactDescriptionRes = R.string.mpkg_preset_compact_compatibility,
            policyPreviewRes = R.string.mpkg_preset_compatibility_preview,
        ),
        MpkgConversionOption(
            preset = MpkgConversionPreset.HIGH_QUALITY,
            labelRes = R.string.mpkg_preset_high_quality,
            compactDescriptionRes = R.string.mpkg_preset_compact_high_quality,
            policyPreviewRes = R.string.mpkg_preset_high_quality_preview,
        ),
        MpkgConversionOption(
            preset = MpkgConversionPreset.EXTREME_QUALITY,
            labelRes = R.string.mpkg_preset_extreme_quality,
            compactDescriptionRes = R.string.mpkg_preset_compact_extreme_quality,
            policyPreviewRes = R.string.mpkg_preset_extreme_quality_preview,
        ),
    )

internal fun mpkgConversionOptions(): List<MpkgConversionOption> = MPKG_CONVERSION_OPTIONS

@Composable
internal fun MpkgConversionOptions(
    preset: MpkgConversionPreset,
    onPresetSelected: (MpkgConversionPreset) -> Unit,
    preserveAudio: Boolean,
    onPreserveAudioChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedOption = mpkgConversionOptions().first { option -> option.preset == preset }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
    ) {
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
        ) {
            mpkgConversionOptions().chunked(2).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                ) {
                    rowOptions.forEach { option ->
                        MpkgConversionPresetCard(
                            option = option,
                            selected = option.preset == preset,
                            onClick = { onPresetSelected(option.preset) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowOptions.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        Text(
            text = stringResource(selectedOption.policyPreviewRes),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (preset == MpkgConversionPreset.EXTREME_QUALITY) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onPreserveAudioChanged(!preserveAudio) }
                    .padding(vertical = WallHubSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.detail_preserve_audio), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.detail_preserve_audio_compact),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = preserveAudio, onCheckedChange = onPreserveAudioChanged)
        }
    }
}

@Composable
internal fun MpkgConversionPreset.label(): String =
    stringResource(mpkgConversionOptions().first { option -> option.preset == this }.labelRes)

@Composable
private fun MpkgConversionPresetCard(
    option: MpkgConversionOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(88.dp).selectable(selected = selected, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = WallHubSpacing.sm, vertical = WallHubSpacing.xs),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(option.labelRes),
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            Text(
                stringResource(option.compactDescriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}
