/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.widget.preference

import androidx.compose.foundation.shape.RoundedCornerShape
import org.uwuaosp.compose.framework.theme.SettingsDimension

internal fun preferenceShape(position: PreferenceGroupPosition): RoundedCornerShape {
    val outer = SettingsDimension.groupOuterRadius
    val inner = SettingsDimension.groupInnerRadius
    return when (position) {
        PreferenceGroupPosition.Single -> RoundedCornerShape(outer)
        PreferenceGroupPosition.Top -> RoundedCornerShape(outer, outer, inner, inner)
        PreferenceGroupPosition.Middle -> RoundedCornerShape(inner)
        PreferenceGroupPosition.Bottom -> RoundedCornerShape(inner, inner, outer, outer)
    }
}
