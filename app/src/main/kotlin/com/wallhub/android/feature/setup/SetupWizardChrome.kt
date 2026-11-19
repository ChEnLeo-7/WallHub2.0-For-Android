package com.wallhub.android.feature.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.wallHubStyle
import org.uwuaosp.compose.wizard.GlifScaffold

/** The shared back action for the current setup step; brand pages do not show it. */
internal val LocalWallHubWizardNavigateBack = staticCompositionLocalOf<(() -> Unit)?> { null }

private val WizardActionClearance = 112.dp
private val ExpressiveWizardContentMaxWidth = 960.dp
private val Md3WizardContentMaxWidth = 1120.dp

/**
 * Settings/wizard page seam: MD3 uses an official Scaffold/TopAppBar with a compact lead-in,
 * while Material 3 Expressive keeps the uwu-sdk [GlifScaffold] header.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WallHubWizardPage(
    icon: ImageVector,
    title: String,
    description: String,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val navigateBack = LocalWallHubWizardNavigateBack.current
    if (wallHubStyle().capabilities.bettboxSurfaces) {
        // GlifScaffold's own wide branch splits the header and content into two columns. On a
        // landscape tablet that leaves the left column mostly empty, so constrain the scaffold to
        // a portrait-width surface and center it inside the pad content area instead.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .widthIn(max = ExpressiveWizardContentMaxWidth)
                        .fillMaxWidth(),
            ) {
                GlifScaffold(
                    imageVector = icon,
                    title = title,
                    description = description,
                    onNavigateBack = navigateBack,
                    navigateBackContentDescription = stringResource(R.string.settings_action_back),
                    contentScrollable = scrollable,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
                        content = content,
                    )
                    Spacer(modifier = Modifier.height(WizardActionClearance))
                }
            }
        }
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                TopAppBar(
                    modifier =
                        Modifier
                            .widthIn(max = Md3WizardContentMaxWidth)
                            .fillMaxWidth(),
                    title = { Text(title) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    navigationIcon = {
                        if (navigateBack != null) {
                            IconButton(onClick = navigateBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = stringResource(R.string.settings_action_back),
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        val scrollModifier =
            if (scrollable) {
                Modifier.verticalScroll(rememberScrollState())
            } else {
                Modifier
            }
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .widthIn(max = Md3WizardContentMaxWidth)
                        .fillMaxWidth()
                        .then(scrollModifier)
                        .padding(horizontal = WallHubSpacing.md),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = WallHubSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(WallHubSpacing.xs))
                content()
                Spacer(modifier = Modifier.height(WizardActionClearance))
            }
        }
    }
}

/** Official Material 3 wizard action bar; M3E themes provide the expressive motion and shapes. */
@Composable
internal fun WallHubWizardActions(
    visible: Boolean,
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    showPrimary: Boolean = true,
    showSecondary: Boolean = secondaryLabel != null && onSecondaryClick != null,
    primaryEnabled: Boolean = true,
) {
    if (!visible || (!showPrimary && !showSecondary)) return
    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = WallHubSpacing.lg,
                            end = WallHubSpacing.lg,
                            top = WallHubSpacing.xs,
                            bottom = WallHubSpacing.md,
                        ),
                verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
            ) {
                if (showSecondary && secondaryLabel != null && onSecondaryClick != null) {
                    OutlinedButton(
                        onClick = onSecondaryClick,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text(secondaryLabel)
                    }
                }
                if (showPrimary) {
                    Button(
                        onClick = onPrimaryClick,
                        enabled = primaryEnabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text(primaryLabel)
                    }
                }
            }
        }
    }
}
