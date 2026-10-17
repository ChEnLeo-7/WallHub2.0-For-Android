package com.wallhub.android.data.steam

import io.steamcontent.sdk.WorkshopItemField
import io.steamcontent.sdk.WorkshopPage
import io.steamcontent.sdk.WorkshopResult

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

internal fun summaryFieldsOf(result: WorkshopResult): SdkSummaryFields =
    SdkSummaryFields(
        LongArray(SdkSummaryFields.ORDINALS.size).also { values ->
            SdkSummaryFields.ORDINALS.forEachIndexed { slot, ordinal ->
                values[slot] = result.field(ordinal).value
            }
        },
    )

internal fun summaryFieldsOf(page: WorkshopPage, index: Int): SdkSummaryFields =
    SdkSummaryFields(
        LongArray(SdkSummaryFields.ORDINALS.size).also { values ->
            SdkSummaryFields.ORDINALS.forEachIndexed { slot, ordinal ->
                values[slot] = page.field(index, ordinal).value
            }
        },
    )

internal data class SdkPlayerProfile(
    val personaName: String,
    val avatarUrl: String?,
)

internal data class SdkCommentRow(
    val text: String,
    val authorId: Long?,
    val createdAt: Long?,
)
