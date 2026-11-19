/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.widget.preference

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A slot-based preference row for content that does not fit the standard title and summary model.
 */
@Composable
fun CustomPreferenceRow(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    index: Int = 0,
    itemCount: Int = 1,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier = modifier) {
        if (index > 0) {
            Spacer(modifier = Modifier.height(2.dp))
        }
        PreferenceSurface(
            enabled = enabled,
            position =
                if (itemCount > 1) {
                    preferenceGroupPosition(index, itemCount)
                } else {
                    LocalPreferenceGroupPosition.current
                },
            onClick = onClick,
            content = content,
        )
    }
}
