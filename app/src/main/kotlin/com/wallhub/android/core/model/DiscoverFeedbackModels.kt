package com.wallhub.android.core.model

import kotlinx.coroutines.flow.Flow

/** Persisted feedback for a stable Discover rail name. */
data class DiscoverRailFeedback(
    val railName: String,
    val liked: Boolean = false,
    val disliked: Boolean = false,
    val favorited: Boolean = false,
) {
    init {
        require(railName.isNotBlank()) { "Discover rail name must not be blank" }
        require(!liked || !disliked) { "A Discover rail cannot be liked and disliked at the same time" }
    }

    val isEmpty: Boolean
        get() = !liked && !disliked && !favorited
}

/** Recommendation inputs derived from persisted feedback. */
data class DiscoverFeedbackWeight(
    val scoreAdjustment: Float = 0f,
    val suppressCurrentGeneration: Boolean = false,
)

interface DiscoverFeedbackRepository {
    /** Emits a complete snapshot keyed by stable rail name. */
    val feedback: Flow<Map<String, DiscoverRailFeedback>>

    suspend fun feedbackFor(railName: String): DiscoverRailFeedback

    suspend fun setLiked(
        railName: String,
        liked: Boolean,
    )

    suspend fun setDisliked(
        railName: String,
        disliked: Boolean,
    )

    suspend fun setFavorited(
        railName: String,
        favorited: Boolean,
    )

    suspend fun weightFor(railName: String): DiscoverFeedbackWeight

    /** Reads one DataStore snapshot for the whole coordinator ranking pass. */
    suspend fun weightsFor(railNames: Collection<String>): Map<String, DiscoverFeedbackWeight>

    suspend fun clearFeedback(railName: String)

    suspend fun clearAllFeedback()
}

enum class DiscoverSavedQueryCategory {
    KEYWORD,
    CREATOR,
    COLLECTION,
    TOPIC,
}

enum class DiscoverSavedQuerySource {
    FAVORITE,
    CUSTOM,
}

/** A portable Discover query snapshot that remains usable after its source generation expires. */
data class DiscoverSavedQuery(
    val id: String,
    val title: String,
    val category: DiscoverSavedQueryCategory,
    val source: DiscoverSavedQuerySource,
    val semantic: String,
    val searchText: String = "",
    val creatorId: String? = null,
    val collectionId: Long? = null,
    val tags: Set<String> = emptySet(),
    val excludedTags: Set<String> = emptySet(),
    val officialTags: Set<String> = emptySet(),
    val excludedOfficialTags: Set<String> = emptySet(),
    val requiredTagGroups: List<Set<String>> = emptyList(),
    val types: Set<WorkshopType> = emptySet(),
    val sort: WorkshopSort = WorkshopSort.TRENDING,
    val days: Int = 30,
    val exactPhrase: Boolean = false,
    val allowNsfw: Boolean = false,
    val mobileCompatibleOnly: Boolean = false,
    val previewUrl: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(id.isNotBlank()) { "Saved Discover query ID must not be blank" }
        require(title.isNotBlank()) { "Saved Discover query title must not be blank" }
    }
}

interface DiscoverSavedQueryRepository {
    val queries: Flow<List<DiscoverSavedQuery>>

    suspend fun upsert(query: DiscoverSavedQuery)

    suspend fun remove(queryId: String)
}

fun DiscoverRailFeedback.toDiscoverFeedbackWeight(): DiscoverFeedbackWeight =
    DiscoverFeedbackWeight(
        scoreAdjustment =
            when {
                disliked -> DISCOVER_DISLIKE_SCORE_ADJUSTMENT
                liked -> DISCOVER_LIKE_SCORE_ADJUSTMENT
                else -> 0f
            },
        suppressCurrentGeneration = disliked,
    )

const val DISCOVER_LIKE_SCORE_ADJUSTMENT = 0.25f
const val DISCOVER_DISLIKE_SCORE_ADJUSTMENT = -1f
