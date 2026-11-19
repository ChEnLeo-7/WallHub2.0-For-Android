package com.wallhub.android.feature.detail

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackLifecycleGateTest {
    @Test
    fun `stop pauses active playback and start restores it once`() {
        val player = FakeLifecyclePlayer(playWhenReady = true)
        val gate = PlaybackLifecycleGate(player::isPlayingRequested, player::pause, player::play)

        gate.onStop()
        gate.onStart()
        gate.onStart()

        assertEquals(1, player.pauseCalls)
        assertEquals(1, player.playCalls)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `stopped player is not started by lifecycle`() {
        val player = FakeLifecyclePlayer(playWhenReady = false)
        val gate = PlaybackLifecycleGate(player::isPlayingRequested, player::pause, player::play)

        gate.onStop()
        gate.onStart()

        assertEquals(0, player.pauseCalls)
        assertEquals(0, player.playCalls)
        assertFalse(player.playWhenReady)
    }
}

private class FakeLifecyclePlayer(
    var playWhenReady: Boolean,
) {
    var pauseCalls = 0
    var playCalls = 0

    fun isPlayingRequested(): Boolean = playWhenReady

    fun pause() {
        pauseCalls += 1
        playWhenReady = false
    }

    fun play() {
        playCalls += 1
        playWhenReady = true
    }
}
