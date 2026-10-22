package com.wallhub.android.data.steamaccess

import java.net.InetAddress

/** Small demand-only cache. Invalidation rejects late DNS results and feedback from old networks. */
internal class SteamRequestRouteCache(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry(val addresses: List<InetAddress>, val expiresAt: Long)
    private val entries = linkedMapOf<String, Entry>()
    @Volatile var generation: Long = 0
        private set

    @Synchronized fun get(key: String, expectedGeneration: Long): List<InetAddress>? {
        if (generation != expectedGeneration) return null
        val entry = entries[key] ?: return null
        if (entry.expiresAt <= nowMillis()) { entries.remove(key); return null }
        return entry.addresses
    }

    @Synchronized fun put(key: String, expectedGeneration: Long, addresses: List<InetAddress>): Boolean {
        if (generation != expectedGeneration) return false
        if (addresses.isEmpty()) return true
        entries.remove(key)
        entries[key] = Entry(addresses.take(4).toList(), nowMillis() + 60_000)
        while (entries.size > 64) entries.remove(entries.keys.first())
        return true
    }

    @Synchronized fun remove(key: String, expectedGeneration: Long) {
        if (generation == expectedGeneration) entries.remove(key)
    }

    @Synchronized fun removeAddress(key: String, expectedGeneration: Long, address: InetAddress) {
        if (generation != expectedGeneration) return
        val entry = entries[key] ?: return
        val remaining = entry.addresses.filterNot { it.hostAddress == address.hostAddress }
        if (remaining.isEmpty()) entries.remove(key) else entries[key] = entry.copy(addresses = remaining)
    }

    @Synchronized fun invalidate() { generation++; entries.clear() }
}

internal fun selectSteamCandidates(
    system: List<InetAddress>, resolved: List<InetAddress>, preferred: Set<String>, cooling: Set<String>,
): List<InetAddress> = (system + resolved).filter(::isAuthenticatedCdnAddress)
    .distinctBy(InetAddress::getHostAddress)
    .filterNot { it.hostAddress in cooling }
    .sortedBy { it.hostAddress !in preferred }
    .take(4)
