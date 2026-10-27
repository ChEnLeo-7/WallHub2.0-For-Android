package com.wallhub.android.data.steam

import com.wallhub.android.core.model.AccountWorkshopCollection
import com.wallhub.android.core.model.AccountWorkshopQuery
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountWorkshopPageSnapshotStoreTest {
    @Test
    fun `round trip preserves freshness and every Workshop page and summary field`() {
        val files = FakeSnapshotFiles()
        val store: AccountWorkshopPageSnapshotStore = BoundedPersistentAccountWorkshopPageSnapshotStore(files)
        val key = AccountWorkshopPageSnapshotKey(ACCOUNT_ONE, completeQuery())
        val expected =
            AccountWorkshopPageSnapshot(
                page =
                    WorkshopPage(
                        items = listOf(completeSummary()),
                        page = 3,
                        hasNextPage = true,
                        totalCount = 123,
                        totalPages = 7,
                    ),
                storedAtMillis = 1_000L,
                freshUntilMillis = 2_000L,
            )

        assertTrue(store.write(key, expected))
        assertEquals(expected, store.read(key))
        assertTrue(expected.isFreshAt(1_999L))
        assertFalse(expected.isFreshAt(2_000L))
        assertFalse(expected.isFreshAt(999L))
    }

    @Test
    fun `key is independent of session generation and isolated by account`() {
        val files = FakeSnapshotFiles()
        val firstStore: AccountWorkshopPageSnapshotStore = BoundedPersistentAccountWorkshopPageSnapshotStore(files)
        val secondStore: AccountWorkshopPageSnapshotStore = BoundedPersistentAccountWorkshopPageSnapshotStore(files)
        val key = AccountWorkshopPageSnapshotKey(ACCOUNT_ONE, completeQuery())
        val snapshot = snapshot(1L, page = key.query.page)

        assertTrue(firstStore.write(key, snapshot))
        assertEquals(snapshot, secondStore.read(key))
        assertNull(secondStore.read(key.copy(accountId = ACCOUNT_TWO)))
    }

    @Test
    fun `every account query field participates in the exact key`() {
        val store: AccountWorkshopPageSnapshotStore =
            BoundedPersistentAccountWorkshopPageSnapshotStore(FakeSnapshotFiles())
        val query = completeQuery()
        val key = AccountWorkshopPageSnapshotKey(ACCOUNT_ONE, query)
        assertTrue(store.write(key, snapshot(1L, page = key.query.page)))

        val variants =
            listOf(
                query.copy(collection = AccountWorkshopCollection.FAVORITES),
                query.copy(page = query.page + 1),
                query.copy(pageSize = query.pageSize + 1),
                query.copy(searchText = query.searchText.uppercase()),
                query.copy(exactPhrase = !query.exactPhrase),
                query.copy(resolveTotalCount = !query.resolveTotalCount),
                query.copy(type = WorkshopType.WEB),
                query.copy(types = setOf(WorkshopType.WEB)),
                query.copy(tags = setOf("different")),
                query.copy(ratings = setOf(WorkshopRating.EVERYONE)),
                query.copy(genres = setOf("different")),
                query.copy(officialTags = setOf("different")),
                query.copy(excludedOfficialTags = setOf("different")),
                query.copy(resolutions = setOf("different")),
            )

        variants.forEach { variant ->
            assertNull(store.read(AccountWorkshopPageSnapshotKey(ACCOUNT_ONE, variant)), variant.toString())
        }
    }

    @Test
    fun `item count strings tags file size and freshness are bounded`() {
        val limits =
            AccountWorkshopPageSnapshotLimits(
                maxFiles = 4,
                maxItemsPerPage = 2,
                maxTagsPerItem = 2,
                maxQueryValues = 2,
                maxStringChars = 12,
                maxStringBytes = 12,
                maxFileBytes = 1_024,
                maxTotalBytes = 4_096,
                maxFreshnessMillis = 1_000L,
            )
        val store: AccountWorkshopPageSnapshotStore =
            BoundedPersistentAccountWorkshopPageSnapshotStore(FakeSnapshotFiles(), limits)
        val key = AccountWorkshopPageSnapshotKey(ACCOUNT_ONE, shortQuery())
        val valid = snapshot(1L, summary = shortSummary())

        assertTrue(store.write(key, valid))
        assertFalse(store.write(key, valid.copy(page = valid.page.copy(items = List(3) { shortSummary(it + 1L) }))))
        assertFalse(
            store.write(
                key,
                valid.copy(page = valid.page.copy(items = listOf(shortSummary(tags = listOf("a", "b", "c"))))),
            ),
        )
        assertFalse(
            store.write(
                key,
                valid.copy(page = valid.page.copy(items = listOf(shortSummary(title = "1234567890123")))),
            ),
        )
        assertFalse(store.write(key, valid.copy(freshUntilMillis = valid.storedAtMillis + 1_001L)))
        assertFalse(
            store.write(
                key.copy(query = key.query.copy(tags = setOf("a", "b", "c"))),
                valid,
            ),
        )
    }

    @Test
    fun `total bytes and file count evict oldest snapshots`() {
        val files = FakeSnapshotFiles()
        val limits =
            AccountWorkshopPageSnapshotLimits(
                maxFiles = 2,
                maxItemsPerPage = 2,
                maxTagsPerItem = 2,
                maxQueryValues = 2,
                maxStringChars = 64,
                maxStringBytes = 128,
                maxFileBytes = 512,
                maxTotalBytes = 700,
            )
        val store: AccountWorkshopPageSnapshotStore = BoundedPersistentAccountWorkshopPageSnapshotStore(files, limits)
        val first = AccountWorkshopPageSnapshotKey(ACCOUNT_ONE, shortQuery(page = 1))
        val second = AccountWorkshopPageSnapshotKey(ACCOUNT_ONE, shortQuery(page = 2))
        val third = AccountWorkshopPageSnapshotKey(ACCOUNT_ONE, shortQuery(page = 3))

        assertTrue(store.write(first, snapshot(1L, shortSummary(1L), page = 1)))
        assertTrue(store.write(second, snapshot(2L, shortSummary(2L), page = 2)))
        assertTrue(store.write(third, snapshot(3L, shortSummary(3L), page = 3)))

        assertTrue(files.entries().size <= limits.maxFiles)
        assertTrue(files.entries().sumOf(AccountWorkshopSnapshotFiles.Entry::byteCount) <= limits.maxTotalBytes)
        assertNull(store.read(first))
        assertNotNull(store.read(third))
    }

    @Test
    fun `corruption oversized reads and storage failures are cache misses`() {
        val files = FakeSnapshotFiles()
        val store: AccountWorkshopPageSnapshotStore =
            BoundedPersistentAccountWorkshopPageSnapshotStore(
                files,
                AccountWorkshopPageSnapshotLimits(maxFileBytes = 512, maxTotalBytes = 1_024),
            )
        val key = AccountWorkshopPageSnapshotKey(ACCOUNT_ONE, shortQuery())
        assertTrue(store.write(key, snapshot(1L, shortSummary())))
        val name = files.entries().single().name

        files.replace(name, byteArrayOf(1, 2, 3))
        assertNull(store.read(key))
        assertTrue(files.entries().isEmpty())

        assertTrue(store.write(key, snapshot(2L, shortSummary())))
        files.replace(name, ByteArray(513))
        assertNull(store.read(key))

        files.failWrites = true
        assertFalse(store.write(key, snapshot(2L, shortSummary())))
        files.failWrites = false
        files.failReads = true
        assertNull(store.read(key))
        files.failReads = false
        files.failListings = true
        assertFalse(store.write(key, snapshot(3L, shortSummary())))
    }

    @Test
    fun `confirmed collection mutations patch durable first pages without crossing accounts`() {
        val files = FakeSnapshotFiles()
        val store: AccountWorkshopPageSnapshotStore = BoundedPersistentAccountWorkshopPageSnapshotStore(files)
        val first = AccountWorkshopPageSnapshotKey(ACCOUNT_ONE, shortQuery())
        val count =
            AccountWorkshopPageSnapshotKey(
                ACCOUNT_ONE,
                shortQuery().copy(pageSize = 1, resolveTotalCount = true),
            )
        val other = AccountWorkshopPageSnapshotKey(ACCOUNT_TWO, shortQuery())
        assertTrue(store.write(first, snapshot(1L, shortSummary(1L))))
        assertTrue(store.write(count, snapshot(1L, shortSummary(1L))))
        assertTrue(store.write(other, snapshot(1L, shortSummary(1L))))

        assertTrue(
            store.updateCollection(
                accountId = ACCOUNT_ONE,
                collection = AccountWorkshopCollection.FAVORITES,
                itemId = 1L,
                present = false,
                summary = null,
            ),
        )
        assertTrue(store.read(first)?.page?.items?.isEmpty() == true)
        assertNull(store.read(count))
        assertEquals(listOf(1L), store.read(other)?.page?.items?.map(WorkshopSummary::id))

        assertTrue(
            store.updateCollection(
                accountId = ACCOUNT_ONE,
                collection = AccountWorkshopCollection.FAVORITES,
                itemId = 2L,
                present = true,
                summary = shortSummary(2L),
            ),
        )
        assertEquals(listOf(2L), store.read(first)?.page?.items?.map(WorkshopSummary::id))
    }

    @Test
    fun `account removal deletes only the selected account`() {
        val files = FakeSnapshotFiles()
        val store: AccountWorkshopPageSnapshotStore = BoundedPersistentAccountWorkshopPageSnapshotStore(files)
        val first = AccountWorkshopPageSnapshotKey(ACCOUNT_ONE, shortQuery())
        val second = AccountWorkshopPageSnapshotKey(ACCOUNT_TWO, shortQuery())
        assertTrue(store.write(first, snapshot(1L, shortSummary(1L))))
        assertTrue(store.write(second, snapshot(2L, shortSummary(2L))))

        assertTrue(store.removeAccount(ACCOUNT_ONE))

        assertNull(store.read(first))
        assertNotNull(store.read(second))
    }

    private fun completeQuery() =
        AccountWorkshopQuery(
            collection = AccountWorkshopCollection.SUBSCRIPTIONS,
            page = 3,
            pageSize = 17,
            searchText = "query text",
            exactPhrase = true,
            resolveTotalCount = true,
            type = WorkshopType.SCENE,
            types = linkedSetOf(WorkshopType.SCENE, WorkshopType.VIDEO),
            tags = linkedSetOf("tag-z", "tag-a"),
            ratings = linkedSetOf(WorkshopRating.MATURE, WorkshopRating.QUESTIONABLE),
            genres = linkedSetOf("genre-z", "genre-a"),
            officialTags = linkedSetOf("official-z", "official-a"),
            excludedOfficialTags = linkedSetOf("excluded-z", "excluded-a"),
            resolutions = linkedSetOf("resolution-z", "resolution-a"),
        )

    private fun shortQuery(page: Int = 1) =
        AccountWorkshopQuery(
            collection = AccountWorkshopCollection.FAVORITES,
            page = page,
            pageSize = 1,
            searchText = "q",
        )

    private fun completeSummary() =
        WorkshopSummary(
            id = 99L,
            title = "Complete title",
            author = "Complete author",
            creatorId = "76561198000000000",
            authorAvatarUrl = "https://example.test/avatar.png",
            previewUrl = "https://example.test/preview.png",
            type = WorkshopType.VIDEO,
            tags = listOf("Wallpaper", "Video"),
            subscriptions = 100L,
            favorites = 90L,
            views = 1_000L,
            fileSizeBytes = 2_000L,
            subscriptionState = SubscriptionState.SUBSCRIBED,
            favoriteState = FavoriteState.FAVORITED,
            isTitlePlaceholder = true,
            authorPlaceholder = WorkshopAuthorPlaceholder.USER_WITH_ID,
        )

    private fun shortSummary(
        id: Long = 1L,
        title: String = "title",
        tags: List<String> = listOf("tag"),
    ) =
        WorkshopSummary(
            id = id,
            title = title,
            author = "author",
            tags = tags,
        )

    private fun snapshot(
        marker: Long,
        summary: WorkshopSummary = completeSummary(),
        page: Int = 1,
    ) =
        AccountWorkshopPageSnapshot(
            page = WorkshopPage(listOf(summary), page = page, hasNextPage = false, totalCount = 1, totalPages = 1),
            storedAtMillis = marker,
            freshUntilMillis = marker + 1_000L,
        )

    private class FakeSnapshotFiles : AccountWorkshopSnapshotFiles {
        private data class Stored(
            val bytes: ByteArray,
            val updatedAtMillis: Long,
        )

        private val values = linkedMapOf<String, Stored>()
        private var revision = 0L
        var failReads = false
        var failWrites = false
        var failListings = false

        override fun read(
            name: String,
            maxBytes: Int,
        ): ByteArray? {
            if (failReads) throw IllegalStateException("read failed")
            val bytes = values[name]?.bytes ?: return null
            return bytes.takeIf { it.size <= maxBytes }?.copyOf()
        }

        override fun write(
            name: String,
            bytes: ByteArray,
        ): Boolean {
            if (failWrites) throw IllegalStateException("write failed")
            revision += 1L
            values[name] = Stored(bytes.copyOf(), revision)
            return true
        }

        override fun entries(): List<AccountWorkshopSnapshotFiles.Entry> {
            if (failListings) throw IllegalStateException("listing failed")
            return values.map { (name, stored) ->
                AccountWorkshopSnapshotFiles.Entry(name, stored.bytes.size.toLong(), stored.updatedAtMillis)
            }
        }

        override fun delete(name: String): Boolean = values.remove(name) != null

        fun replace(
            name: String,
            bytes: ByteArray,
        ) {
            revision += 1L
            values[name] = Stored(bytes.copyOf(), revision)
        }
    }

    private companion object {
        const val ACCOUNT_ONE = 76561198000000001L
        const val ACCOUNT_TWO = 76561198000000002L
    }
}
