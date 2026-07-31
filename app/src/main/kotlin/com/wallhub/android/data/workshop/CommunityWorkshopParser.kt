package com.wallhub.android.data.workshop

import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

internal data class SteamWebProfile(
    val displayName: String,
    val avatarUrl: String?,
)

internal fun parsePublicCommentsPage(
    payload: JSONObject,
    requestedStart: Int,
    requestedCount: Int,
    creatorId: String,
): WorkshopCommentPage {
    val values = payload.optJSONArray("comments") ?: JSONArray()
    val comments =
        buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                if (value.optBoolean("deleted") || value.optBoolean("hidden")) continue
                val text = value.optString("text").trim()
                if (text.isBlank()) continue
                val authorId = value.opt("steamid")?.toString()?.takeIf(String::isNotBlank)
                add(
                    WorkshopComment(
                        author = "Steam 用户",
                        authorId = authorId,
                        text = text,
                        isCreator = authorId == creatorId,
                        timestamp = value.opt("timestamp")?.toString()?.toLongOrNull(),
                    ),
                )
            }
        }
    val start =
        payload
            .opt("start")
            ?.toString()
            ?.toIntOrNull()
            ?.coerceAtLeast(0)
            ?: requestedStart
    val pageCount =
        payload
            .opt("count")
            ?.toString()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: requestedCount
    val total =
        payload
            .opt("total_count")
            ?.toString()
            ?.toIntOrNull()
            ?.coerceAtLeast(0)
    val nextStart = start + pageCount
    return WorkshopCommentPage(
        comments = comments,
        start = start,
        count = pageCount,
        nextStart = nextStart,
        total = total,
        hasMore = if (total != null) nextStart < total else comments.size >= pageCount,
        ownerId = payload.opt("steamid")?.toString()?.takeIf(String::isNotBlank) ?: creatorId,
    )
}

internal object CommunityWorkshopParser {
    private val itemIdPattern =
        Regex(
            """sharedfiles(?:\\/|/)filedetails(?:\\/|/)\?id=(\d+)""",
            RegexOption.IGNORE_CASE,
        )
    private val escapedTotalPagesPattern =
        Regex(
            """(?i)\\+"total_pages\\+"\s*:\s*(\d+)""",
        )
    private val escapedTotalCountPattern =
        Regex(
            """(?i)\\+"total_count\\+"\s*:\s*(\d+)""",
        )
    private val legacyTotalCountPattern =
        Regex(
            """(?i)showing\s+[\d,]+-[\d,]+\s+of\s+([\d,]+)""",
        )
    private val pagingInfoPattern =
        Regex(
            """(?is)<div\b[^>]*\bclass\s*=\s*["'][^"']*\bworkshopBrowsePagingInfo\b[^"']*["'][^>]*>(.*?)</div>""",
        )
    private val pagingInfoNumberPattern = Regex("""\d[\d,]*""")
    private val creatorsBlockPattern =
        Regex(
            """(?i)<div\b[^>]*\bclass\s*=\s*["'][^"']*\bcreatorsBlock\b[^"']*["'][^>]*>""",
        )
    private val commentBlockStartPattern =
        Regex(
            """(?i)<div\b[^>]*\bid\s*=\s*["']comment_\d+["'][^>]*>""",
        )
    private val commentAuthorPattern =
        Regex(
            """(?is)<a\b[^>]*\bclass\s*=\s*["'][^"']*\bcommentthread_author_link\b[^"']*["'][^>]*>(.*?)</a>""",
        )
    private val commentAuthorFallbackPattern =
        Regex(
            """(?is)<span\b[^>]*\bclass\s*=\s*["'][^"']*\bcommentthread_author\b[^"']*["'][^>]*>(.*?)</span>""",
        )
    private val commentTextPattern =
        Regex(
            """(?is)<div\b[^>]*\bclass\s*=\s*["'][^"']*\bcommentthread_(?:comment_)?text\b[^"']*["'][^>]*>(.*?)</div>""",
        )
    private val commentAvatarContainerPattern =
        Regex(
            """(?i)<div\b[^>]*\bclass\s*=\s*["'][^"']*\bcommentthread_comment_avatar\b[^"']*["'][^>]*>""",
        )
    private val commentAvatarProfileLinkPattern =
        Regex(
            """(?is)<a\b(?=[^>]*\bdata-miniprofile\s*=)[^>]*>(.*?)</a>""",
        )
    private val imageTagPattern =
        Regex(
            """(?is)<img\b[^>]*>""",
        )
    private val imageSrcPattern =
        Regex(
            """(?i)\bsrc\s*=\s*["']([^"']+)["']""",
        )
    private val imageSrcSetPattern =
        Regex(
            """(?i)\bsrcset\s*=\s*["']([^"']+)["']""",
        )
    private val miniProfilePattern =
        Regex(
            """(?i)\bdata-miniprofile\s*=\s*["'](\d+)["']""",
        )
    private val profileSteamIdPattern =
        Regex(
            """(?i)\bhref\s*=\s*["'][^"']*/profiles/(\d+)[^"']*["']""",
        )
    private val commentTimestampPattern =
        Regex(
            """(?i)\bdata-(?:time|timestamp)\s*=\s*["'](\d+)["']""",
        )

    fun extractItemIds(html: String): List<Long> {
        val ids = LinkedHashSet<Long>()
        itemIdPattern.findAll(html).forEach { match ->
            match.groupValues[1]
                .toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let(ids::add)
        }
        return ids.toList()
    }

    fun extractTotalPages(html: String): Int? = escapedTotalPagesPattern.extractInt(html)

    fun extractTotalCount(html: String): Int? =
        escapedTotalCountPattern.extractInt(html)
            ?: pagingInfoPattern
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { pagingInfo -> pagingInfoNumberPattern.findAll(pagingInfo).lastOrNull()?.value }
                ?.replace(",", "")
                ?.toLongOrNull()
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?.toInt()
            ?: legacyTotalCountPattern.extractInt(html)

    private fun Regex.extractInt(input: String): Int? =
        find(input)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(",", "")
            ?.toLongOrNull()
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()

    /** Steam's public details endpoint only exposes a creator ID, not the display name. */
    fun extractAuthorName(html: String): String? {
        val creatorsBlock = creatorsBlockPattern.find(html) ?: return null
        val friendContentIndex =
            html.indexOf(
                string = "friendBlockContent",
                startIndex = creatorsBlock.range.last + 1,
                ignoreCase = true,
            )
        if (friendContentIndex < 0) return null

        val contentStart = html.indexOf('>', friendContentIndex)
        if (contentStart < 0) return null
        val lineBreakIndex =
            html.indexOf(
                string = "<br",
                startIndex = contentStart + 1,
                ignoreCase = true,
            )
        val contentEnd =
            if (lineBreakIndex >= 0) {
                lineBreakIndex
            } else {
                html.indexOf("</div>", contentStart + 1, ignoreCase = true)
            }
        if (contentEnd <= contentStart) return null

        return cleanHtml(html.substring(contentStart + 1, contentEnd))
            .lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
    }

    fun parseDetails(json: String): List<WorkshopDetail> {
        val response = JSONObject(json).optJSONObject("response") ?: return emptyList()
        val details = response.optJSONArray("publishedfiledetails") ?: JSONArray()
        return buildList {
            for (index in 0 until details.length()) {
                val detail = details.optJSONObject(index) ?: continue
                if (detail.jsonLong("result") != RESULT_OK) continue
                val id = detail.jsonLong("publishedfileid")
                if (id <= 0L) continue
                add(detail.toWorkshopDetail(id))
            }
        }
    }

    fun parseComments(
        html: String,
        limit: Int,
        creatorId: String? = null,
    ): List<WorkshopComment> {
        if (html.isBlank()) return emptyList()
        val starts = commentBlockStartPattern.findAll(html).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        return buildList {
            starts.forEachIndexed { index, start ->
                val end = starts.getOrNull(index + 1) ?: html.length
                val block = html.substring(start, end)
                val text =
                    commentTextPattern
                        .find(block)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.let(::cleanHtml)
                        .orEmpty()
                if (text.isBlank()) return@forEachIndexed
                val authorLink = commentAuthorPattern.find(block)
                val author =
                    (
                        authorLink?.groupValues?.getOrNull(1)
                            ?: commentAuthorFallbackPattern.find(block)?.groupValues?.getOrNull(1)
                    )?.let(::cleanHtml)
                        ?.takeIf(String::isNotBlank)
                        ?: DEFAULT_COMMENT_AUTHOR
                add(
                    WorkshopComment(
                        author = author,
                        text = text,
                        avatarUrl = extractCommentAvatarUrl(block, authorLink?.range?.first),
                        isCreator = isCreatorComment(authorLink?.value, creatorId),
                        timestamp =
                            commentTimestampPattern
                                .find(block)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toLongOrNull(),
                    ),
                )
                if (size >= limit.coerceIn(1, MAX_COMMENT_PAGE_SIZE)) return@buildList
            }
        }
    }

    private fun extractCommentAvatarUrl(
        block: String,
        authorLinkStart: Int?,
    ): String? {
        val avatarStart = commentAvatarContainerPattern.find(block)?.range?.first ?: return null
        val avatarEnd = authorLinkStart?.takeIf { it > avatarStart } ?: block.length
        val avatarMarkup = block.substring(avatarStart, avatarEnd)
        val profileMarkup =
            commentAvatarProfileLinkPattern
                .find(avatarMarkup)
                ?.groupValues
                ?.getOrNull(1)
                ?: return null
        val imageTag = imageTagPattern.find(profileMarkup)?.value ?: return null
        val rawSource =
            imageSrcPattern.find(imageTag)?.groupValues?.getOrNull(1)
                ?: imageSrcSetPattern.find(imageTag)?.groupValues?.getOrNull(1)
        return rawSource
            ?.substringBefore(',')
            ?.trim()
            ?.substringBefore(' ')
            ?.replace("&amp;", "&")
            ?.takeIf(String::isNotBlank)
    }

    private fun isCreatorComment(
        authorLinkMarkup: String?,
        creatorId: String?,
    ): Boolean {
        val normalizedCreatorId =
            creatorId
                ?.filter(Char::isDigit)
                ?.takeIf(String::isNotBlank)
                ?: return false
        val directProfileId =
            authorLinkMarkup
                ?.let(profileSteamIdPattern::find)
                ?.groupValues
                ?.getOrNull(1)
        if (directProfileId == normalizedCreatorId) return true

        val commenterAccountId =
            authorLinkMarkup
                ?.let(miniProfilePattern::find)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: return false
        val rawCreatorId = normalizedCreatorId.toLongOrNull() ?: return false
        val creatorAccountId =
            if (rawCreatorId >= STEAM_ID64_ACCOUNT_BASE) {
                rawCreatorId - STEAM_ID64_ACCOUNT_BASE
            } else {
                rawCreatorId
            }
        return commenterAccountId == creatorAccountId
    }

    private fun JSONObject.toWorkshopDetail(id: Long): WorkshopDetail {
        val tags = jsonTags("tags")
        val title = jsonString("title").ifBlank { "壁纸 $id" }
        val previewUrl = jsonString("preview_url").ifBlank { null }
        val description =
            cleanHtml(
                jsonString("description").ifBlank {
                    jsonString("short_description")
                },
            )
        val summary =
            WorkshopSummary(
                id = id,
                title = title,
                author = jsonString("creator_name").ifBlank { DEFAULT_AUTHOR_NAME },
                creatorId = jsonString("creator").takeIf(String::isNotBlank),
                previewUrl = previewUrl,
                type = classifyType(tags),
                tags = tags,
                subscriptions =
                    jsonLong("subscriptions").takeIf { it > 0L }
                        ?: jsonLong("lifetime_subscriptions").takeIf { it > 0L },
                favorites =
                    jsonLong("favorited").takeIf { it > 0L }
                        ?: jsonLong("lifetime_favorited").takeIf { it > 0L },
                views = jsonLong("views").takeIf { it > 0L },
                fileSizeBytes = jsonLong("file_size").takeIf { it > 0L },
                subscriptionState = SubscriptionState.UNKNOWN,
                favoriteState = FavoriteState.UNKNOWN,
            )
        return WorkshopDetail(
            summary = summary,
            description = description,
            fileSizeBytes = jsonLong("file_size").takeIf { it > 0L },
            previewMediaUrl = previewUrl,
            createdAt = jsonLong("time_created").takeIf { it > 0L },
            updatedAt = jsonLong("time_updated").takeIf { it > 0L },
            subscriptions = jsonLong("subscriptions").takeIf { it > 0L },
            creatorId = jsonString("creator").takeIf(String::isNotBlank),
        )
    }

    private fun JSONObject.jsonTags(name: String): List<String> {
        val values = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                val raw =
                    values.optJSONObject(index)?.optString("tag")
                        ?: values.optString(index)
                raw.trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct()
    }

    private fun JSONObject.jsonString(name: String): String = opt(name)?.toString()?.trim().orEmpty()

    private fun JSONObject.jsonLong(name: String): Long =
        when (val value = opt(name)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }

    private fun classifyType(tags: List<String>): WorkshopType {
        val normalized = tags.map(String::lowercase).toSet()
        return when {
            "video" in normalized -> WorkshopType.VIDEO
            "web" in normalized || "website" in normalized || "application" in normalized -> WorkshopType.WEB
            "scene" in normalized -> WorkshopType.SCENE
            else -> WorkshopType.UNKNOWN
        }
    }

    private fun cleanHtml(value: String): String =
        decodeNumericEntities(
            value
                .replace(Regex("""(?i)<br\s*/?>"""), "\n")
                .replace(Regex("""(?i)</(?:p|div|li|h[1-6])\s*>"""), "\n")
                .replace(Regex("""<[^>]+>"""), "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace(Regex("""[ \t]+\n"""), "\n")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim(),
        )

    private fun decodeNumericEntities(value: String): String {
        val hexadecimal =
            Regex("""&#x([0-9a-fA-F]+);""").replace(value) { match ->
                decodeCodePoint(match.groupValues[1].toIntOrNull(16), match.value)
            }
        return Regex("""&#(\d+);""").replace(hexadecimal) { match ->
            decodeCodePoint(match.groupValues[1].toIntOrNull(), match.value)
        }
    }

    private fun decodeCodePoint(
        codePoint: Int?,
        fallback: String,
    ): String =
        codePoint
            ?.takeIf(Character::isValidCodePoint)
            ?.let { value -> String(Character.toChars(value)) }
            ?: fallback

    private const val RESULT_OK = 1L
    private const val DEFAULT_AUTHOR_NAME = "Steam 创作者"
    private const val DEFAULT_COMMENT_AUTHOR = "Steam 用户"
    private const val MAX_COMMENT_PAGE_SIZE = 50
    private const val STEAM_ID64_ACCOUNT_BASE = 76_561_197_960_265_728L
}
