package com.wallhub.android.data.discover

import com.wallhub.android.feature.discover.model.DiscoverMetadataSnapshot
import com.wallhub.android.feature.discover.model.DiscoverMetadataSource
import com.wallhub.android.feature.discover.model.DiscoverCuratorCategory
import com.wallhub.android.feature.discover.model.DiscoverCuratorDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

internal data class LegacyExploreResponseDto(
    val items: List<LegacyExploreItemDto>,
)

internal data class LegacyExploreItemDto(
    val declaredId: String?,
    val category: String,
    val itemId: String?,
    val keyword: String?,
    val queryTypes: List<String>,
    val tags: List<String>,
    val includeTags: List<String>,
    val excludeTags: List<String>,
    val dependentTags: List<String>,
    val requiredTagGroups: List<List<String>>,
    val exact: Boolean,
    val platforms: Set<String>,
    val timestampStart: Long?,
    val timestampEnd: Long?,
    val priority: Float,
    val weight: Int,
    val sticky: Boolean,
)

internal data class ParsedCuratorMetadata(
    val descriptors: List<DiscoverCuratorDescriptor>,
    val receivedItemCount: Int,
    val rejectedItemCount: Int,
)

internal object LegacyExploreCuratorParser {
    private val supportedPlatforms = setOf("all", "any", "android", "mobile", "pc", "steam", "steamint", "windows")
    private const val MAX_CURATOR_ITEMS = 512
    private const val MAX_LIST_ITEMS = 64
    private const val MAX_TAG_GROUPS = 16
    private const val MAX_TAGS_PER_GROUP = 16
    private const val MAX_TEXT_LENGTH = 256
    private const val MAX_WEIGHT = 100
    private const val MIN_PRIORITY = -100f
    private const val MAX_PRIORITY = 100f

    fun parse(json: String): ParsedCuratorMetadata {
        val root = JSONObject(json)
        val response = root.optJSONObject("response") ?: throw IllegalArgumentException("Missing response object")
        val rawItems = response.optJSONArray("items") ?: throw IllegalArgumentException("Missing response.items array")
        require(rawItems.length() in 1..MAX_CURATOR_ITEMS) { "response.items count is outside the supported range" }

        val dto =
            LegacyExploreResponseDto(
                items =
                    buildList {
                        repeat(rawItems.length()) { index ->
                            rawItems.optJSONObject(index)?.toDtoOrNull()?.let(::add)
                        }
                    },
            )
        val descriptors = dto.items.mapNotNull(::toDescriptorOrNull).distinctBy(DiscoverCuratorDescriptor::stableId)
        if (descriptors.isEmpty()) throw IllegalArgumentException("response.items has no supported descriptors")
        return ParsedCuratorMetadata(
            descriptors = descriptors,
            receivedItemCount = rawItems.length(),
            rejectedItemCount = rawItems.length() - descriptors.size,
        )
    }

    fun snapshot(
        parsed: ParsedCuratorMetadata,
        nowMillis: Long,
        ttlMillis: Long,
        source: DiscoverMetadataSource = DiscoverMetadataSource.NETWORK,
    ): DiscoverMetadataSnapshot =
        DiscoverMetadataSnapshot(
            descriptors = parsed.descriptors,
            version = parsed.descriptors.metadataVersion(),
            fetchedAtMillis = nowMillis,
            expiresAtMillis = nowMillis + ttlMillis,
            source = source,
            receivedItemCount = parsed.receivedItemCount,
            rejectedItemCount = parsed.rejectedItemCount,
        )

    private fun JSONObject.toDtoOrNull(): LegacyExploreItemDto? {
        if (!validOptionalScalar("timestampstart") { it.toLongOrNull() != null }) return null
        if (!validOptionalScalar("timestampend") { it.toLongOrNull() != null }) return null
        if (!validOptionalScalar("priority") { it.toFloatOrNull()?.isFinite() == true }) return null
        if (!validOptionalScalar("weight") { it.toLongOrNull() != null }) return null
        if (!validOptionalBoolean("exact") || !validOptionalBoolean("sticky")) return null
        val queryTypes = stringList("querytypes") ?: return null
        val tags = stringList("tags") ?: return null
        val includeTags = stringList("includetags") ?: return null
        val excludeTags = stringList("excludetags") ?: return null
        val dependentTags = stringList("dependenttags") ?: return null
        val requiredTagGroups = tagGroups("requiredtaggroups") ?: return null
        val platforms = platformSet("platforms") ?: return null
        return LegacyExploreItemDto(
            declaredId = scalarString("_stableid"),
            category = optString("category").trim().lowercase(Locale.ROOT),
            itemId = scalarString("itemid"),
            keyword = scalarString("keyword"),
            queryTypes = queryTypes.map { it.lowercase(Locale.ROOT) },
            tags = tags,
            includeTags = includeTags,
            excludeTags = excludeTags,
            dependentTags = dependentTags,
            requiredTagGroups = requiredTagGroups,
            exact = scalarBoolean("exact") ?: false,
            platforms = platforms,
            timestampStart = scalarLong("timestampstart"),
            timestampEnd = scalarLong("timestampend"),
            priority = (scalarFloat("priority") ?: 0f).coerceIn(MIN_PRIORITY, MAX_PRIORITY),
            weight = (scalarLong("weight") ?: 1L).coerceIn(1L, MAX_WEIGHT.toLong()).toInt(),
            sticky = scalarBoolean("sticky") ?: false,
        )
    }

    private fun toDescriptorOrNull(dto: LegacyExploreItemDto): DiscoverCuratorDescriptor? {
        if (dto.platforms.isNotEmpty() && dto.platforms.none(supportedPlatforms::contains)) return null
        if (dto.timestampStart != null && dto.timestampEnd != null && dto.timestampStart > dto.timestampEnd) return null
        val category =
            when (dto.category) {
                "creator" -> DiscoverCuratorCategory.CREATOR
                "keyword" -> DiscoverCuratorCategory.KEYWORD
                "collection" -> DiscoverCuratorCategory.COLLECTION
                else -> return null
            }
        val subject =
            when (category) {
                DiscoverCuratorCategory.CREATOR -> dto.itemId?.takeIf(::isSteamId)
                DiscoverCuratorCategory.COLLECTION -> dto.itemId?.takeIf(::isWorkshopId)
                DiscoverCuratorCategory.KEYWORD -> dto.keyword?.trim()?.takeIf(String::isNotEmpty)
            } ?: return null
        val queryIdentity = dto.queryTypes.joinToString(",").ifBlank { "default" }
        return DiscoverCuratorDescriptor(
            stableId = dto.declaredId ?: "${category.name.lowercase(Locale.ROOT)}:${subject.lowercase(Locale.ROOT)}:$queryIdentity",
            category = category,
            itemId = dto.itemId,
            keyword = dto.keyword?.trim(),
            queryTypes = dto.queryTypes.distinct(),
            tags = dto.tags.distinct(),
            includeTags = dto.includeTags.distinct(),
            excludeTags = dto.excludeTags.distinct(),
            dependentTags = dto.dependentTags.distinct(),
            requiredTagGroups = dto.requiredTagGroups.map(List<String>::distinct).filter(List<String>::isNotEmpty),
            exact = dto.exact,
            platforms = dto.platforms,
            timestampStart = dto.timestampStart,
            timestampEnd = dto.timestampEnd,
            priority = dto.priority,
            weight = dto.weight,
            sticky = dto.sticky,
        )
    }

    private fun JSONObject.stringList(name: String): List<String>? {
        if (!has(name) || isNull(name)) return emptyList()
        val array = optJSONArray(name) ?: return null
        if (array.length() > MAX_LIST_ITEMS) return null
        return buildList {
            repeat(array.length()) { index ->
                val value = array.opt(index)
                if (value !is String) return null
                val normalized = value.trim()
                if (normalized.length > MAX_TEXT_LENGTH) return null
                normalized.takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }

    private fun JSONObject.tagGroups(name: String): List<List<String>>? {
        if (!has(name) || isNull(name)) return emptyList()
        val array = optJSONArray(name) ?: return null
        if (array.length() > MAX_TAG_GROUPS) return null
        return buildList {
            repeat(array.length()) { index ->
                when (val value = array.opt(index)) {
                    is String -> {
                        val normalized = value.trim()
                        if (normalized.length > MAX_TEXT_LENGTH) return null
                        normalized.takeIf(String::isNotEmpty)?.let { add(listOf(it)) }
                    }
                    is JSONArray -> {
                        if (value.length() > MAX_TAGS_PER_GROUP) return null
                        val group = buildList {
                            repeat(value.length()) { groupIndex ->
                                val tag = value.opt(groupIndex)
                                if (tag !is String) return null
                                val normalized = tag.trim()
                                if (normalized.length > MAX_TEXT_LENGTH) return null
                                normalized.takeIf(String::isNotEmpty)?.let(::add)
                            }
                        }
                        if (group.isNotEmpty()) add(group)
                    }
                    else -> return null
                }
            }
        }
    }

    private fun JSONObject.platformSet(name: String): Set<String>? {
        if (!has(name) || isNull(name)) return emptySet()
        optJSONArray(name)?.let { array ->
            if (array.length() > MAX_LIST_ITEMS) return null
            return buildSet {
                repeat(array.length()) { index ->
                    val value = array.opt(index)
                    if (value !is String) return null
                    val normalized = value.trim()
                    if (normalized.length > MAX_TEXT_LENGTH) return null
                    normalized.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)?.let(::add)
                }
            }
        }
        val values = optJSONObject(name) ?: return null
        val keys = values.keys().asSequence().toList()
        if (keys.size > MAX_LIST_ITEMS || keys.any { it.trim().length > MAX_TEXT_LENGTH }) return null
        return buildSet {
            keys.forEach { key ->
                val enabled = values.opt(key)
                if (enabled !is Boolean) return null
                if (enabled) key.trim().takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)?.let(::add)
            }
        }
    }

    private fun JSONObject.validOptionalScalar(
        name: String,
        predicate: (String) -> Boolean,
    ): Boolean {
        if (!has(name) || isNull(name)) return true
        val normalized = opt(name)?.toString()?.trim() ?: return false
        return normalized.isNotEmpty() && normalized.length <= MAX_TEXT_LENGTH && predicate(normalized)
    }

    private fun JSONObject.validOptionalBoolean(name: String): Boolean {
        if (!has(name) || isNull(name)) return true
        return when (val value = opt(name)) {
            is Boolean -> true
            is Number -> value.toInt() == 0 || value.toInt() == 1
            is String -> value == "true" || value == "false"
            else -> false
        }
    }

    private fun JSONObject.scalarString(name: String): String? =
        if (!has(name) || isNull(name)) {
            null
        } else {
            opt(name)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT_LENGTH }
        }

    private fun JSONObject.scalarLong(name: String): Long? =
        scalarString(name)?.toLongOrNull()

    private fun JSONObject.scalarFloat(name: String): Float? =
        scalarString(name)?.toFloatOrNull()?.takeIf(Float::isFinite)

    private fun JSONObject.scalarBoolean(name: String): Boolean? =
        when (val value = opt(name)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.toBooleanStrictOrNull()
            else -> null
        }

    private fun isSteamId(value: String): Boolean = value.toULongOrNull()?.let { it > 0uL } == true

    private fun isWorkshopId(value: String): Boolean = value.toULongOrNull()?.let { it > 0uL } == true
}

private fun List<DiscoverCuratorDescriptor>.metadataVersion(): String {
    val canonical =
        joinToString("\n") { descriptor ->
            listOf(
                descriptor.stableId,
                descriptor.itemId.orEmpty(),
                descriptor.keyword.orEmpty(),
                descriptor.queryTypes.joinToString(","),
                descriptor.tags.joinToString(","),
                descriptor.includeTags.joinToString(","),
                descriptor.excludeTags.joinToString(","),
                descriptor.dependentTags.joinToString(","),
                descriptor.requiredTagGroups.joinToString(";") { it.joinToString(",") },
                descriptor.exact.toString(),
                descriptor.platforms.sorted().joinToString(","),
                descriptor.timestampStart.orEmptyString(),
                descriptor.timestampEnd.orEmptyString(),
                descriptor.priority.toString(),
                descriptor.weight.toString(),
                descriptor.sticky.toString(),
            ).joinToString("|")
        }
    return MessageDigest
        .getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(16)
}

private fun Long?.orEmptyString(): String = this?.toString().orEmpty()
