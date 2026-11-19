/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.widget.preference

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
import org.uwuaosp.compose.framework.theme.SettingsDimension
import org.uwuaosp.compose.widget.ui.PreferenceLeadingIcon

@Composable
fun PreferenceRow(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    index: Int = 0,
    itemCount: Int = 1,
    showSummary: Boolean = true,
    onClick: (() -> Unit)? = null,
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
        onClick = onClick,
    ) {
        if (iconContent != null) {
            PreferenceLeadingIcon(iconContent)
        } else {
            PreferenceIcon(icon)
        }
        Column(
            modifier =
                Modifier.weight(1f).padding(vertical = SettingsDimension.preferenceVerticalPadding)
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
    position: PreferenceGroupPosition = PreferenceGroupPosition.Single,
    onClick: (() -> Unit)? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    role: Role? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val alphaValue = if (enabled) 1f else SettingsDimension.disabledAlpha
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .alpha(alphaValue)
                .clip(preferenceShape(position))
                .background(MaterialTheme.colorScheme.surfaceBright)
                .then(
                    when {
                        checked != null && onCheckedChange != null ->
                            Modifier.toggleable(
                                value = checked,
                                enabled = enabled,
                                role = role,
                                onValueChange = onCheckedChange,
                            )

                        onClick != null ->
                            Modifier.clickable(enabled = enabled, onClick = onClick)
                                .then(
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
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = SettingsDimension.preferenceMinHeight)
                    .padding(horizontal = SettingsDimension.preferencePadding),
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
        modifier = Modifier.size(SettingsDimension.itemIconSize),
    )
    Spacer(modifier = Modifier.width(SettingsDimension.itemIconSpacing))
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
