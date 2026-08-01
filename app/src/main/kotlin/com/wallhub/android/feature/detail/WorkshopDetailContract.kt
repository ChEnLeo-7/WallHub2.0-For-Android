@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.detail

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
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
    val error: DetailUiText? = null,
    val interaction: WorkshopInteraction = WorkshopInteraction(),
    val isLoadingInteraction: Boolean = false,
    val isUpdatingInteraction: Boolean = false,
    val interactionMessage: DetailUiText? = null,
    val outputTreeUri: String? = null,
    val outputDirectoryLabel: String? = null,
    val exportFormat: ExportFormat = ExportFormat.AUTO,
    val onlineChunkPlaybackEnabled: Boolean = false,
    val isEnqueuingDownload: Boolean = false,
    val downloadMessage: DetailUiText? = null,
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
    val commentsError: DetailUiText? = null,
    val steamSession: SteamSessionState = SteamSessionState(),
    val commentDraft: String = "",
    val isPostingComment: Boolean = false,
    val commentPostError: DetailUiText? = null,
    val inlineVideoStream: WorkshopVideoStreamSession? = null,
    val inlineVideoPlayer: ExoPlayer? = null,
    val isInlineVideoFullscreen: Boolean = false,
    val isLoadingInlineVideo: Boolean = false,
    val inlineVideoError: DetailUiText? = null,
)

sealed interface DetailUiText {
    data class Resource(
        @StringRes val resourceId: Int,
        val args: List<Any> = emptyList(),
    ) : DetailUiText

    data class Dynamic(
        val value: String,
    ) : DetailUiText
}

@Composable
internal fun DetailUiText.resolve(): String =
    when (this) {
        is DetailUiText.Resource -> stringResource(resourceId, *args.toTypedArray())
        is DetailUiText.Dynamic -> value
    }

internal fun Throwable.toDetailUiText(
    @StringRes fallback: Int,
): DetailUiText =
    when (this) {
        is DetailUiTextException -> DetailUiText.Resource(resourceId)
        else -> message?.let(DetailUiText::Dynamic) ?: DetailUiText.Resource(fallback)
    }

internal class DetailUiTextException(
    @StringRes val resourceId: Int,
) : IllegalStateException()

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
        val message: DetailUiText,
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
        val message: DetailUiText,
    ) : WorkshopDetailEffect
}
