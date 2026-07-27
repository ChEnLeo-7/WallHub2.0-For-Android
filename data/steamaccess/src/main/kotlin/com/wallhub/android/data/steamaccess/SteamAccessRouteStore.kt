package com.wallhub.android.data.steamaccess

import android.content.Context
import java.net.InetAddress
import org.json.JSONArray
import org.json.JSONObject

internal class SteamAccessRouteStore(context: Context) {
    private val preferences = context.getSharedPreferences("steam_access_routes", Context.MODE_PRIVATE)

    fun preferred(networkType: String, hostname: String): List<InetAddress> {
        val array = runCatching { JSONArray(preferences.getString(key(networkType, hostname), "[]")) }
            .getOrNull() ?: return emptyList()
        val now = System.currentTimeMillis()
        return buildList {
            repeat(array.length()) { index ->
                val record = array.optJSONObject(index) ?: return@repeat
                if (record.optLong("cooldownUntil") > now) return@repeat
                val address = runCatching { InetAddress.getByName(record.optString("ip")) }.getOrNull()
                    ?: return@repeat
                add(record to address)
            }
        }.sortedByDescending { (record, _) -> score(record, now) }
            .map(Pair<JSONObject, InetAddress>::second)
    }

    fun coolingDownAddresses(networkType: String, hostname: String): Set<String> {
        val array = runCatching { JSONArray(preferences.getString(key(networkType, hostname), "[]")) }
            .getOrNull() ?: return emptySet()
        val now = System.currentTimeMillis()
        return buildSet {
            repeat(array.length()) { index ->
                val record = array.optJSONObject(index) ?: return@repeat
                if (record.optLong("cooldownUntil") > now) add(record.optString("ip"))
            }
        }
    }

    fun recordSuccess(networkType: String, hostname: String, address: InetAddress) {
        update(networkType, hostname, address) { record ->
            record.put("success", record.optInt("success") + 1)
            record.put("consecutiveFailure", 0)
            record.put("lastSuccessAt", System.currentTimeMillis())
            record.put("cooldownUntil", 0L)
        }
    }

    fun recordFailure(networkType: String, hostname: String, address: InetAddress) {
        update(networkType, hostname, address) { record ->
            val failures = record.optInt("consecutiveFailure") + 1
            record.put("failure", record.optInt("failure") + 1)
            record.put("consecutiveFailure", failures)
            record.put("cooldownUntil", System.currentTimeMillis() + failureCooldown(failures))
        }
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun update(
        networkType: String,
        hostname: String,
        address: InetAddress,
        mutation: (JSONObject) -> Unit,
    ) {
        val storageKey = key(networkType, hostname)
        synchronized(this) {
            val array = runCatching { JSONArray(preferences.getString(storageKey, "[]")) }
                .getOrDefault(JSONArray())
            val records = buildList {
                repeat(array.length()) { index -> array.optJSONObject(index)?.let(::add) }
            }.toMutableList()
            val record = records.firstOrNull { it.optString("ip") == address.hostAddress }
                ?: JSONObject().put("ip", address.hostAddress).also(records::add)
            mutation(record)
            val trimmed = records.sortedByDescending { it.optLong("lastSuccessAt") }.take(MAX_RECORDS_PER_HOST)
            preferences.edit().putString(storageKey, JSONArray(trimmed).toString()).apply()
        }
    }

    private fun key(networkType: String, hostname: String): String = "$networkType|${hostname.lowercase()}"

    companion object {
        private const val MAX_RECORDS_PER_HOST = 24

        internal fun failureCooldown(consecutiveFailures: Int): Long = when {
            consecutiveFailures >= 3 -> 30 * 60_000L
            consecutiveFailures == 2 -> 10 * 60_000L
            else -> 3 * 60_000L
        }

        internal fun score(record: JSONObject, now: Long): Int {
            val recentBonus = if (now - record.optLong("lastSuccessAt") < 30 * 60_000L) 400 else 0
            return record.optInt("success") * 100 + recentBonus -
                record.optInt("consecutiveFailure") * 150
        }
    }
}
