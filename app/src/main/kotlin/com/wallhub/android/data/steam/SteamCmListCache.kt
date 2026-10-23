package com.wallhub.android.data.steam

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Request
import org.json.JSONObject

internal const val CM_LIST_CACHE_STALE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
internal const val MAX_CM_LIST_CACHE_BYTES = 256L * 1024L

internal fun cmListCacheStaleUsable(ageMs: Long): Boolean =
    ageMs in 0 until CM_LIST_CACHE_STALE_TTL_MS

/**
 * Whether a failed live Directory request may fall back to the persisted response.
 *
 * Every cold start asks Steam first. A normal attempt may use a bounded cached response only after that live
 * request fails; a transport-recovery retry never reuses the same candidate input already under suspicion.
 */
internal fun cmCandidatesMayFallBack(
    ageMs: Long?,
    refreshLive: Boolean,
): Boolean = !refreshLive && ageMs != null && cmListCacheStaleUsable(ageMs)

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
    internal constructor(
        private val cacheFile: File,
    ) {
        @Inject
        constructor(
            @ApplicationContext context: Context,
        ) : this(File(context.filesDir, "ksteam/cm-list-cache.json"))

        @Synchronized
        fun load(key: String): CachedCmList? {
            val entry = loadEntry() ?: return null
            if (entry.optString(KEY_QUERY).takeIf(String::isNotEmpty) != key.takeIf(String::isNotEmpty)) return null
            val savedAt = entry.optLong(KEY_SAVED_AT, 0L)
            val body = entry.optString(KEY_BODY).takeIf(String::isNotEmpty) ?: return null
            if (savedAt <= 0L) return null
            return CachedCmList(body = body, savedAt = savedAt)
        }

        @Synchronized
        fun save(key: String, body: String) {
            if (body.toByteArray(StandardCharsets.UTF_8).size.toLong() > MAX_CM_LIST_CACHE_BYTES) return
            runCatching {
                cacheFile.parentFile?.mkdirs()
                val entry =
                    JSONObject()
                        .put(KEY_QUERY, key)
                        .put(KEY_SAVED_AT, System.currentTimeMillis())
                        .put(KEY_BODY, body)
                        .toString()
                val encoded = entry.toByteArray(StandardCharsets.UTF_8)
                if (encoded.size.toLong() > MAX_CM_LIST_CACHE_BYTES) return@runCatching
                val temp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
                temp.writeBytes(encoded)
                if (!temp.renameTo(cacheFile)) {
                    cacheFile.writeBytes(encoded)
                    temp.delete()
                }
            }
        }

        /**
         * The cached directory whatever query produced it.
         *
         * One directory serves every reader - the kSteam transport and the SDK route source ask the same
         * service for the same list - so a reader that only needs the body does not have to reproduce the
         * other reader's query string.
         */
        @Synchronized
        fun loadMostRecent(): CachedCmList? {
            val entry = loadEntry() ?: return null
            val savedAt = entry.optLong(KEY_SAVED_AT, 0L)
            val body = entry.optString(KEY_BODY).takeIf(String::isNotEmpty) ?: return null
            if (savedAt <= 0L) return null
            return CachedCmList(body = body, savedAt = savedAt)
        }

        private fun loadEntry(): JSONObject? {
            if (!cacheFile.isFile || cacheFile.length() > MAX_CM_LIST_CACHE_BYTES) return null
            return runCatching {
                JSONObject(cacheFile.readBytes().toString(StandardCharsets.UTF_8))
            }.getOrNull()
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
