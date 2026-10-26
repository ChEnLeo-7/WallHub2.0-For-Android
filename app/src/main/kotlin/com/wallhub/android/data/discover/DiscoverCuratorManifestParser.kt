package com.wallhub.android.data.discover

import org.json.JSONArray
import org.json.JSONObject

/**
 * Accepts WallHub's bounded versioned curator manifest and the current legacy explore payload.
 * Neither format may provide request URLs; both normalize into the same closed query vocabulary.
 */
internal object DiscoverCuratorManifestParser {
    fun parse(json: String): ParsedCuratorMetadata {
        val root = JSONObject(json)
        if (!root.has("schemaVersion")) return LegacyExploreCuratorParser.parse(json)
        require(root.optInt("schemaVersion") == SUPPORTED_SCHEMA_VERSION) { "Unsupported curator schema version" }
        require(root.keys().asSequence().all(ALLOWED_ROOT_FIELDS::contains)) { "Unknown curator manifest field" }
        val revision = root.optString("revision").trim()
        require(revision.matches(SECTION_ID_PATTERN)) { "Invalid curator revision" }
        val expiresAtMillis =
            root.optionalLong("expiresAt")
                ?.let { Math.multiplyExact(it, 1_000L) }
        val sections = root.optJSONArray("sections") ?: throw IllegalArgumentException("Missing curator sections")
        require(sections.length() in 1..MAX_SECTIONS) { "Curator section count is outside the supported range" }
        val legacyItems = JSONArray()
        repeat(sections.length()) { index ->
            val section = sections.optJSONObject(index) ?: throw IllegalArgumentException("Invalid curator section")
            require(section.keys().asSequence().all(ALLOWED_SECTION_FIELDS::contains)) { "Unknown curator section field" }
            require(section.optString("coverPolicy", "first_result") in ALLOWED_COVER_POLICIES) { "Unsupported curator cover policy" }
            val stableId = section.optString("id").trim()
            require(stableId.matches(SECTION_ID_PATTERN)) { "Invalid curator section id" }
            val activeFrom = section.optionalLong("activeFrom")
            val activeUntil = section.optionalLong("activeUntil")
            require(activeFrom == null || activeUntil == null || activeFrom <= activeUntil) { "Invalid curator active interval" }
            val now = System.currentTimeMillis() / 1_000L
            if ((activeFrom != null && now < activeFrom) || (activeUntil != null && now > activeUntil)) return@repeat
            legacyItems.put(
                JSONObject()
                    .put("_stableid", stableId)
                    .copyIfPresent(section, "category", "category")
                    .copyIfPresent(section, "itemId", "itemid")
                    .copyIfPresent(section, "keyword", "keyword")
                    .copyIfPresent(section, "queryTypes", "querytypes")
                    .copyIfPresent(section, "tags", "tags")
                    .copyIfPresent(section, "includeTags", "includetags")
                    .copyIfPresent(section, "excludeTags", "excludetags")
                    .copyIfPresent(section, "dependentTags", "dependenttags")
                    .copyIfPresent(section, "requiredTagGroups", "requiredtaggroups")
                    .copyIfPresent(section, "exact", "exact")
                    .copyIfPresent(section, "platforms", "platforms")
                    .copyIfPresent(section, "createdAfter", "timestampstart")
                    .copyIfPresent(section, "createdBefore", "timestampend")
                    .copyIfPresent(section, "priority", "priority")
                    .copyIfPresent(section, "weight", "weight")
                    .copyIfPresent(section, "sticky", "sticky"),
            )
        }
        if (legacyItems.length() == 0) {
            return ParsedCuratorMetadata(
                descriptors = emptyList(),
                receivedItemCount = sections.length(),
                rejectedItemCount = 0,
                versionHint = revision,
                expiresAtMillis = expiresAtMillis,
            )
        }
        val normalized = JSONObject().put("response", JSONObject().put("items", legacyItems))
        return LegacyExploreCuratorParser
            .parse(normalized.toString())
            .copy(versionHint = revision, expiresAtMillis = expiresAtMillis)
    }

    private fun JSONObject.optionalLong(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return opt(name)?.toString()?.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid curator $name")
    }

    private fun JSONObject.copyIfPresent(
        source: JSONObject,
        sourceName: String,
        targetName: String,
    ): JSONObject {
        if (source.has(sourceName) && !source.isNull(sourceName)) put(targetName, source.get(sourceName))
        return this
    }

    private const val SUPPORTED_SCHEMA_VERSION = 1
    private const val MAX_SECTIONS = 512
    private val SECTION_ID_PATTERN = Regex("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}")
    private val ALLOWED_ROOT_FIELDS = setOf("schemaVersion", "revision", "generatedAt", "expiresAt", "sections")
    private val ALLOWED_COVER_POLICIES = setOf("first_result", "collection_first_eligible", "auto")
    private val ALLOWED_SECTION_FIELDS =
        setOf(
            "id",
            "category",
            "itemId",
            "keyword",
            "queryTypes",
            "tags",
            "includeTags",
            "excludeTags",
            "dependentTags",
            "requiredTagGroups",
            "exact",
            "platforms",
            "createdAfter",
            "createdBefore",
            "priority",
            "weight",
            "sticky",
            "activeFrom",
            "activeUntil",
            "coverPolicy",
        )
}
