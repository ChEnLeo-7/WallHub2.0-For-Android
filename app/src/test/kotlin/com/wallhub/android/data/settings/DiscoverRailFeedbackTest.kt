package com.wallhub.android.data.settings

import com.wallhub.android.core.model.DISCOVER_DISLIKE_SCORE_ADJUSTMENT
import com.wallhub.android.core.model.DISCOVER_LIKE_SCORE_ADJUSTMENT
import com.wallhub.android.core.model.DiscoverRailFeedback
import com.wallhub.android.core.model.toDiscoverFeedbackWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverRailFeedbackTest {
    @Test
    fun `liked rail receives like adjustment without suppression`() {
        val weight = DiscoverRailFeedback(railName = "keyword:space:trend_year", liked = true).toDiscoverFeedbackWeight()

        assertEquals(DISCOVER_LIKE_SCORE_ADJUSTMENT, weight.scoreAdjustment)
        assertFalse(weight.suppressCurrentGeneration)
    }

    @Test
    fun `favorite follows a query without changing recommendation weight`() {
        val weight =
            DiscoverRailFeedback(
                railName = "creator:76561198000000000:published_votes",
                liked = true,
                favorited = true,
            ).toDiscoverFeedbackWeight()

        assertEquals(
            DISCOVER_LIKE_SCORE_ADJUSTMENT,
            weight.scoreAdjustment,
        )
        assertFalse(weight.suppressCurrentGeneration)
    }

    @Test
    fun `favorite alone has no recommendation adjustment`() {
        val weight = DiscoverRailFeedback(railName = "keyword:rain", favorited = true).toDiscoverFeedbackWeight()

        assertEquals(0f, weight.scoreAdjustment)
        assertFalse(weight.suppressCurrentGeneration)
    }

    @Test
    fun `disliked rail is penalized and suppressed for current generation`() {
        val weight = DiscoverRailFeedback(railName = "collection:123", disliked = true).toDiscoverFeedbackWeight()

        assertEquals(DISCOVER_DISLIKE_SCORE_ADJUSTMENT, weight.scoreAdjustment)
        assertTrue(weight.suppressCurrentGeneration)
    }

    @Test
    fun `rail cannot be liked and disliked simultaneously`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiscoverRailFeedback(
                railName = "genre:anime:top_rated",
                liked = true,
                disliked = true,
            )
        }
    }
}
