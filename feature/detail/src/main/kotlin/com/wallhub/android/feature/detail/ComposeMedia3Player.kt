@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.wallhub.android.feature.detail

import android.graphics.drawable.ColorDrawable
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

/**
 * Hosts Media3's standard PlayerView and PlayerControlView inside the Compose hierarchy.
 * Fullscreen remains owned by the surrounding screen so the same player instance is retained.
 */
@Composable
internal fun ComposeMedia3Player(
    player: Player,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { viewContext ->
            LayoutInflater
                .from(viewContext)
                .inflate(R.layout.wallhub_media3_player_view, null, false)
                .let { it as PlayerView }
                .apply {
                    this.player = player
                    useController = true
                    setShowSubtitleButton(false)
                    setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                    setKeepContentOnPlayerReset(true)
                    setOnTouchListener(HoldToDoubleSpeedController(this))
                    WallHubPlayerControlsBinder(this).also { binder ->
                        setTag(R.id.wallhub_playback_speed, binder)
                        binder.bind(player)
                    }
                }
        },
        update = { view ->
            view.player = player
            view.useController = true
            (view.getTag(R.id.wallhub_playback_speed) as? WallHubPlayerControlsBinder)?.bind(player)
            view.setFullscreenButtonState(fullscreen)
            view.setFullscreenButtonClickListener { requestedFullscreen ->
                onFullscreenChange(requestedFullscreen)
            }
        },
        onRelease = { view ->
            (view.getTag(R.id.wallhub_playback_speed) as? WallHubPlayerControlsBinder)?.release()
        },
        modifier = modifier,
    )
}

private class WallHubPlayerControlsBinder(
    private val playerView: PlayerView,
) : Player.Listener,
    SeekBar.OnSeekBarChangeListener {
    private val speedButton = playerView.findViewById<TextView>(R.id.wallhub_playback_speed)
    private val muteButton = playerView.findViewById<ImageButton>(R.id.wallhub_mute)
    private val volumeBar = playerView.findViewById<SeekBar>(R.id.wallhub_volume)
    private var boundPlayer: Player? = null
    private var lastAudibleVolume = 1f
    private var speedPopup: PopupWindow? = null
    private var controllerTimeoutBeforePopup: Int? = null

    init {
        speedButton?.setOnClickListener(::showSpeedMenu)
        muteButton?.setOnClickListener { toggleMute() }
        volumeBar?.setOnSeekBarChangeListener(this)
        playerView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) updateResponsiveControls(right - left)
        }
    }

    fun bind(player: Player) {
        if (boundPlayer !== player) {
            boundPlayer?.removeListener(this)
            boundPlayer = player
            player.addListener(this)
        }
        updateSpeed(player.playbackParameters.speed)
        updateVolume(player.volume)
        updateCommandAvailability(player)
        updateResponsiveControls(playerView.width)
    }

    fun release() {
        speedPopup?.dismiss()
        speedPopup = null
        restoreControllerTimeout()
        boundPlayer?.removeListener(this)
        boundPlayer = null
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        updateSpeed(playbackParameters.speed)
    }

    override fun onVolumeChanged(volume: Float) {
        updateVolume(volume)
    }

    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
        boundPlayer?.let(::updateCommandAvailability)
    }

    override fun onProgressChanged(
        seekBar: SeekBar,
        progress: Int,
        fromUser: Boolean,
    ) {
        if (!fromUser) return
        val player = boundPlayer ?: return
        if (player.isCommandAvailable(Player.COMMAND_SET_VOLUME)) {
            player.volume = progress / VOLUME_STEPS.toFloat()
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit

    private fun showSpeedMenu(anchor: View) {
        val player = boundPlayer ?: return
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return
        speedPopup?.dismiss()
        val inflater = LayoutInflater.from(anchor.context)
        val menuContainer =
            inflater.inflate(
                R.layout.wallhub_player_speed_popup,
                null,
                false,
            ) as LinearLayout
        val optionsGrid = menuContainer.findViewById<GridLayout>(R.id.wallhub_speed_options)
        val popup =
            PopupWindow(
                menuContainer,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true,
            ).apply {
                setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
                isOutsideTouchable = true
                isClippingEnabled = true
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                elevation = SPEED_POPUP_ELEVATION_DP * anchor.resources.displayMetrics.density
                animationStyle = R.style.WallHubPlayerSpeedPopupAnimation
                setOnDismissListener {
                    speedPopup = null
                    restoreControllerTimeout()
                }
            }
        PLAYBACK_SPEEDS.forEach { speed ->
            val option =
                inflater.inflate(
                    R.layout.wallhub_player_speed_option,
                    menuContainer,
                    false,
                ) as TextView
            option.text = speed.toSpeedLabel()
            option.isSelected = abs(player.playbackParameters.speed - speed) < SPEED_EPSILON
            option.contentDescription = "${anchor.context.getString(R.string.wallhub_player_playback_speed)} ${option.text}"
            option.setOnClickListener {
                player.setPlaybackParameters(player.playbackParameters.withSpeed(speed))
                popup.dismiss()
            }
            optionsGrid.addView(option)
        }
        menuContainer.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val density = anchor.resources.displayMetrics.density
        val gapPx = (SPEED_POPUP_GAP_DP * density).roundToInt()
        val horizontalOffset = (anchor.width - menuContainer.measuredWidth) / 2
        speedPopup = popup
        holdControllerVisible()
        popup.showAsDropDown(
            anchor,
            horizontalOffset,
            -(anchor.height + menuContainer.measuredHeight + gapPx),
        )
        playerView.showController()
    }

    private fun holdControllerVisible() {
        if (controllerTimeoutBeforePopup == null) {
            controllerTimeoutBeforePopup = playerView.controllerShowTimeoutMs
        }
        playerView.setControllerShowTimeoutMs(0)
        playerView.showController()
    }

    private fun restoreControllerTimeout() {
        val timeout = controllerTimeoutBeforePopup ?: return
        controllerTimeoutBeforePopup = null
        playerView.setControllerShowTimeoutMs(timeout)
        playerView.showController()
    }

    private fun toggleMute() {
        val player = boundPlayer ?: return
        if (!player.isCommandAvailable(Player.COMMAND_SET_VOLUME)) return
        player.volume =
            if (player.volume > MUTED_VOLUME_THRESHOLD) {
                lastAudibleVolume = player.volume
                0f
            } else {
                lastAudibleVolume.coerceAtLeast(DEFAULT_UNMUTE_VOLUME)
            }
    }

    private fun updateSpeed(speed: Float) {
        speedButton?.text = speed.toSpeedLabel()
    }

    private fun updateVolume(volume: Float) {
        if (volume > MUTED_VOLUME_THRESHOLD) lastAudibleVolume = volume
        volumeBar?.progress = (volume * VOLUME_STEPS).roundToInt()
        val isMuted = volume <= MUTED_VOLUME_THRESHOLD
        muteButton?.apply {
            setImageResource(
                if (isMuted) {
                    R.drawable.wallhub_player_volume_off
                } else {
                    R.drawable.wallhub_player_volume_up
                },
            )
            contentDescription =
                context.getString(
                    if (isMuted) R.string.wallhub_player_unmute else R.string.wallhub_player_mute,
                )
        }
    }

    private fun updateCommandAvailability(player: Player) {
        val canChangeSpeed = player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)
        speedButton?.isEnabled = canChangeSpeed
        speedButton?.alpha = if (canChangeSpeed) 1f else DISABLED_CONTROL_ALPHA

        val canChangeVolume = player.isCommandAvailable(Player.COMMAND_SET_VOLUME)
        muteButton?.isEnabled = canChangeVolume
        volumeBar?.isEnabled = canChangeVolume
        muteButton?.alpha = if (canChangeVolume) 1f else DISABLED_CONTROL_ALPHA
        volumeBar?.alpha = if (canChangeVolume) 1f else DISABLED_CONTROL_ALPHA
    }

    private fun updateResponsiveControls(widthPx: Int) {
        if (widthPx <= 0) return
        val widthDp = widthPx / playerView.resources.displayMetrics.density
        volumeBar?.visibility = if (widthDp >= VOLUME_BAR_MIN_WIDTH_DP) View.VISIBLE else View.GONE
    }
}

private fun Float.toSpeedLabel(): String {
    val rounded = roundToInt()
    val numeric = if (abs(this - rounded) < SPEED_EPSILON) rounded.toString() else toString()
    return "${numeric}x"
}

/** Temporarily doubles playback speed while the video surface is held. */
private class HoldToDoubleSpeedController(
    private val playerView: PlayerView,
) : View.OnTouchListener {
    private var acceleratedPlayer: Player? = null
    private var restorePlaybackParameters: PlaybackParameters? = null
    private val gestureDetector =
        GestureDetector(
            playerView.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onLongPress(event: MotionEvent) {
                    startDoubleSpeed()
                }
            },
        )

    override fun onTouch(
        view: View,
        event: MotionEvent,
    ): Boolean {
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            restoreSpeed()
        }
        return false
    }

    private fun startDoubleSpeed() {
        if (acceleratedPlayer != null) return
        val player = playerView.player ?: return
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return
        restorePlaybackParameters = player.playbackParameters
        acceleratedPlayer = player
        player.setPlaybackParameters(player.playbackParameters.withSpeed(2f))
    }

    private fun restoreSpeed() {
        val player = acceleratedPlayer ?: return
        val parameters = restorePlaybackParameters
        if (parameters != null && player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            player.setPlaybackParameters(parameters)
        }
        acceleratedPlayer = null
        restorePlaybackParameters = null
    }
}

private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private const val SPEED_EPSILON = 0.01f
private const val SPEED_POPUP_GAP_DP = 4f
private const val SPEED_POPUP_ELEVATION_DP = 12f
private const val VOLUME_STEPS = 100
private const val MUTED_VOLUME_THRESHOLD = 0.01f
private const val DEFAULT_UNMUTE_VOLUME = 0.5f
private const val DISABLED_CONTROL_ALPHA = 0.42f
private const val VOLUME_BAR_MIN_WIDTH_DP = 360f
