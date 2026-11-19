/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.settingslib

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

typealias SettingsPreferenceGroupScope =
    org.uwuaosp.compose.widget.preference.SettingsPreferenceGroupScope

// Compatibility entry points for applications using the original settingslib package.
@Composable
fun SettingsPreferenceGroup(
    modifier: Modifier = Modifier,
    content: SettingsPreferenceGroupScope.() -> Unit,
) =
    org.uwuaosp.compose.widget.preference.SettingsPreferenceGroup(
        modifier = modifier,
        content = content,
    )

@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: SettingsPreferenceGroupScope.() -> Unit,
) =
    org.uwuaosp.compose.widget.preference.SettingsSection(
        title = title,
        modifier = modifier,
        content = content,
    )

@Composable
fun SettingsSectionSpacer() = org.uwuaosp.compose.widget.preference.SettingsSectionSpacer()

@Composable
fun SettingsSectionHeader(title: String, modifier: Modifier = Modifier, bottomPadding: Dp = 8.dp) =
    org.uwuaosp.compose.widget.preference.SettingsSectionHeader(
        title = title,
        modifier = modifier,
        bottomPadding = bottomPadding,
    )
