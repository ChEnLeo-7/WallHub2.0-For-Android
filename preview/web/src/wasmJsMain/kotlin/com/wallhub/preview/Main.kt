package com.wallhub.preview

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.wallhub.shared.ui.WallHubSharedUiCompatibilityProbe
import kotlinx.browser.document
import kotlinx.browser.window
import kotlin.js.ExperimentalWasmJsInterop

private const val CLICK_COUNT_KEY = "wallhub.preview.compatibility.clicks"

@OptIn(ExperimentalComposeUiApi::class, ExperimentalWasmJsInterop::class)
fun main() {
    val body = checkNotNull(document.body)
    ComposeViewport(body) {
        var route by remember { mutableStateOf(window.location.pathname) }
        val reducedMotion =
            remember {
                window.matchMedia("(prefers-reduced-motion: reduce)").matches
            }
        val persistedClicks =
            remember {
                window.localStorage.getItem(CLICK_COUNT_KEY)?.toIntOrNull() ?: 0
            }
        DisposableEffect(Unit) {
            val previousHandler = window.onpopstate
            window.onpopstate = { route = window.location.pathname }
            onDispose { window.onpopstate = previousHandler }
        }
        WallHubSharedUiCompatibilityProbe(
            route = route,
            persistedClicks = persistedClicks,
            reducedMotion = reducedMotion,
            onClicksChanged = { value ->
                window.localStorage.setItem(CLICK_COUNT_KEY, value.toString())
            },
            onOpenDetail = {
                val destination = "/workshop/compatibility-probe"
                window.history.pushState(destination.toJsString(), "", destination)
                route = destination
            },
        )
    }
}
