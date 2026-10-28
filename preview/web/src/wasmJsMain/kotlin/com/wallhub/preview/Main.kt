package com.wallhub.preview

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.wallhub.shared.ui.WallHubSharedUiCompatibilityProbe
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val body = checkNotNull(document.body)
    ComposeViewport(body) {
        WallHubSharedUiCompatibilityProbe()
    }
}
