package com.wallhub.android.core.model

const val WORKSHOP_COMMENT_MAX_LENGTH = 1_000

enum class WorkshopType {
    VIDEO,
    SCENE,
    WEB,
    UNKNOWN,
}

enum class WorkshopRating(
    val steamTag: String?,
) {
    ALL(null),
    EVERYONE("Everyone"),
    QUESTIONABLE("Questionable"),
    MATURE("Mature"),
}

enum class SubscriptionState {
    UNKNOWN,
    SUBSCRIBED,
    NOT_SUBSCRIBED,
}

enum class FavoriteState {
    UNKNOWN,
    FAVORITED,
    NOT_FAVORITED,
}

/** Lists owned by the signed-in Steam account rather than the public Workshop. */
enum class AccountWorkshopCollection {
    SUBSCRIPTIONS,
    FAVORITES,
    VOTED,
}

enum class WorkshopSort {
    TRENDING,
    MOST_RECENT,
    TOP_RATED,
    MOST_VOTES,
    MOST_SUBSCRIBERS,
}

data class WorkshopBrowseQuery(
    val page: Int = 1,
    val pageSize: Int = 24,
    val searchText: String = "",
    val creatorId: String? = null,
    val type: WorkshopType? = null,
    val types: Set<WorkshopType> = emptySet(),
    val tags: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val officialTags: Set<String> = emptySet(),
    val resolutions: Set<String> = emptySet(),
    val ratings: Set<WorkshopRating> = setOf(WorkshopRating.EVERYONE),
    val days: Int = 30,
    val exactPhrase: Boolean = false,
    val sort: WorkshopSort = WorkshopSort.TRENDING,
)

data class AccountWorkshopQuery(
    val collection: AccountWorkshopCollection,
    val page: Int = 1,
    val pageSize: Int = 24,
    val searchText: String = "",
    val resolveTotalCount: Boolean = false,
    val type: WorkshopType? = null,
    val tags: Set<String> = emptySet(),
)

data class WorkshopPage(
    val items: List<WorkshopSummary>,
    val page: Int,
    val hasNextPage: Boolean,
    val totalCount: Int? = null,
    val totalPages: Int? = null,
)

data class WorkshopInteraction(
    val subscriptionState: SubscriptionState = SubscriptionState.UNKNOWN,
    val favoriteState: FavoriteState = FavoriteState.UNKNOWN,
)

data class WorkshopSummary(
    val id: Long,
    val title: String,
    val author: String,
    val creatorId: String? = null,
    val previewUrl: String? = null,
    val type: WorkshopType = WorkshopType.UNKNOWN,
    val tags: List<String> = emptyList(),
    val subscriptions: Long? = null,
    val favorites: Long? = null,
    val views: Long? = null,
    val fileSizeBytes: Long? = null,
    val subscriptionState: SubscriptionState = SubscriptionState.UNKNOWN,
    val favoriteState: FavoriteState = FavoriteState.UNKNOWN,
)

data class WorkshopDetail(
    val summary: WorkshopSummary,
    val description: String = "",
    val fileSizeBytes: Long? = null,
    val previewMediaUrl: String? = null,
    val canPlay: Boolean = false,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val subscriptions: Long? = null,
    val creatorId: String? = null,
)

data class WorkshopComment(
    val author: String,
    val text: String,
    val authorId: String? = null,
    val avatarUrl: String? = null,
    val isCreator: Boolean = false,
    val timestamp: Long? = null,
    val dateLabel: String? = null,
)

data class WorkshopCommentPage(
    val comments: List<WorkshopComment>,
    val start: Int,
    val count: Int,
    val nextStart: Int,
    val total: Int? = null,
    val hasMore: Boolean = false,
    val ownerId: String? = null,
)

data class WorkshopResolutionGroup(
    val id: String,
    val options: List<String>,
)

object WorkshopFilterCatalog {
    val genres =
        listOf(
            "Abstract",
            "Animal",
            "Anime",
            "Cartoon",
            "CGI",
            "Cyberpunk",
            "Fantasy",
            "Game",
            "Girls",
            "Guys",
            "Landscape",
            "Medieval",
            "Memes",
            "MMD",
            "Music",
            "Nature",
            "Pixel art",
            "Relaxing",
            "Retro",
            "Sci-Fi",
            "Sports",
            "Technology",
            "Television",
            "Vehicle",
            "Unspecified",
        )

    val officialTags =
        listOf(
            "Approved",
            "Audio responsive",
            "3D",
            "Customizable",
            "Puppet Warp",
            "HDR",
            "Media Integration",
            "User Shortcut",
            "Video Texture",
            "Asset Pack",
        )

    val resolutionGroups =
        listOf(
            WorkshopResolutionGroup(
                "widescreen",
                listOf("Standard", "1280 x 720", "1366 x 768", "1920 x 1080", "2560 x 1440", "3840 x 2160"),
            ),
            WorkshopResolutionGroup("ultrawide", listOf("Ultrawide", "2560 x 1080", "3440 x 1440")),
            WorkshopResolutionGroup("dual", listOf("Dual monitor", "3840 x 1080", "5120 x 1440", "7680 x 2160")),
            WorkshopResolutionGroup("triple", listOf("Triple monitor", "4096 x 768", "5760 x 1080", "7680 x 1440", "11520 x 2160")),
            WorkshopResolutionGroup("portrait", listOf("Portrait", "720 x 1280", "1080 x 1920", "1440 x 2560", "2160 x 3840")),
            WorkshopResolutionGroup("other", listOf("Other resolution", "Dynamic resolution")),
        )

    val resolutions: List<String> = resolutionGroups.flatMap(WorkshopResolutionGroup::options)
}
