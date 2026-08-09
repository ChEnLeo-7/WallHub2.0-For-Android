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
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

    fun actionContentPadding(
        visible: Boolean,
        expanded: Boolean,
        showPrimary: Boolean,
        showSecondary: Boolean,
    ): Dp = when {
        !visible || (!showPrimary && !showSecondary) -> 0.dp
        !expanded && showPrimary && showSecondary ->
            ActionTopPadding + (ActionHeight * 2) + ActionSpacing + ActionVerticalPadding
        else -> ActionTopPadding + ActionHeight + ActionVerticalPadding
    }
}

@Composable
fun wizardActionContentPadding(
    visible: Boolean,
    expanded: Boolean,
    showPrimary: Boolean,
    showSecondary: Boolean,
): Dp = WizardDefaults.actionContentPadding(
    visible = visible,
    expanded = expanded,
    showPrimary = showPrimary,
    showSecondary = showSecondary,
) + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

val LocalWizardToolbarProgressUpdater = compositionLocalOf<(Float) -> Unit> { {} }

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
    contentBottomSpacing: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            val wideLayout = maxWidth >= WizardDefaults.WideLayoutThreshold
            if (wideLayout && illustration != null) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(0.9f),
                        content = illustration,
                    )
                    WizardContentColumn(
                        config = config,
                        headerIcon = headerIcon,
                        animateContentEntry = animateContentEntry,
                        bottomContentPadding = bottomContentPadding,
                        scrollable = scrollable,
                        contentSpacing = contentSpacing,
                        contentBottomSpacing = contentBottomSpacing,
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight(),
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
                    contentBottomSpacing = contentBottomSpacing,
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
    contentBottomSpacing: Dp,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val contentVisible = remember {
        MutableTransitionState(false).apply { targetState = true }
    }
    val scrollState = rememberScrollState()
    val updateToolbarProgress = LocalWizardToolbarProgressUpdater.current
    val toolbarProgress = (scrollState.value / 72f).coerceIn(0f, 1f)
    LaunchedEffect(toolbarProgress) {
        updateToolbarProgress(toolbarProgress)
    }

    Column(modifier = modifier) {
        val contentModifier = Modifier
            .weight(1f)
            .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
            .padding(
                start = WizardDefaults.PageHorizontalPadding,
                top = 108.dp,
                end = WizardDefaults.PageHorizontalPadding,
                bottom = bottomContentPadding + contentBottomSpacing,
            )
        Column(modifier = contentModifier) {
            if (animateContentEntry) {
                AnimatedVisibility(
                    visibleState = contentVisible,
                    enter = fadeIn(tween(WizardDefaults.ContentEnterDurationMillis)) +
                        slideInVertically(
                            animationSpec = tween(WizardDefaults.ContentEnterDurationMillis),
                            initialOffsetY = { fullHeight -> fullHeight / 20 },
                        ),
                    exit = ExitTransition.None,
                ) {
                    WizardPageContent(config, headerIcon, contentSpacing, content = content)
                }
            } else {
                WizardPageContent(
                    config = config,
                    headerIcon = headerIcon,
                    contentSpacing = contentSpacing,
                    modifier = if (scrollable) Modifier else Modifier.fillMaxHeight(),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun WizardPageContent(
    config: WizardPageConfig,
    headerIcon: @Composable BoxScope.() -> Unit,
    contentSpacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Box(modifier = Modifier.height(48.dp).fillMaxWidth(), content = headerIcon)
        Spacer(Modifier.height(24.dp))
        Text(
            text = config.title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        if (!config.description.isNullOrBlank()) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = config.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
            )
        }
        Spacer(Modifier.height(contentSpacing))
        content()
    }
}
