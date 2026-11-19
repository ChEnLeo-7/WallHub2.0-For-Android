package com.wallhub.android.feature.detail

import android.content.pm.ActivityInfo
import android.view.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.filters.SdkSuppress
import com.wallhub.android.testhost.FullscreenWindowTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 30)
class FullscreenWindowStateTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<FullscreenWindowTestActivity>()

    @Test
    fun exitingFullscreenRestoresTheEntryWindowState() {
        var fullscreen by mutableStateOf(false)
        prepareMixedBars()
        composeRule.setContent { FullscreenSystemBarsEffect(fullscreen) }
        awaitBars(statusVisible = true, navigationVisible = false)

        composeRule.runOnIdle { fullscreen = true }
        assertFullscreen()
        composeRule.runOnIdle { fullscreen = false }

        assertEntryState()
    }

    @Test
    fun removingFullscreenEffectRestoresTheEntryWindowState() {
        var attached by mutableStateOf(true)
        prepareMixedBars()
        composeRule.setContent {
            if (attached) FullscreenSystemBarsEffect(true)
        }
        assertFullscreen()
        composeRule.runOnIdle { attached = false }

        assertEntryState()
    }

    @Test
    fun recreatingActivityWhileFullscreenRestoresTheOriginalEntryWindowState() {
        prepareMixedBars()
        val original = composeRule.activity
        composeRule.runOnUiThread {
            original.installFullscreenHost()
            original.fullscreen = true
        }
        assertFullscreen()

        composeRule.activityRule.scenario.recreate()
        val recreated = composeRule.activity
        assertNotSame(original, recreated)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            var composed = false
            composeRule.runOnUiThread { composed = recreated.hostComposed }
            composed
        }
        composeRule.runOnUiThread { assertTrue(recreated.fullscreen) }
        assertFullscreen()

        composeRule.runOnIdle { recreated.fullscreen = false }
        assertEntryState()
    }

    private fun prepareMixedBars() {
        composeRule.runOnUiThread {
            val activity = composeRule.activity
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                show(WindowInsetsCompat.Type.statusBars())
                hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
        awaitBars(statusVisible = true, navigationVisible = false)
        composeRule.runOnUiThread {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, composeRule.activity.requestedOrientation)
            assertEquals(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT, controllerBehavior())
        }
    }

    private fun assertFullscreen() {
        awaitBars(statusVisible = false, navigationVisible = false)
        composeRule.runOnUiThread {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, composeRule.activity.requestedOrientation)
            assertEquals(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, controllerBehavior())
        }
    }

    private fun assertEntryState() {
        awaitBars(statusVisible = true, navigationVisible = false)
        composeRule.runOnUiThread {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, composeRule.activity.requestedOrientation)
            assertEquals(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT, controllerBehavior())
        }
    }

    private fun controllerBehavior(): Int {
        val window = composeRule.activity.window
        return WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior
    }

    private fun awaitBars(statusVisible: Boolean, navigationVisible: Boolean) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            var matches = false
            composeRule.runOnUiThread {
                val insets = composeRule.activity.window.decorView.rootWindowInsets
                matches = insets != null &&
                    insets.isVisible(WindowInsets.Type.statusBars()) == statusVisible &&
                    insets.isVisible(WindowInsets.Type.navigationBars()) == navigationVisible
            }
            matches
        }
    }
}
