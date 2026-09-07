package com.wallhub.android.data.steam

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Request
import org.json.JSONObject

internal const val CM_LIST_CACHE_FRESH_TTL_MS = 24L * 60L * 60L * 1000L
internal const val CM_LIST_CACHE_STALE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
internal const val MAX_CM_LIST_CACHE_BYTES = 256L * 1024L

internal enum class SteamCmListCacheAction {
    SERVE_CACHED,
    FETCH_LIVE,
    FETCH_LIVE_WITH_FALLBACK,
}

internal fun cmListCacheDecision(ageMs: Long?): SteamCmListCacheAction =
    when {
        ageMs == null -> SteamCmListCacheAction.FETCH_LIVE
        ageMs < CM_LIST_CACHE_FRESH_TTL_MS -> SteamCmListCacheAction.SERVE_CACHED
        else -> SteamCmListCacheAction.FETCH_LIVE_WITH_FALLBACK
    }

internal fun cmListCacheStaleUsable(ageMs: Long): Boolean =
    ageMs in 0 until CM_LIST_CACHE_STALE_TTL_MS

internal fun Request.isCmListRequest(): Boolean =
    url.host.equals("api.steampowered.com", ignoreCase = true) &&
        url.encodedPath.contains("GetCMListForConnect", ignoreCase = true)

internal fun looksLikeCmListJson(body: String): Boolean =
    body.trimStart().startsWith("{") &&
        body.contains("\"response\"") &&
        body.contains("\"serverlist\"") &&
        body.contains("\"endpoint\"")

@Singleton
class SteamCmListCache
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val cacheFile = File(context.filesDir, "ksteam/cm-list-cache.json")

        @Synchronized
        fun load(key: String): CachedCmList? {
            val entry =
                runCatching {
                    JSONObject(cacheFile.readText())
                }.getOrNull() ?: return null
            if (entry.optString(KEY_QUERY).takeIf(String::isNotEmpty) != key.takeIf(String::isNotEmpty)) return null
            val savedAt = entry.optLong(KEY_SAVED_AT, 0L)
            val body = entry.optString(KEY_BODY).takeIf(String::isNotEmpty) ?: return null
            if (savedAt <= 0L) return null
            return CachedCmList(body = body, savedAt = savedAt)
        }

        @Synchronized
        fun save(key: String, body: String) {
            runCatching {
                cacheFile.parentFile?.mkdirs()
                val entry =
                    JSONObject()
                        .put(KEY_QUERY, key)
                        .put(KEY_SAVED_AT, System.currentTimeMillis())
                        .put(KEY_BODY, body)
                val temp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
                temp.writeText(entry.toString())
                if (!temp.renameTo(cacheFile)) {
                    cacheFile.writeText(entry.toString())
                }
            }
        }

        data class CachedCmList(
            val body: String,
            val savedAt: Long,
        ) {
            val ageMs: Long get() = System.currentTimeMillis() - savedAt
        }

        private companion object {
            const val KEY_QUERY = "query"
            const val KEY_SAVED_AT = "savedAt"
            const val KEY_BODY = "body"
        }
    }
