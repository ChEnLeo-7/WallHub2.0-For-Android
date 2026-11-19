/*
 * SPDX-FileCopyrightText: The Android Open Source Project
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.widget.preference

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** State and actions are supplied by the host, following AOSP Spa's preference model contract. */
interface PreferenceModel {
    val title: String
    val summary: () -> String?
        get() = { null }

    val icon: (@Composable () -> Unit)?
        get() = null

    val enabled: () -> Boolean
        get() = { true }

    val onClick: (() -> Unit)?
        get() = null
}

@Composable
fun Preference(model: PreferenceModel, modifier: Modifier = Modifier) {
    PreferenceRow(
        title = model.title,
        summary = model.summary(),
        modifier = modifier,
        iconContent = model.icon,
        enabled = model.enabled(),
        onClick = model.onClick,
    )
}

/** A null callback disables interaction while preserving the current checked state. */
interface SwitchPreferenceModel : PreferenceModel {
    val checked: () -> Boolean
    val onCheckedChange: ((Boolean) -> Unit)?
}

@Composable
fun SwitchPreference(model: SwitchPreferenceModel, modifier: Modifier = Modifier) {
    val summary = model.summary()
    SwitchPreferenceRow(
        title = model.title,
        summary = summary.orEmpty(),
        checked = model.checked(),
        onCheckedChange = { model.onCheckedChange?.invoke(it) },
        modifier = modifier,
        enabled = model.enabled() && model.onCheckedChange != null,
        iconContent = model.icon,
        showSummary = summary != null,
    )
}
