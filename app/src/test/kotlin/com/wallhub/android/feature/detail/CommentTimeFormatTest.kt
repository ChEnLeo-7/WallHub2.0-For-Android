package com.wallhub.android.feature.detail

import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.WorkshopComment
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals

class CommentTimeFormatTest {
    private val zone = ZoneId.systemDefault()
    private val now =
        LocalDateTime
            .of(2026, 7, 15, 12, 0, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `recent comments use web relative time rules`() {
        assertEquals(
            "5 分钟以前",
            formatCommentDate(commentAt(now - 5 * 60_000L), AppLanguage.ZH, now),
        )
        assertEquals(
            "3 小时以前",
            formatCommentDate(commentAt(now - 3 * 60 * 60_000L), AppLanguage.ZH, now),
        )
    }

    @Test
    fun `older comments omit only the current year`() {
        val sameYear =
            LocalDateTime
                .of(2026, 6, 11, 15, 4, 3)
                .atZone(zone)
                .toEpochSecond()
        val priorYear =
            LocalDateTime
                .of(2025, 6, 11, 15, 4, 3)
                .atZone(zone)
                .toEpochSecond()

        assertEquals(
            "06 月 11 日 下午 03:04:03",
            formatCommentDate(
                WorkshopComment(author = "A", text = "B", timestamp = sameYear),
                AppLanguage.ZH,
                now,
            ),
        )
        assertEquals(
            "2025 年 06 月 11 日 下午 03:04:03",
            formatCommentDate(
                WorkshopComment(author = "A", text = "B", timestamp = priorYear),
                AppLanguage.ZH,
                now,
            ),
        )
    }

    private fun commentAt(timestampMillis: Long) =
        WorkshopComment(
            author = "A",
            text = "B",
            timestamp = timestampMillis / 1_000L,
        )
}
