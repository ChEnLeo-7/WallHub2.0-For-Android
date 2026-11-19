/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.widget.preference

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.uwuaosp.compose.framework.theme.SettingsDimension

/** Owns the selection target; the Material checkbox only draws its current state. */
@Composable
fun CheckboxPreferenceRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    PreferenceSurface(
        modifier = modifier,
        enabled = enabled,
        position = LocalPreferenceGroupPosition.current,
        checked = checked,
        onCheckedChange = onCheckedChange,
        role = Role.Checkbox,
    ) {
        Column(
            Modifier.weight(1f).padding(vertical = SettingsDimension.preferenceVerticalPadding)
        ) {
            PreferenceTitle(title)
            summary?.let { PreferenceSummary(it) }
        }
        Spacer(Modifier.width(16.dp))
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Place sibling rows in a selectableGroup owned by the caller. */
@Composable
fun RadioPreferenceRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    PreferenceSurface(
        modifier = modifier.semantics { this.selected = selected },
        enabled = enabled,
        position = LocalPreferenceGroupPosition.current,
        onClick = onSelect,
        role = Role.RadioButton,
    ) {
        Column(
            Modifier.weight(1f).padding(vertical = SettingsDimension.preferenceVerticalPadding)
        ) {
            PreferenceTitle(title)
            summary?.let { PreferenceSummary(it) }
        }
        Spacer(Modifier.width(16.dp))
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}
