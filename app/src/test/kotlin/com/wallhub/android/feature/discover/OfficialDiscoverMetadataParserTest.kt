package com.wallhub.android.data.discover

import com.wallhub.android.feature.discover.model.DiscoverMetadataSource
import com.wallhub.android.feature.discover.model.OfficialDiscoverCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialDiscoverMetadataParserTest {
    @Test
    fun `parse normalizes missing arrays and preserves query semantics`() {
        val parsed =
            OfficialDiscoverMetadataParser.parse(
                """{"response":{"items":[{"category":"keyword","keyword":"City","querytypes":["trend_year"],"timestampstart":"100","timestampend":200,"exact":true}]}}""",
            )

        val descriptor = parsed.descriptors.single()
        assertEquals(OfficialDiscoverCategory.KEYWORD, descriptor.category)
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
            OfficialDiscoverMetadataParser.parse(
                """{"response":{"items":[
                    {"category":"creator","itemid":"76561198219876757","platforms":["windows"]},
                    {"category":"keyword","keyword":"Mobile","platforms":["ios"]},
                    {"category":"collection","itemid":"not-an-id"},
                    {"category":"keyword","keyword":"Steam provider","platforms":{"steam":true,"wegame":false}}
                ]}}""",
            )

        assertEquals(2, parsed.descriptors.size)
        assertEquals(OfficialDiscoverCategory.CREATOR, parsed.descriptors.first().category)
        assertEquals(2, parsed.rejectedItemCount)
    }

    @Test
    fun `snapshot version is stable and expiry is explicit`() {
        val parsed =
            OfficialDiscoverMetadataParser.parse(
                """{"response":{"items":[{"category":"collection","itemid":"2852303026","tags":["Nature"]}]}}""",
            )
        val first = OfficialDiscoverMetadataParser.snapshot(parsed, nowMillis = 1_000L, ttlMillis = 500L)
        val second = OfficialDiscoverMetadataParser.snapshot(parsed, nowMillis = 2_000L, ttlMillis = 500L)

        assertEquals(first.version, second.version)
        assertEquals(DiscoverMetadataSource.NETWORK, first.source)
        assertFalse(first.isExpired(1_499L))
        assertTrue(first.isExpired(1_500L))
    }
}
