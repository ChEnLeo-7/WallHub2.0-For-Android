package com.wallhub.android.data.steam

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamCmEndpointSourceTest {
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
    fun aBodyWithNoUsableEndpointYieldsNothing() {
        assertEquals("", cmCandidatesFromBody("""{"response":{"serverlist":[]}}"""))
        assertEquals("", cmCandidatesFromBody(""))
    }
}
