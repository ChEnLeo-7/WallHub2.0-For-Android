@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.wallhub.android.feature.detail

import android.graphics.drawable.ColorDrawable
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSpacing
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
    var holdDoubleSpeedActive by remember(player) { mutableStateOf(false) }
    var holdSpeedFrame by remember(player) { mutableStateOf<Bitmap?>(null) }
    var holdSpeed by remember(player) { mutableStateOf(2f) }
    val holdSpeedState by rememberUpdatedState { active: Boolean, frame: Bitmap?, speed: Float ->
        holdDoubleSpeedActive = active
        holdSpeed = speed
        if (frame != null) holdSpeedFrame = frame
        if (!active) holdSpeedFrame = null
    }
    Box(modifier = modifier) {
        AndroidView(
            factory = { viewContext ->
                val layoutParamsParent = FrameLayout(viewContext)
                LayoutInflater
                    .from(viewContext)
                    .inflate(R.layout.wallhub_media3_player_view, layoutParamsParent, false)
                    .let { it as PlayerView }
                    .apply {
                        this.player = player
                        useController = true
                        setShowSubtitleButton(false)
                        setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                        setKeepContentOnPlayerReset(true)
                        HoldToDoubleSpeedController(this) { active, frame, speed -> holdSpeedState(active, frame, speed) }.also { controller ->
                            setOnTouchListener(controller)
                            setTag(R.id.wallhub_hold_speed_controller, controller)
                        }
                        WallHubPlayerControlsBinder(this).also { binder ->
                            setTag(R.id.wallhub_playback_speed, binder)
                            binder.bind(player)
                        }
                    }
            },
            update = { view ->
                view.player = player
                view.useController = true
                (view.getTag(R.id.wallhub_playback_speed) as? WallHubPlayerControlsBinder)?.apply {
                    bind(player)
                }
                view.setFullscreenButtonState(fullscreen)
                view.setFullscreenButtonClickListener { requestedFullscreen ->
                    onFullscreenChange(requestedFullscreen)
                }
            },
            onRelease = { view ->
                (view.getTag(R.id.wallhub_hold_speed_controller) as? HoldToDoubleSpeedController)?.release()
                (view.getTag(R.id.wallhub_playback_speed) as? WallHubPlayerControlsBinder)?.release()
            },
            modifier = Modifier.fillMaxSize(),
        )
        HoldDoubleSpeedIndicator(
            visible = holdDoubleSpeedActive,
            videoFrame = holdSpeedFrame,
            speed = holdSpeed,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = HOLD_SPEED_TOP_PADDING),
        )
    }
}

@Composable
private fun HoldDoubleSpeedIndicator(
    visible: Boolean,
    videoFrame: Bitmap?,
    speed: Float,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(HOLD_SPEED_CORNER_RADIUS)
    val materialColor = Color.Black
    val portrait = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(120)) + scaleIn(tween(180, easing = FastOutSlowInEasing), initialScale = 0.94f),
        exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.97f),
    ) {
        Surface(
            modifier =
                Modifier
                    .border(
                        width = HOLD_SPEED_BORDER_WIDTH,
                        color = Color.White.copy(alpha = 0.28f),
                        shape = shape,
                    ),
            shape = shape,
            color = Color.Transparent,
            contentColor = Color.White,
            shadowElevation = HOLD_SPEED_ELEVATION,
        ) {
            Box(contentAlignment = Alignment.Center) {
                videoFrame?.let { frame ->
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize().blur(HOLD_SPEED_BLUR_RADIUS),
                    )
                }
                Box(modifier = Modifier.matchParentSize().background(materialColor.copy(alpha = 0.46f)))
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.detail_hold_double_speed, speed.toSpeedLabel()),
                    style = if (portrait) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(
                        horizontal = if (portrait) WallHubSpacing.sm else WallHubSpacing.lg,
                        vertical = if (portrait) 2.dp else WallHubSpacing.sm,
                    ),
                )
            }
        }
    }
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
        val layoutParamsParent = FrameLayout(anchor.context)
        val menuContainer =
            inflater.inflate(
                R.layout.wallhub_player_speed_popup,
                layoutParamsParent,
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
            option.contentDescription =
                anchor.context.getString(R.string.detail_playback_speed_value, option.text)
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
                    if (isMuted) R.string.detail_unmute else R.string.detail_mute,
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
    private val onActiveChanged: (Boolean, Bitmap?, Float) -> Unit,
) : View.OnTouchListener {
    private var acceleratedPlayer: Player? = null
    private var restorePlaybackParameters: PlaybackParameters? = null
    private var longPressActivated = false
    private var activeSpeed = 1f
    private val gestureDetector =
        GestureDetector(
            playerView.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onLongPress(event: MotionEvent) {
                    longPressActivated = true
                    startAcceleratedSpeed()
                }
            },
        )

    override fun onTouch(
        view: View,
        event: MotionEvent,
    ): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> longPressActivated = false
            MotionEvent.ACTION_UP -> {
                restoreSpeed()
                if (!longPressActivated) view.performClick()
            }
            MotionEvent.ACTION_CANCEL -> restoreSpeed()
        }
        return handled
    }

    private fun startAcceleratedSpeed() {
        if (acceleratedPlayer != null) return
        val player = playerView.player ?: return
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return
        restorePlaybackParameters = player.playbackParameters
        acceleratedPlayer = player
        activeSpeed = (player.playbackParameters.speed + 1f).coerceAtMost(MAX_HOLD_SPEED)
        player.setPlaybackParameters(player.playbackParameters.withSpeed(activeSpeed))
        vibrate()
        onActiveChanged(true, captureVideoFrame(), activeSpeed)
    }

    private fun restoreSpeed() {
        val player = acceleratedPlayer ?: return
        val parameters = restorePlaybackParameters
        if (parameters != null && player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            player.setPlaybackParameters(parameters)
        }
        acceleratedPlayer = null
        restorePlaybackParameters = null
        onActiveChanged(false, null, restorePlaybackParameters?.speed ?: 1f)
    }

    private fun vibrate() {
        val context = playerView.context
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE),
                )
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(45L)
            }
        }
    }

    private fun captureVideoFrame(): Bitmap? {
        val textureView = playerView.videoSurfaceView as? TextureView ?: return null
        if (!textureView.isAvailable) return null
        return runCatching {
            textureView.getBitmap(HOLD_SPEED_FRAME_WIDTH_PX, HOLD_SPEED_FRAME_HEIGHT_PX)
        }.getOrNull()
    }

    fun release() {
        restoreSpeed()
    }
}

private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
private const val SPEED_EPSILON = 0.01f
private const val SPEED_POPUP_GAP_DP = 4f
private const val SPEED_POPUP_ELEVATION_DP = 12f
private const val VOLUME_STEPS = 100
private const val MUTED_VOLUME_THRESHOLD = 0.01f
private const val DEFAULT_UNMUTE_VOLUME = 0.5f
private const val DISABLED_CONTROL_ALPHA = 0.42f
private const val VOLUME_BAR_MIN_WIDTH_DP = 480f
private val HOLD_SPEED_TOP_PADDING = 20.dp
private val HOLD_SPEED_CORNER_RADIUS = 24.dp
private val HOLD_SPEED_BLUR_RADIUS = 20.dp
private val HOLD_SPEED_BORDER_WIDTH = 1.dp
private val HOLD_SPEED_ELEVATION = 6.dp
private const val HOLD_SPEED_FRAME_WIDTH_PX = 240
private const val HOLD_SPEED_FRAME_HEIGHT_PX = 135
private const val MAX_HOLD_SPEED = 4f
