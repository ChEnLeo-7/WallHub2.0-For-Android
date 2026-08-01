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

import android.util.TypedValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun preferenceShape(position: PreferencePosition): RoundedCornerShape {
    return when (position) {
        PreferencePosition.Single -> RoundedCornerShape(20.dp)
        PreferencePosition.Top -> RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomEnd = 4.dp,
            bottomStart = 4.dp,
        )
        PreferencePosition.Middle -> RoundedCornerShape(4.dp)
        PreferencePosition.Bottom -> RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomEnd = 20.dp,
            bottomStart = 20.dp,
        )
    }
}

@Composable
internal fun actionBarSize(): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, density) {
        val value = TypedValue()
        val sizePx = if (context.theme.resolveAttribute(android.R.attr.actionBarSize, value, true)) {
            if (value.resourceId != 0) {
                context.resources.getDimension(value.resourceId)
            } else {
                TypedValue.complexToDimension(value.data, context.resources.displayMetrics)
            }
        } else {
            with(density) { 56.dp.toPx() }
        }
        with(density) {
            sizePx.toDp()
        }
    }
}
