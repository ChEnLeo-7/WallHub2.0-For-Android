package com.wallhub.android.data.discover

import com.wallhub.android.feature.discover.model.DiscoverMetadataSnapshot
import com.wallhub.android.feature.discover.model.DiscoverMetadataSource
import com.wallhub.android.feature.discover.model.OfficialDiscoverCategory
import com.wallhub.android.feature.discover.model.OfficialDiscoverDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

internal data class OfficialDiscoverResponseDto(
    val items: List<OfficialDiscoverItemDto>,
)

internal data class OfficialDiscoverItemDto(
    val category: String,
    val itemId: String?,
    val keyword: String?,
    val coverSubmissionId: Long?,
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

internal data class ParsedDiscoverMetadata(
    val descriptors: List<OfficialDiscoverDescriptor>,
    val receivedItemCount: Int,
    val rejectedItemCount: Int,
)

internal object OfficialDiscoverMetadataParser {
    private val supportedPlatforms = setOf("all", "any", "android", "mobile", "pc", "steam", "steamint", "windows")

    fun parse(json: String): ParsedDiscoverMetadata {
        val root = JSONObject(json)
        val response = root.optJSONObject("response") ?: throw IllegalArgumentException("Missing response object")
        val rawItems = response.optJSONArray("items") ?: throw IllegalArgumentException("Missing response.items array")
        if (rawItems.length() == 0) throw IllegalArgumentException("response.items is empty")

        val dto =
            OfficialDiscoverResponseDto(
                items =
                    buildList {
                        repeat(rawItems.length()) { index ->
                            rawItems.optJSONObject(index)?.toDtoOrNull()?.let(::add)
                        }
                    },
            )
        val descriptors = dto.items.mapNotNull(::toDescriptorOrNull).distinctBy(OfficialDiscoverDescriptor::stableId)
        if (descriptors.isEmpty()) throw IllegalArgumentException("response.items has no supported descriptors")
        return ParsedDiscoverMetadata(
            descriptors = descriptors,
            receivedItemCount = rawItems.length(),
            rejectedItemCount = rawItems.length() - descriptors.size,
        )
    }

    fun snapshot(
        parsed: ParsedDiscoverMetadata,
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

    private fun JSONObject.toDtoOrNull(): OfficialDiscoverItemDto? {
        val queryTypes = stringList("querytypes") ?: return null
        val tags = stringList("tags") ?: return null
        val includeTags = stringList("includetags") ?: return null
        val excludeTags = stringList("excludetags") ?: return null
        val dependentTags = stringList("dependenttags") ?: return null
        val requiredTagGroups = tagGroups("requiredtaggroups") ?: return null
        val platforms = platformSet("platforms") ?: return null
        return OfficialDiscoverItemDto(
            category = optString("category").trim().lowercase(Locale.ROOT),
            itemId = scalarString("itemid"),
            keyword = scalarString("keyword"),
            coverSubmissionId = scalarLong("coversubmission"),
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
            priority = scalarFloat("priority") ?: 0f,
            weight = (scalarLong("weight") ?: 1L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
            sticky = scalarBoolean("sticky") ?: false,
        )
    }

    private fun toDescriptorOrNull(dto: OfficialDiscoverItemDto): OfficialDiscoverDescriptor? {
        if (dto.platforms.isNotEmpty() && dto.platforms.none(supportedPlatforms::contains)) return null
        if (dto.timestampStart != null && dto.timestampEnd != null && dto.timestampStart > dto.timestampEnd) return null
        val category =
            when (dto.category) {
                "creator" -> OfficialDiscoverCategory.CREATOR
                "keyword" -> OfficialDiscoverCategory.KEYWORD
                "collection" -> OfficialDiscoverCategory.COLLECTION
                else -> return null
            }
        val subject =
            when (category) {
                OfficialDiscoverCategory.CREATOR -> dto.itemId?.takeIf(::isSteamId)
                OfficialDiscoverCategory.COLLECTION -> dto.itemId?.takeIf(::isWorkshopId)
                OfficialDiscoverCategory.KEYWORD -> dto.keyword?.trim()?.takeIf(String::isNotEmpty)
            } ?: return null
        val queryIdentity = dto.queryTypes.joinToString(",").ifBlank { "default" }
        return OfficialDiscoverDescriptor(
            stableId = "${category.name.lowercase(Locale.ROOT)}:${subject.lowercase(Locale.ROOT)}:$queryIdentity",
            category = category,
            itemId = dto.itemId,
            keyword = dto.keyword?.trim(),
            coverSubmissionId = dto.coverSubmissionId?.takeIf { it > 0L },
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
        return buildList {
            repeat(array.length()) { index ->
                val value = array.opt(index)
                if (value !is String) return null
                value.trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }

    private fun JSONObject.tagGroups(name: String): List<List<String>>? {
        if (!has(name) || isNull(name)) return emptyList()
        val array = optJSONArray(name) ?: return null
        return buildList {
            repeat(array.length()) { index ->
                when (val value = array.opt(index)) {
                    is String -> value.trim().takeIf(String::isNotEmpty)?.let { add(listOf(it)) }
                    is JSONArray -> {
                        val group = buildList {
                            repeat(value.length()) { groupIndex ->
                                val tag = value.opt(groupIndex)
                                if (tag !is String) return null
                                tag.trim().takeIf(String::isNotEmpty)?.let(::add)
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
            return buildSet {
                repeat(array.length()) { index ->
                    val value = array.opt(index)
                    if (value !is String) return null
                    value.trim().takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)?.let(::add)
                }
            }
        }
        val values = optJSONObject(name) ?: return null
        return buildSet {
            values.keys().forEach { key ->
                if (values.optBoolean(key, false)) key.trim().takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)?.let(::add)
            }
        }
    }

    private fun JSONObject.scalarString(name: String): String? =
        if (!has(name) || isNull(name)) {
            null
        } else {
            opt(name)?.toString()?.trim()?.takeIf(String::isNotEmpty)
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

private fun List<OfficialDiscoverDescriptor>.metadataVersion(): String {
    val canonical =
        joinToString("\n") { descriptor ->
            listOf(
                descriptor.stableId,
                descriptor.itemId.orEmpty(),
                descriptor.keyword.orEmpty(),
                descriptor.coverSubmissionId.orEmptyString(),
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
