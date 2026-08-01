/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.uwuaosp.compose.settingslib

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun MainSwitchPreference(
    title: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontFamilies = rememberSettingsFontFamilies()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .clip(RoundedCornerShape(42.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .heightIn(min = 72.dp)
            .padding(start = 32.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (title != null) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium.emphasized(
                    fontFamilies.titleMediumEmphasized
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 16.dp, end = 8.dp, bottom = 16.dp),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        SettingsSwitch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun SwitchPreferenceRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconContent: (@Composable () -> Unit)? = null,
    position: PreferencePosition = PreferencePosition.Single,
    showSummary: Boolean = true,
) {
    PreferenceSurface(
        modifier = modifier,
        enabled = enabled,
        position = position,
        role = Role.Switch,
        checked = checked,
        onCheckedChange = onCheckedChange,
    ) {
        if (iconContent != null) {
            PreferenceLeadingIcon(iconContent)
        } else {
            PreferenceIcon(icon)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 16.dp)
        ) {
            PreferenceTitle(title)
            if (showSummary) {
                PreferenceSummary(summary)
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        SettingsSwitch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}

@Composable
fun PrimarySwitchPreferenceRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    switchEnabled: Boolean = enabled,
    icon: ImageVector? = null,
    iconContent: (@Composable () -> Unit)? = null,
    position: PreferencePosition = PreferencePosition.Single,
    showSummary: Boolean = true,
) {
    PreferenceSurface(
        modifier = modifier,
        enabled = enabled,
        position = position,
        onClick = onClick,
    ) {
        if (iconContent != null) {
            PreferenceLeadingIcon(iconContent)
        } else {
            PreferenceIcon(icon)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 16.dp)
        ) {
            PreferenceTitle(title)
            if (showSummary) {
                PreferenceSummary(summary)
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Spacer(modifier = Modifier.width(16.dp))
        SettingsSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled && switchEnabled,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}

@Composable
private fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val surfaceHighest = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outline

    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = onPrimary,
            checkedTrackColor = primary,
            checkedBorderColor = primary,
            uncheckedThumbColor = outline,
            uncheckedTrackColor = surfaceHighest,
            uncheckedBorderColor = outline,
            checkedIconColor = primary,
            uncheckedIconColor = surfaceHighest,
        ),
        thumbContent = {
            Icon(
                imageVector = if (checked) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
}
