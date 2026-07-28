package com.wallhub.android.data.steam

import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.data.steam.protobuf.CommunityMessages

internal data class SteamProfile(
    val displayName: String,
    val avatarUrl: String? = null,
)

internal fun buildUnifiedWorkshopDetailRequest(
    workshopId: Long,
): SteammessagesPublishedfileSteamclient.CPublishedFile_GetDetails_Request {
    require(workshopId > 0L) { "创意工坊项目 ID 无效" }
    return SteammessagesPublishedfileSteamclient.CPublishedFile_GetDetails_Request
        .newBuilder()
        .addPublishedfileids(workshopId)
        .setIncludetags(true)
        .setIncludeadditionalpreviews(true)
        .setIncludechildren(true)
        .setIncludekvtags(true)
        .setIncludevotes(true)
        .setIncludemetadata(true)
        .setStripDescriptionBbcode(true)
        .setAppid(WALLPAPER_ENGINE_APP_ID)
        .build()
}

internal fun mapUnifiedWorkshopDetail(
    detail: SteammessagesPublishedfileSteamclient.PublishedFileDetails,
    creatorProfile: SteamProfile? = null,
): WorkshopDetail {
    val summary = detail.toWorkshopSummary().let { source ->
        source.copy(author = creatorProfile?.displayName ?: "Steam 创作者")
    }
    val previewMediaUrl = detail.previewsList
        .asSequence()
        .map { preview -> preview.url.trim() }
        .firstOrNull(String::isNotBlank)
        ?: detail.previewUrl.trim().takeIf(String::isNotBlank)
    return WorkshopDetail(
        summary = summary,
        description = detail.fileDescription.trim().ifBlank { detail.shortDescription.trim() },
        fileSizeBytes = detail.fileSize.takeIf { it > 0L },
        previewMediaUrl = previewMediaUrl,
        createdAt = detail.timeCreated.toLong().takeIf { it > 0L },
        updatedAt = detail.timeUpdated.toLong().takeIf { it > 0L },
        subscriptions = detail.subscriptions.toLong().takeIf { it > 0L }
            ?: detail.lifetimeSubscriptions.toLong().takeIf { it > 0L },
        creatorId = detail.creator.toString().takeIf { detail.creator > 0L },
    )
}

internal fun buildCommunityCommentRequest(
    workshopId: Long,
    ownerId: String,
    start: Int,
    count: Int,
): CommunityMessages.GetCommentThreadRequest {
    require(workshopId > 0L) { "创意工坊项目 ID 无效" }
    val owner = ownerId.toULongOrNull()?.takeIf { it > 0uL }
        ?: error("创意工坊作者 ID 无效")
    return CommunityMessages.GetCommentThreadRequest.newBuilder()
        .setSteamid(owner.toLong())
        .setCommentThreadType(PUBLISHED_FILE_PUBLIC_COMMENT_THREAD)
        .setGidfeature(workshopId)
        .setGidfeature2(-1L)
        .setStart(start.coerceAtLeast(0))
        .setCount(count.coerceIn(1, MAX_COMMENT_PAGE_SIZE))
        .build()
}

internal fun buildCommunityPostRequest(
    request: NormalizedWorkshopCommentRequest,
): CommunityMessages.PostCommentToThreadRequest = CommunityMessages.PostCommentToThreadRequest
    .newBuilder()
    .setSteamid(request.ownerId.toULong().toLong())
    .setCommentThreadType(PUBLISHED_FILE_PUBLIC_COMMENT_THREAD)
    .setGidfeature(request.workshopId)
    .setGidfeature2(-1L)
    .setText(request.text)
    .build()

internal fun mapCommunityComments(
    response: CommunityMessages.GetCommentThreadResponse,
    requestedStart: Int,
    requestedCount: Int,
    creatorId: String,
    profiles: Map<Long, SteamProfile> = emptyMap(),
): WorkshopCommentPage {
    val comments = response.commentsList.mapNotNull { comment ->
        val text = comment.text.trim()
        if (text.isBlank() || comment.deleted || comment.hidden) return@mapNotNull null
        val profile = profiles[comment.steamid]
        WorkshopComment(
            author = profile?.displayName ?: "Steam 用户",
            authorId = comment.steamid.toString().takeIf { comment.steamid > 0L },
            text = text,
            avatarUrl = profile?.avatarUrl,
            isCreator = comment.steamid.toString() == creatorId,
            timestamp = comment.timestamp.toLong().takeIf { it > 0L },
        )
    }
    val start = response.start.takeIf { response.hasStart() && it >= 0 } ?: requestedStart.coerceAtLeast(0)
    val pageCount = response.count.takeIf { response.hasCount() && it > 0 }
        ?: requestedCount.coerceIn(1, MAX_COMMENT_PAGE_SIZE)
    val nextStart = start + pageCount
    val total = response.totalCount.takeIf { response.hasTotalCount() && it >= 0 }
    return WorkshopCommentPage(
        comments = comments,
        start = start,
        count = pageCount,
        nextStart = nextStart,
        total = total,
        hasMore = if (total != null) nextStart < total else comments.size >= pageCount,
        ownerId = response.steamid.toString().takeIf { response.hasSteamid() && response.steamid > 0L }
            ?: creatorId,
    )
}

internal const val WALLPAPER_ENGINE_APP_ID = 431960
private const val PUBLISHED_FILE_PUBLIC_COMMENT_THREAD = 5
private const val MAX_COMMENT_PAGE_SIZE = 50
