package com.wallhub.android.data.workshop

import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val MAX_COMMUNITY_HTML_BYTES = 2 * 1024 * 1024

/** Public filedetails markup, observed on item 3804689861 (2026-10-22).
 * Display sizes/dates are rounded/localized, not content identities. Leave them unknown so
 * the existing SDK resolver, rather than a preview UGC URL, supplies download metadata.
 */
internal fun parseCommunityHtmlDetail(html: String, workshopId: Long): WorkshopDetail {
    require(workshopId > 0L) { "Invalid Workshop item ID" }
    require(html.length <= MAX_COMMUNITY_HTML_BYTES) { "Steam Community detail exceeds the HTML limit" }
    // Ignore script/template text and comments, which may contain lookalike element markup.
    val markup = HTML_INERT.replace(html, "")
    val title = detailText(detailBlocks(markup, "workshopItemTitle").firstOrNull().orEmpty())
    check(title.isNotBlank()) { "Steam Community did not return a public Workshop detail page" }
    val description = detailBlocks(markup, "workshopItemDescription").firstOrNull().orEmpty()
    val creators = detailBlocks(markup, "creatorsBlock").firstOrNull().orEmpty()
    val author = detailBlocks(creators, "friendBlockContent").firstOrNull()
        ?.split(Regex("(?i)<br\\b"), limit = 2)?.first()
        ?.let(::detailText).orEmpty()
    val creatorId = Regex("""\bdata-miniprofile\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE)
        .find(creators)?.groupValues?.get(1)?.toLongOrNull()
        ?.takeIf { it in 1..0xffff_ffffL }
        ?.let { (76_561_197_960_265_728L + it).toString() }
    val tags = detailBlocks(markup, "workshopTags").flatMap { block ->
        HTML_ANCHOR.findAll(block).mapNotNull { link ->
            // Canonical tag values in href remain English even when visible labels are localized.
            val href = detailAttribute(link.value.substringBefore('>'), "href").replace("&amp;", "&")
            href.substringAfter('?', "").split('&').firstNotNullOfOrNull { part ->
                val key = part.substringBefore('=')
                if (key.equals("requiredtags%5B%5D", true) || key == "requiredtags[]") {
                    runCatching { java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrNull()
                } else null
            }
        }.toList()
    }.filter(String::isNotBlank).distinct().take(128)
    val preview = detailPreviewUrl(markup)
    val avatar = detailBlocks(creators, "playerAvatar").firstOrNull()
        ?.let { HTML_IMAGE.find(it)?.value }?.let { detailAttribute(it, "src") }?.let(::detailHttpsUrl)
    return WorkshopDetail(
        summary = WorkshopSummary(
            id = workshopId,
            title = title,
            author = author,
            creatorId = creatorId,
            authorAvatarUrl = avatar,
            previewUrl = preview,
            type = when {
                tags.any { it.equals("Video", true) } -> WorkshopType.VIDEO
                tags.any { it.equals("Scene", true) } -> WorkshopType.SCENE
                tags.any { it.lowercase() in setOf("web", "website", "application") } -> WorkshopType.WEB
                else -> WorkshopType.UNKNOWN
            },
            tags = tags,
            authorPlaceholder = if (author.isBlank()) WorkshopAuthorPlaceholder.CREATOR else WorkshopAuthorPlaceholder.NONE,
        ),
        description = detailText(description),
        previewMediaUrl = preview,
        creatorId = creatorId,
    )
}

/** The webpage thumbnail may contain baked-in black borders; use its own enlargement link. */
private fun detailPreviewUrl(markup: String): String? {
    val image = HTML_IMAGE.findAll(markup)
        .firstOrNull {
            detailAttribute(it.value, "id") in setOf("previewImageMain", "previewImage")
        } ?: return null
    val thumbnail = detailHttpsUrl(detailAttribute(image.value, "src")) ?: return null
    val thumbnailUrl = thumbnail.toHttpUrlOrNull()
        ?.takeIf(::isSteamPreviewUrl)
        ?: return null
    val anchor = HTML_ANCHOR.findAll(markup).firstOrNull {
        image.range.first in it.range && image.range.last in it.range
    } ?: return thumbnail
    val openingTag = HTML_ANCHOR_OPEN.find(anchor.value)?.value ?: return thumbnail
    val action = detailAttribute(openingTag, "onclick")
        .replace("&quot;", "\"").replace("&#39;", "'")
    // Extract a literal URL only. Never execute page JavaScript or accept another image/host.
    val enlarged = ENLARGED_PREVIEW.matchEntire(action)?.let {
        it.groupValues[1].ifEmpty { it.groupValues[2] }
    }?.let(::detailHttpsUrl)?.toHttpUrlOrNull() ?: return thumbnail
    if (enlarged.host != thumbnailUrl.host || enlarged.port != thumbnailUrl.port ||
        enlarged.encodedPath != thumbnailUrl.encodedPath ||
        enlarged.username.isNotEmpty() || enlarged.password.isNotEmpty()
    ) return thumbnail
    if (!isSteamPreviewUrl(enlarged)) return thumbnail
    return enlarged.newBuilder()
        .setQueryParameter("imw", "1920")
        .setQueryParameter("imh", "1920")
        .setQueryParameter("ima", "fit")
        .setQueryParameter("letterbox", "false")
        .build().toString()
}

private fun isSteamPreviewUrl(url: okhttp3.HttpUrl): Boolean =
    url.scheme == "https" &&
        url.host == "images.steamusercontent.com" &&
        url.port == 443 &&
        url.username.isEmpty() &&
        url.password.isEmpty() &&
        url.encodedPath.startsWith("/ugc/")

private val HTML_ANCHOR_OPEN = Regex("""(?is)<a\b(?:"[^"]*"|'[^']*'|[^'">]++)*+>""")
private val ENLARGED_PREVIEW = Regex("""\s*ShowEnlargedImagePreview\(\s*(?:'([^'\\]*)'|"([^"\\]*)")\s*\)\s*;?\s*""")

/** Match exact class tokens and balance nested divs; never stop at a description's first child. */
private fun detailBlocks(html: String, className: String): List<String> {
    val blocks = mutableListOf<String>()
    var depth = 0
    var start = -1
    for (tag in HTML_DIV.findAll(html)) {
        if (tag.value.startsWith("</")) {
            if (depth > 0 && --depth == 0) {
                blocks.add(html.substring(start, tag.range.first))
                if (blocks.size == 128) break
            }
        } else if (depth > 0) {
            check(++depth <= 128) { "Steam Community HTML nesting exceeds the limit" }
        } else if (detailAttribute(tag.value, "class").split(Regex("\\s+")).contains(className)) {
            depth = 1
            start = tag.range.last + 1
        }
    }
    check(depth == 0) { "Steam Community detail markup is incomplete" }
    return blocks
}

private fun detailAttribute(tag: String, name: String): String =
    Regex("""(?:\s)$name\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
        .find(tag)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }.orEmpty()

private fun detailHttpsUrl(value: String): String? =
    value.replace("&amp;", "&").takeIf { it.startsWith("https://") }

private fun detailText(value: String): String = CommunityWorkshopParser.cleanHtml(value)

private val HTML_INERT = Regex("""(?is)<!--.*?(?:-->|$)|<(script|style)\b[^>]*>.*?(?:</\1\s*>|$)""")
private val HTML_DIV = Regex("""(?is)</?div\b(?:"[^"]*"|'[^']*'|[^'">]++)*+>""")
private val HTML_ANCHOR = Regex("""(?is)<a\b(?:"[^"]*"|'[^']*'|[^'">]++)*+>(.*?)</a\s*>""")
private val HTML_IMAGE = Regex("""(?is)<img\b(?:"[^"]*"|'[^']*'|[^'">]++)*+>""")
