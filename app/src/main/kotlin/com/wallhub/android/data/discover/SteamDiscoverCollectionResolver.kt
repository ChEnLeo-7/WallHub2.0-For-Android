package com.wallhub.android.data.discover

import com.wallhub.android.core.model.WorkshopRepository
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopPage
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import com.wallhub.android.feature.discover.DiscoverCollectionResolver
import com.wallhub.android.feature.discover.DiscoverNetworkBudget
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SteamDiscoverCollectionResolver
    @Inject
    constructor(
        private val workshopRepository: WorkshopRepository,
        private val unifiedWorkshopRepository: SteamUnifiedWorkshopRepository,
        private val networkBudget: DiscoverNetworkBudget,
        clientFactory: SteamHttpClientFactory,
    ) : DiscoverCollectionResolver {
        private val memberCache = ConcurrentHashMap<Long, CachedCollectionMembers>()
        private val client =
            clientFactory
                .newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()

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
                unifiedMembers?.takeIf(List<Long>::isNotEmpty)
                    ?: networkBudget.withPermit { loadMembers(collectionId) }
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

        private suspend fun loadMembers(collectionId: Long): List<Long> = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            memberCache[collectionId]?.takeIf { now - it.loadedAtMillis < COLLECTION_CACHE_TTL_MILLIS }?.let {
                return@withContext it.memberIds
            }
            val form =
                FormBody
                    .Builder()
                    .add("collectioncount", "1")
                    .add("publishedfileids[0]", collectionId.toString())
                    .build()
            val request =
                Request
                    .Builder()
                    .url(COLLECTION_DETAILS_URL)
                    .post(form)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .build()
            val memberIds =
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Steam collection details request failed: HTTP ${response.code}")
                    }
                    parseCollectionMemberIds(response.body.string(), collectionId)
                }
            if (memberIds.isNotEmpty()) {
                memberCache[collectionId] = CachedCollectionMembers(memberIds, now)
            }
            memberIds
        }

        private suspend fun resolveDetails(ids: List<Long>): List<WorkshopSummary> =
            runCatching {
                networkBudget.withPermit {
                    unifiedWorkshopRepository
                        .getPublicDetails(ids)
                        .ifEmpty { workshopRepository.getDetails(ids) }
                        .map(WorkshopDetail::summary)
                }
            }.getOrDefault(emptyList())
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

private data class CachedCollectionMembers(
    val memberIds: List<Long>,
    val loadedAtMillis: Long,
)

@Module
@InstallIn(SingletonComponent::class)
abstract class DiscoverCollectionResolverModule {
    @Binds
    @Singleton
    abstract fun bindDiscoverCollectionResolver(
        resolver: SteamDiscoverCollectionResolver,
    ): DiscoverCollectionResolver
}

private const val COLLECTION_DETAILS_URL =
    "https://api.steampowered.com/ISteamRemoteStorage/GetCollectionDetails/v1/"
private const val USER_AGENT = "WallHub-Android/0.8 (Discover Collection Resolver)"
private const val MAX_COLLECTION_PAGE_SIZE = 50
private const val COLLECTION_CACHE_TTL_MILLIS = 10 * 60 * 1_000L
