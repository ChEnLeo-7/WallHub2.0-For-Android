@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.ExoPlayer
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopVideoStreamSession

data class WorkshopDetailUiState(
    val detail: WorkshopDetail? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val interaction: WorkshopInteraction = WorkshopInteraction(),
    val isLoadingInteraction: Boolean = false,
    val isUpdatingInteraction: Boolean = false,
    val interactionMessage: String? = null,
    val outputTreeUri: String? = null,
    val outputDirectoryLabel: String? = null,
    val exportFormat: ExportFormat = ExportFormat.AUTO,
    val onlineChunkPlaybackEnabled: Boolean = false,
    val isEnqueuingDownload: Boolean = false,
    val downloadMessage: String? = null,
    val localVideoTaskId: String? = null,
    val activeVideoTaskId: String? = null,
    val waitingForLocalVideoPlayback: Boolean = false,
    val stagedTaskId: String? = null,
    val comments: List<WorkshopComment> = emptyList(),
    val commentsTotal: Int? = null,
    val commentsNextStart: Int = 0,
    val commentsHasMore: Boolean = false,
    val commentsOwnerId: String? = null,
    val isLoadingComments: Boolean = false,
    val isLoadingMoreComments: Boolean = false,
    val commentsError: String? = null,
    val steamSession: SteamSessionState = SteamSessionState(),
    val commentDraft: String = "",
    val isPostingComment: Boolean = false,
    val commentPostError: String? = null,
    val inlineVideoStream: WorkshopVideoStreamSession? = null,
    val inlineVideoPlayer: ExoPlayer? = null,
    val isInlineVideoFullscreen: Boolean = false,
    val isLoadingInlineVideo: Boolean = false,
    val inlineVideoError: String? = null,
)

sealed interface WorkshopDetailPendingOperation {
    data object Download : WorkshopDetailPendingOperation

    data object LocalVideoPlayback : WorkshopDetailPendingOperation

    data class ConvertExisting(
        val format: ExportFormat,
    ) : WorkshopDetailPendingOperation
}

sealed interface WorkshopDetailAction {
    data class RequestOperation(
        val operation: WorkshopDetailPendingOperation,
    ) : WorkshopDetailAction

    data class LegacyStoragePermissionResult(
        val operation: WorkshopDetailPendingOperation,
        val granted: Boolean,
    ) : WorkshopDetailAction

    data object Back : WorkshopDetailAction

    data class SearchAuthor(
        val author: String,
    ) : WorkshopDetailAction

    data class CopyText(
        val text: String,
        val message: String,
    ) : WorkshopDetailAction

    data class OpenSteam(
        val workshopId: Long,
    ) : WorkshopDetailAction

    data class ShowMessage(
        val message: String,
    ) : WorkshopDetailAction
}

sealed interface WorkshopDetailEffect {
    data class ResolveLegacyStoragePermission(
        val operation: WorkshopDetailPendingOperation,
    ) : WorkshopDetailEffect

    data object Back : WorkshopDetailEffect

    data class SearchAuthor(
        val author: String,
    ) : WorkshopDetailEffect

    data class CopyText(
        val text: String,
        val message: String,
    ) : WorkshopDetailEffect

    data class OpenSteam(
        val workshopId: Long,
    ) : WorkshopDetailEffect

    data class OpenLocalVideo(
        val taskId: String,
    ) : WorkshopDetailEffect

    data class ShowMessage(
        val message: String,
    ) : WorkshopDetailEffect
}
