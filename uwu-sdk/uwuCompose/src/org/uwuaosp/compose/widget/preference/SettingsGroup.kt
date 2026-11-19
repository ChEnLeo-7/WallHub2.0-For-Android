/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.widget.preference

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.uwuaosp.compose.framework.theme.SettingsDimension

internal enum class PreferenceGroupPosition {
    Single,
    Top,
    Middle,
    Bottom,
}

internal val LocalPreferenceGroupPosition = compositionLocalOf { PreferenceGroupPosition.Single }

internal fun preferenceGroupPosition(index: Int, itemCount: Int): PreferenceGroupPosition {
    return when {
        itemCount <= 1 -> PreferenceGroupPosition.Single
        index == 0 -> PreferenceGroupPosition.Top
        index == itemCount - 1 -> PreferenceGroupPosition.Bottom
        else -> PreferenceGroupPosition.Middle
    }
}

class SettingsPreferenceGroupScope
internal constructor(private val items: MutableList<@Composable () -> Unit>) {
    fun item(visible: Boolean = true, content: @Composable () -> Unit) {
        if (visible) items += content
    }
}

@Composable
fun SettingsPreferenceGroup(
    modifier: Modifier = Modifier,
    content: SettingsPreferenceGroupScope.() -> Unit,
) {
    val items = buildList { SettingsPreferenceGroupScope(this).content() }

    Column(modifier = modifier) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Spacer(Modifier.height(SettingsDimension.groupSpacing))
            }
            val position = preferenceGroupPosition(index, items.size)
            CompositionLocalProvider(LocalPreferenceGroupPosition provides position) { item() }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: SettingsPreferenceGroupScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        )
        SettingsPreferenceGroup(content = content)
    }
}

@Composable
fun SettingsSectionSpacer() {
    Spacer(modifier = Modifier.height(SettingsDimension.sectionSpacing))
}

@Composable
fun SettingsSectionHeader(title: String, modifier: Modifier = Modifier, bottomPadding: Dp = 8.dp) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier.padding(start = 8.dp, top = 8.dp, bottom = bottomPadding),
    )
}
