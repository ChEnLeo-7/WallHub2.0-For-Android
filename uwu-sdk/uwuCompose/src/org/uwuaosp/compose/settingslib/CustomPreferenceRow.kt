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

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A slot-based preference row for content that does not fit the standard title and summary model.
 */
@Composable
fun CustomPreferenceRow(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    position: PreferencePosition = PreferencePosition.Single,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    PreferenceSurface(
        modifier = modifier,
        enabled = enabled,
        position = position,
        onClick = onClick,
        content = content,
    )
}
