package com.wallhub.android.feature.home

import com.wallhub.android.core.model.WorkshopFilterCatalog
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopType
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeFilterSelectionTest {
    @Test
    fun `defaults have no active filter sections`() {
        assertEquals(0, HomeFilterSelection.defaults().activeSectionCount())
    }

    @Test
    fun `active count reports sections instead of individual tags`() {
        val selection = HomeFilterSelection.defaults().copy(
            sort = WorkshopSort.MOST_RECENT,
            types = setOf(WorkshopType.SCENE, WorkshopType.VIDEO),
            ratings = setOf(WorkshopRating.QUESTIONABLE),
            genres = setOf("Abstract", "Nature"),
            officialTags = setOf("Approved", "HDR"),
            resolutions = setOf("1920 x 1080", "2560 x 1440"),
        )

        assertEquals(6, selection.activeSectionCount())
    }

    @Test
    fun `normalization converts empty bounded selections to unrestricted`() {
        val normalized = HomeFilterSelection.defaults().copy(
            days = 900,
            types = setOf(WorkshopType.UNKNOWN, WorkshopType.WEB),
            ratings = setOf(WorkshopRating.MATURE),
            genres = emptySet(),
            officialTags = setOf("Approved", "not-a-tag"),
            resolutions = emptySet(),
        ).normalized(matureContentEnabled = false)

        assertEquals(365, normalized.days)
        assertEquals(setOf(WorkshopType.WEB), normalized.types)
        assertEquals(setOf(WorkshopRating.EVERYONE), normalized.ratings)
        assertEquals(WorkshopFilterCatalog.genres.toSet(), normalized.genres)
        assertEquals(setOf("Approved"), normalized.officialTags)
        assertEquals(WorkshopFilterCatalog.resolutions.toSet(), normalized.resolutions)
    }

    @Test
    fun `all rating excludes mature content when mature content is disabled`() {
        val normalized = HomeFilterSelection.defaults().copy(
            ratings = setOf(WorkshopRating.ALL),
        ).normalized(matureContentEnabled = false)

        assertEquals(
            setOf(WorkshopRating.EVERYONE, WorkshopRating.QUESTIONABLE),
            normalized.ratings,
        )
    }

    @Test
    fun `all rating remains unrestricted when mature content is enabled`() {
        val normalized = HomeFilterSelection.defaults().copy(
            ratings = setOf(WorkshopRating.ALL, WorkshopRating.EVERYONE),
        ).normalized(matureContentEnabled = true)

        assertEquals(setOf(WorkshopRating.ALL), normalized.ratings)
    }

    @Test
    fun `bounded inversion selects the complement`() {
        assertEquals(
            setOf("B", "D"),
            setOf("A", "C").invertBounded(setOf("A", "B", "C", "D")),
        )
    }

    @Test
    fun `bounded inversion returns unrestricted when complement is empty`() {
        val allOptions = setOf("A", "B")

        assertEquals(allOptions, allOptions.invertBounded(allOptions))
    }

}
