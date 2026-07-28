package com.wallhub.android.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SteamAccessDohEndpointsTest {
    @Test
    fun `normalizer accepts secure DoH URLs and trims surrounding whitespace`() {
        assertEquals(
            "https://dns.example/dns-query?profile=steam",
            normalizeSteamAccessDohEndpoint("  https://dns.example/dns-query?profile=steam  "),
        )
        assertEquals(
            "https://[2606:4700:4700::1111]/dns-query",
            normalizeSteamAccessDohEndpoint("https://[2606:4700:4700::1111]/dns-query"),
        )
    }

    @Test
    fun `normalizer rejects insecure or ambiguous DoH URLs`() {
        assertNull(normalizeSteamAccessDohEndpoint("http://dns.example/dns-query"))
        assertNull(normalizeSteamAccessDohEndpoint("https:///dns-query"))
        assertNull(normalizeSteamAccessDohEndpoint("https://user:password@dns.example/dns-query"))
        assertNull(normalizeSteamAccessDohEndpoint("https://dns.example/dns-query#fragment"))
        assertNull(normalizeSteamAccessDohEndpoint("https://dns.example:70000/dns-query"))
    }

    @Test
    fun `disabled endpoints remain ordered but are excluded from active DoH queries`() {
        val first = "https://first.example/dns-query"
        val second = "https://second.example/dns-query"
        val third = "https://third.example/dns-query"
        val preferences = AppPreferences(
            steamAccessDohEndpoints = listOf(first, second, third),
            steamAccessDisabledDohEndpoints = setOf(second),
        )

        assertEquals(listOf(first, third), preferences.enabledSteamAccessDohEndpoints())
        assertEquals(listOf(first, second, third), preferences.steamAccessDohEndpoints)
    }

    @Test
    fun `list normalizer preserves priority while removing duplicates and enforcing limit`() {
        val endpoints = buildList {
            add("https://preferred.example/dns-query")
            add("https://preferred.example/dns-query")
            repeat(STEAM_ACCESS_DOH_ENDPOINT_LIMIT) { index ->
                add("https://dns$index.example/dns-query")
            }
        }

        assertEquals(
            listOf("https://preferred.example/dns-query") +
                List(STEAM_ACCESS_DOH_ENDPOINT_LIMIT - 1) { index ->
                    "https://dns$index.example/dns-query"
                },
            normalizeSteamAccessDohEndpoints(endpoints),
        )
    }
}
