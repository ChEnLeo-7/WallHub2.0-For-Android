package com.wallhub.android.data.steam

import kotlin.test.assertNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamCmEndpointSourceTest {
    @Test
    fun emptyCandidateWindowCountsAsZero() {
        assertEquals(0, cmCandidateCount(""))
        assertEquals(2, cmCandidateCount("cmp1.steamserver.net:443\ncmp2.steamserver.net:443"))
    }

    @Test
    fun theDirectoryOrderIsPreservedAcrossCmPorts() {
        val body =
            """
            {"response":{"serverlist":[
              {"endpoint":"cmp1-a.steamserver.net:27019","type":"websockets"},
              {"endpoint":"cmp2-b.steamserver.net:443","type":"websockets"},
              {"endpoint":"cmp3-c.steamserver.net:27017","type":"netfilter"}
            ],"success":true}}
            """.trimIndent()

        assertEquals(
            "cmp1-a.steamserver.net:27019\ncmp2-b.steamserver.net:443\ncmp3-c.steamserver.net:27017",
            cmCandidatesFromBody(body),
        )
    }

    @Test
    fun anEndpointThePlatformCannotAccelerateIsDropped() {
        val body =
            """
            {"response":{"serverlist":[
              {"endpoint":"cmp1-a.steamserver.net:80"},
              {"endpoint":"cdn.example.com:443"},
              {"endpoint":"cmp1-a.steamserver.net:443"}
            ]}}
            """.trimIndent()

        assertEquals("cmp1-a.steamserver.net:443", cmCandidatesFromBody(body))
    }

    @Test
    fun theListIsDeduplicatedAndCapped() {
        val body =
            """
            {"response":{"serverlist":[
              {"endpoint":"cmp1.steamserver.net:443"},
              {"endpoint":"cmp1.steamserver.net:443"},
              {"endpoint":"cmp2.steamserver.net:443"},
              {"endpoint":"cmp3.steamserver.net:443"},
              {"endpoint":"cmp4.steamserver.net:443"},
              {"endpoint":"cmp5.steamserver.net:443"}
            ]}}
            """.trimIndent()

        assertEquals(
            "cmp1.steamserver.net:443\ncmp2.steamserver.net:443\ncmp3.steamserver.net:443\ncmp4.steamserver.net:443",
            cmCandidatesFromBody(body),
        )
    }

    @Test
    fun refreshedWindowKeepsNewPriorityAndUnusedPriorCandidates() {
        assertEquals(
            "cmp5.steamserver.net:443\ncmp2.steamserver.net:443\ncmp1.steamserver.net:443\ncmp3.steamserver.net:443",
            mergeCmCandidateWindows(
                live = "cmp5.steamserver.net:443\ncmp2.steamserver.net:443",
                previous =
                    "cmp1.steamserver.net:443\ncmp2.steamserver.net:443\n" +
                        "cmp3.steamserver.net:443\ncmp4.steamserver.net:443",
            ),
        )
    }

    @Test
    fun aBodyWithNoUsableEndpointYieldsNothing() {
        assertEquals("", cmCandidatesFromBody("""{"response":{"serverlist":[]}}"""))
        assertEquals("", cmCandidatesFromBody(""))
    }

    @Test
    fun `normal selection is live then cache and refresh is live or null`() {
        val cached = "cached.steamserver.net:443"
        val live = "live.steamserver.net:443"

        assertEquals(live, selectCmCandidates(live, cached, refreshLive = false))
        assertEquals(cached, selectCmCandidates("", cached, refreshLive = false))
        assertEquals(live, selectCmCandidates(live, cached, refreshLive = true))
        assertNull(selectCmCandidates("", cached, refreshLive = true))
        assertNull(selectCmCandidates("", "", refreshLive = false))
    }

    @Test
    fun `directory body read rejects declared and streamed data over the byte bound`() {
        val mediaType = "application/json".toMediaType()
        val exact = ByteArray(MAX_CM_LIST_CACHE_BYTES.toInt()) { 'x'.code.toByte() }.toResponseBody(mediaType)
        val over = ByteArray(MAX_CM_LIST_CACHE_BYTES.toInt() + 1) { 'x'.code.toByte() }.toResponseBody(mediaType)

        assertEquals(MAX_CM_LIST_CACHE_BYTES.toInt(), readBoundedCmDirectoryBody(exact)?.length)
        assertNull(readBoundedCmDirectoryBody(over))
    }
}
