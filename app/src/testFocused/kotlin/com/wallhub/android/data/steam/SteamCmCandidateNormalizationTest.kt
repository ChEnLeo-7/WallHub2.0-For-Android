package com.wallhub.android.data.steam

import io.steamcontent.sdk.SessionTransportKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SteamCmCandidateNormalizationTest {
    @Test
    fun `account websocket route keeps first four distinct hosts in Steam directory order`() {
        val directoryBody =
            """
            {"response":{"serverlist":[
              {"endpoint":"cmp1-hkg1.steamserver.net:27018"},
              {"endpoint":"cmp1-hkg1.steamserver.net:443"},
              {"endpoint":"cmp2-hkg1.steamserver.net:27018"},
              {"endpoint":"cmp2-hkg1.steamserver.net:443"},
              {"endpoint":"cmp3-hkg1.steamserver.net:27018"},
              {"endpoint":"cmp4-hkg1.steamserver.net:27018"},
              {"endpoint":"cmp5-hkg1.steamserver.net:27018"}
            ]}}
            """.trimIndent()

        val directoryCandidates = cmCandidatesFromBody(directoryBody)
        val websocketCandidates = cmRouteCandidates(directoryCandidates, SessionTransportKind.WEBSOCKET)

        assertEquals(
            listOf(
                "cmp1-hkg1.steamserver.net:443",
                "cmp2-hkg1.steamserver.net:443",
                "cmp3-hkg1.steamserver.net:443",
                "cmp4-hkg1.steamserver.net:443",
            ),
            websocketCandidates.orEmpty().lines(),
        )
    }
}
