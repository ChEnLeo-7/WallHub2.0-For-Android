package com.wallhub.android.data.steam

import io.steamcontent.sdk.WorkshopItemField

/** Field snapshot shared by SDK page and resolved-detail mappings. */
internal class SdkSummaryFields(
    private val numbers: LongArray,
) {
    fun at(slot: Int): Long = numbers[slot]

    fun authorIdOrNull(): Long? = at(AUTHOR).takeIf { it > 0L }

    fun tagCount(): Int = at(TAG_COUNT).coerceIn(0L, MAX_SUMMARY_TAGS.toLong()).toInt()

    companion object {
        const val ITEM = 0
        const val AUTHOR = 1
        const val CONTENT_TYPE = 2
        const val SUBSCRIPTIONS = 3
        const val FAVOURITES = 4
        const val TAG_COUNT = 5
        const val VIEWS = 6
        const val FILE_SIZE = 7

        private const val MAX_SUMMARY_TAGS = 256

        val ORDINALS: IntArray =
            intArrayOf(
                WorkshopItemField.ITEM,
                WorkshopItemField.AUTHOR,
                WorkshopItemField.CONTENT_TYPE,
                WorkshopItemField.SUBSCRIPTIONS,
                WorkshopItemField.FAVOURITES,
                WorkshopItemField.TAG_COUNT,
                WorkshopItemField.VIEWS,
                WorkshopItemField.FILE_SIZE,
            )
    }
}

internal data class SdkPlayerProfile(
    val personaName: String,
    val avatarUrl: String?,
)

internal data class SdkCommentRow(
    val text: String,
    val authorId: Long?,
    val createdAt: Long?,
)
