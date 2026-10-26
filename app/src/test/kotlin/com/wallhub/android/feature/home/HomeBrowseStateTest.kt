package com.wallhub.android.feature.home

import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBrowseStateTest {
    @Test
    fun `default reset clears search author and every changed filter`() {
        val reset =
            HomeUiState(
                query = "forest",
                creatorId = "7656119810",
                exactPhrase = true,
                selectedTypes = setOf(WorkshopType.VIDEO),
                selectedRatings = setOf(WorkshopRating.QUESTIONABLE),
                selectedGenres = setOf("Anime"),
                selectedOfficialTags = setOf("Audio responsive"),
                selectedExcludedOfficialTags = setOf("Mature"),
                selectedResolutions = setOf("4K"),
                requiredTags = setOf("blue"),
                sort = WorkshopSort.TOP_RATED,
                days = 7,
            ).resetForDefaultBrowse()

        assertEquals("", reset.query)
        assertNull(reset.creatorId)
        assertEquals(HomeFilterSelection.defaults(), reset.filterSelection())
        assertTrue(reset.requiredTags.isEmpty())
        assertTrue(reset.selectedExcludedOfficialTags.isEmpty())
    }
}
