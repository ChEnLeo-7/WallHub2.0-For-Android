package com.wallhub.android.data.update

import kotlin.test.Test
import kotlin.test.assertEquals

class GitHubNetworkClientTest {
    @Test
    fun `DoH parser keeps distinct public IPv4 answers only`() {
        val addresses =
            parseGitHubPublicIpv4Answers(
                """
                {
                  "Status": 0,
                  "Answer": [
                    {"type": 1, "data": "20.205.243.168"},
                    {"type": 1, "data": "185.199.108.133"},
                    {"type": 1, "data": "20.205.243.168"},
                    {"type": 28, "data": "2606:50c0::1"}
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(listOf("20.205.243.168", "185.199.108.133"), addresses.map { it.hostAddress })
    }

    @Test
    fun `DoH parser rejects private fake reserved malformed and failed answers`() {
        val payload =
            """
            {
              "Status": 0,
              "Answer": [
                {"type": 1, "data": "10.0.0.1"},
                {"type": 1, "data": "100.64.0.1"},
                {"type": 1, "data": "169.254.1.1"},
                {"type": 1, "data": "192.0.2.1"},
                {"type": 1, "data": "198.18.0.1"},
                {"type": 1, "data": "198.51.100.1"},
                {"type": 1, "data": "203.0.113.1"},
                {"type": 1, "data": "999.1.1.1"},
                {"type": 5, "data": "api.github.com."}
              ]
            }
            """.trimIndent()

        assertEquals(emptyList(), parseGitHubPublicIpv4Answers(payload))
        assertEquals(emptyList(), parseGitHubPublicIpv4Answers("{\"Status\":2}"))
        assertEquals(emptyList(), parseGitHubPublicIpv4Answers("not-json"))
    }
}
