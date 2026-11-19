package com.wallhub.android.data.steam

import com.wallhub.android.core.model.WORKSHOP_COMMENT_MAX_LENGTH

/**
 * The product's own validation of a comment the user is about to post.
 *
 * It used to live beside the kSteam request builder; it stayed because the rule is the product's, not the
 * transport's: the SDK adapter validates the draft before it hands it to the engine, and the removed kSteam
 * implementation validated it the same way.
 */

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
