package com.wallhub.android.feature.discover.model

interface DiscoverCuratorRepository {
    /** Returns the most recent validated memory or disk snapshot without accessing the network. */
    suspend fun loadCachedMetadata(): DiscoverMetadataSnapshot? = null

    /** Conditionally revalidates the curator provider and falls back to the last-known-good snapshot. */
    suspend fun loadMetadata(): DiscoverMetadataSnapshot
}

enum class DiscoverCuratorCategory {
    CREATOR,
    KEYWORD,
    COLLECTION,
}

enum class DiscoverMetadataSource {
    NETWORK,
    MEMORY_CACHE,
    PERSISTED_CACHE,
    GENERIC_FALLBACK,
}

data class DiscoverCuratorDescriptor(
    val stableId: String,
    val category: DiscoverCuratorCategory,
    val itemId: String? = null,
    val keyword: String? = null,
    val queryTypes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val includeTags: List<String> = emptyList(),
    val excludeTags: List<String> = emptyList(),
    val dependentTags: List<String> = emptyList(),
    val requiredTagGroups: List<List<String>> = emptyList(),
    val exact: Boolean = false,
    val platforms: Set<String> = emptySet(),
    val timestampStart: Long? = null,
    val timestampEnd: Long? = null,
    val priority: Float = 0f,
    val weight: Int = 1,
    val sticky: Boolean = false,
)

data class DiscoverMetadataSnapshot(
    val descriptors: List<DiscoverCuratorDescriptor>,
    val version: String,
    val fetchedAtMillis: Long,
    val expiresAtMillis: Long,
    val source: DiscoverMetadataSource,
    val receivedItemCount: Int,
    val rejectedItemCount: Int,
) {
    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis
}
