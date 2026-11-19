package com.wallhub.android.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign

enum class WallHubLoadingLayout {
    HOME_GRID,
    DISCOVER_RAILS,
}

/** Material 3 loading state; the layout parameter is retained for callers. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WallHubPageLoading(
    layout: WallHubLoadingLayout,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val expressive = wallHubStyle().capabilities.bettboxSurfaces
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(contentPadding)
                .semantics {
                    this.contentDescription = contentDescription
                    liveRegion = LiveRegionMode.Polite
                },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            if (expressive) {
                LoadingIndicator()
            } else {
                CircularProgressIndicator()
            }
            Text(
                text = contentDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
