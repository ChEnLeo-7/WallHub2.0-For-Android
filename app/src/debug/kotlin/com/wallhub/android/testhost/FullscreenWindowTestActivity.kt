package com.wallhub.android.testhost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wallhub.android.feature.detail.FullscreenSystemBarsEffect

class FullscreenWindowTestActivity : ComponentActivity() {
    var fullscreen by mutableStateOf(false)
    var hostComposed = false
        private set
    private var hostEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fullscreen = savedInstanceState?.getBoolean(STATE_FULLSCREEN) ?: false
        if (savedInstanceState?.getBoolean(STATE_HOST_ENABLED) == true) {
            installFullscreenHost()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_HOST_ENABLED, hostEnabled)
        outState.putBoolean(STATE_FULLSCREEN, fullscreen)
        super.onSaveInstanceState(outState)
    }

    fun installFullscreenHost() {
        check(!hostEnabled)
        hostEnabled = true
        setContent {
            FullscreenSystemBarsEffect(fullscreen)
            SideEffect { hostComposed = true }
        }
    }

    private companion object {
        const val STATE_HOST_ENABLED = "fullscreen_test_host_enabled"
        const val STATE_FULLSCREEN = "fullscreen_test_fullscreen"
    }
}
