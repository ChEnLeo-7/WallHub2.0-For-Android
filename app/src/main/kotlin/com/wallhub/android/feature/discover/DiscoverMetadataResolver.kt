package com.wallhub.android.feature.discover

import com.wallhub.android.core.model.CmPublicDetailResult
import com.wallhub.android.core.model.SteamPublicProfile
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Resolves Discover presentation metadata in bounded batches rather than issuing work per card. */
@Singleton
class DiscoverMetadataResolver
    @Inject
    constructor(
        private val unifiedWorkshopRepository: SteamUnifiedWorkshopRepository,
    ) {
        internal suspend fun enrichWallpapers(items: List<WorkshopSummary>): List<WorkshopSummary> {
            val accountIds =
                items
                    .asSequence()
                    .filter { it.authorPlaceholder != WorkshopAuthorPlaceholder.NONE || it.author.isBlank() }
                    .mapNotNull { it.creatorId.toPositiveAccountIdOrNull() }
                    .toSet()
            if (accountIds.isEmpty()) return items
            val profiles = resolveProfiles(accountIds)
            return items.map { item ->
                if (item.authorPlaceholder != WorkshopAuthorPlaceholder.NONE || item.author.isBlank()) {
                    item.withProfile(profiles[item.creatorId.toPositiveAccountIdOrNull()])
                } else {
                    item
                }
            }
        }

        internal suspend fun resolveFeatured(candidates: List<DiscoverFeaturedCandidate>): List<DiscoverFeaturedItem> = coroutineScope {
            if (candidates.isEmpty()) return@coroutineScope emptyList()
            val creatorIds =
                candidates
                    .asSequence()
                    .filter { it.spec.category == DiscoverCategory.CREATOR }
                    .mapNotNull { candidate -> candidate.cover.creatorId.toPositiveAccountIdOrNull() }
                    .toSet()
            val collectionIds =
                candidates
                    .asSequence()
                    .filter { it.spec.category == DiscoverCategory.COLLECTION }
                    .mapNotNull { candidate -> candidate.spec.queryPlan.collectionId }
                    .filter { it > 0L }
                    .distinct()
                    .toList()
            val profilesDeferred = async { resolveProfiles(creatorIds) }
            val collectionsDeferred = async { resolveCollectionTitles(collectionIds) }
            val profiles = profilesDeferred.await()
            val collectionTitles = collectionsDeferred.await()

            candidates.map { candidate ->
                val accountId = candidate.cover.creatorId.toPositiveAccountIdOrNull()
                val profile = profiles[accountId]
                val enrichedCover = candidate.cover.withProfile(profile)
                val title =
                    when (candidate.spec.category) {
                        DiscoverCategory.CREATOR ->
                            profile?.displayName.normalizedLabel()
                                ?: enrichedCover.author.takeIf {
                                    enrichedCover.authorPlaceholder == WorkshopAuthorPlaceholder.NONE
                                }.normalizedLabel()
                        DiscoverCategory.COLLECTION ->
                            candidate.spec.queryPlan.collectionId?.let(collectionTitles::get)
                        else -> candidate.spec.titleArgument.normalizedLabel()
                    }
                DiscoverFeaturedItem(candidate.spec, enrichedCover, title)
            }
        }

        private suspend fun resolveProfiles(accountIds: Collection<Long>): Map<Long, SteamPublicProfile> {
            if (accountIds.isEmpty()) return emptyMap()
            return metadataOrEmpty { unifiedWorkshopRepository.resolvePublicProfiles(accountIds) }
                .filterValues { it.displayName.isNotBlank() }
        }

        private suspend fun resolveCollectionTitles(collectionIds: List<Long>): Map<Long, String> {
            if (collectionIds.isEmpty()) return emptyMap()
            val batch = metadataOrNull { unifiedWorkshopRepository.getPublicDetailsResult(collectionIds) } ?: return emptyMap()
            return batch.outcomes.mapNotNull { (id, outcome) ->
                val summary = (outcome as? CmPublicDetailResult.Available)?.detail?.summary ?: return@mapNotNull null
                summary.title
                    .takeUnless { summary.isTitlePlaceholder }
                    .normalizedLabel()
                    ?.let { title -> id to title }
            }.toMap()
        }
    }

private fun WorkshopSummary.withProfile(profile: SteamPublicProfile?): WorkshopSummary =
    if (profile == null || profile.displayName.isBlank()) {
        this
    } else {
        copy(
            author = profile.displayName.trim(),
            authorAvatarUrl = profile.avatarUrl ?: authorAvatarUrl,
            authorPlaceholder = WorkshopAuthorPlaceholder.NONE,
        )
    }

private fun String?.toPositiveAccountIdOrNull(): Long? = this?.toLongOrNull()?.takeIf { it > 0L }

private fun String?.normalizedLabel(): String? = this?.trim()?.takeIf(String::isNotBlank)

private suspend fun <K, V> metadataOrEmpty(block: suspend () -> Map<K, V>): Map<K, V> =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyMap()
    }

private suspend fun <T> metadataOrNull(block: suspend () -> T): T? =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
