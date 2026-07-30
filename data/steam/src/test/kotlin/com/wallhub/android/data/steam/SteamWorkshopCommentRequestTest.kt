package com.wallhub.android.data.steam

import com.wallhub.android.core.model.WORKSHOP_COMMENT_MAX_LENGTH
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SteamWorkshopCommentRequestTest {
    @Test
    fun `normalizes owner and comment whitespace`() {
        assertEquals(
            NormalizedWorkshopCommentRequest(
                workshopId = 123L,
                ownerId = "76561198000000001",
                text = "一条评论",
            ),
            normalizeWorkshopCommentRequest(
                workshopId = 123L,
                ownerId = " 76561198000000001 ",
                text = "  一条评论\n",
            ),
        )
    }

    @Test
    fun `rejects invalid ids and blank comments`() {
        assertFailsWith<IllegalArgumentException> {
            normalizeWorkshopCommentRequest(0L, "76561198000000001", "comment")
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeWorkshopCommentRequest(123L, "not-a-steam-id", "comment")
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeWorkshopCommentRequest(123L, "76561198000000001", "  ")
        }
    }

    @Test
    fun `accepts limit and rejects oversized comments`() {
        normalizeWorkshopCommentRequest(
            workshopId = 123L,
            ownerId = "76561198000000001",
            text = "a".repeat(WORKSHOP_COMMENT_MAX_LENGTH),
        )
        assertFailsWith<IllegalArgumentException> {
            normalizeWorkshopCommentRequest(
                workshopId = 123L,
                ownerId = "76561198000000001",
                text = "a".repeat(WORKSHOP_COMMENT_MAX_LENGTH + 1),
            )
        }
    }
}
