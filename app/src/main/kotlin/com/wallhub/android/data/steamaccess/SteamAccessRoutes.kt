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

    fun supports(hostname: String): Boolean = SteamDomainPolicy.supports(hostname)

    fun aliases(hostname: String): List<String> = aliases[hostname.lowercase().trimEnd('.')].orEmpty()

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
