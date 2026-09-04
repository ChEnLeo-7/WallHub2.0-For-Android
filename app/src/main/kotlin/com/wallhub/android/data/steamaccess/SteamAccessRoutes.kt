package com.wallhub.android.data.steamaccess

import java.net.InetAddress

internal object SteamAccessRoutes {
    private val aliases =
        mapOf(
            "steamcommunity.com" to listOf("steamcommunity-a.akamaihd.net.edgesuite.net"),
            "www.steamcommunity.com" to listOf("steamcommunity-a.akamaihd.net.edgesuite.net"),
            "api.steampowered.com" to
                listOf(
                    "api.steampowered.com.edgekey.net",
                    "api.steampowered.com.edgesuite.net",
                ),
            "community.steam-api.com" to
                listOf(
                    "community.steam-api.com.edgekey.net",
                    "community.steam-api.com.edgesuite.net",
                ),
        )

    private val seedAddresses =
        mapOf(
            "steamcommunity.com" to
                listOf(
                    "23.44.248.222",
                    "23.44.248.223",
                    "23.52.74.146",
                    "23.52.74.163",
                    "104.90.128.70",
                    "184.25.56.178",
                ),
            "api.steampowered.com" to
                listOf(
                    "173.222.146.99",
                    "184.85.112.102",
                    "23.45.12.51",
                    "23.45.12.60",
                    "96.6.190.4",
                    "96.7.49.34",
                ),
            "community.steam-api.com" to
                listOf(
                    "173.222.146.99",
                    "184.85.112.102",
                    "23.45.12.51",
                    "23.45.12.60",
                    "96.6.190.4",
                    "96.7.49.34",
                ),
        )

    fun supports(hostname: String): Boolean = SteamDomainPolicy.supports(hostname)

    fun aliases(hostname: String): List<String> = aliases[hostname.lowercase().trimEnd('.')].orEmpty()

    fun seeds(hostname: String): List<InetAddress> {
        val host = hostname.lowercase().trimEnd('.')
        val seedHost = if (host == "www.steamcommunity.com") "steamcommunity.com" else host
        return seedAddresses[seedHost]
            .orEmpty()
            .mapNotNull { address -> runCatching { InetAddress.getByName(address) }.getOrNull() }
    }
}

internal fun steamRouteCacheKey(
    networkType: String,
    hostname: String,
    port: Int,
): String = "$networkType|${hostname.lowercase().trimEnd('.')}|$port"

internal object SteamHostsParser {
    fun parse(text: String): Map<String, List<InetAddress>> {
        val result = linkedMapOf<String, MutableList<InetAddress>>()
        text.lineSequence().forEach { rawLine ->
            val fields =
                rawLine
                    .substringBefore('#')
                    .trim()
                    .split(Regex("\\s+"))
                    .filter(String::isNotBlank)
            if (fields.size < 2) return@forEach
            val address = parseLiteralAddress(fields.first()) ?: return@forEach
            fields.drop(1).forEach { rawHost ->
                val host = rawHost.lowercase().trimEnd('.')
                if (SteamAccessRoutes.supports(host)) {
                    result.getOrPut(host) { mutableListOf() }.apply {
                        if (none { it.hostAddress == address.hostAddress }) add(address)
                    }
                }
            }
        }
        return result
    }

    private fun parseLiteralAddress(value: String): InetAddress? {
        val isIpv4 =
            value.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")) &&
                value.split('.').all { part ->
                    part.toIntOrNull()?.let { octet -> octet in 0..255 } == true
                }
        val isIpv6 = ':' in value && value.matches(Regex("[0-9a-fA-F:.%]+"))
        if (!isIpv4 && !isIpv6) return null
        return runCatching { InetAddress.getByName(value.substringBefore('%')) }.getOrNull()
    }
}
