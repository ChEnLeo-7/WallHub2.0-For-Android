package com.wallhub.android.data.steamaccess

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

internal data class SteamCachedRoute(
    val available: Boolean,
    val accelerated: Boolean,
    val addresses: List<InetAddress>,
    val freshUntil: Long,
    val staleUntil: Long,
)

internal data class SteamRouteLookup(
    val route: SteamCachedRoute?,
    val shouldRefresh: Boolean,
)

internal class SteamRouteSnapshotCache(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val routes = ConcurrentHashMap<String, SteamCachedRoute>()

    fun lookup(key: String): SteamRouteLookup {
        val route = routes[key] ?: return SteamRouteLookup(route = null, shouldRefresh = true)
        val now = nowMillis()
        if (route.staleUntil <= now) {
            routes.remove(key, route)
            return SteamRouteLookup(route = null, shouldRefresh = true)
        }
        return SteamRouteLookup(
            route = route,
            shouldRefresh = route.freshUntil <= now,
        )
    }

    fun publish(
        key: String,
        route: SteamCachedRoute,
    ) {
        routes[key] = route
    }

    fun publishKeepingUsable(
        key: String,
        route: SteamCachedRoute,
    ): SteamCachedRoute {
        var selected = route
        routes.compute(key) { _, current ->
            selected = if (route.available || current == null || !current.available) route else current
            selected
        }
        return selected
    }

    fun markAllStale() {
        routes.replaceAll { _, route -> route.copy(freshUntil = 0L) }
    }

    fun remove(key: String) {
        routes.remove(key)
    }

    fun removeAddress(
        key: String,
        address: InetAddress,
    ): Boolean {
        var becameEmpty = false
        routes.computeIfPresent(key) { _, route ->
            val remaining = route.addresses.filterNot { candidate -> candidate.hostAddress == address.hostAddress }
            becameEmpty = route.accelerated && remaining.isEmpty()
            if (becameEmpty) null else route.copy(addresses = remaining)
        }
        return becameEmpty
    }

    fun clear() {
        routes.clear()
    }
}
