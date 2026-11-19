package com.wallhub.android.data.steamaccess

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * What "the baseline reaches Steam" means on a network that only half-works.
 *
 * The China-direct field report is exactly the case where one Steam plane answers and another is
 * blocked. A verdict that turns the first answer into "Steam is open" forbids the enhancement that the
 * blocked plane needs, so every required plane has to answer before the baseline counts as open. The
 * reasons stay distinct, and Google is not consulted at all.
 */
class SteamEnvironmentProbeTest {
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `every required plane answering is the only open verdict`() {
        runBlocking {
            MockWebServer().use { first ->
                MockWebServer().use { second ->
                    first.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                    second.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                    first.start()
                    second.start()

                    val environment =
                        SteamNetworkEnvironmentProbe(
                            listOf(first.url("/").toString(), second.url("/").toString()),
                        ).probe(SteamEgress.Direct)

                    assertEquals(SteamEnvironment.Open, environment)
                }
            }
        }
    }

    @Test
    fun `one blocked plane keeps the baseline blocked even when the first plane answers`() {
        runBlocking {
            MockWebServer().use { reachable ->
                reachable.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                reachable.start()
                val blocked = "http://127.0.0.1:${closedPort()}/workshop/browse/"

                val environment =
                    SteamNetworkEnvironmentProbe(
                        listOf(reachable.url("/").toString(), blocked),
                    ).probe(SteamEgress.Direct)

                val verdict = assertIs<SteamEnvironment.Blocked>(environment)
                assertEquals(SteamBlockReason.CONNECT_FAILURE, verdict.reason)
            }
        }
    }

    @Test
    fun `a first plane that fails is not rescued by a later plane that answers`() {
        runBlocking {
            MockWebServer().use { reachable ->
                reachable.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                reachable.start()
                val blocked = "http://127.0.0.1:${closedPort()}/"

                val environment =
                    SteamNetworkEnvironmentProbe(
                        listOf(blocked, reachable.url("/").toString()),
                    ).probe(SteamEgress.Direct)

                assertIs<SteamEnvironment.Blocked>(environment)
            }
        }
    }

    @Test
    fun `a server rejection is reported as the server rejecting rather than as a timeout`() {
        runBlocking {
            MockWebServer().use { rejecting ->
                rejecting.enqueue(MockResponse().setResponseCode(503))
                rejecting.start()

                val environment =
                    SteamNetworkEnvironmentProbe(listOf(rejecting.url("/").toString()))
                        .probe(SteamEgress.Direct)

                val verdict = assertIs<SteamEnvironment.Blocked>(environment)
                assertEquals(SteamBlockReason.SERVER_REJECTED, verdict.reason)
            }
        }
    }

    @Test
    fun `the real probe target set still covers the api and community planes`() {
        val hosts = STEAM_PROBE_TARGETS.map { java.net.URI(it).host }.toSet()

        assertTrue("api.steampowered.com" in hosts, "the Web API plane must be probed")
        assertTrue("steamcommunity.com" in hosts, "the Community HTML plane must be probed")
    }
}
