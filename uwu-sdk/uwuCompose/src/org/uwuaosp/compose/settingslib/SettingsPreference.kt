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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class PreferencePosition {
    Single,
    Top,
    Middle,
    Bottom,
}

@Composable
fun PreferenceGroupSpacer() {
    Spacer(modifier = Modifier.height(2.dp))
}

@Composable
fun PreferenceRow(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    position: PreferencePosition = PreferencePosition.Single,
    showSummary: Boolean = true,
    onClick: () -> Unit,
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
                .padding(vertical = 12.dp)
        ) {
            PreferenceTitle(title)
            if (showSummary && summary != null) {
                PreferenceSummary(summary)
            }
        }
        trailingContent?.invoke(this)
    }
}

@Composable
internal fun PreferenceSurface(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    position: PreferencePosition = PreferencePosition.Single,
    onClick: (() -> Unit)? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    role: Role? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val alphaValue = if (enabled) 1f else 0.38f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alphaValue)
            .clip(preferenceShape(position))
            .background(MaterialTheme.colorScheme.surfaceBright)
            .then(
                when {
                    checked != null && onCheckedChange != null -> Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = role,
                        onValueChange = onCheckedChange,
                    )

                    onClick != null -> Modifier.clickable(
                        enabled = enabled,
                        onClick = onClick,
                    ).then(
                        if (role != null) {
                            Modifier.semantics { this.role = role }
                        } else {
                            Modifier
                        }
                    )

                    else -> Modifier
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

@Composable
internal fun PreferenceIcon(icon: ImageVector?) {
    if (icon == null) return
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(24.dp)
            .padding(end = 0.dp),
    )
    Spacer(modifier = Modifier.width(24.dp))
}

@Composable
internal fun PreferenceTitle(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun PreferenceSummary(summary: String) {
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = summary,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}
