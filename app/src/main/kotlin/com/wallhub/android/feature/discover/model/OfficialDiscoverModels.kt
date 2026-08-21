package com.wallhub.android.feature.discover.model

interface OfficialDiscoverMetadataRepository {
    suspend fun loadMetadata(): DiscoverMetadataSnapshot
}

enum class OfficialDiscoverCategory {
    CREATOR,
    KEYWORD,
    COLLECTION,
}

enum class DiscoverMetadataSource {
    NETWORK,
    MEMORY_CACHE,
    STATIC_FALLBACK,
}

data class OfficialDiscoverDescriptor(
    val stableId: String,
    val category: OfficialDiscoverCategory,
    val itemId: String? = null,
    val keyword: String? = null,
    val coverSubmissionId: Long? = null,
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
    val descriptors: List<OfficialDiscoverDescriptor>,
    val version: String,
    val fetchedAtMillis: Long,
    val expiresAtMillis: Long,
    val source: DiscoverMetadataSource,
    val receivedItemCount: Int,
    val rejectedItemCount: Int,
) {
    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis
}
