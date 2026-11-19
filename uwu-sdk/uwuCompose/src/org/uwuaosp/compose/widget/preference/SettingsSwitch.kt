/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.uwuaosp.compose.widget.preference

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.uwuaosp.compose.framework.theme.SettingsDimension
import org.uwuaosp.compose.widget.ui.PreferenceLeadingIcon

@Composable
fun MainSwitchPreference(
    title: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .clip(RoundedCornerShape(42.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .heightIn(min = SettingsDimension.preferenceMinHeight)
                .padding(start = 32.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (title != null) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMediumEmphasized,
                modifier = Modifier.weight(1f).padding(top = 16.dp, end = 8.dp, bottom = 16.dp),
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
    index: Int = 0,
    itemCount: Int = 1,
    showSummary: Boolean = true,
) {
    PreferenceSurface(
        modifier = modifier,
        enabled = enabled,
        position =
            if (itemCount > 1) {
                preferenceGroupPosition(index, itemCount)
            } else {
                LocalPreferenceGroupPosition.current
            },
        role = Role.Switch,
        checked = checked,
        onCheckedChange = onCheckedChange,
    ) {
        if (iconContent != null) {
            PreferenceLeadingIcon(iconContent)
        } else {
            PreferenceIcon(icon)
        }
        Column(modifier = Modifier.weight(1f).padding(vertical = 16.dp)) {
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
    index: Int = 0,
    itemCount: Int = 1,
    showSummary: Boolean = true,
) {
    val position =
        if (itemCount > 1) {
            preferenceGroupPosition(index, itemCount)
        } else {
            LocalPreferenceGroupPosition.current
        }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else SettingsDimension.disabledAlpha)
                .clip(preferenceShape(position))
                .background(MaterialTheme.colorScheme.surfaceBright)
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = SettingsDimension.preferenceMinHeight)
                    .padding(horizontal = SettingsDimension.preferencePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier =
                    Modifier.weight(1f)
                        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (iconContent != null) {
                    PreferenceLeadingIcon(iconContent)
                } else {
                    PreferenceIcon(icon)
                }
                Column(modifier = Modifier.weight(1f).padding(vertical = 16.dp)) {
                    PreferenceTitle(title)
                    if (showSummary) {
                        PreferenceSummary(summary)
                    }
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Box(
                modifier =
                    Modifier.width(1.dp)
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
        colors =
            SwitchDefaults.colors(
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
