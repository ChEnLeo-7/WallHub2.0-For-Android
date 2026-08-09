package com.wallhub.android.feature.detail

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubEmptyState
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.model.DownloadTaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh

data class LocalVideoPlayerUiState(
    val title: String = "",
    val videoPath: String? = null,
    val isLoading: Boolean = true,
    val error: DetailUiText? = null,
)

@HiltViewModel
class LocalVideoPlayerViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val downloadTaskRepository: DownloadTaskRepository,
    ) : ViewModel() {
        private val taskId = checkNotNull(savedStateHandle.get<String>("taskId"))
        private val mutableState = MutableStateFlow(LocalVideoPlayerUiState())

        val uiState: StateFlow<LocalVideoPlayerUiState> = mutableState.asStateFlow()

        init {
            load()
        }

        fun load() {
            viewModelScope.launch {
                mutableState.value = mutableState.value.copy(isLoading = true, error = null)
                runCatching {
                    val task =
                        downloadTaskRepository.find(taskId)
                            ?: throw DetailUiTextException(R.string.detail_download_task_missing)
                    val root =
                        task.stagingDirectory
                            ?.let(::File)
                            ?.takeIf(File::isDirectory)
                            ?: throw DetailUiTextException(R.string.detail_download_staging_missing)
                    val video =
                        resolveVideoFile(root)
                            ?: throw DetailUiTextException(R.string.detail_playable_video_missing)
                    task.title to video.absolutePath
                }.onSuccess { (title, path) ->
                    mutableState.value =
                        LocalVideoPlayerUiState(
                            title = title,
                            videoPath = path,
                            isLoading = false,
                        )
                }.onFailure { error ->
                    mutableState.value =
                        LocalVideoPlayerUiState(
                            isLoading = false,
                            error = error.toDetailUiText(R.string.detail_local_video_prepare_failed),
                        )
                }
            }
        }
    }

@Composable
fun LocalVideoPlayerRoute(
    onBack: () -> Unit,
    viewModel: LocalVideoPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LocalVideoPlayerScreen(state = state, onBack = onBack, onRetry = viewModel::load)
}

@Composable
fun LocalVideoPlayerScreen(
    state: LocalVideoPlayerUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val player = state.videoPath?.let { videoPath -> rememberLocalVideoPlayer(videoPath) }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    FullscreenSystemBarsEffect(enabled = fullscreen)
    if (fullscreen && player != null) {
        ComposeMedia3Player(
            player = player,
            fullscreen = true,
            onFullscreenChange = { fullscreen = it },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    WallHubPageScaffold(
        title = state.title.ifBlank { stringResource(R.string.detail_video_player) },
        showBackButton = true,
        onNavigateUp = onBack,
    ) { padding ->
        when {
            state.isLoading ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

            state.error != null ->
                WallHubEmptyState(
                    icon = Icons.Outlined.Refresh,
                    title = state.error.resolve(),
                    actionLabel = stringResource(R.string.detail_retry),
                    onAction = onRetry,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )

            player != null ->
                LocalVideoPlayer(
                    player = player,
                    fullscreen = false,
                    onFullscreenChange = { fullscreen = it },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
        }
    }
}

@Composable
private fun rememberLocalVideoPlayer(videoPath: String): ExoPlayer {
    val context = LocalContext.current
    val player = remember(videoPath) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(videoPath, player) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(videoPath))))
        player.prepare()
    }
    return player
}

@Composable
private fun LocalVideoPlayer(
    player: ExoPlayer,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ComposeMedia3Player(
        player = player,
        fullscreen = fullscreen,
        onFullscreenChange = onFullscreenChange,
        modifier = modifier,
    )
}

private fun resolveVideoFile(root: File): File? {
    val declared =
        File(root, "project.json")
            .takeIf(File::isFile)
            ?.readText()
            ?.let { content -> Regex("\\\"file\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(content)?.groupValues?.getOrNull(1) }
            ?.let { relativePath -> File(root, relativePath.replace('/', File.separatorChar)) }
            ?.takeIf(File::isFile)
    if (declared != null) return declared
    return root
        .walkTopDown()
        .maxDepth(4)
        .firstOrNull { file ->
            file.isFile && file.extension.lowercase() in setOf("mp4", "webm", "mkv", "avi", "mov")
        }
}
