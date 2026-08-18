package com.wallhub.android.core.model

import java.util.Locale

data class WorkshopSteamTagCriteria(
    val requiredTags: List<String>,
    val excludedTags: List<String>,
    val allowedTypes: Set<WorkshopType>,
)

private val workshopIdPattern = Regex("(?:publishedfileid|id)=([0-9]{6,})", RegexOption.IGNORE_CASE)
private val workshopDetailsPattern = Regex("sharedfiles/filedetails/\\?id=([0-9]{6,})", RegexOption.IGNORE_CASE)
private val detailTagPrefix = Regex("^tag:", RegexOption.IGNORE_CASE)
private val whitespacePattern = Regex("\\s+")

fun String.workshopSearchIdOrNull(): Long? {
    val raw = trim()
    val candidate =
        when {
            raw.matches(Regex("^[0-9]{6,}$")) -> raw
            else -> workshopIdPattern.find(raw)?.groupValues?.getOrNull(1)
                ?: workshopDetailsPattern.find(raw)?.groupValues?.getOrNull(1)
        }
    return candidate?.toLongOrNull()?.takeIf { it > 0L }
}

fun String.workshopAuthorSearchOrNull(): String? {
    val raw = trim()
    if (!raw.startsWith("author:")) return null
    return raw.substringAfter(':').trim().takeIf(String::isNotEmpty)
}

fun String.workshopDetailTagSearch(): List<String> {
    val raw = trim()
    if (!detailTagPrefix.containsMatchIn(raw)) return emptyList()
    val seen = linkedSetOf<String>()
    return raw
        .replaceFirst(detailTagPrefix, "")
        .split('|')
        .map { tag -> tag.trim().replace(whitespacePattern, " ") }
        .filter(String::isNotBlank)
        .filter { tag -> seen.add(tag.lowercase(Locale.ROOT)) }
        .take(MAX_WORKSHOP_DETAIL_SEARCH_TAGS)
}

fun WorkshopBrowseQuery.steamSearchText(): String {
    val value = searchText.trim()
    if (
        value.isBlank() ||
        value.workshopSearchIdOrNull() != null ||
        value.workshopAuthorSearchOrNull() != null ||
        value.workshopDetailTagSearch().isNotEmpty()
    ) {
        return ""
    }
    return if (exactPhrase && '"' !in value) "\"$value\"" else value
}

fun WorkshopBrowseQuery.steamTagCriteria(): WorkshopSteamTagCriteria {
    val required = linkedSetOf("Wallpaper")
    val excluded = linkedSetOf("Preset")
    required += tags.map(String::trim).filter(String::isNotBlank)
    required += officialTags.map(String::trim).filter { it in WorkshopFilterCatalog.officialTags }
    excluded += excludedOfficialTags.map(String::trim).filter { it in WorkshopFilterCatalog.officialTags }

    fun applyComplement(
        selected: Set<String>,
        catalog: Set<String>,
    ) {
        if (selected.isEmpty() || selected.size >= catalog.size) return
        excluded += catalog - selected
    }

    val selectedTypes =
        effectiveWorkshopTypes()
            .ifEmpty { VISIBLE_WORKSHOP_TYPES }
            .mapNotNull(WorkshopType::steamWorkshopTag)
            .toSet()
    applyComplement(selectedTypes, STEAM_WORKSHOP_TYPE_TAGS)

    val selectedRatings =
        effectiveWorkshopRatings()
            .takeUnless { WorkshopRating.ALL in it }
            ?.mapNotNull(WorkshopRating::steamTag)
            ?.toSet()
            .orEmpty()
    applyComplement(selectedRatings, STEAM_CONTENT_RATING_TAGS)

    val selectedGenres = genres.filter { it in WorkshopFilterCatalog.genres }.toSet()
    applyComplement(selectedGenres, WorkshopFilterCatalog.genres.toSet())

    val selectedResolutions =
        resolutions
            .mapNotNull(WORKSHOP_RESOLUTION_TAG_MAP::get)
            .toSet()
    applyComplement(selectedResolutions, STEAM_WORKSHOP_RESOLUTION_TAGS)

    if (mobileCompatibleOnly) excluded += setOf("Application", "Web")
    if (!allowNsfw) excluded += BLOCKED_WORKSHOP_TAGS

    val groupedTags =
        STEAM_WORKSHOP_TYPE_TAGS +
            STEAM_CONTENT_RATING_TAGS +
            WorkshopFilterCatalog.genres +
            STEAM_WORKSHOP_RESOLUTION_TAGS +
            setOf("Wallpaper", "Preset")
    val cleanRequired = required.filterNot(groupedTags::contains).toMutableList().apply { add(0, "Wallpaper") }
    return WorkshopSteamTagCriteria(
        requiredTags = cleanRequired.distinct().take(MAX_WORKSHOP_QUERY_TAGS),
        excludedTags = excluded.distinct().take(MAX_WORKSHOP_QUERY_TAGS),
        allowedTypes =
            selectedTypes.mapNotNull { tag ->
                when (tag) {
                    "Scene" -> WorkshopType.SCENE
                    "Video" -> WorkshopType.VIDEO
                    "Web" -> WorkshopType.WEB
                    else -> null
                }
            }.toSet(),
    )
}

fun WorkshopBrowseQuery.matchesSteamWallpaper(summary: WorkshopSummary): Boolean {
    val criteria = steamTagCriteria()
    val itemTags = summary.tags.map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank).toSet()
    if ("asset" in itemTags) return false
    if (summary.type !in criteria.allowedTypes) return false
    val detailRequired = criteria.requiredTags.filterNot { it.equals("Wallpaper", ignoreCase = true) }
    if (!detailRequired.all { it.lowercase(Locale.ROOT) in itemTags }) return false
    if (!allowNsfw && itemTags.any { it in BLOCKED_WORKSHOP_TAGS_LOWERCASE }) return false
    val requestedCreator = creatorId?.trim().orEmpty()
    if (requestedCreator.isNotEmpty() && summary.creatorId != requestedCreator) return false
    return true
}

fun WorkshopBrowseQuery.needsQuestionableRatingFallback(): Boolean {
    val excluded = steamTagCriteria().excludedTags.toSet()
    return "Everyone" in excluded && "Questionable" in excluded
}

fun WorkshopBrowseQuery.allowQuestionableRatingFallback(): WorkshopBrowseQuery =
    copy(ratings = setOf(WorkshopRating.QUESTIONABLE, WorkshopRating.MATURE))

fun WorkshopBrowseQuery.effectiveWorkshopTypes(): Set<WorkshopType> =
    types.filter { it != WorkshopType.UNKNOWN }.toSet().ifEmpty {
        type?.takeIf { it != WorkshopType.UNKNOWN }?.let(::setOf).orEmpty()
    }

fun WorkshopBrowseQuery.effectiveWorkshopRatings(): Set<WorkshopRating> =
    ratings.ifEmpty { setOf(WorkshopRating.EVERYONE) }

fun WorkshopType.steamWorkshopTag(): String? =
    when (this) {
        WorkshopType.VIDEO -> "Video"
        WorkshopType.SCENE -> "Scene"
        WorkshopType.WEB -> "Web"
        WorkshopType.UNKNOWN -> null
    }

fun WorkshopSort.steamQueryType(): Int =
    when (this) {
        WorkshopSort.TRENDING -> 3
        WorkshopSort.MOST_RECENT -> 1
        WorkshopSort.TOP_RATED -> 0
        WorkshopSort.MOST_VOTES -> 11
        WorkshopSort.MOST_SUBSCRIBERS -> 9
    }

fun String.validSteamAuthorIdOrNull(): Long? =
    trim().takeIf { it.matches(Regex("^7656119[0-9]{10}$")) }?.toLongOrNull()

val VISIBLE_WORKSHOP_TYPES = setOf(WorkshopType.SCENE, WorkshopType.VIDEO, WorkshopType.WEB)
val STEAM_WORKSHOP_TYPE_TAGS = setOf("Scene", "Video", "Web", "Application")
val STEAM_CONTENT_RATING_TAGS = setOf("Everyone", "Questionable", "Mature")
val BLOCKED_WORKSHOP_TAGS =
    setOf("Mature", "Adult Only Sexual Content", "Sexual Content", "Nudity", "R18", "NSFW")
private val BLOCKED_WORKSHOP_TAGS_LOWERCASE = BLOCKED_WORKSHOP_TAGS.map { it.lowercase(Locale.ROOT) }.toSet()

val WORKSHOP_RESOLUTION_TAG_MAP =
    mapOf(
        "Standard" to "Standard Definition",
        "1280 x 720" to "1280 x 720",
        "1366 x 768" to "1366 x 768",
        "1920 x 1080" to "1920 x 1080",
        "2560 x 1440" to "2560 x 1440",
        "3840 x 2160" to "3840 x 2160",
        "Ultrawide" to "Ultrawide Standard Definition",
        "2560 x 1080" to "Ultrawide 2560 x 1080",
        "3440 x 1440" to "Ultrawide 3440 x 1440",
        "Dual monitor" to "Dual Standard Definition",
        "3840 x 1080" to "Dual 3840 x 1080",
        "5120 x 1440" to "Dual 5120 x 1440",
        "7680 x 2160" to "Dual 7680 x 2160",
        "Triple monitor" to "Triple Standard Definition",
        "4096 x 768" to "Triple 4096 x 768",
        "5760 x 1080" to "Triple 5760 x 1080",
        "7680 x 1440" to "Triple 7680 x 1440",
        "11520 x 2160" to "Triple 11520 x 2160",
        "Portrait" to "Portrait Standard Definition",
        "720 x 1280" to "Portrait 720 x 1280",
        "1080 x 1920" to "Portrait 1080 x 1920",
        "1440 x 2560" to "Portrait 1440 x 2560",
        "2160 x 3840" to "Portrait 2160 x 3840",
        "Other resolution" to "Other resolution",
        "Dynamic resolution" to "Dynamic resolution",
    )
val STEAM_WORKSHOP_RESOLUTION_TAGS = WORKSHOP_RESOLUTION_TAG_MAP.values.toSet()

const val MAX_WORKSHOP_DETAIL_SEARCH_TAGS = 12
const val MAX_WORKSHOP_QUERY_TAGS = 48
