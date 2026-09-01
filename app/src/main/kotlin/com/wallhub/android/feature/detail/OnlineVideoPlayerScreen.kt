@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.detail

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.media3.common.Timeline
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
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.model.WorkshopVideoStreamRepository
import com.wallhub.android.core.model.WorkshopVideoStreamSession
import com.wallhub.android.core.model.WorkshopVideoFullCacheState
import com.wallhub.android.core.model.WorkshopVideoFullCacheStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.coroutines.coroutineContext
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
    viewModel: OnlineVideoPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OnlineVideoPlayerScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::load,
        shouldShowPlaybackStartNotice = viewModel::consumePlaybackStartNotice,
    )
}

@Composable
fun OnlineVideoPlayerScreen(
    state: OnlineVideoPlayerUiState,
    onBack: () -> Unit,
    onRetry: (Long) -> Unit,
    shouldShowPlaybackStartNotice: () -> Boolean = { true },
) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    var playbackHapticDelivered by remember(state.stream) { mutableStateOf(false) }
    val context = LocalContext.current
    val streamingStartedMessage = stringResource(R.string.detail_streaming_started)
    val steamCdnMessageTemplate = stringResource(R.string.detail_steam_cdn, "%s")
    val onFirstFrameRendered = {
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
    BackHandler(enabled = fullscreen) { fullscreen = false }
    FullscreenSystemBarsEffect(enabled = fullscreen)
    LaunchedEffect(state.stream) {
        if (state.stream == null) fullscreen = false
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (fullscreen && playback != null) {
            SteamChunkVideoPlayer(
                playback = playback,
                fullscreen = true,
                onFullscreenChange = { fullscreen = it },
                onRetry = { onRetry(playback.player.currentPosition.coerceAtLeast(0L)) },
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
                            onAction = { onRetry(state.resumePositionMs) },
                            modifier = Modifier.fillMaxSize().then(Modifier.padding(padding)),
                        )
                    }

                    playback != null ->
                        SteamChunkVideoPlayer(
                            playback = playback,
                            fullscreen = false,
                            onFullscreenChange = { fullscreen = it },
                            onRetry = { onRetry(playback.player.currentPosition.coerceAtLeast(0L)) },
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
            val mediaItem = MediaItem.fromUri(Uri.parse("steamchunk://${stream.fileName}"))
            val mediaSource =
                ProgressiveMediaSource
                    .Factory(SteamChunkDataSourceFactory(stream))
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
    val bufferState by stream.playbackBufferState.collectAsStateWithLifecycle()
    val fullCacheState by stream.fullCacheState.collectAsStateWithLifecycle()
    var renderedFirstFrame by remember(stream, player) {
        mutableStateOf(player.videoSize.width > 0 && player.videoSize.height > 0)
    }
    var playbackError by remember(stream, player) {
        mutableStateOf(
            player.playerError?.toDetailUiText(R.string.detail_online_playback_failed),
        )
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
                    playbackError = error.toDetailUiText(R.string.detail_online_playback_failed)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    val bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
                    when {
                        playbackState == Player.STATE_BUFFERING && playbackStarted && rebufferStartedAtMs == 0L -> {
                            rebufferStartedAtMs = nowMs
                            rebufferCount += 1
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
                    player.reportPlaybackDemand(stream)
                }

                override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                    player.reportPlaybackDemand(stream)
                }

                override fun onTimelineChanged(
                    timeline: Timeline,
                    reason: Int,
                ) {
                    player.reportPlaybackDemand(stream)
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
        player.reportPlaybackDemand(stream)
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
    LaunchedEffect(player, stream) {
        while (isActive) {
            player.reportPlaybackDemand(stream)
            delay(STREAM_DEMAND_REPORT_INTERVAL_MS)
        }
    }
    return SteamChunkPlayback(
        player = player,
        renderedFirstFrame = renderedFirstFrame,
        error = playbackError,
        bufferState = bufferState,
        fullCacheState = fullCacheState,
        onCancelCompleteFileCache = stream::cancelFullCache,
    )
}

internal data class SteamChunkPlayback(
    val player: ExoPlayer,
    val renderedFirstFrame: Boolean,
    val error: DetailUiText?,
    val bufferState: com.wallhub.android.core.model.WorkshopVideoBufferState,
    val fullCacheState: WorkshopVideoFullCacheState,
    val onCancelCompleteFileCache: () -> Unit,
)

private fun ExoPlayer.reportPlaybackDemand(stream: WorkshopVideoStreamSession) {
    stream.updatePlaybackDemand(
        playbackSpeed = playbackParameters.speed,
        playbackPositionMs = currentPosition,
        bufferedPositionMs = bufferedPosition,
        durationMs = duration,
    )
}

@Composable
internal fun SteamChunkVideoPlayer(
    playback: SteamChunkPlayback,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        ComposeMedia3Player(
            player = playback.player,
            fullscreen = fullscreen,
            onFullscreenChange = onFullscreenChange,
            modifier = Modifier.fillMaxSize(),
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
        SteamFullCacheBanner(
            playback = playback,
            visible = playback.error == null,
            modifier = Modifier.align(Alignment.TopCenter).padding(WallHubSpacing.md),
        )
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
private fun SteamFullCacheBanner(
    playback: SteamChunkPlayback,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val fullCache = playback.fullCacheState
    val show =
        fullCache.status in
            setOf(
                WorkshopVideoFullCacheStatus.CACHING,
                WorkshopVideoFullCacheStatus.COMPLETE,
                WorkshopVideoFullCacheStatus.ERROR,
            )
    if (!visible || !show) return
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(WallHubSpacing.md)) {
            Text(text = fullCacheMessage(fullCache), style = MaterialTheme.typography.bodySmall)
            when {
                fullCache.status == WorkshopVideoFullCacheStatus.CACHING -> {
                    val progress =
                        if (fullCache.totalBytes > 0L) {
                            (fullCache.cachedBytes.toFloat() / fullCache.totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = WallHubSpacing.sm),
                    )
                    TextButton(onClick = playback.onCancelCompleteFileCache) {
                        Text(stringResource(R.string.settings_action_cancel))
                    }
                }

            }
        }
    }
}

@Composable
private fun fullCacheMessage(state: WorkshopVideoFullCacheState): String =
    when (state.status) {
        WorkshopVideoFullCacheStatus.CACHING -> stringResource(R.string.detail_caching_complete_video)
        WorkshopVideoFullCacheStatus.COMPLETE -> stringResource(R.string.detail_complete_video_cached)
        WorkshopVideoFullCacheStatus.ERROR ->
            if (state.errorCode == "STREAM_FULL_CACHE_LIMIT") {
                stringResource(R.string.detail_complete_video_cache_limit)
            } else {
                stringResource(R.string.detail_complete_video_cache_failed)
            }
        else -> ""
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
    val context = LocalContext.current
    val activity = context.findActivity()
    val window = activity?.window
    var enteredFullscreen by rememberSaveable { mutableStateOf(false) }
    val latestFullscreenEnabled by rememberUpdatedState(enabled)

    LaunchedEffect(enabled, activity, window) {
        if (activity == null || window == null) return@LaunchedEffect
        if (enabled) {
            enteredFullscreen = true
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        } else if (enteredFullscreen) {
            restoreWindowAfterFullscreen(activity, window)
            enteredFullscreen = false
        }
    }

    DisposableEffect(activity) {
        onDispose {
            if (activity != null &&
                window != null &&
                latestFullscreenEnabled &&
                !activity.isChangingConfigurations
            ) {
                restoreWindowAfterFullscreen(activity, window)
                enteredFullscreen = false
            }
        }
    }
}

private fun restoreWindowAfterFullscreen(
    activity: Activity,
    window: android.view.Window,
) {
    WindowCompat
        .getInsetsController(window, window.decorView)
        .show(WindowInsetsCompat.Type.systemBars())
    WindowCompat.setDecorFitsSystemWindows(window, true)
    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

internal class SteamChunkDataSourceFactory(
    private val stream: WorkshopVideoStreamSession,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = SteamChunkDataSource(stream)
}

private class SteamChunkDataSource(
    private val stream: WorkshopVideoStreamSession,
) : BaseDataSource(false) {
    private var currentUri: Uri? = null
    private var readPosition = 0L
    private var bytesRemaining = 0L
    private var opened = false

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
        val read =
            try {
                runBlocking {
                    stream.readAt(
                        position = readPosition,
                        destination = buffer,
                        destinationOffset = offset,
                        length = requestedLength,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                throw error
            } catch (error: VirtualMachineError) {
                throw error
            } catch (error: Throwable) {
                // Media3 retries IO failures; preserve the underlying cause for diagnostics.
                throw IOException("Steam video chunk read failed", error)
            }
        if (read == 0) return C.RESULT_END_OF_INPUT
        readPosition += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private companion object {
        const val STREAM_READ_WINDOW_BYTES = 512L * 1024L
    }
}

private const val STREAM_LOAD_RETRY_COUNT = 5
private const val STREAM_DEMAND_REPORT_INTERVAL_MS = 500L
private const val STREAM_PLAYER_LOG_TAG = "WallHubStreamPlayer"
private const val STREAM_MIN_BUFFER_MS = 12_000
private const val STREAM_MAX_BUFFER_MS = 30_000
private const val STREAM_BUFFER_FOR_PLAYBACK_MS = 1_000
private const val STREAM_BUFFER_AFTER_REBUFFER_MS = 8_000
