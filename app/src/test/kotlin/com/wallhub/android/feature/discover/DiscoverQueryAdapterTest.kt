package com.wallhub.android.feature.discover

import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.steamSearchText
import com.wallhub.android.feature.discover.model.OfficialDiscoverCategory
import com.wallhub.android.feature.discover.model.OfficialDiscoverDescriptor
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoverQueryAdapterTest {
    private val adapter = DiscoverQueryAdapter()

    @Test
    fun `official query types preserve their distinct sort and time window`() {
        val descriptor = descriptor(
            queryTypes = listOf("trend_month", "trend_year", "published_votes", "published_desc"),
        )

        val plans = adapter.adapt(descriptor, SteamWorkshopDataSource.WEB_API).associateBy { it.semantic }

        assertEquals(WorkshopSort.TRENDING, plans.getValue("trend_month").query?.sort)
        assertEquals(30, plans.getValue("trend_month").query?.days)
        assertEquals(365, plans.getValue("trend_year").query?.days)
        assertEquals(WorkshopSort.MOST_VOTES, plans.getValue("published_votes").query?.sort)
        assertEquals(WorkshopSort.MOST_RECENT, plans.getValue("published_desc").query?.sort)
    }

    @Test
    fun `web api preserves keyword exact tags groups and created range`() {
        val descriptor = descriptor(
            keyword = "city rain",
            exact = true,
            includeTags = listOf("Approved"),
            excludeTags = listOf("Unspecified"),
            requiredTagGroups = listOf(listOf("Scene", "Video")),
            timestampStart = 1_700_000_000L,
            timestampEnd = 1_710_000_000L,
        )

        val plan = adapter.adapt(descriptor, SteamWorkshopDataSource.WEB_API).single()

        assertEquals("\"city rain\"", plan.query?.steamSearchText())
        assertEquals(setOf("Approved"), plan.query?.tags)
        assertEquals(setOf("Unspecified"), plan.query?.excludedTags)
        assertEquals(listOf(setOf("Scene", "Video")), plan.query?.requiredTagGroups)
        assertEquals(1_700_000_000L, plan.query?.createdAfterEpochSeconds)
        assertEquals(DiscoverQueryFidelity.DEGRADED, plan.fidelity)
        assertTrue(DiscoverQueryDegradation.EXACT_KEYWORD_USES_QUOTED_SEARCH in plan.degradations)
    }

    @Test
    fun `community reports created range loss and applies tag groups as client filter`() {
        val descriptor = descriptor(
            requiredTagGroups = listOf(listOf("Scene", "Video"), listOf("Approved")),
            timestampStart = 1_700_000_000L,
        )

        val plan = adapter.adapt(descriptor, SteamWorkshopDataSource.COMMUNITY_HTML).single()

        assertNull(plan.query?.createdAfterEpochSeconds)
        assertTrue(plan.query?.requiredTagGroups.isNullOrEmpty())
        assertEquals(2, plan.clientRequiredTagGroups.size)
        assertEquals(DiscoverQueryFidelity.DEGRADED, plan.fidelity)
        assertTrue(DiscoverQueryDegradation.COMMUNITY_CREATED_RANGE_UNSUPPORTED in plan.degradations)
        assertTrue(DiscoverQueryDegradation.COMMUNITY_TAG_GROUPS_CLIENT_FILTERED in plan.degradations)
    }

    @Test
    fun `creator and collection stay independent from generic wallpaper browse`() {
        val creator = descriptor(
            category = OfficialDiscoverCategory.CREATOR,
            itemId = "76561198000000000",
            queryTypes = listOf("published_votes"),
        )
        val collection = descriptor(
            category = OfficialDiscoverCategory.COLLECTION,
            itemId = "1234567890",
            queryTypes = emptyList(),
        )

        val creatorPlan = adapter.adapt(creator, SteamWorkshopDataSource.CM_WEBSOCKET).single()
        val collectionPlan = adapter.adapt(collection, SteamWorkshopDataSource.CM_WEBSOCKET).single()

        assertEquals("76561198000000000", creatorPlan.query?.creatorId)
        assertTrue(DiscoverQueryDegradation.CREATOR_SORT_APPROXIMATED_BY_RECENCY in creatorPlan.degradations)
        assertTrue(collectionPlan.isExecutable)
        assertEquals(1234567890L, collectionPlan.collectionId)
        assertEquals(DiscoverQueryFidelity.EXACT, collectionPlan.fidelity)
    }

    @Test
    fun `static official rails keep recent positive and mobile semantics distinct`() {
        val recent = adapter.recentPositive()
        val mobile = adapter.mobileEssentials(
            dataSource = SteamWorkshopDataSource.WEB_API,
            requiredTagGroups = listOf(setOf("Scene", "Video")),
        )

        assertEquals(WorkshopSort.MOST_RECENT, recent.query?.sort)
        assertEquals(setOf("Approved"), recent.query?.officialTags)
        assertEquals(setOf("Unspecified"), recent.query?.excludedTags)
        assertFalse(recent.query?.mobileCompatibleOnly ?: true)
        assertTrue(mobile.query?.mobileCompatibleOnly == true)
        assertEquals(listOf(setOf("Scene", "Video")), mobile.query?.requiredTagGroups)
    }

    private fun descriptor(
        category: OfficialDiscoverCategory = OfficialDiscoverCategory.KEYWORD,
        itemId: String? = null,
        keyword: String? = null,
        exact: Boolean = false,
        queryTypes: List<String> = listOf("top_rated"),
        includeTags: List<String> = emptyList(),
        excludeTags: List<String> = emptyList(),
        requiredTagGroups: List<List<String>> = emptyList(),
        timestampStart: Long? = null,
        timestampEnd: Long? = null,
    ) = OfficialDiscoverDescriptor(
        stableId = "test",
        category = category,
        itemId = itemId,
        keyword = keyword,
        exact = exact,
        queryTypes = queryTypes,
        includeTags = includeTags,
        excludeTags = excludeTags,
        requiredTagGroups = requiredTagGroups,
        timestampStart = timestampStart,
        timestampEnd = timestampEnd,
    )
}
