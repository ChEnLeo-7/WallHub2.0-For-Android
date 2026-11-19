package com.wallhub.android.data.discover

import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.data.downloads.readBytesBounded
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import com.wallhub.android.feature.discover.DiscoverCollectionResolver
import com.wallhub.android.feature.discover.DiscoverNetworkBudget
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@Singleton
class SteamDiscoverCollectionResolver
    @Inject
    constructor(
        private val unifiedWorkshopRepository: SteamUnifiedWorkshopRepository,
        private val workshopRepository: WorkshopRepository,
        private val networkBudget: DiscoverNetworkBudget,
        clientFactory: SteamHttpClientFactory,
    ) : DiscoverCollectionResolver {
        private val publicCollectionClient: OkHttpClient =
            clientFactory
                .newAnonymousReadBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

        override suspend fun invalidateDiscoverCache() = Unit

        override suspend fun browse(
            collectionId: Long,
            page: Int,
            pageSize: Int,
        ): WorkshopPage {
            require(collectionId > 0L) { "Invalid Workshop collection ID" }
            val normalizedPage = page.coerceAtLeast(1)
            val normalizedPageSize = pageSize.coerceIn(1, MAX_COLLECTION_PAGE_SIZE)
            val unifiedMembers =
                networkBudget.withPermit {
                    unifiedWorkshopRepository.getPublicCollectionChildren(collectionId)
                }
            val members =
                unifiedMembers ?: networkBudget.withPermit { loadPublicCollectionChildren(collectionId) }
            val start = ((normalizedPage - 1L) * normalizedPageSize).coerceAtMost(members.size.toLong()).toInt()
            val end = (start + normalizedPageSize).coerceAtMost(members.size)
            val pageIds = members.subList(start, end)
            val items = resolveDetails(pageIds)
            return WorkshopPage(
                items = items,
                page = normalizedPage,
                hasNextPage = end < members.size,
                totalCount = members.size,
                totalPages = ((members.size + normalizedPageSize - 1) / normalizedPageSize).coerceAtLeast(1),
            )
        }

        private suspend fun loadPublicCollectionChildren(collectionId: Long): List<Long> =
            withContext(Dispatchers.IO) {
                val body =
                    FormBody
                        .Builder()
                        .add("collectioncount", "1")
                        .add("publishedfileids[0]", collectionId.toString())
                        .build()
                val request =
                    Request
                        .Builder()
                        .url(PUBLIC_COLLECTION_DETAILS_URL)
                        .header("Accept", "application/json")
                        .header("User-Agent", "WallHub-Android/Discover")
                        .post(body)
                        .build()
                publicCollectionClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Steam collection details failed: HTTP ${response.code}")
                    val payload =
                        response.body.byteStream().use { input ->
                            input.readBytesBounded(MAX_COLLECTION_RESPONSE_BYTES).toString(Charsets.UTF_8)
                        }
                    parseCollectionMemberIds(payload, collectionId)
                }
            }

        private suspend fun resolveDetails(ids: List<Long>): List<WorkshopSummary> {
            if (ids.isEmpty()) return emptyList()
            val primary =
                runCatching {
                    networkBudget.withPermit { unifiedWorkshopRepository.getPublicDetailsResult(ids) }
                }.getOrNull()
            val resolved = primary?.available.orEmpty().associateBy { it.summary.id }.toMutableMap()
            val missing = ids.filterNot(resolved::containsKey)
            if (missing.isNotEmpty()) {
                val fallback =
                    runCatching {
                        networkBudget.withPermit { workshopRepository.getDetails(missing) }
                    }.getOrDefault(emptyList())
                fallback.forEach { resolved.putIfAbsent(it.summary.id, it) }
            }
            return ids.mapNotNull(resolved::get).map(WorkshopDetail::summary)
        }
    }

internal fun parseCollectionMemberIds(
    payload: String,
    expectedCollectionId: Long,
): List<Long> {
    val collections = JSONObject(payload).optJSONObject("response")?.optJSONArray("collectiondetails")
        ?: return emptyList()
    for (index in 0 until collections.length()) {
        val collection = collections.optJSONObject(index) ?: continue
        if (collection.opt("publishedfileid")?.toString()?.toLongOrNull() != expectedCollectionId) continue
        val children = collection.optJSONArray("children") ?: return emptyList()
        return buildList {
            for (childIndex in 0 until children.length()) {
                children
                    .optJSONObject(childIndex)
                    ?.opt("publishedfileid")
                    ?.toString()
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?.let(::add)
            }
        }.distinct()
    }
    return emptyList()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DiscoverCollectionResolverModule {
    @Binds
    @Singleton
    abstract fun bindDiscoverCollectionResolver(
        resolver: SteamDiscoverCollectionResolver,
    ): DiscoverCollectionResolver
}

private const val MAX_COLLECTION_PAGE_SIZE = 50
private const val MAX_COLLECTION_RESPONSE_BYTES = 512 * 1024
private const val PUBLIC_COLLECTION_DETAILS_URL =
    "https://api.steampowered.com/ISteamRemoteStorage/GetCollectionDetails/v1/"
