/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.uwuaosp.compose.widget.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp

private val HomepageIconSize = 40.dp
private val HomepageIconForegroundInset = 8.dp
private val HomepageIconForegroundSize = HomepageIconSize - (HomepageIconForegroundInset * 2)
private val HomepageIconShape = CircleShape

@Composable
fun SettingsHomepageIcon(
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    foregroundColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    SettingsHomepageIcon(
        imageVector = ImageVector.vectorResource(iconRes),
        modifier = modifier,
        backgroundColor = backgroundColor,
        foregroundColor = foregroundColor,
    )
}

@Composable
fun SettingsHomepageIcon(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    foregroundColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Box(modifier = modifier.size(HomepageIconSize), contentAlignment = Alignment.Center) {
        SettingsHomepageIconBackground(color = backgroundColor)
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = foregroundColor,
            modifier = Modifier.size(HomepageIconForegroundSize),
        )
    }
}

@Composable
private fun SettingsHomepageIconBackground(color: Color) {
    Box(modifier = Modifier.size(HomepageIconSize).clip(HomepageIconShape).background(color))
}

@Composable
internal fun PreferenceLeadingIcon(icon: (@Composable () -> Unit)?) {
    if (icon == null) return
    icon()
    Spacer(modifier = Modifier.size(width = 16.dp, height = 1.dp))
}
