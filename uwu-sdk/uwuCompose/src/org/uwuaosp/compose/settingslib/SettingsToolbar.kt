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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val ExpressiveBackIcon = ImageVector.Builder(
    name = "SettingsLibExpressiveBack",
    defaultWidth = 16.dp,
    defaultHeight = 16.dp,
    viewportWidth = 16f,
    viewportHeight = 16f,
).addPath(
    pathData = addPathNodes(
        "M3.626,9L8.526,13.9C8.726,14.1 8.817,14.333 8.801,14.6" +
            "C8.801,14.867 8.701,15.1 8.501,15.3C8.301,15.483 8.067,15.583 " +
            "7.801,15.6C7.534,15.6 7.301,15.5 7.101,15.3L0.501,8.7C0.401,8.6 " +
            "0.326,8.492 0.276,8.375C0.242,8.258 0.226,8.133 0.226,8C0.226,7.867 " +
            "0.242,7.742 0.276,7.625C0.326,7.508 0.401,7.4 0.501,7.3L7.101,0.7" +
            "C7.284,0.517 7.509,0.425 7.776,0.425C8.059,0.425 8.301,0.517 " +
            "8.501,0.7C8.701,0.9 8.801,1.142 8.801,1.425C8.801,1.692 8.701,1.925 " +
            "8.501,2.125L3.626,7H14.801C15.084,7 15.317,7.1 15.501,7.3C15.701,7.483 " +
            "15.801,7.717 15.801,8C15.801,8.283 15.701,8.525 15.501,8.725C15.317,8.908 " +
            "15.084,9 14.801,9H3.626Z"
    ),
    fill = SolidColor(Color.White),
).build()

@Composable
fun SettingsToolbarActionButton(
    imageVector: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    buttonSize: Dp = 56.dp,
    containerSize: Dp = 40.dp,
) {
    Box(
        modifier = modifier.size(buttonSize),
        contentAlignment = Alignment.Center,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(containerSize)
                .clip(RoundedCornerShape(100.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
internal fun SettingsToolbar(
    title: String,
    showBackButton: Boolean,
    useCollapsingToolbar: Boolean,
    progress: Float,
    toolbarHeight: Dp,
    statusBarPadding: Dp,
    onNavigateUp: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val actionBarHeight = actionBarSize()
    val fontFamilies = rememberSettingsFontFamilies()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(statusBarPadding + toolbarHeight)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(top = statusBarPadding)
    ) {
        if (useCollapsingToolbar) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.displaySmall.emphasized(
                    fontFamilies.displaySmallEmphasized
                ),
                maxLines = 2,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .alpha(1f - progress)
                    .padding(start = 24.dp, end = 24.dp, bottom = 40.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(actionBarHeight)
                .background(MaterialTheme.colorScheme.surfaceContainer),
        )
        if (showBackButton) {
            SettingsToolbarActionButton(
                imageVector = ExpressiveBackIcon,
                contentDescription = null,
                onClick = onNavigateUp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 15.dp)
                    .height(actionBarHeight),
                iconSize = 16.dp,
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .height(actionBarHeight)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .height(actionBarHeight)
                .padding(start = if (showBackButton) 88.dp else 24.dp, end = 24.dp)
                .alpha(if (useCollapsingToolbar) progress else 1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge.emphasized(
                    fontFamilies.titleLargeEmphasized
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
