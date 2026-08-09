/*
 * Copyright (C) 2026 UwUniverse
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

package org.uwuaosp.compose.wizard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun WizardBrandPage(
    title: String,
    illustration: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    animateContent: Boolean = false,
    bottomContentPadding: Dp = 0.dp,
    assistContent: (@Composable () -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(!animateContent) }
    LaunchedEffect(animateContent) { visible = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(420),
        label = "wizardBrandAlpha",
    )
    val contentScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.96f,
        animationSpec = tween(520, easing = FastOutSlowInEasing),
        label = "wizardBrandScale",
    )
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(
                        horizontal = WizardDefaults.PageHorizontalPadding,
                        vertical = WizardDefaults.ActionVerticalPadding,
                    )
                    .graphicsLayer {
                        alpha = contentAlpha
                        scaleX = contentScale
                        scaleY = contentScale
                    },
                contentAlignment = Alignment.Center,
            ) {
                illustration()
            }
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(
                        start = WizardDefaults.PageHorizontalPadding,
                        end = WizardDefaults.PageHorizontalPadding,
                        bottom = if (subtitle == null) 30.dp else 10.dp,
                    )
                    .graphicsLayer { alpha = contentAlpha },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f),
                    modifier = Modifier
                        .padding(
                            start = WizardDefaults.PageHorizontalPadding,
                            end = WizardDefaults.PageHorizontalPadding,
                            bottom = 30.dp,
                        )
                        .graphicsLayer { alpha = contentAlpha },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = WizardDefaults.PageHorizontalPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                assistContent?.invoke()
            }
            Spacer(Modifier.height(bottomContentPadding))
        }
    }
}
