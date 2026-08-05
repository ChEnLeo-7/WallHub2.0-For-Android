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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class WizardPageConfig(
    val title: String,
    val description: String? = null,
)

object WizardDefaults {
    val PageHorizontalPadding = 28.dp
    val ActionVerticalPadding = 24.dp
    val ActionTopPadding = 12.dp
    val ActionSpacing = 12.dp
    val ActionHeight = 56.dp
    val WideLayoutThreshold = 720.dp
    const val ContentEnterDurationMillis = 360
    const val ActionMorphDurationMillis = 460
}

@Composable
fun wizardActionContentPadding(
    visible: Boolean,
    expanded: Boolean,
    showPrimary: Boolean,
    showSecondary: Boolean,
): Dp {
    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return when {
        !visible || (!showPrimary && !showSecondary) -> 0.dp
        !expanded && showPrimary && showSecondary ->
            WizardDefaults.ActionTopPadding + (WizardDefaults.ActionHeight * 2) +
                WizardDefaults.ActionSpacing + WizardDefaults.ActionVerticalPadding + navigationBarPadding
        else ->
            WizardDefaults.ActionTopPadding + WizardDefaults.ActionHeight +
                WizardDefaults.ActionVerticalPadding + navigationBarPadding
    }
}

@Composable
fun WizardPageScaffold(
    config: WizardPageConfig,
    headerIcon: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    illustration: (@Composable BoxScope.() -> Unit)? = null,
    animateContentEntry: Boolean = false,
    bottomContentPadding: Dp = 0.dp,
    scrollable: Boolean = true,
    contentSpacing: Dp = 28.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            if (maxWidth >= WizardDefaults.WideLayoutThreshold && illustration != null) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxHeight().weight(0.9f), content = illustration)
                    WizardContentColumn(
                        config = config,
                        headerIcon = headerIcon,
                        animateContentEntry = animateContentEntry,
                        bottomContentPadding = bottomContentPadding,
                        scrollable = scrollable,
                        contentSpacing = contentSpacing,
                        modifier = Modifier.fillMaxHeight().weight(1.1f),
                        content = content,
                    )
                }
            } else {
                WizardContentColumn(
                    config = config,
                    headerIcon = headerIcon,
                    animateContentEntry = animateContentEntry,
                    bottomContentPadding = bottomContentPadding,
                    scrollable = scrollable,
                    contentSpacing = contentSpacing,
                    modifier = Modifier.fillMaxSize(),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun WizardContentColumn(
    config: WizardPageConfig,
    headerIcon: @Composable BoxScope.() -> Unit,
    animateContentEntry: Boolean,
    bottomContentPadding: Dp,
    scrollable: Boolean,
    contentSpacing: Dp,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val scrollState = rememberScrollState()
    val body = @Composable {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(48.dp), content = headerIcon)
            Spacer(Modifier.height(24.dp))
            Text(
                text = config.title,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            if (!config.description.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = config.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(contentSpacing))
            content()
        }
    }
    Column(
        modifier = modifier
            .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
            .padding(
                start = WizardDefaults.PageHorizontalPadding,
                top = topInset + 72.dp,
                end = WizardDefaults.PageHorizontalPadding,
                bottom = bottomContentPadding + 24.dp,
            ),
    ) {
        if (animateContentEntry) {
            AnimatedVisibility(
                visibleState = visible,
                enter = fadeIn(tween(WizardDefaults.ContentEnterDurationMillis)) +
                    slideInVertically(
                        animationSpec = tween(WizardDefaults.ContentEnterDurationMillis),
                        initialOffsetY = { it / 20 },
                    ),
                exit = ExitTransition.None,
            ) { body() }
        } else {
            body()
        }
    }
}

@Composable
fun WizardBrandPage(
    title: String,
    illustration: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    bottomContentPadding: Dp = 0.dp,
) {
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = safeInsets.calculateTopPadding() + 24.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = WizardDefaults.PageHorizontalPadding),
                contentAlignment = Alignment.Center,
            ) { illustration() }
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = WizardDefaults.PageHorizontalPadding),
            )
            if (subtitle != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = WizardDefaults.PageHorizontalPadding),
                )
            }
            Spacer(Modifier.height(30.dp + bottomContentPadding))
        }
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
    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.background,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = WizardDefaults.PageHorizontalPadding,
                        top = WizardDefaults.ActionTopPadding,
                        end = WizardDefaults.PageHorizontalPadding,
                        bottom = WizardDefaults.ActionVerticalPadding + navigationBarPadding,
                    ),
            ) {
                val width by animateDpAsState(
                    maxWidth,
                    tween(WizardDefaults.ActionMorphDurationMillis),
                    label = "wizardActionWidth",
                )
                Column(
                    modifier = Modifier.width(width),
                    verticalArrangement = Arrangement.spacedBy(WizardDefaults.ActionSpacing),
                ) {
                    if (!expanded && showSecondary && secondaryLabel != null && onSecondaryClick != null) {
                        OutlinedButton(
                            onClick = onSecondaryClick,
                            modifier = Modifier.fillMaxWidth().height(WizardDefaults.ActionHeight),
                            shape = RoundedCornerShape(WizardDefaults.ActionHeight / 2),
                        ) { WizardActionLabel(secondaryLabel) }
                    }
                    if (showPrimary) {
                        Button(
                            onClick = onPrimaryClick,
                            modifier = Modifier.fillMaxWidth().height(WizardDefaults.ActionHeight),
                            shape = RoundedCornerShape(WizardDefaults.ActionHeight / 2),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) { WizardActionLabel(primaryLabel) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardActionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

fun wizardPageTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return (
        slideInHorizontally(
            animationSpec = tween(400, easing = FastOutSlowInEasing),
            initialOffsetX = { it / 10 * direction },
        ) + fadeIn(tween(260, delayMillis = 50))
        ).togetherWith(
        slideOutHorizontally(
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            targetOffsetX = { -it / 18 * direction },
        ) + fadeOut(tween(190)),
    )
}
