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

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun WizardToolbarBackground(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = progress)),
    )
}

@Composable
fun WizardToolbarTitle(
    title: String,
    progress: Float,
    modifier: Modifier = Modifier,
    icon: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer { alpha = progress },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun WizardActions(
    visible: Boolean,
    expanded: Boolean,
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    showPrimary: Boolean = true,
    showSecondary: Boolean = secondaryLabel != null && onSecondaryClick != null,
) {
    if (!visible || (!showPrimary && !showSecondary)) return
    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = WizardDefaults.PageHorizontalPadding,
                        end = WizardDefaults.PageHorizontalPadding,
                        top = WizardDefaults.ActionTopPadding,
                        bottom = WizardDefaults.ActionVerticalPadding,
                    )
                    .height(
                        if (!expanded && showPrimary && showSecondary) {
                            (WizardDefaults.ActionHeight * 2) + WizardDefaults.ActionSpacing
                        } else {
                            WizardDefaults.ActionHeight
                        },
                    ),
                contentAlignment = Alignment.BottomEnd,
            ) {
                val buttonWidth by animateDpAsState(
                    targetValue = maxWidth,
                    animationSpec = tween(WizardDefaults.ActionMorphDurationMillis),
                    label = "wizardActionWidth",
                )
                val cornerRadius by animateDpAsState(
                    targetValue = WizardDefaults.ActionHeight / 2,
                    animationSpec = tween(WizardDefaults.ActionMorphDurationMillis),
                    label = "wizardActionCornerRadius",
                )
                Column(
                    modifier = Modifier.width(buttonWidth),
                    verticalArrangement = Arrangement.spacedBy(WizardDefaults.ActionSpacing),
                ) {
                    if (!expanded && showSecondary && secondaryLabel != null && onSecondaryClick != null) {
                        OutlinedButton(
                            onClick = onSecondaryClick,
                            shape = RoundedCornerShape(cornerRadius),
                            modifier = Modifier.fillMaxWidth().height(WizardDefaults.ActionHeight),
                        ) {
                            WizardActionLabel(secondaryLabel)
                        }
                    }
                    if (showPrimary) {
                        Button(
                            onClick = onPrimaryClick,
                            shape = RoundedCornerShape(cornerRadius),
                            modifier = Modifier.fillMaxWidth().height(WizardDefaults.ActionHeight),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                        ) {
                            WizardActionLabel(primaryLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardActionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

fun wizardPageTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return (
        slideInHorizontally(
            animationSpec = tween(400, easing = FastOutSlowInEasing),
            initialOffsetX = { fullWidth -> fullWidth / 10 * direction },
        ) + fadeIn(tween(260, delayMillis = 50))
        ).togetherWith(
        slideOutHorizontally(
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            targetOffsetX = { fullWidth -> -fullWidth / 18 * direction },
        ) + fadeOut(tween(190)),
    )
}
