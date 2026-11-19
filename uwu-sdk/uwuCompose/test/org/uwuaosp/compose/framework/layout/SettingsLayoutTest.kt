/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.framework.layout

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLayoutTest {
    @Test
    fun widthClassesFollowMaterialBreakpoints() {
        assertEquals(SettingsWidthClass.Compact, SettingsLayoutDefaults.metrics(599.dp).widthClass)
        assertEquals(SettingsWidthClass.Medium, SettingsLayoutDefaults.metrics(600.dp).widthClass)
        assertEquals(SettingsWidthClass.Medium, SettingsLayoutDefaults.metrics(839.dp).widthClass)
        assertEquals(SettingsWidthClass.Expanded, SettingsLayoutDefaults.metrics(840.dp).widthClass)
    }

    @Test
    fun mediumAndExpandedWidthsUseRoomierMargins() {
        assertEquals(16.dp, SettingsLayoutDefaults.metrics(599.dp).horizontalPadding)
        assertEquals(24.dp, SettingsLayoutDefaults.metrics(600.dp).horizontalPadding)
        assertEquals(24.dp, SettingsLayoutDefaults.metrics(1280.dp).horizontalPadding)
    }

    @Test
    fun toolbarChromeContainsTheContentColumnAndMargins() {
        val metrics = SettingsLayoutDefaults.metrics(1280.dp)

        assertEquals(760.dp, metrics.contentMaxWidth)
        assertEquals(808.dp, metrics.chromeMaxWidth)
    }
}
