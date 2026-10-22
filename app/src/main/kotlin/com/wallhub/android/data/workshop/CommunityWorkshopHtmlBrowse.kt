package com.wallhub.android.data.workshop

import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.matchesSteamWallpaper
import org.json.JSONArray
import org.json.JSONObject

private fun boundedCommunityJson(text: String): JSONObject {
    require(text.length <= MAX_COMMUNITY_HTML_BYTES) { "Steam Community JSON exceeds the limit" }
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
        } else {
            when (char) {
                '"' -> quoted = true
                '{', '[' -> require(++depth <= 64) { "Steam Community JSON nesting exceeds the limit" }
                '}', ']' -> require(--depth >= 0) { "Steam Community JSON is incomplete" }
            }
        }
    }
    require(!quoted && depth == 0) { "Steam Community JSON is incomplete" }
    return JSONObject(text)
}

/** Steam's public SSR browse envelope, observed 2026-10-22; no scripts are executed. */
internal fun parseCommunityHtmlBrowse(html: String, query: WorkshopBrowseQuery): WorkshopPage {
    require(html.length <= MAX_COMMUNITY_HTML_BYTES) { "Steam Community browse exceeds the HTML limit" }
    val script = Regex("""(?is)<script\b[^>]*\bid=["']valve-ssr-data["'][^>]*>(.*?)</script\s*>""")
        .find(html)?.groupValues?.get(1)
        ?: error("Steam Community returned an unsupported Workshop browse page")
    val envelope = boundedCommunityJson(script)
    val queries = boundedCommunityJson(envelope.getJSONObject("renderContext").getString("queryData")).getJSONArray("queries")
    require(queries.length() <= 512) { "Steam Community browse query limit exceeded" }
    val data = (0 until queries.length()).asSequence().mapNotNull { index ->
        queries.optJSONObject(index)?.takeIf { it.optJSONArray("queryKey")?.optString(0) == "workshop_browse" }
            ?.optJSONObject("state")?.optJSONObject("data")
    }.firstOrNull() ?: error("Steam Community returned no Workshop browse results")
    check(data.optInt("eresult") == 1) { "Steam Community Workshop browse failed" }
    val rows = data.getJSONArray("results")
    require(rows.length() <= 100) { "Steam Community browse result limit exceeded" }
    val profiles = data.optJSONArray("creator_player_link_details") ?: JSONArray()
    require(profiles.length() <= 100) { "Steam Community creator limit exceeded" }
    val names = (0 until profiles.length()).mapNotNull { index ->
        val profile = profiles.optJSONObject(index)?.optJSONObject("public_data") ?: return@mapNotNull null
        val id = profile.optString("steamid")
        val name = profile.optString("persona_name")
        if (id.isBlank() || name.isBlank()) null else id to name
    }.toMap()
    val adaptedRows = JSONArray()
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        // The SSR success is page-scoped, unlike the Web API's per-row result field.
        adaptedRows.put(row.put("result", 1))
    }
    val items = CommunityWorkshopParser.parseDetails(
        JSONObject().put("response", JSONObject().put("publishedfiledetails", adaptedRows)).toString(),
    ).map { it.summary }.map { summary ->
        summary.creatorId?.let(names::get)?.let { name ->
            summary.copy(author = name, authorPlaceholder = WorkshopAuthorPlaceholder.NONE)
        } ?: summary
    }.filter { query.matchesSteamWallpaper(it) }.take(query.communityBrowsePageSize())
    val total = data.optLong("total_count", -1).takeIf { it >= 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    val pages = data.optInt("total_pages", 0).takeIf { it > 0 }
    return WorkshopPage(
        items = items,
        page = query.page,
        hasNextPage = pages?.let { query.page < it } ?: (rows.length() >= query.communityBrowsePageSize()),
        totalCount = total,
        totalPages = pages,
    )
}
