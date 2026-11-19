@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.detail

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.wallhub.android.R
import com.wallhub.android.app.DebugVideoCommandBus
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.model.WorkshopVideoCdnProvider
import com.wallhub.android.core.model.WorkshopVideoReadDemand
import com.wallhub.android.core.model.WorkshopVideoReadKind
import com.wallhub.android.core.model.WorkshopVideoStreamRepository
import com.wallhub.android.core.model.WorkshopVideoStreamSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import javax.inject.Inject
import kotlin.math.min
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow

data class OnlineVideoPlayerUiState(
    val title: String = "",
    val stream: WorkshopVideoStreamSession? = null,
    val isLoading: Boolean = true,
    val error: DetailUiText? = null,
    val resumePositionMs: Long = 0L,
)

@HiltViewModel
class OnlineVideoPlayerViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val videoStreamRepository: WorkshopVideoStreamRepository,
    ) : ViewModel() {
        private val workshopId = checkNotNull(savedStateHandle.get<Long>("workshopId"))
        private val mutableState = MutableStateFlow(OnlineVideoPlayerUiState())
        private val playbackStartNoticeGate = PlaybackStartNoticeGate()
        private var loadJob: Job? = null
        private var loadGeneration = 0L

        val uiState: StateFlow<OnlineVideoPlayerUiState> = mutableState.asStateFlow()

        fun consumePlaybackStartNotice(): Boolean = playbackStartNoticeGate.consume()

        init {
            load()
        }

        fun load(resumePositionMs: Long = 0L) {
            val generation = ++loadGeneration
            loadJob?.cancel()
            val previousStream = mutableState.value.stream
            mutableState.value = OnlineVideoPlayerUiState(isLoading = true, resumePositionMs = resumePositionMs)
            previousStream?.close()
            loadJob = viewModelScope.launch {
                var openedStream: WorkshopVideoStreamSession? = null
                try {
                    openedStream = videoStreamRepository.open(workshopId)
                    coroutineContext.ensureActive()
                    if (generation != loadGeneration) return@launch
                    mutableState.value =
                        OnlineVideoPlayerUiState(
                            title = openedStream.title,
                            stream = openedStream,
                            isLoading = false,
                            resumePositionMs = resumePositionMs,
                        )
                    openedStream = null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: VirtualMachineError) {
                    throw error
                } catch (error: Throwable) {
                    if (generation == loadGeneration) {
                        mutableState.value =
                            OnlineVideoPlayerUiState(
                                isLoading = false,
                                error = error.toDetailUiText(R.string.detail_online_video_init_failed),
                                resumePositionMs = resumePositionMs,
                            )
                    }
                } finally {
                    openedStream?.close()
                }
            }
        }

        override fun onCleared() {
            loadJob?.cancel()
            mutableState.value.stream?.close()
            super.onCleared()
        }
    }

@Composable
fun OnlineVideoPlayerRoute(
    onBack: () -> Unit,
    onFullscreenPlaybackChanged: (Boolean) -> Unit = {},
    viewModel: OnlineVideoPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(fullscreen) { onFullscreenPlaybackChanged(fullscreen) }
    DisposableEffect(Unit) {
        onDispose { onFullscreenPlaybackChanged(false) }
    }
    OnlineVideoPlayerScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::load,
        fullscreen = fullscreen,
        onFullscreenChange = { fullscreen = it },
        shouldShowPlaybackStartNotice = viewModel::consumePlaybackStartNotice,
    )
}

@Composable
fun OnlineVideoPlayerScreen(
    state: OnlineVideoPlayerUiState,
    onBack: () -> Unit,
    onRetry: (Long) -> Unit,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    shouldShowPlaybackStartNotice: () -> Boolean = { true },
) {
    val hapticFeedback = LocalHapticFeedback.current
    var playbackHapticDelivered by remember(state.stream) { mutableStateOf(false) }
    val recoveryBudget =
        rememberSaveable(saver = StreamRecoveryBudgetSaver) {
            StreamRecoveryBudget()
        }
    val context = LocalContext.current
    val streamingStartedMessage = stringResource(R.string.detail_streaming_started)
    val steamCdnMessageTemplate = stringResource(R.string.detail_steam_cdn, "%s")
    val onFirstFrameRendered = {
        recoveryBudget.onFirstFrameRendered()
        if (!playbackHapticDelivered) {
            playbackHapticDelivered = true
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        if (shouldShowPlaybackStartNotice()) {
            Toast.makeText(
                context.applicationContext,
                state.stream?.currentCdnHost?.let { host -> steamCdnMessageTemplate.format(host) }
                    ?: streamingStartedMessage,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val playback =
        state.stream?.let { stream ->
            rememberSteamChunkPlayback(
                stream = stream,
                startPositionMs = state.resumePositionMs,
                onFirstFrameRendered = onFirstFrameRendered,
            )
        }
    LaunchedEffect(state.stream, playback?.rawError) {
        val failedPlayback = playback ?: return@LaunchedEffect
        val playerError = failedPlayback.rawError ?: return@LaunchedEffect
        if (!recoveryBudget.tryAcquire(StreamPlaybackPath.FULL_SCREEN, playerError)) return@LaunchedEffect
        val resumePositionMs = failedPlayback.player.currentPosition.coerceAtLeast(0L)
        // Typed SDK session/route failures replace the stream immediately. Content, decoder and untyped transport
        // failures keep their raw Media3 outcome and never spend or trigger this app-owned retry budget.
        onRetry(resumePositionMs)
    }
    BackHandler(enabled = fullscreen) { onFullscreenChange(false) }
    FullscreenSystemBarsEffect(enabled = fullscreen)
    LaunchedEffect(state.stream) {
        if (state.stream == null) onFullscreenChange(false)
    }
    AnimatedContent(
        targetState = fullscreen && playback != null,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
        label = "OnlinePlayerFullscreen",
    ) { showFullscreen ->
        if (showFullscreen && playback != null) {
            SteamChunkVideoPlayer(
                playback = playback,
                fullscreen = true,
                onFullscreenChange = onFullscreenChange,
                onRetry = {
                    recoveryBudget.resetForManualRetry()
                    onRetry(playback.player.currentPosition.coerceAtLeast(0L))
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            WallHubPageScaffold(
                title = state.title.ifBlank { stringResource(R.string.detail_online_video_player) },
                showBackButton = true,
                onNavigateUp = onBack,
            ) { padding ->
                when {
                    state.isLoading -> {
                        PlayerLoadingIndicator(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(padding),
                        )
                    }

                    state.error != null -> {
                        WallHubEmptyState(
                            icon = Icons.Outlined.PlayArrow,
                            title = state.error.resolve(),
                            actionLabel = stringResource(R.string.detail_retry),
                            onAction = {
                                recoveryBudget.resetForManualRetry()
                                onRetry(state.resumePositionMs)
                            },
                            modifier = Modifier.fillMaxSize().then(Modifier.padding(padding)),
                        )
                    }

                    playback != null ->
                        SteamChunkVideoPlayer(
                            playback = playback,
                            fullscreen = false,
                            onFullscreenChange = onFullscreenChange,
                            onRetry = {
                                recoveryBudget.resetForManualRetry()
                                onRetry(playback.player.currentPosition.coerceAtLeast(0L))
                            },
                            modifier = Modifier.fillMaxSize().then(Modifier.padding(padding)),
                        )
                }
            }
        }
    }
}

@Composable
internal fun rememberSteamChunkPlayback(
    stream: WorkshopVideoStreamSession,
    startPositionMs: Long = 0L,
    onFirstFrameRendered: () -> Unit,
): SteamChunkPlayback {
    val context = LocalContext.current
    val player =
        remember(stream) {
            createSteamChunkPlayer(context = context, stream = stream, startPositionMs = startPositionMs)
        }
    return rememberSteamChunkPlaybackState(
        stream = stream,
        player = player,
        onFirstFrameRendered = onFirstFrameRendered,
        releasePlayerWhenDisposed = true,
    )
}

/** Binds a ViewModel-owned player to Compose without releasing it during a configuration change. */
@Composable
internal fun rememberRetainedSteamChunkPlayback(
    stream: WorkshopVideoStreamSession,
    player: ExoPlayer,
    onFirstFrameRendered: () -> Unit,
): SteamChunkPlayback =
    rememberSteamChunkPlaybackState(
        stream = stream,
        player = player,
        onFirstFrameRendered = onFirstFrameRendered,
        releasePlayerWhenDisposed = false,
    )

internal fun createSteamChunkPlayer(
    context: Context,
    stream: WorkshopVideoStreamSession,
    startPositionMs: Long = 0L,
): ExoPlayer =
    ExoPlayer
        .Builder(context.applicationContext)
        .setRenderersFactory(
            DefaultRenderersFactory(context.applicationContext)
                .setEnableDecoderFallback(true),
        )
        .setLoadControl(createSteamStreamingLoadControl())
        .build()
        .also { player ->
            val demandFeedback = PlayerDemandFeedback(player, stream.contentLength)
            player.addListener(demandFeedback)
            val mediaItem = MediaItem.fromUri(Uri.parse("steamchunk://${stream.fileName}"))
            val mediaSource =
                ProgressiveMediaSource
                    .Factory(SteamChunkDataSourceFactory(stream, demandFeedback))
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(STREAM_LOAD_RETRY_COUNT))
                    .createMediaSource(mediaItem)
            player.setMediaSource(mediaSource)
            if (startPositionMs > 0L) player.seekTo(startPositionMs)
            player.prepare()
            // Start as soon as Media3 has its small decode-ready window. Steam
            // continues filling the larger disk buffer while playback advances.
            player.playWhenReady = true
        }

internal fun createSteamStreamingLoadControl(): DefaultLoadControl =
    DefaultLoadControl
        .Builder()
        .setBufferDurationsMsForStreaming(
            STREAM_MIN_BUFFER_MS,
            STREAM_MAX_BUFFER_MS,
            STREAM_BUFFER_FOR_PLAYBACK_MS,
            STREAM_BUFFER_AFTER_REBUFFER_MS,
        )
        .setPrioritizeTimeOverSizeThresholdsForStreaming(true)
        .build()

@Composable
private fun rememberSteamChunkPlaybackState(
    stream: WorkshopVideoStreamSession,
    player: ExoPlayer,
    onFirstFrameRendered: () -> Unit,
    releasePlayerWhenDisposed: Boolean,
): SteamChunkPlayback {
    var renderedFirstFrame by remember(stream, player) {
        mutableStateOf(player.videoSize.width > 0 && player.videoSize.height > 0)
    }
    var rawPlaybackError by remember(stream, player) {
        mutableStateOf<PlaybackException?>(player.playerError)
    }
    var playbackError by remember(stream, player) {
        mutableStateOf(player.playerError?.toDetailUiText(R.string.detail_online_playback_failed))
    }
    var firstFrameCallbackDelivered by remember(stream, player) { mutableStateOf(false) }
    val onFirstFrameRenderedState by rememberUpdatedState(onFirstFrameRendered)
    DisposableEffect(player, stream) {
        val playbackCreatedAtMs = android.os.SystemClock.elapsedRealtime()
        var playbackStarted = renderedFirstFrame
        var rebufferStartedAtMs = 0L
        var rebufferCount = 0
        var totalRebufferMs = 0L
        val listener =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    renderedFirstFrame = true
                    playbackStarted = true
                    Log.i(
                        STREAM_PLAYER_LOG_TAG,
                        "firstFrameMs=${android.os.SystemClock.elapsedRealtime() - playbackCreatedAtMs} " +
                            "positionMs=${player.currentPosition}",
                    )
                    if (!firstFrameCallbackDelivered) {
                        firstFrameCallbackDelivered = true
                        onFirstFrameRenderedState()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    rawPlaybackError = error
                    playbackError = error.toDetailUiText(R.string.detail_online_playback_failed)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    val bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
                    when {
                        playbackState == Player.STATE_BUFFERING && playbackStarted && rebufferStartedAtMs == 0L -> {
                            rebufferStartedAtMs = nowMs
                            rebufferCount += 1
                            stream.reportPlaybackRebuffer()
                            Log.i(
                                STREAM_PLAYER_LOG_TAG,
                                "rebufferStart count=$rebufferCount positionMs=${player.currentPosition} " +
                                    "bufferedMs=$bufferedMs",
                            )
                        }

                        playbackState == Player.STATE_READY && rebufferStartedAtMs > 0L -> {
                            val rebufferMs = nowMs - rebufferStartedAtMs
                            totalRebufferMs += rebufferMs
                            rebufferStartedAtMs = 0L
                            Log.i(
                                STREAM_PLAYER_LOG_TAG,
                                "rebufferRecovered count=$rebufferCount rebufferMs=$rebufferMs " +
                                    "totalRebufferMs=$totalRebufferMs positionMs=${player.currentPosition} " +
                                    "bufferedMs=$bufferedMs",
                            )
                        }
                    }
                    Log.d(
                        STREAM_PLAYER_LOG_TAG,
                        "state=$playbackState positionMs=${player.currentPosition} bufferedMs=$bufferedMs " +
                            "rebufferCount=$rebufferCount totalRebufferMs=$totalRebufferMs",
                    )
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    StreamTelemetry.player(
                        stream = stream,
                        event = "position_discontinuity",
                        fields = mapOf("reason" to reason, "position_ms" to newPosition.positionMs),
                    )
                }
            }
        val analyticsListener =
            object : AnalyticsListener {
                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime,
                    droppedFrames: Int,
                    elapsedMs: Long,
                ) {
                    Log.w(
                        STREAM_PLAYER_LOG_TAG,
                        "droppedFrames=$droppedFrames elapsedMs=$elapsedMs " +
                            "positionMs=${player.currentPosition}",
                    )
                }
            }
        player.addListener(listener)
        player.addAnalyticsListener(analyticsListener)
        StreamTelemetry.lifecycle(stream, "player_attached")
        onDispose {
            player.removeListener(listener)
            player.removeAnalyticsListener(analyticsListener)
            val unfinishedRebufferMs =
                rebufferStartedAtMs.takeIf { it > 0L }?.let { android.os.SystemClock.elapsedRealtime() - it } ?: 0L
            Log.i(
                STREAM_PLAYER_LOG_TAG,
                "sessionEnd rebufferCount=$rebufferCount totalRebufferMs=${totalRebufferMs + unfinishedRebufferMs} " +
                    "positionMs=${player.currentPosition}",
            )
            if (releasePlayerWhenDisposed) player.release()
        }
    }
    return SteamChunkPlayback(
        player = player,
        renderedFirstFrame = renderedFirstFrame,
        error = playbackError,
        rawError = rawPlaybackError,
        debugSeek = player.debugSeekSeam(stream),
    )
}

internal data class SteamChunkPlayback(
    val player: ExoPlayer,
    val renderedFirstFrame: Boolean,
    val error: DetailUiText?,
    val rawError: PlaybackException?,
    val debugSeek: WorkshopVideoDebugSeek,
)

/** Programmatic seek seam for tests and debug tooling; no gesture or ADB dependency. */
internal fun interface WorkshopVideoDebugSeek {
    fun seekTo(positionMs: Long)
}

internal fun ExoPlayer.debugSeekSeam(stream: WorkshopVideoStreamSession): WorkshopVideoDebugSeek =
    WorkshopVideoDebugSeek { positionMs ->
        val safePosition = positionMs.coerceAtLeast(0L)
        StreamTelemetry.player(stream, "debug_seek", mapOf("position_ms" to safePosition))
        seekTo(safePosition)
    }

@Composable
internal fun SteamChunkVideoPlayer(
    playback: SteamChunkPlayback,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    inlineCornerRadius: androidx.compose.ui.unit.Dp? = null,
) {
    LaunchedEffect(playback) {
        DebugVideoCommandBus.seekPositionMs.collect { positionMs ->
            playback.debugSeek.seekTo(positionMs)
        }
    }
    Box(modifier = modifier) {
        ComposeMedia3Player(
            player = playback.player,
            fullscreen = fullscreen,
            onFullscreenChange = onFullscreenChange,
            modifier = Modifier.fillMaxSize(),
            inlineCornerRadius = inlineCornerRadius,
        )
        AnimatedVisibility(
            visible = !playback.renderedFirstFrame && playback.error == null,
            enter =
                fadeIn(tween(160)) +
                    scaleIn(
                        initialScale = 0.94f,
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                    ),
            exit =
                fadeOut(tween(160)) +
                    scaleOut(
                        targetScale = 0.98f,
                        animationSpec = tween(160, easing = FastOutSlowInEasing),
                    ),
            modifier = Modifier.fillMaxSize(),
        ) {
            PlayerLoadingIndicator(
                modifier = Modifier.fillMaxSize(),
            )
        }
        playback.error?.let { error ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(WallHubSpacing.md),
                ) {
                    Text(text = error.resolve())
                    onRetry?.let { retry ->
                        TextButton(onClick = retry) { Text(stringResource(R.string.detail_retry)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerLoadingIndicator(
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(WallHubSizeTokens.compactIconButton))
            Text(
                text = stringResource(R.string.detail_preparing_video),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = WallHubSpacing.sm),
            )
        }
    }
}

@Composable
internal fun FullscreenSystemBarsEffect(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    val window = activity?.window
    var savedSnapshot by rememberSaveable { mutableStateOf<IntArray?>(null) }

    LaunchedEffect(enabled, activity, window) {
        if (activity == null || window == null) return@LaunchedEffect
        if (enabled) {
            val decor = window.decorView as? ViewGroup ?: return@LaunchedEffect
            val controller = WindowCompat.getInsetsController(window, decor)
            if (savedSnapshot == null) {
                val insets = awaitRootWindowInsets(decor)
                savedSnapshot =
                    FullscreenWindowSnapshot(
                        orientation = activity.requestedOrientation,
                        behavior = controller.systemBarsBehavior,
                        statusVisible = insets.isVisible(WindowInsetsCompat.Type.statusBars()),
                        navigationVisible = insets.isVisible(WindowInsetsCompat.Type.navigationBars()),
                    ).toSavedState()
            }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        } else {
            savedSnapshot?.let { restoreWindowAfterFullscreen(activity, window, fullscreenSnapshotFromSavedState(it)) }
            savedSnapshot = null
        }
    }

    DisposableEffect(activity, window) {
        onDispose {
            if (activity != null && window != null && !activity.isChangingConfigurations) {
                savedSnapshot?.let {
                    restoreWindowAfterFullscreen(activity, window, fullscreenSnapshotFromSavedState(it))
                }
                savedSnapshot = null
            }
        }
    }
}

private data class FullscreenWindowSnapshot(
    val orientation: Int,
    val behavior: Int,
    val statusVisible: Boolean,
    val navigationVisible: Boolean,
) {
    fun toSavedState(): IntArray =
        intArrayOf(orientation, behavior, if (statusVisible) 1 else 0, if (navigationVisible) 1 else 0)
}

private fun fullscreenSnapshotFromSavedState(saved: IntArray): FullscreenWindowSnapshot =
    FullscreenWindowSnapshot(
        orientation = saved[0],
        behavior = saved[1],
        statusVisible = saved[2] != 0,
        navigationVisible = saved[3] != 0,
    )

private suspend fun awaitRootWindowInsets(decor: ViewGroup): WindowInsetsCompat {
    ViewCompat.getRootWindowInsets(decor)?.let { return it }
    val probe = View(decor.context)
    try {
        return suspendCancellableCoroutine { continuation ->
            ViewCompat.setOnApplyWindowInsetsListener(probe) { _, insets ->
                ViewCompat.getRootWindowInsets(decor)?.let { root ->
                    probe.post {
                        if (continuation.isActive) continuation.resume(root)
                    }
                }
                insets
            }
            probe.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        ViewCompat.requestApplyInsets(view)
                    }

                    override fun onViewDetachedFromWindow(view: View) = Unit
                },
            )
            decor.addView(probe, 0, ViewGroup.LayoutParams(0, 0))
            ViewCompat.getRootWindowInsets(decor)?.let { root ->
                if (continuation.isActive) continuation.resume(root)
            }
        }
    } finally {
        ViewCompat.setOnApplyWindowInsetsListener(probe, null)
        decor.removeView(probe)
    }
}

private fun restoreWindowAfterFullscreen(
    activity: Activity,
    window: Window,
    snapshot: FullscreenWindowSnapshot,
) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior = snapshot.behavior
    if (snapshot.statusVisible) controller.show(WindowInsetsCompat.Type.statusBars())
    else controller.hide(WindowInsetsCompat.Type.statusBars())
    if (snapshot.navigationVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
    else controller.hide(WindowInsetsCompat.Type.navigationBars())
    activity.requestedOrientation = snapshot.orientation
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

internal class PlayerDemandFeedback(
    private val player: Player,
    private val contentLength: Long,
) : Player.Listener {
    private val bufferedBytes = AtomicReference(0L)
    private val deliveredBytesPerSecond = AtomicReference(0L)
    private val lastSampleAtMs = AtomicReference(0L)

    override fun onIsLoadingChanged(isLoading: Boolean) {
        update()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        update()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        update()
    }

    private fun update() {
        val bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
        val durationMs = player.duration
        bufferedBytes.set(
            if (durationMs > 0L && contentLength > 0L) {
                (bufferedMs * contentLength / durationMs).coerceIn(0L, contentLength)
            } else {
                0L
            },
        )
    }

    fun bufferedBytes(): Long = bufferedBytes.get()

    fun deliveredBytesPerSecond(): Long = deliveredBytesPerSecond.get()

    fun recordDelivered(bytes: Int, elapsedMs: Long, nowMs: Long) {
        if (bytes <= 0 || elapsedMs < MIN_RATE_SAMPLE_MS) return
        val previousAt = lastSampleAtMs.getAndSet(nowMs)
        val sampleBytesPerSecond = (bytes.toLong() * 1_000L / elapsedMs).coerceAtLeast(1L)
        val previous = deliveredBytesPerSecond.get()
        deliveredBytesPerSecond.set(
            if (previousAt == 0L || nowMs - previousAt > THROUGHPUT_STALE_MS) {
                sampleBytesPerSecond
            } else {
                (previous * 3L + sampleBytesPerSecond) / 4L
            },
        )
    }

    private companion object {
        const val MIN_RATE_SAMPLE_MS = 25L
        const val THROUGHPUT_STALE_MS = 5_000L
    }
}

internal class SteamChunkDataSourceFactory(
    private val stream: WorkshopVideoStreamSession,
    private val feedback: PlayerDemandFeedback? = null,
) : DataSource.Factory {
    private val cursor = StreamByteCursor()

    override fun createDataSource(): DataSource = SteamChunkDataSource(stream, cursor, feedback)
}

/** Shared across Media3 data-source instances so reopen-at-cursor is not mistaken for a seek. */
internal class StreamByteCursor {
    private var expectedPosition: Long? = null

    @Synchronized
    fun classifyOpen(position: Long): WorkshopVideoReadKind {
        val kind =
            if (expectedPosition == null || expectedPosition == position) {
                WorkshopVideoReadKind.SEQUENTIAL
            } else {
                WorkshopVideoReadKind.SEEK
            }
        expectedPosition = position
        return kind
    }

    @Synchronized
    fun advance(position: Long) {
        expectedPosition = position
    }
}

internal class StreamDemandThrottle(
    private val byteInterval: Long = 256L * 1024L,
    private val timeIntervalMs: Long = 250L,
) {
    private var lastPosition = Long.MIN_VALUE
    private var lastAtMs = Long.MIN_VALUE

    @Synchronized
    fun shouldPublish(position: Long, nowMs: Long, force: Boolean = false): Boolean {
        val byteAdvance = if (lastPosition == Long.MIN_VALUE) Long.MAX_VALUE else position - lastPosition
        val elapsedMs = if (lastAtMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - lastAtMs
        if (!force && byteAdvance < byteInterval && elapsedMs < timeIntervalMs) return false
        lastPosition = position
        lastAtMs = nowMs
        return true
    }
}

internal class SteamChunkDataSource(
    private val stream: WorkshopVideoStreamSession,
    private val cursor: StreamByteCursor = StreamByteCursor(),
    private val feedback: PlayerDemandFeedback? = null,
    private val clockMs: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val demandThrottle: StreamDemandThrottle = StreamDemandThrottle(),
) : BaseDataSource(false) {
    private var currentUri: Uri? = null
    private var readPosition = 0L
    private var bytesRemaining = 0L
    private var opened = false
    private var lastReadLogPosition = Long.MIN_VALUE

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        check(dataSpec.position in 0..stream.contentLength) { "Invalid video read position" }
        currentUri = dataSpec.uri
        readPosition = dataSpec.position
        val available = stream.contentLength - readPosition
        bytesRemaining =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                available
            } else {
                min(available, dataSpec.length)
            }
        val kind = cursor.classifyOpen(readPosition)
        publishDemand(readPosition, 0L, kind, force = true)
        StreamTelemetry.data(
            stream,
            "open",
            mapOf("position" to readPosition, "length" to bytesRemaining, "kind" to kind.name),
        )
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val requestedLength = min(length.toLong(), min(bytesRemaining, STREAM_READ_WINDOW_BYTES)).toInt()
        publishDemand(
            position = readPosition,
            bufferedBytes = feedback?.bufferedBytes() ?: requestedLength.toLong(),
            kind = WorkshopVideoReadKind.SEQUENTIAL,
        )
        val readStartedMs = android.os.SystemClock.elapsedRealtime()
        val read =
            try {
                stream.readAtBlocking(
                    position = readPosition,
                    destination = buffer,
                    destinationOffset = offset,
                    length = requestedLength,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                StreamTelemetry.data(
                    stream,
                    "read_failed",
                    mapOf(
                        "position" to readPosition,
                        "failure_type" to "io",
                        "elapsed_ms" to (android.os.SystemClock.elapsedRealtime() - readStartedMs),
                    ),
                )
                // IOException is the only retry-compatible type exposed at this boundary. Do not label it network.
                throw error
            } catch (error: VirtualMachineError) {
                throw error
            } catch (error: Throwable) {
                StreamTelemetry.data(
                    stream,
                    "read_failed",
                    mapOf(
                        "position" to readPosition,
                        "failure_type" to "unexpected",
                        "elapsed_ms" to (android.os.SystemClock.elapsedRealtime() - readStartedMs),
                    ),
                )
                throw IOException("Steam video chunk read failed", error)
            }
        if (read == 0) return C.RESULT_END_OF_INPUT
        readPosition += read
        bytesRemaining -= read
        feedback?.recordDelivered(
            bytes = read,
            elapsedMs = android.os.SystemClock.elapsedRealtime() - readStartedMs,
            nowMs = android.os.SystemClock.elapsedRealtime(),
        )
        cursor.advance(readPosition)
        bytesTransferred(read)
        if (lastReadLogPosition == Long.MIN_VALUE || readPosition - lastReadLogPosition >= STREAM_LOG_INTERVAL_BYTES) {
            lastReadLogPosition = readPosition
            StreamTelemetry.data(
                stream,
                "read",
                mapOf(
                    "position" to readPosition,
                    "bytes" to read,
                    "elapsed_ms" to (android.os.SystemClock.elapsedRealtime() - readStartedMs),
                ),
            )
        }
        return read
    }

    private fun publishDemand(
        position: Long,
        bufferedBytes: Long,
        kind: WorkshopVideoReadKind,
        force: Boolean = false,
    ) {
        if (!demandThrottle.shouldPublish(position, clockMs(), force)) return
        stream.updateReadDemand(
            WorkshopVideoReadDemand(
                position = position,
                bufferedBytes = bufferedBytes,
                kind = kind,
                deliveredBytesPerSecond = feedback?.deliveredBytesPerSecond() ?: 0L,
            ),
        )
        StreamTelemetry.data(
            stream,
            "demand",
            mapOf("position" to position, "bytes" to bufferedBytes, "kind" to kind.name),
        )
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        if (opened) {
            opened = false
            StreamTelemetry.lifecycle(stream, "data_source_closed")
            transferEnded()
        }
    }

    private companion object {
        const val STREAM_READ_WINDOW_BYTES = 512L * 1024L
        const val STREAM_LOG_INTERVAL_BYTES = 1024L * 1024L
    }
}

/** Bounded structured telemetry: correlation IDs and numeric/enumerated fields only. */
internal object StreamTelemetry {
    private val nextId = java.util.concurrent.atomic.AtomicLong(1L)
    private val ids = java.util.WeakHashMap<WorkshopVideoStreamSession, Long>()

    fun data(stream: WorkshopVideoStreamSession, event: String, fields: Map<String, Any> = emptyMap()) =
        record(stream, "stream", event, fields)

    fun player(stream: WorkshopVideoStreamSession, event: String, fields: Map<String, Any> = emptyMap()) =
        record(stream, "player", event, fields)

    fun lifecycle(stream: WorkshopVideoStreamSession, event: String) = record(stream, "lifecycle", event, emptyMap())

    private fun record(
        stream: WorkshopVideoStreamSession,
        area: String,
        event: String,
        fields: Map<String, Any>,
    ) {
        val correlationId = synchronized(ids) { ids.getOrPut(stream) { nextId.getAndIncrement() } }
        val safeFields =
            fields.entries.take(MAX_FIELDS).joinToString(separator = " ") { (key, value) ->
                val safeKey = key.filter { it.isLetterOrDigit() || it == '_' }.take(MAX_FIELD_CHARS)
                val safeValue = value.toString().filter { it.isLetterOrDigit() || it in "_-" }.take(MAX_FIELD_CHARS)
                "$safeKey=$safeValue"
            }
        Log.d(
            STREAM_PLAYER_LOG_TAG,
            "stream_id=$correlationId area=$area event=${event.take(MAX_FIELD_CHARS)}" +
                if (safeFields.isEmpty()) "" else " $safeFields",
        )
    }

    private const val MAX_FIELDS = 8
    private const val MAX_FIELD_CHARS = 48
}

internal fun cdnProviderLabel(context: Context, provider: WorkshopVideoCdnProvider): String =
    context.getString(
        when (provider) {
            WorkshopVideoCdnProvider.VALVE -> R.string.detail_cdn_provider_valve
            WorkshopVideoCdnProvider.STEAM_CHINA -> R.string.detail_cdn_provider_steam_china
            WorkshopVideoCdnProvider.HUAWEI_CLOUD -> R.string.detail_cdn_provider_huawei
            WorkshopVideoCdnProvider.BAISHAN_CLOUD -> R.string.detail_cdn_provider_baishan
            WorkshopVideoCdnProvider.TENCENT_CLOUD -> R.string.detail_cdn_provider_tencent
            WorkshopVideoCdnProvider.ALIBABA_CLOUD -> R.string.detail_cdn_provider_alibaba
            WorkshopVideoCdnProvider.NETEASE_CLOUD -> R.string.detail_cdn_provider_netease
            WorkshopVideoCdnProvider.CHINA_NET_CENTER -> R.string.detail_cdn_provider_chinanetcenter
            WorkshopVideoCdnProvider.FASTLY -> R.string.detail_cdn_provider_fastly
            WorkshopVideoCdnProvider.EDGECAST -> R.string.detail_cdn_provider_edgecast
            WorkshopVideoCdnProvider.SINA_CLOUD -> R.string.detail_cdn_provider_sina
            WorkshopVideoCdnProvider.OTHER -> R.string.detail_cdn_provider_other
        },
    )

private const val STREAM_LOAD_RETRY_COUNT = 0
private const val STREAM_PLAYER_LOG_TAG = "WallHubStreamPlayer"
private const val STREAM_MIN_BUFFER_MS = 15_000
private const val STREAM_MAX_BUFFER_MS = 30_000
private const val STREAM_BUFFER_FOR_PLAYBACK_MS = 3_000
private const val STREAM_BUFFER_AFTER_REBUFFER_MS = 15_000
