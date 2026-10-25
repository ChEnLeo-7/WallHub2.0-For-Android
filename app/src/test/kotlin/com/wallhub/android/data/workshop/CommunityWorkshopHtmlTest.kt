package com.wallhub.android.data.workshop

import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopContentIdentity
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.core.model.WorkshopVideoOpenRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommunityWorkshopHtmlTest {
    @Test
    fun `browse request uses canonical trailing slash endpoint`() {
        val url = buildBrowseUrl(WorkshopBrowseQuery())

        assertTrue(url.startsWith("https://steamcommunity.com/workshop/browse/?"))
        assertTrue("num_per_page=" in url)
    }

    @Test
    fun `detail reads observed markup without treating preview as download metadata`() {
        val detail = parseCommunityHtmlDetail(DETAIL_HTML, 42)
        assertEquals("Title & more", detail.summary.title)
        assertEquals("Creator", detail.summary.author)
        assertEquals("76561197960265851", detail.creatorId)
        assertEquals(WorkshopType.VIDEO, detail.summary.type)
        assertEquals("First\nNested\nLast", detail.description)
        assertEquals("https://images.steamusercontent.com/ugc/999/preview?a=1&b=2", detail.previewMediaUrl)
        assertEquals(WorkshopContentIdentity(), detail.contentIdentity)
        assertNull(detail.fileSizeBytes)
        assertNull(detail.updatedAt)
        assertEquals(42L, WorkshopVideoOpenRequest.from(detail).workshopId)
        assertNull(WorkshopVideoOpenRequest.from(detail).manifestId)
    }

    @Test
    fun `missing optional fields stay unknown and invalid pages fail`() {
        val detail = parseCommunityHtmlDetail("<div class='workshopItemTitle'>Only title</div>", 42)
        assertNull(detail.creatorId)
        assertNull(detail.previewMediaUrl)
        assertEquals(WorkshopType.UNKNOWN, detail.summary.type)
        assertThrows(IllegalStateException::class.java) { parseCommunityHtmlDetail("<h1>Sign in</h1>", 42) }
        assertThrows(IllegalStateException::class.java) {
            parseCommunityHtmlDetail("<div class='workshopItemTitle'>Truncated", 42)
        }
        assertThrows(IllegalStateException::class.java) {
            parseCommunityHtmlDetail("<script>\"<div class='workshopItemTitle'>Fake</div>\"</script>", 42)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCommunityHtmlDetail("x".repeat(MAX_COMMUNITY_HTML_BYTES + 1), 42)
        }
    }

    @Test
    fun `browse consumes observed SSR result tags and exact totals without API enrichment`() {
        val page = parseCommunityHtmlBrowse(browseHtml(), WorkshopBrowseQuery())
        assertEquals(1, page.items.size)
        assertEquals("A video", page.items.single().title)
        assertEquals(WorkshopType.VIDEO, page.items.single().type)
        assertEquals(301, page.totalCount)
        assertEquals(11, page.totalPages)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `missing corrupt and deeply nested browse envelopes fail not empty success`() {
        assertThrows(IllegalStateException::class.java) { parseCommunityHtmlBrowse("<html>Unavailable</html>", WorkshopBrowseQuery()) }
        assertThrows(Exception::class.java) { parseCommunityHtmlBrowse("<script id='valve-ssr-data'>{</script>", WorkshopBrowseQuery()) }
        assertThrows(IllegalArgumentException::class.java) {
            parseCommunityHtmlBrowse("<script id='valve-ssr-data'>" + "[".repeat(65) + "]".repeat(65) + "</script>", WorkshopBrowseQuery())
        }
    }

    @Test
    fun `cover uses its enlargement without letterboxing and bounds requested dimensions`() {
        val base = "https://images.steamusercontent.com/ugc/123/ABC/"
        val html = """
            <div class="workshopItemTitle">Cover</div>
            <a onclick="ShowEnlargedImagePreview( '$base?imw=5000&amp;imh=5000&amp;ima=fit&amp;letterbox=false' );">
                <img id="previewImage" src="$base?imw=637&amp;imh=358&amp;letterbox=true">
            </a>
        """.trimIndent()
        val detail = parseCommunityHtmlDetail(html, 42)
        assertEquals("${base}?imw=1920&imh=1920&ima=fit&letterbox=false", detail.summary.previewUrl)
        assertEquals(detail.summary.previewUrl, detail.previewMediaUrl)
        assertEquals(WorkshopContentIdentity(), detail.contentIdentity)
    }

    @Test
    fun `cover ignores unrelated unsafe or malformed enlargement links`() {
        val thumbnail = "https://images.steamusercontent.com/ugc/123/ABC/?imw=637"
        val actions = listOf(
            "ShowEnlargedImagePreview( 'http://images.steamusercontent.com/ugc/123/ABC/' );",
            "ShowEnlargedImagePreview( 'https://other.example/ugc/123/ABC/' );",
            "ShowEnlargedImagePreview( 'https://images.steamusercontent.com/ugc/999/OTHER/' );",
            "ShowEnlargedImagePreview( '$thumbnail' ); otherFunction();",
        )
        actions.forEach { action ->
            val html = """<div class="workshopItemTitle">Cover</div><a onclick="$action"><img id="previewImage" src="$thumbnail"></a>"""
            assertEquals(thumbnail, parseCommunityHtmlDetail(html, 42).summary.previewUrl)
        }
        val unrelated = """
            <div class="workshopItemTitle">Cover</div>
            <a onclick="ShowEnlargedImagePreview( '$thumbnail' );"><img id="otherImage" src="$thumbnail"></a>
            <img id="previewImage" src="$thumbnail">
        """
        assertEquals(thumbnail, parseCommunityHtmlDetail(unrelated, 42).summary.previewUrl)
        val lookalike = """
            <div class="workshopItemTitle">Cover</div>
            <img id="previewImageMain" src="https://images.steamusercontent.com.evil.example/ugc/123/ABC/">
        """.trimIndent()
        assertNull(parseCommunityHtmlDetail(lookalike, 42).summary.previewUrl)
    }

    companion object {
        // Reduced, synthetic text/URLs; structural selectors match the public 3804689861 page.
        val DETAIL_HTML = """
            <div class="workshopItemTitle">Title &amp; more</div>
            <img id="previewImageMain" src="https://images.steamusercontent.com/ugc/999/preview?a=1&amp;b=2">
            <div class="workshopTags"><span>Type:</span><a href="/workshop/browse/?appid=431960&amp;requiredtags%5B%5D=Video">视频</a></div>
            <div class="creatorsBlock"><div class="friendBlock persona" data-miniprofile="123">
              <div class="playerAvatar"><img src="https://avatars.example/avatar.jpg"></div>
              <div class="friendBlockContent">Creator<br><span>Offline</span></div>
            </div></div>
            <div class="detailsStatRight">174.194 MB</div>
            <div class="workshopItemDescription" id="highlightContent">First<br><div>Nested</div>Last</div>
        """.trimIndent()

        fun browseHtml(): String {
            val row = JSONObject("""{"publishedfileid":"42","title":"A video","tags":[{"tag":"Video"},{"tag":"Wallpaper"},{"tag":"Everyone"}]}""")
            val data = JSONObject().put("eresult", 1).put("total_count", 301).put("total_pages", 11)
                .put("results", JSONArray().put(row))
            val query = JSONObject().put("queryKey", JSONArray().put("workshop_browse"))
                .put("state", JSONObject().put("data", data))
            val envelope = JSONObject().put("renderContext", JSONObject().put("queryData",
                JSONObject().put("queries", JSONArray().put(query)).toString()))
            return "<script type=\"application/json\" id=\"valve-ssr-data\">$envelope</script>"
        }
    }
}
