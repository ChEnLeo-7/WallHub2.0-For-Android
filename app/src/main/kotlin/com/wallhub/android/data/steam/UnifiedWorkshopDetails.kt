package com.wallhub.android.data.steam

import com.wallhub.android.core.model.WORKSHOP_COMMENT_MAX_LENGTH
import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import steam.webui.community.CCommunity_GetCommentThread_Request
import steam.webui.community.CCommunity_GetCommentThread_Response
import steam.webui.community.CCommunity_PostCommentToThread_Request
import steam.webui.publishedfile.CPublishedFile_GetDetails_Request
import steam.webui.publishedfile.CPublishedFile_GetDetails_Response
import steam.webui.publishedfile.PublishedFileDetails

internal fun buildUnifiedWorkshopDetailRequest(workshopId: Long): CPublishedFile_GetDetails_Request =
    buildUnifiedWorkshopDetailRequest(listOf(workshopId))

internal fun buildUnifiedWorkshopDetailRequest(workshopIds: List<Long>): CPublishedFile_GetDetails_Request {
    val ids = workshopIds.filter { it > 0L }.distinct()
    require(ids.isNotEmpty()) { "At least one valid Workshop item ID is required" }
    return CPublishedFile_GetDetails_Request(
        publishedfileids = ids,
        includetags = true,
        includeadditionalpreviews = true,
        includechildren = true,
        includekvtags = true,
        includevotes = true,
        includemetadata = true,
        strip_description_bbcode = true,
        appid = WALLPAPER_ENGINE_APP_ID,
    )
}

internal fun mapUnifiedWorkshopDetail(
    detail: PublishedFileDetails,
    creatorProfile: SteamProfile? = null,
): WorkshopDetail {
    val summary =
        detail.toWorkshopSummary().let { source ->
            source.copy(
                author = creatorProfile?.displayName.orEmpty(),
                authorAvatarUrl = creatorProfile?.avatarUrl,
                authorPlaceholder =
                    if (creatorProfile == null) {
                        WorkshopAuthorPlaceholder.CREATOR
                    } else {
                        WorkshopAuthorPlaceholder.NONE
                    },
            )
        }
    val previewMediaUrl =
        detail.previews
            .asSequence()
            .map { preview -> preview.url.orEmpty().trim() }
            .firstOrNull(String::isNotBlank)
            ?: detail.preview_url.orEmpty().trim().takeIf(String::isNotBlank)
    return WorkshopDetail(
        summary = summary,
        description = detail.file_description.orEmpty().trim().ifBlank { detail.short_description.orEmpty().trim() },
        fileSizeBytes = (detail.file_size ?: 0L).takeIf { it > 0L },
        previewMediaUrl = previewMediaUrl,
        createdAt = (detail.time_created ?: 0).toLong().takeIf { it > 0L },
        updatedAt = (detail.time_updated ?: 0).toLong().takeIf { it > 0L },
        subscriptions =
            (detail.subscriptions ?: 0).toLong().takeIf { it > 0L }
                ?: (detail.lifetime_subscriptions ?: 0).toLong().takeIf { it > 0L },
        creatorId = (detail.creator ?: 0L).toString().takeIf { (detail.creator ?: 0L) > 0L },
    )
}

internal fun mapUnifiedCollectionChildIds(
    collectionId: Long,
    response: CPublishedFile_GetDetails_Response,
): List<Long> =
    response.publishedfiledetails
        .firstOrNull { detail ->
            detail.publishedfileid == collectionId && detail.result == ERESULT_OK
        }?.children
        .orEmpty()
        .sortedBy { child -> child.sortorder ?: 0 }
        .mapNotNull { child -> child.publishedfileid?.takeIf { it > 0L } }
        .distinct()

internal fun buildCommunityCommentRequest(
    workshopId: Long,
    ownerId: String,
    start: Int,
    count: Int,
): CCommunity_GetCommentThread_Request {
    require(workshopId > 0L) { "Invalid Workshop item ID" }
    val owner =
        ownerId.toULongOrNull()?.takeIf { it > 0uL }
            ?: error("Invalid Workshop author ID")
    return CCommunity_GetCommentThread_Request(
        steamid = owner.toLong(),
        comment_thread_type = PUBLISHED_FILE_PUBLIC_COMMENT_THREAD,
        gidfeature = workshopId,
        gidfeature2 = -1L,
        start = start.coerceAtLeast(0),
        count = count.coerceIn(1, MAX_COMMENT_PAGE_SIZE),
    )
}

internal fun buildCommunityPostRequest(request: NormalizedWorkshopCommentRequest): CCommunity_PostCommentToThread_Request =
    CCommunity_PostCommentToThread_Request(
        steamid = request.ownerId.toULong().toLong(),
        comment_thread_type = PUBLISHED_FILE_PUBLIC_COMMENT_THREAD,
        gidfeature = request.workshopId,
        gidfeature2 = -1L,
        text = request.text,
    )

internal fun mapCommunityComments(
    response: CCommunity_GetCommentThread_Response,
    requestedStart: Int,
    requestedCount: Int,
    creatorId: String,
    profiles: Map<Long, SteamProfile> = emptyMap(),
): WorkshopCommentPage {
    val comments =
        response.comments.mapNotNull { comment ->
            val text = comment.text.orEmpty().trim()
            val steamId = comment.steamid ?: 0L
            if (text.isBlank() || comment.deleted == true || comment.hidden == true) return@mapNotNull null
            val profile = profiles[steamId]
            WorkshopComment(
                author = profile?.displayName.orEmpty(),
                authorId = steamId.toString().takeIf { steamId > 0L },
                text = text,
                avatarUrl = profile?.avatarUrl,
                isCreator = steamId.toString() == creatorId,
                timestamp = (comment.timestamp ?: 0).toLong().takeIf { it > 0L },
                isAuthorPlaceholder = profile == null,
            )
        }
    val start = response.start?.takeIf { it >= 0 } ?: requestedStart.coerceAtLeast(0)
    val pageCount =
        response.count?.takeIf { it > 0 }
            ?: requestedCount.coerceIn(1, MAX_COMMENT_PAGE_SIZE)
    val nextStart = start + pageCount
    val total = response.total_count?.takeIf { it >= 0 }
    return WorkshopCommentPage(
        comments = comments,
        start = start,
        count = pageCount,
        nextStart = nextStart,
        total = total,
        hasMore = if (total != null) nextStart < total else comments.size >= pageCount,
        ownerId = response.steamid?.toString()?.takeIf { it != "0" } ?: creatorId,
    )
}

internal data class NormalizedWorkshopCommentRequest(
    val workshopId: Long,
    val ownerId: String,
    val text: String,
)

internal fun normalizeWorkshopCommentRequest(
    workshopId: Long,
    ownerId: String,
    text: String,
): NormalizedWorkshopCommentRequest {
    require(workshopId > 0L) { "Invalid Workshop item ID" }
    val normalizedOwnerId = ownerId.trim()
    require(normalizedOwnerId.toULongOrNull()?.let { it > 0uL } == true) {
        "Invalid Workshop author ID"
    }
    val normalizedText = text.trim()
    require(normalizedText.isNotEmpty()) { "Comment must not be empty" }
    require(normalizedText.length <= WORKSHOP_COMMENT_MAX_LENGTH) {
        "Comment must not exceed $WORKSHOP_COMMENT_MAX_LENGTH characters"
    }
    return NormalizedWorkshopCommentRequest(
        workshopId = workshopId,
        ownerId = normalizedOwnerId,
        text = normalizedText,
    )
}

internal const val SUBSCRIPTION_LIST_TYPE = 1
internal const val FAVORITE_RELATIONSHIP = 1
private const val PUBLISHED_FILE_PUBLIC_COMMENT_THREAD = 5
private const val MAX_COMMENT_PAGE_SIZE = 50
