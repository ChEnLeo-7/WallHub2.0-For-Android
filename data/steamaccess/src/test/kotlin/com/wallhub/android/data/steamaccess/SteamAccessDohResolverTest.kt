package com.wallhub.android.data.steamaccess

import kotlin.test.Test
import kotlin.test.assertEquals

class SteamAccessDohResolverTest {
    @Test
    fun `json parser keeps only requested address records`() {
        val body = """
            {"Answer":[
              {"type":5,"data":"edge.example.net."},
              {"type":1,"data":"23.44.248.222"},
              {"type":28,"data":"2600:1406:3a00::17d5:2a65"}
            ]}
        """.trimIndent()

        val ipv4 = SteamAccessDohResolver.parseAddresses(body, 1)
        val ipv6 = SteamAccessDohResolver.parseAddresses(body, 28)

        assertEquals(listOf("23.44.248.222"), ipv4.map { it.hostAddress })
        assertEquals(1, ipv6.size)
    }
}
