package com.wallhub.android.data.workshop

import com.wallhub.android.core.model.WorkshopType
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test

class CommunityWorkshopParserTest {
    @Test
    fun `extracts paging metadata from Steam SSR data`() {
        val html = """
            <script>
            window.SSR.loaderData = ["{\\\"state\\\":{\\\"data\\\":{\\\"current_page\\\":1,\\\"total_pages\\\":1000,\\\"total_count\\\":2840535}}}"];
            </script>
        """.trimIndent()

        assertEquals(1_000, CommunityWorkshopParser.extractTotalPages(html))
        assertEquals(2_840_535, CommunityWorkshopParser.extractTotalCount(html))
    }

    @Test
    fun `extracts total count from legacy paging text`() {
        val html = """
            <div class="workshopBrowsePagingInfo">Showing 1-30 of 2,840,535 entries</div>
        """.trimIndent()

        assertEquals(2_840_535, CommunityWorkshopParser.extractTotalCount(html))
    }

    @Test
    fun `extracts author total count from localized paging text`() {
        val html = """
            <div class="workshopBrowsePagingInfo">正在显示第 1 - 9 项，共 330 项条目</div>
        """.trimIndent()

        assertEquals(330, CommunityWorkshopParser.extractTotalCount(html))
    }

    @Test
    fun `extracts unique item ids from current and escaped community links`() {
        val html = """
            <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=123"></a>
            <a href="/sharedfiles/filedetails/?id=123"></a>
            https:\/\/steamcommunity.com\/sharedfiles\/filedetails\/?id=456
        """.trimIndent()

        assertEquals(listOf(123L, 456L), CommunityWorkshopParser.extractItemIds(html))
    }

    @Test
    fun `maps public steam details into a typed workshop detail`() {
        val details = CommunityWorkshopParser.parseDetails(
            """
            {
              "response": {
                "publishedfiledetails": [{
                  "result": 1,
                  "publishedfileid": "123",
                  "title": "Sample video",
                  "creator": "76561198000000000",
                  "preview_url": "https://example.test/preview.jpg",
                  "short_description": "A <b>sample</b> item",
                  "file_size": "1048576",
                  "tags": [{"tag":"Video"}, {"tag":"Anime"}]
                }]
              }
            }
            """.trimIndent(),
        )

        assertEquals(1, details.size)
        assertEquals(123L, details.single().summary.id)
        assertEquals(WorkshopType.VIDEO, details.single().summary.type)
        assertEquals("A sample item", details.single().description)
        assertEquals(1_048_576L, details.single().fileSizeBytes)
        assertEquals("Steam 创作者", details.single().summary.author)
        assertEquals("76561198000000000", details.single().creatorId)
        assertTrue("Anime" in details.single().summary.tags)
    }

    @Test
    fun `extracts creator display name from a workshop creators block`() {
        val html = """
            <div class="creatorsBlock">
                <div class="friendBlockContent">
                    Example &amp; Studio<br>
                    <span class="friendSmallText">Offline</span>
                </div>
            </div>
        """.trimIndent()

        assertEquals("Example & Studio", CommunityWorkshopParser.extractAuthorName(html))
    }

    @Test
    fun parsesPublicCommentBlocks() {
        val comments = CommunityWorkshopParser.parseComments(
            """
            <div id="comment_11">
              <div class="commentthread_comment_avatar">
                <div class="profile_avatar_frame"><img src="https://frames.example.test/frame.png"></div>
                <a data-miniprofile="39734272"><img src="https://avatars.example.test/alice.jpg"></a>
              </div>
              <a class="commentthread_author_link" data-miniprofile="39734272" href="https://steamcommunity.com/profiles/76561198000000000">Alice &amp; Bob</a>
              <span class="commentthread_comment_timestamp" data-timestamp="1712345678">April 6</span>
              <div class="commentthread_comment_text">第一行<br>第二行 &#x1F44D;</div>
            </div>
            <div class="commentthread_comment" id="comment_12">
              <span class="commentthread_author">Carol</span>
              <div class="commentthread_text">Looks great</div>
            </div>
            """.trimIndent(),
            limit = 10,
            creatorId = "76561198000000000",
        )

        assertEquals(2, comments.size)
        assertEquals("Alice & Bob", comments[0].author)
        assertEquals("https://avatars.example.test/alice.jpg", comments[0].avatarUrl)
        assertTrue(comments[0].isCreator)
        assertEquals("第一行\n第二行 👍", comments[0].text)
        assertEquals(1_712_345_678L, comments[0].timestamp)
        assertEquals("Carol", comments[1].author)
        assertEquals("Looks great", comments[1].text)
    }

    @Test
    fun `maps public unified comment response without community html`() {
        val page = parsePublicCommentsPage(
            payload = JSONObject(
                """
                {
                  "steamid":"76561198000000000",
                  "start":0,
                  "count":2,
                  "total_count":3,
                  "comments":[
                    {"steamid":"76561198000000000","timestamp":1712345678,"text":"Creator"},
                    {"steamid":"76561198000000001","timestamp":1712345679,"text":"Reader"}
                  ]
                }
                """.trimIndent(),
            ),
            requestedStart = 0,
            requestedCount = 2,
            creatorId = "76561198000000000",
        )

        assertEquals(2, page.comments.size)
        assertEquals("76561198000000001", page.comments[1].authorId)
        assertTrue(page.comments[0].isCreator)
        assertEquals(2, page.nextStart)
        assertEquals(3, page.total)
        assertTrue(page.hasMore)
    }

    @Test
    fun `ignores profile frames and keeps animated avatar source sets`() {
        val comments = CommunityWorkshopParser.parseComments(
            """
            <div id="comment_21">
              <div class="commentthread_comment_avatar playerAvatar online">
                <div class="profile_avatar_frame"><img src="https://frames.example.test/animated-frame.png"></div>
                <a data-miniprofile="123">
                  <picture><img srcset="https://avatars.example.test/animated-avatar.gif"></picture>
                </a>
              </div>
              <a class="commentthread_author_link" data-miniprofile="123">Animated user</a>
              <div class="commentthread_comment_text">Animated avatar</div>
            </div>
            """.trimIndent(),
            limit = 10,
        )

        assertEquals("https://avatars.example.test/animated-avatar.gif", comments.single().avatarUrl)
    }
}
