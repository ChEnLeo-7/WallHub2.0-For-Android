package com.wallhub.android.feature.discover

import com.wallhub.android.core.model.CmPublicDetailResult
import com.wallhub.android.core.model.CmPublicDetailsBatch
import com.wallhub.android.core.model.SteamPublicProfile
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopCommentPage
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscoverMetadataResolverTest {
    @Test
    fun `ordinary cards resolve real Steam creators in one deduplicated batch`() = runTest {
        val repository = RecordingUnifiedRepository(
            profiles = mapOf(
                42L to SteamPublicProfile("Alice", "https://steam/avatar/alice"),
                43L to SteamPublicProfile("Bob", "https://steam/avatar/bob"),
            ),
        )
        val resolver = DiscoverMetadataResolver(repository)

        val resolved = resolver.enrichWallpapers(
            listOf(
                unresolvedSummary(id = 1L, creatorId = "42"),
                unresolvedSummary(id = 2L, creatorId = "42"),
                unresolvedSummary(id = 3L, creatorId = "43"),
            ),
        )

        assertEquals(listOf(setOf(42L, 43L)), repository.profileRequests)
        assertEquals(listOf("Alice", "Alice", "Bob"), resolved.map(WorkshopSummary::author))
        assertEquals(
            listOf("https://steam/avatar/alice", "https://steam/avatar/alice", "https://steam/avatar/bob"),
            resolved.map(WorkshopSummary::authorAvatarUrl),
        )
        assertEquals(List(3) { WorkshopAuthorPlaceholder.NONE }, resolved.map(WorkshopSummary::authorPlaceholder))
    }

    @Test
    fun `enrichment preserves trustworthy identity and existing avatar on mixed rows`() = runTest {
        val repository = RecordingUnifiedRepository(
            profiles = mapOf(42L to SteamPublicProfile("Resolved Alice", avatarUrl = null)),
        )
        val resolver = DiscoverMetadataResolver(repository)
        val trusted =
            unresolvedSummary(id = 1L, creatorId = "42").copy(
                author = "Trusted Alice",
                authorAvatarUrl = "https://steam/avatar/trusted",
                authorPlaceholder = WorkshopAuthorPlaceholder.NONE,
            )
        val unresolved =
            unresolvedSummary(id = 2L, creatorId = "42").copy(
                authorAvatarUrl = "https://steam/avatar/existing",
            )

        val resolved = resolver.enrichWallpapers(listOf(trusted, unresolved))

        assertEquals("Trusted Alice", resolved[0].author)
        assertEquals("https://steam/avatar/trusted", resolved[0].authorAvatarUrl)
        assertEquals("Resolved Alice", resolved[1].author)
        assertEquals("https://steam/avatar/existing", resolved[1].authorAvatarUrl)
    }

    @Test
    fun `focus cards use creator profile and collection parent title without exposing ids`() = runTest {
        val collectionId = 987654321L
        val repository = RecordingUnifiedRepository(
            profiles = mapOf(42L to SteamPublicProfile("Alice", "https://steam/avatar/alice")),
            collectionTitles = mapOf(collectionId to "Rainy Day Collection"),
        )
        val resolver = DiscoverMetadataResolver(repository)
        val creator = featuredCandidate(
            id = "creator",
            category = DiscoverCategory.CREATOR,
            creatorId = "42",
            cover = unresolvedSummary(id = 1L, creatorId = "42"),
        )
        val collection = featuredCandidate(
            id = "collection",
            category = DiscoverCategory.COLLECTION,
            collectionId = collectionId,
            cover = unresolvedSummary(id = 2L, creatorId = "43"),
        )

        val resolved = resolver.resolveFeatured(listOf(creator, collection))

        assertEquals(listOf(setOf(42L)), repository.profileRequests)
        assertEquals(listOf(listOf(collectionId)), repository.detailRequests)
        assertEquals("Alice", resolved[0].title)
        assertEquals("https://steam/avatar/alice", resolved[0].cover.authorAvatarUrl)
        assertEquals("Rainy Day Collection", resolved[1].title)
    }

    @Test
    fun `missing focus metadata stays explicitly unresolved instead of using numeric id`() = runTest {
        val repository = RecordingUnifiedRepository()
        val resolver = DiscoverMetadataResolver(repository)
        val collectionId = 987654321L

        val resolved = resolver.resolveFeatured(
            listOf(
                featuredCandidate(
                    id = "collection",
                    category = DiscoverCategory.COLLECTION,
                    collectionId = collectionId,
                    cover = unresolvedSummary(id = 2L, creatorId = "43"),
                ),
            ),
        ).single()

        assertNull(resolved.title)
    }
}

private class RecordingUnifiedRepository(
    private val profiles: Map<Long, SteamPublicProfile> = emptyMap(),
    private val collectionTitles: Map<Long, String> = emptyMap(),
) : SteamUnifiedWorkshopRepository {
    val profileRequests = mutableListOf<Set<Long>>()
    val detailRequests = mutableListOf<List<Long>>()

    override suspend fun browsePublic(query: WorkshopBrowseQuery): WorkshopPage? = null

    override suspend fun getPublicDetail(workshopId: Long): WorkshopDetail? = null

    override suspend fun getAuthenticatedComments(
        workshopId: Long,
        start: Int,
        count: Int,
        ownerId: String,
    ): WorkshopCommentPage? = null

    override suspend fun resolvePublicProfiles(accountIds: Collection<Long>): Map<Long, SteamPublicProfile> {
        profileRequests += accountIds.toSet()
        return profiles.filterKeys(accountIds.toSet()::contains)
    }

    override suspend fun getPublicDetailsResult(workshopIds: List<Long>): CmPublicDetailsBatch {
        detailRequests += workshopIds
        return CmPublicDetailsBatch(
            workshopIds.map { id ->
                id to (
                    collectionTitles[id]?.let { title ->
                        CmPublicDetailResult.Available(
                            WorkshopDetail(WorkshopSummary(id = id, title = title, author = "")),
                        )
                    } ?: CmPublicDetailResult.NotFound
                )
            },
        )
    }
}

private fun unresolvedSummary(id: Long, creatorId: String) =
    WorkshopSummary(
        id = id,
        title = "Cover $id",
        author = "",
        creatorId = creatorId,
        authorPlaceholder = WorkshopAuthorPlaceholder.USER_WITH_ID,
    )

private fun featuredCandidate(
    id: String,
    category: DiscoverCategory,
    creatorId: String? = null,
    collectionId: Long? = null,
    cover: WorkshopSummary,
): DiscoverFeaturedCandidate =
    DiscoverFeaturedCandidate(
        spec = DiscoverRailSpec(
            id = id,
            category = category,
            semantic = id,
            titleKind = if (category == DiscoverCategory.CREATOR) DiscoverTitleKind.CREATOR else DiscoverTitleKind.COLLECTION,
            titleArgument = collectionId?.toString(),
            queryPlan = DiscoverQueryPlan(
                descriptorId = id,
                semantic = id,
                query = WorkshopBrowseQuery(creatorId = creatorId),
                collectionId = collectionId,
                fidelity = DiscoverQueryFidelity.EXACT,
            ),
            priority = 1f,
            weight = 1,
            sticky = false,
            source = DiscoverSpecSource.CURATOR_MANIFEST,
            drillDown = DiscoverDrillDown.FULL_QUERY_RESULTS,
        ),
        cover = cover,
    )
