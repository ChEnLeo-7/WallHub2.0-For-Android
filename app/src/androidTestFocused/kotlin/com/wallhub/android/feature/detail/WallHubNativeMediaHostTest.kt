package com.wallhub.android.feature.detail

import android.graphics.Outline
import android.view.View
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WallHubNativeMediaHostTest {
    @Test
    fun inlinePresentationClipsTheNativePlayerTreeToItsRoundedBounds() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val host = WallHubNativeMediaFrame(context)
            val playerView = PlayerView(context)

            host.attach(playerView)
            host.layout(0, 0, 600, 400)
            host.setInlineCornerRadius(40f)

            val outline = Outline()
            host.outlineProvider.getOutline(host, outline)

            assertTrue(host.clipChildren)
            assertTrue(host.clipToPadding)
            assertTrue(host.clipToOutline)
            assertEquals(600, host.width)
            assertEquals(400, host.height)
            assertFalse(outline.isEmpty)
            assertEquals(40f, outline.radius, 0.5f)
        }
    }

    @Test
    fun fullscreenDisablesRoundedClippingAndInlineRestoresIt() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val host = WallHubNativeMediaFrame(context)
            host.attach(PlayerView(context))
            host.layout(0, 0, 600, 400)

            host.setInlineCornerRadius(40f)
            assertTrue(host.clipToOutline)

            host.setInlineCornerRadius(null)
            assertFalse(host.clipToOutline)
            val fullscreenOutline = Outline()
            host.outlineProvider.getOutline(host, fullscreenOutline)
            assertFalse(fullscreenOutline.isEmpty)
            assertEquals(0f, fullscreenOutline.radius, 0.5f)

            host.setInlineCornerRadius(24f)
            assertTrue(host.clipToOutline)
        }
    }
}
