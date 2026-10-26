package com.wallhub.android.data.discover

import com.wallhub.android.feature.discover.model.DiscoverMetadataSource
import com.wallhub.android.feature.discover.model.DiscoverCuratorCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class LegacyExploreCuratorParserTest {
    @Test
    fun `parse normalizes missing arrays and preserves query semantics`() {
        val parsed =
            LegacyExploreCuratorParser.parse(
                """{"response":{"items":[{"category":"keyword","keyword":"City","querytypes":["trend_year"],"timestampstart":"100","timestampend":200,"exact":true}]}}""",
            )

        val descriptor = parsed.descriptors.single()
        assertEquals(DiscoverCuratorCategory.KEYWORD, descriptor.category)
        assertEquals(listOf("trend_year"), descriptor.queryTypes)
        assertTrue(descriptor.tags.isEmpty())
        assertEquals(100L, descriptor.timestampStart)
        assertEquals(200L, descriptor.timestampEnd)
        assertTrue(descriptor.exact)
        assertEquals(0, parsed.rejectedItemCount)
    }

    @Test
    fun `parse filters unsupported platforms and malformed descriptors`() {
        val parsed =
            LegacyExploreCuratorParser.parse(
                """{"response":{"items":[
                    {"category":"creator","itemid":"76561198219876757","platforms":["windows"]},
                    {"category":"keyword","keyword":"Mobile","platforms":["ios"]},
                    {"category":"collection","itemid":"not-an-id"},
                    {"category":"keyword","keyword":"Steam provider","platforms":{"steam":true,"wegame":false}}
                ]}}""",
            )

        assertEquals(2, parsed.descriptors.size)
        assertEquals(DiscoverCuratorCategory.CREATOR, parsed.descriptors.first().category)
        assertEquals(2, parsed.rejectedItemCount)
    }

    @Test
    fun `legacy cover submission hint is ignored`() {
        val parsed =
            LegacyExploreCuratorParser.parse(
                """{"response":{"items":[{"category":"keyword","keyword":"Nature","coversubmission":"2406282996"}]}}""",
            )

        assertEquals("Nature", parsed.descriptors.single().keyword)
        assertTrue(parsed.descriptors.single().itemId == null)
    }

    @Test
    fun `parser rejects an excessive curator item count`() {
        val items = (1..513).joinToString(",") { index ->
            """{"category":"keyword","keyword":"topic-$index"}"""
        }

        assertFailsWith<IllegalArgumentException> {
            LegacyExploreCuratorParser.parse("""{"response":{"items":[$items]}}""")
        }
    }

    @Test
    fun `oversized filter values reject their descriptor instead of broadening it`() {
        val oversized = "x".repeat(257)
        val parsed =
            LegacyExploreCuratorParser.parse(
                """{"response":{"items":[
                    {"category":"keyword","keyword":"bad","excludetags":["$oversized"]},
                    {"category":"keyword","keyword":"good","excludetags":["Anime"]}
                ]}}""",
            )

        assertEquals(listOf("good"), parsed.descriptors.map { it.keyword })
        assertEquals(1, parsed.rejectedItemCount)
    }

    @Test
    fun `versioned manifest rejects unknown fields and fixed cover policy`() {
        assertFailsWith<IllegalArgumentException> {
            DiscoverCuratorManifestParser.parse(
                """{"schemaVersion":1,"sections":[{"category":"keyword","keyword":"Nature","requestUrl":"https://example.com"}]}""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DiscoverCuratorManifestParser.parse(
                """{"schemaVersion":1,"sections":[{"category":"keyword","keyword":"Nature","coverPolicy":"submission_id"}]}""",
            )
        }
    }

    @Test
    fun `versioned manifest maps bounded query descriptors`() {
        val parsed =
            DiscoverCuratorManifestParser.parse(
                """{"schemaVersion":1,"revision":"r1","sections":[{"id":"nature","category":"keyword","keyword":"Nature","queryTypes":["trend_year"],"coverPolicy":"first_result"}]}""",
            )

        assertEquals("Nature", parsed.descriptors.single().keyword)
        assertEquals(listOf("trend_year"), parsed.descriptors.single().queryTypes)
    }

    @Test
    fun `collection parser preserves Steam child order and removes later duplicates`() {
        val ids =
            parseCollectionMemberIds(
                """{"response":{"collectiondetails":[{"publishedfileid":"77","children":[{"publishedfileid":"9"},{"publishedfileid":"4"},{"publishedfileid":"9"},{"publishedfileid":"6"}]}]}}""",
                expectedCollectionId = 77L,
            )

        assertEquals(listOf(9L, 4L, 6L), ids)
    }

    @Test
    fun `snapshot version is stable and expiry is explicit`() {
        val parsed =
            LegacyExploreCuratorParser.parse(
                """{"response":{"items":[{"category":"collection","itemid":"2852303026","tags":["Nature"]}]}}""",
            )
        val first = LegacyExploreCuratorParser.snapshot(parsed, nowMillis = 1_000L, ttlMillis = 500L)
        val second = LegacyExploreCuratorParser.snapshot(parsed, nowMillis = 2_000L, ttlMillis = 500L)

        assertEquals(first.version, second.version)
        assertEquals(DiscoverMetadataSource.NETWORK, first.source)
        assertFalse(first.isExpired(1_499L))
        assertTrue(first.isExpired(1_500L))
    }
}
