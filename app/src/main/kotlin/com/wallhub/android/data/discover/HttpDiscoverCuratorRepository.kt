package com.wallhub.android.data.discover

import android.content.Context
import android.util.AtomicFile
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.data.downloads.readBytesBounded
import com.wallhub.android.feature.discover.model.DiscoverMetadataSnapshot
import com.wallhub.android.feature.discover.model.DiscoverMetadataSource
import com.wallhub.android.feature.discover.model.DiscoverCuratorRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@Singleton
class HttpDiscoverCuratorRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val settingsRepository: SettingsRepository,
        private val noSniTransport: DiscoverCuratorNoSniTransport,
    ) : DiscoverCuratorRepository {
        private data class PersistedManifest(
            val payload: String,
            val etag: String?,
            val lastModified: String?,
            val fetchedAtMillis: Long,
            val expiresAtMillis: Long,
        )

        private sealed interface FetchResult {
            data class Updated(
                val payload: String,
                val parsed: ParsedCuratorMetadata,
                val etag: String?,
                val lastModified: String?,
            ) : FetchResult

            data object NotModified : FetchResult
        }

        private val client =
            OkHttpClient
                .Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        private val manifestFile = AtomicFile(File(context.noBackupFilesDir, MANIFEST_FILE_NAME))
        private val refreshMutex = Mutex()

        @Volatile
        private var cachedSnapshot: DiscoverMetadataSnapshot? = null
        private var persistedManifest: PersistedManifest? = null
        private var persistentLoaded = false

        override suspend fun loadCachedMetadata(): DiscoverMetadataSnapshot? = refreshMutex.withLock {
            val now = System.currentTimeMillis()
            cachedSnapshot?.takeIf { it.descriptors.isNotEmpty() }?.let {
                return it.copy(source = DiscoverMetadataSource.MEMORY_CACHE)
            }
            withContext(Dispatchers.IO) {
                ensurePersistentLoaded()
                persistedSnapshot(now, allowExpired = true)
            }?.also { cachedSnapshot = it }
        }

        override suspend fun loadMetadata(): DiscoverMetadataSnapshot = refreshMutex.withLock {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) { ensurePersistentLoaded() }
            val previous = persistedManifest
            try {
                when (val result = fetch(previous)) {
                    FetchResult.NotModified -> {
                        val retained = requireNotNull(previous) { "Curator returned 304 without a persisted manifest" }
                        val refreshed = retained.copy(
                            fetchedAtMillis = now,
                            expiresAtMillis = now + NETWORK_SNAPSHOT_TTL_MILLIS,
                        )
                        withContext(Dispatchers.IO) {
                            persist(refreshed)
                            snapshot(refreshed, DiscoverMetadataSource.NETWORK)
                        }.also { cachedSnapshot = it }
                    }
                    is FetchResult.Updated -> {
                        val updated = PersistedManifest(
                            payload = result.payload,
                            etag = result.etag,
                            lastModified = result.lastModified,
                            fetchedAtMillis = now,
                            expiresAtMillis = now + NETWORK_SNAPSHOT_TTL_MILLIS,
                        )
                        withContext(Dispatchers.IO) {
                            persist(updated)
                            LegacyExploreCuratorParser.snapshot(result.parsed, now, NETWORK_SNAPSHOT_TTL_MILLIS)
                        }.also { cachedSnapshot = it }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                withContext(Dispatchers.IO) { persistedSnapshot(now, allowExpired = true) }
                    ?.copy(
                        fetchedAtMillis = now,
                        expiresAtMillis = now + FALLBACK_RETRY_TTL_MILLIS,
                    )?.also { cachedSnapshot = it }
                    ?: genericFallbackSnapshot(now).also { cachedSnapshot = it }
            }
        }

        private suspend fun fetch(previous: PersistedManifest?): FetchResult {
            val antiBlockingEnabled = settingsRepository.preferences.first().steamAccessEnabled
            return if (antiBlockingEnabled) {
                fetchNoSni(previous)
            } else {
                fetchDirect(previous)
            }
        }

        private suspend fun fetchNoSni(previous: PersistedManifest?): FetchResult {
            val response =
                noSniTransport.get(
                    url = DISCOVER_CURATOR_URL,
                    etag = previous?.etag,
                    lastModified = previous?.lastModified,
                )
            if (response.statusCode == 304) return FetchResult.NotModified
            if (response.statusCode !in 200..299) {
                throw IOException("Discover curator failed: HTTP ${response.statusCode}")
            }
            return FetchResult.Updated(
                payload = response.body,
                parsed = DiscoverCuratorManifestParser.parse(response.body),
                etag = response.etag.boundedHeaderOrNull(),
                lastModified = response.lastModified.boundedHeaderOrNull(),
            )
        }

        private suspend fun fetchDirect(previous: PersistedManifest?): FetchResult =
            withContext(Dispatchers.IO) {
                val requestBuilder =
                    Request
                        .Builder()
                        .url(DISCOVER_CURATOR_URL)
                        .header("Accept", "application/json")
                        .header("User-Agent", USER_AGENT)
                previous?.etag?.let { requestBuilder.header("If-None-Match", it) }
                previous?.lastModified?.let { requestBuilder.header("If-Modified-Since", it) }
                client.newCall(requestBuilder.get().build()).execute().use { response ->
                    if (response.code == 304) return@withContext FetchResult.NotModified
                    if (!response.isSuccessful) throw IOException("Discover curator failed: HTTP ${response.code}")
                    val payload =
                        response.body.byteStream().use { input ->
                            input.readBytesBounded(MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                        }
                    FetchResult.Updated(
                        payload = payload,
                        parsed = DiscoverCuratorManifestParser.parse(payload),
                        etag = response.header("ETag").boundedHeaderOrNull(),
                        lastModified = response.header("Last-Modified").boundedHeaderOrNull(),
                    )
                }
            }

        private fun ensurePersistentLoaded() {
            if (persistentLoaded) return
            persistentLoaded = true
            persistedManifest = readPersisted()
        }

        private fun persistedSnapshot(now: Long, allowExpired: Boolean): DiscoverMetadataSnapshot? {
            val persisted = persistedManifest ?: return null
            if (!allowExpired && now >= persisted.expiresAtMillis) return null
            return runCatching { snapshot(persisted, DiscoverMetadataSource.PERSISTED_CACHE) }.getOrNull()
        }

        private fun snapshot(
            persisted: PersistedManifest,
            source: DiscoverMetadataSource,
        ): DiscoverMetadataSnapshot {
            val parsed = DiscoverCuratorManifestParser.parse(persisted.payload)
            return LegacyExploreCuratorParser.snapshot(
                parsed = parsed,
                nowMillis = persisted.fetchedAtMillis,
                ttlMillis = (persisted.expiresAtMillis - persisted.fetchedAtMillis).coerceAtLeast(1L),
                source = source,
            )
        }

        private fun readPersisted(): PersistedManifest? =
            runCatching {
                val bytes = manifestFile.readFully()
                require(bytes.size <= MAX_PERSISTED_BYTES) { "Persisted Discover manifest exceeds limit" }
                val root = JSONObject(bytes.toString(Charsets.UTF_8))
                require(root.optInt("schemaVersion") == PERSISTED_SCHEMA_VERSION)
                val payload = root.getString("payload")
                require(root.getString("payloadSha256") == payload.sha256()) { "Persisted Discover manifest checksum mismatch" }
                PersistedManifest(
                    payload = payload,
                    etag = root.optString("etag").takeIf(String::isNotBlank),
                    lastModified = root.optString("lastModified").takeIf(String::isNotBlank),
                    fetchedAtMillis = root.getLong("fetchedAtMillis"),
                    expiresAtMillis = root.getLong("expiresAtMillis"),
                ).also { DiscoverCuratorManifestParser.parse(it.payload) }
            }.getOrNull()

        private fun persist(manifest: PersistedManifest) {
            val root =
                JSONObject()
                    .put("schemaVersion", PERSISTED_SCHEMA_VERSION)
                    .put("payload", manifest.payload)
                    .put("payloadSha256", manifest.payload.sha256())
                    .put("etag", manifest.etag.orEmpty())
                    .put("lastModified", manifest.lastModified.orEmpty())
                    .put("fetchedAtMillis", manifest.fetchedAtMillis)
                    .put("expiresAtMillis", manifest.expiresAtMillis)
            val output = manifestFile.startWrite()
            try {
                output.write(root.toString().toByteArray(Charsets.UTF_8))
                manifestFile.finishWrite(output)
                persistedManifest = manifest
            } catch (error: Throwable) {
                manifestFile.failWrite(output)
                throw error
            }
        }

        private fun genericFallbackSnapshot(nowMillis: Long): DiscoverMetadataSnapshot =
            LegacyExploreCuratorParser.snapshot(
                parsed = ParsedCuratorMetadata(emptyList(), 0, 0),
                nowMillis = nowMillis,
                ttlMillis = FALLBACK_RETRY_TTL_MILLIS,
                source = DiscoverMetadataSource.GENERIC_FALLBACK,
            )

        private companion object {
            const val DISCOVER_CURATOR_URL = "https://www.wallpaperengineapi.com/api/explore/v1"
            const val USER_AGENT = "WallHub-Android/Discover"
            const val MANIFEST_FILE_NAME = "discover-curator-manifest.json"
            const val PERSISTED_SCHEMA_VERSION = 1
            const val MAX_MANIFEST_BYTES = 512 * 1024
            const val MAX_PERSISTED_BYTES = 1024 * 1024
            const val NETWORK_SNAPSHOT_TTL_MILLIS = 15L * 60L * 1_000L
            const val FALLBACK_RETRY_TTL_MILLIS = 2L * 60L * 1_000L
        }
    }

private fun String?.boundedHeaderOrNull(): String? = this?.takeIf { it.length <= MAX_VALIDATOR_LENGTH }

private const val MAX_VALIDATOR_LENGTH = 1_024

private fun String.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
