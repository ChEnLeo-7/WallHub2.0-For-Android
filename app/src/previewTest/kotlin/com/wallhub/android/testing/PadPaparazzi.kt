package com.wallhub.android.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.wallhub.android.TopLevelDestination
import com.wallhub.android.WallHubPadNavigationSidebar
import com.wallhub.android.topLevelDestinations
import com.wallhub.android.core.designsystem.LocalWallHubAdaptiveLayout
import com.wallhub.android.core.designsystem.WallHubAdaptiveLayout
import com.wallhub.android.core.designsystem.WallHubWindowHeight
import com.wallhub.android.core.designsystem.WallHubWindowLayout

internal val PadLandscapeDevice =
    DeviceConfig.PIXEL_TABLET.copy(
        screenHeight = 1968,
        screenWidth = 2800,
        xdpi = 283,
        ydpi = 283,
        orientation = ScreenOrientation.LANDSCAPE,
        density = Density.create(283),
        locale = "zh-rCN",
    )

internal val PadPortraitDevice =
    PadLandscapeDevice.copy(
        screenHeight = 2800,
        screenWidth = 1968,
        orientation = ScreenOrientation.PORTRAIT,
    )

@Composable
internal fun PadLandscapeAdaptiveContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalWallHubAdaptiveLayout provides
            WallHubAdaptiveLayout(
                width = WallHubWindowLayout.EXPANDED,
                height = WallHubWindowHeight.MEDIUM,
            ),
        content = content,
    )
}

@Composable
internal fun PadPortraitAdaptiveContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalWallHubAdaptiveLayout provides
            WallHubAdaptiveLayout(
                width = WallHubWindowLayout.MEDIUM,
                height = WallHubWindowHeight.EXPANDED,
            ),
        content = content,
    )
}

@Composable
internal fun PadTopLevelPreview(
    selectedLabel: String,
    railExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    val selectedDestination =
        when (selectedLabel) {
            "发现" -> TopLevelDestination.DISCOVER
            "下载" -> TopLevelDestination.DOWNLOADS
            "我的" -> TopLevelDestination.PROFILE
            else -> TopLevelDestination.HOME
        }
    Row(modifier = Modifier.fillMaxSize()) {
        WallHubPadNavigationSidebar(
            destinations = topLevelDestinations(includeDownloads = true),
            currentDestination = selectedDestination,
            expanded = railExpanded,
            enabled = true,
            onExpandedChange = {},
            onNavigateTo = {},
            onHomeReset = {},
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

@Composable
internal fun PadPortraitTopLevelPreview(
    selectedLabel: String,
    railExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    PadTopLevelPreview(
        selectedLabel = selectedLabel,
        railExpanded = railExpanded,
        content = content,
    )
}
