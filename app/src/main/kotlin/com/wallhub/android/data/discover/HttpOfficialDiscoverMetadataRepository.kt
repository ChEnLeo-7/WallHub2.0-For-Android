package com.wallhub.android.data.discover

import com.wallhub.android.feature.discover.model.DiscoverMetadataSnapshot
import com.wallhub.android.feature.discover.model.DiscoverMetadataSource
import com.wallhub.android.feature.discover.model.OfficialDiscoverCategory
import com.wallhub.android.feature.discover.model.OfficialDiscoverDescriptor
import com.wallhub.android.feature.discover.model.OfficialDiscoverMetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HttpOfficialDiscoverMetadataRepository
    @Inject
    constructor() : OfficialDiscoverMetadataRepository {
        private val client =
            OkHttpClient
                .Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        private val refreshMutex = Mutex()

        @Volatile
        private var cachedSnapshot: DiscoverMetadataSnapshot? = null

        override suspend fun loadMetadata(): DiscoverMetadataSnapshot = refreshMutex.withLock {
            val now = System.currentTimeMillis()
            cachedSnapshot?.takeUnless { it.isExpired(now) }?.let {
                val cacheSource =
                    if (it.source == DiscoverMetadataSource.STATIC_FALLBACK) {
                        DiscoverMetadataSource.STATIC_FALLBACK
                    } else {
                        DiscoverMetadataSource.MEMORY_CACHE
                    }
                return it.copy(source = cacheSource)
            }

            try {
                val parsed = fetchAndParse()
                OfficialDiscoverMetadataParser
                    .snapshot(parsed, now, NETWORK_SNAPSHOT_TTL_MILLIS)
                    .also { cachedSnapshot = it }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                cachedSnapshot?.copy(source = DiscoverMetadataSource.MEMORY_CACHE)
                    ?: staticFallbackSnapshot(now).also { cachedSnapshot = it }
            }
        }

        private suspend fun fetchAndParse(): ParsedDiscoverMetadata =
            withContext(Dispatchers.IO) {
                val request =
                    Request
                        .Builder()
                        .url(OFFICIAL_DISCOVER_URL)
                        .header("Accept", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Official Discover metadata failed: HTTP ${response.code}")
                    }
                    OfficialDiscoverMetadataParser.parse(response.body.string())
                }
            }

        private fun staticFallbackSnapshot(nowMillis: Long): DiscoverMetadataSnapshot {
            val parsed =
                ParsedDiscoverMetadata(
                    descriptors = STATIC_FALLBACK_DESCRIPTORS,
                    receivedItemCount = STATIC_FALLBACK_DESCRIPTORS.size,
                    rejectedItemCount = 0,
                )
            return OfficialDiscoverMetadataParser.snapshot(
                parsed = parsed,
                nowMillis = nowMillis,
                ttlMillis = FALLBACK_RETRY_TTL_MILLIS,
                source = DiscoverMetadataSource.STATIC_FALLBACK,
            )
        }

        private companion object {
            const val OFFICIAL_DISCOVER_URL = "https://www.wallpaperengineapi.com/api/explore/v1"
            const val USER_AGENT = "WallHub-Android/Discover"
            const val NETWORK_SNAPSHOT_TTL_MILLIS = 15L * 60L * 1_000L
            const val FALLBACK_RETRY_TTL_MILLIS = 2L * 60L * 1_000L

            val STATIC_FALLBACK_DESCRIPTORS =
                listOf(
                    fallbackCollection("day-night", 2852303026L, 2406282996L, listOf("Relaxing", "Nature")),
                    fallbackCollection("cars", 2840422685L, 1443280386L, listOf("Vehicle")),
                    fallbackCollection("vaporwave", 2833488043L, 2596848703L, listOf("Retro", "CGI", "Technology")),
                    fallbackCollection("audio-visualizers", 2383361385L, 2067939514L, listOf("Abstract", "Music", "Retro", "Audio responsive")),
                    fallbackKeyword("city", "City", listOf("Cyberpunk", "Landscape"), excludeTags = listOf("Vehicle", "Girls", "Anime")),
                    fallbackKeyword("space", "Space", listOf("Sci-Fi"), includeTags = listOf("Sci-Fi", "Approved")),
                    fallbackKeyword("forest", "Forest", listOf("Nature"), excludeTags = listOf("Anime")),
                    fallbackKeyword("lo-fi", "Lo-Fi", listOf("Music", "Relaxing"), exact = true),
                    fallbackKeyword("pixel-art", "Pixel art", listOf("Pixel art", "Game", "Retro")),
                    fallbackKeyword("anime", "Anime", listOf("Anime")),
                    fallbackKeyword("nature", "Nature", listOf("Nature", "Landscape")),
                    fallbackKeyword("abstract", "Abstract", listOf("Abstract")),
                    fallbackKeyword("fantasy", "Fantasy", listOf("Fantasy")),
                    fallbackKeyword("games", "Game", listOf("Game")),
                    fallbackKeyword("cyberpunk", "Cyberpunk", listOf("Cyberpunk", "Sci-Fi")),
                    fallbackKeyword("retro", "Retro", listOf("Retro")),
                    fallbackKeyword("technology", "Technology", listOf("Technology")),
                    fallbackKeyword("vehicles", "Vehicle", listOf("Vehicle")),
                    fallbackKeyword("animals", "Animal", listOf("Animal")),
                    fallbackKeyword("relaxing", "Relaxing", listOf("Relaxing", "Nature")),
                    fallbackKeyword("clock", "Clock", listOf("Technology", "Abstract")),
                    fallbackKeyword("sakura", "Sakura", listOf("Nature", "Landscape")),
                    fallbackKeyword("ocean", "Ocean", listOf("Nature")),
                    fallbackKeyword("parallax", "Parallax", listOf("Relaxing")),
                )

            fun fallbackCollection(
                id: String,
                collectionId: Long,
                coverSubmissionId: Long,
                tags: List<String>,
            ) =
                OfficialDiscoverDescriptor(
                    stableId = "collection:static-$id",
                    category = OfficialDiscoverCategory.COLLECTION,
                    itemId = collectionId.toString(),
                    coverSubmissionId = coverSubmissionId,
                    tags = tags,
                )

            fun fallbackKeyword(
                id: String,
                keyword: String,
                tags: List<String>,
                includeTags: List<String> = listOf("Approved"),
                excludeTags: List<String> = emptyList(),
                exact: Boolean = false,
            ) =
                OfficialDiscoverDescriptor(
                    stableId = "keyword:static-$id:trend_year",
                    category = OfficialDiscoverCategory.KEYWORD,
                    keyword = keyword,
                    queryTypes = listOf("trend_year"),
                    tags = tags,
                    includeTags = includeTags,
                    excludeTags = excludeTags,
                    exact = exact,
                    priority = 0.5f,
                )
        }
    }
