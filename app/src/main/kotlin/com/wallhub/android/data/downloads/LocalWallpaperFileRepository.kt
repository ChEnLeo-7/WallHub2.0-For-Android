package com.wallhub.android.data.downloads

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.database.LocalWallpaperMetadataDao
import com.wallhub.android.core.database.LocalWallpaperStateEntity
import com.wallhub.android.core.model.LocalWallpaperDeleteResult
import com.wallhub.android.core.model.LocalWallpaperRepository
import com.wallhub.android.core.model.LocalWallpaperResource
import com.wallhub.android.core.model.LocalWallpaperScanIssue
import com.wallhub.android.core.model.LocalWallpaperScanSnapshot
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.data.steamaccess.SteamHttpClientFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class LocalWallpaperFileRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
        internal val settingsRepository: SettingsRepository,
        internal val taskDao: FormalTaskRecordDao,
        internal val metadataDao: LocalWallpaperMetadataDao,
        clientFactory: SteamHttpClientFactory,
    ) : LocalWallpaperRepository {
        internal val applicationContext = context.applicationContext
        internal val resolver = applicationContext.contentResolver
        internal val thumbnailCache = SteamWorkshopThumbnailCache(applicationContext, clientFactory.newBuilder())
        internal val projectContentUris = ConcurrentHashMap<String, List<String>>()

        override fun scan(): Flow<LocalWallpaperScanSnapshot> =
            flow {
                val preferences = settingsRepository.preferences.first()
                val plans = sourcePlans(preferences)
                val sources = plans.map(SourcePlan::source)
                val states = metadataDao.listStates().associateBy(LocalWallpaperStateEntity::resourceId)
                val tags = metadataDao.listTags().groupBy({ tag -> tag.resourceId }, { tag -> tag.tag })
                val tasks = taskDao.listAll()
                val knownPreviewUrls =
                    tasks
                        .asSequence()
                        .sortedBy(FormalTaskRecordEntity::updatedAt)
                        .mapNotNull { task ->
                            task.previewUrl
                                ?.takeIf(String::isNotBlank)
                                ?.let { previewUrl -> task.workshopId to previewUrl }
                        }.toMap()
                val resources = linkedMapOf<String, LocalWallpaperResource>()
                val issues = mutableListOf<LocalWallpaperScanIssue>()
                projectContentUris.clear()
                var discoveredCount = 0

                emit(
                    LocalWallpaperScanSnapshot(
                        sources = sources,
                        isScanning = true,
                    ),
                )

                plans.forEach { plan ->
                    currentCoroutineContext().ensureActive()
                    emit(
                        snapshot(
                            resources = resources.values,
                            sources = sources,
                            discoveredCount = discoveredCount,
                            currentSourceLabel = plan.source.label,
                            issues = issues,
                            isScanning = true,
                        ),
                    )
                    val scan =
                        try {
                            scanSource(plan)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: SecurityException) {
                            failedSourceScan(plan, error, requiresAuthorization = true)
                        } catch (error: Throwable) {
                            failedSourceScan(plan, error, requiresAuthorization = false)
                        }
                    scan.issue?.let(issues::add)
                    discoveredCount += scan.nodes.size
                    var inspectedCount = 0
                    inspectNodes(
                        nodes = scan.nodes,
                        tasks = tasks,
                        states = states,
                        tags = tags,
                    ) { resource ->
                        val cachedThumbnail = resource.workshopId?.let(thumbnailCache::cachedUri)
                        resources[resource.id] = resource.copy(thumbnailUri = cachedThumbnail)
                        inspectedCount += 1
                        if (inspectedCount % RESULT_EMIT_BATCH_SIZE == 0) {
                            emit(
                                snapshot(
                                    resources = resources.values,
                                    sources = sources,
                                    discoveredCount = discoveredCount,
                                    currentSourceLabel = plan.source.label,
                                    issues = issues,
                                    isScanning = true,
                                ),
                            )
                        }
                    }
                    emit(
                        snapshot(
                            resources = resources.values,
                            sources = sources,
                            discoveredCount = discoveredCount,
                            currentSourceLabel = plan.source.label,
                            issues = issues,
                            isScanning = true,
                        ),
                    )
                }

                val workshopIds = resources.values.mapNotNullTo(linkedSetOf(), LocalWallpaperResource::workshopId)
                if (workshopIds.any { workshopId -> thumbnailCache.cachedUri(workshopId) == null }) {
                    emit(
                        snapshot(
                            resources = resources.values,
                            sources = sources,
                            discoveredCount = discoveredCount,
                            currentSourceLabel = NETWORK_COVER_LABEL,
                            issues = issues,
                            isScanning = true,
                        ),
                    )
                    val thumbnails = thumbnailCache.resolve(workshopIds, knownPreviewUrls)
                    resources.keys.toList().forEach { resourceId ->
                        val resource = resources[resourceId] ?: return@forEach
                        resources[resourceId] =
                            resource.copy(
                                thumbnailUri = resource.workshopId?.let(thumbnails::get),
                            )
                    }
                }

                emit(
                    snapshot(
                        resources = resources.values,
                        sources = sources,
                        discoveredCount = discoveredCount,
                        currentSourceLabel = null,
                        issues = issues,
                        isScanning = false,
                    ),
                )
            }.flowOn(Dispatchers.IO)

        override suspend fun setFavorite(
            resourceId: String,
            favorite: Boolean,
        ) {
            val previous = metadataDao.listStates().firstOrNull { state -> state.resourceId == resourceId }
            metadataDao.upsertState(
                LocalWallpaperStateEntity(
                    resourceId = resourceId,
                    isFavorite = favorite,
                    importRequestedAt = previous?.importRequestedAt,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

        override suspend fun replaceTags(
            resourceId: String,
            tags: Set<String>,
        ) {
            metadataDao.replaceTags(resourceId, tags.mapNotNull(::normalizeTag).toSet())
        }

        override suspend fun renameTag(
            oldTag: String,
            newTag: String,
        ) {
            val normalizedOld = normalizeTag(oldTag) ?: return
            val normalizedNew = normalizeTag(newTag) ?: return
            if (normalizedOld == normalizedNew) return
            metadataDao.renameTag(normalizedOld, normalizedNew)
        }

        override suspend fun deleteTag(tag: String) {
            normalizeTag(tag)?.let { normalized -> metadataDao.deleteTag(normalized) }
        }

        override suspend fun markImportRequested(
            resourceId: String,
            requestedAt: Long,
        ) {
            val previous = metadataDao.listStates().firstOrNull { state -> state.resourceId == resourceId }
            metadataDao.upsertState(
                LocalWallpaperStateEntity(
                    resourceId = resourceId,
                    isFavorite = previous?.isFavorite ?: false,
                    importRequestedAt = requestedAt,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

        override suspend fun delete(resource: LocalWallpaperResource): LocalWallpaperDeleteResult {
            if (runCatching { Uri.parse(resource.contentUri) }.isFailure) {
                return LocalWallpaperDeleteResult(false, "文件地址无效")
            }
            return runCatching {
                val uris = (projectContentUris[resource.id].orEmpty() + resource.contentUri).distinct()
                var deletedCount = 0
                uris.asReversed().forEach { rawUri ->
                    val target = Uri.parse(rawUri)
                    val deleted =
                        when (target.scheme) {
                            "file" -> File(requireNotNull(target.path)).delete()
                            else ->
                                resolver.delete(target, null, null) > 0 ||
                                    DocumentFile.fromSingleUri(applicationContext, target)?.delete() == true
                        }
                    if (deleted) deletedCount += 1
                }
                if (deletedCount == uris.size) {
                    metadataDao.deleteMetadata(resource.id)
                    LocalWallpaperDeleteResult(true, "已删除 ${resource.displayName}")
                } else {
                    LocalWallpaperDeleteResult(
                        false,
                        "已删除 $deletedCount/${uris.size} 个文件，请检查目录权限",
                    )
                }
            }.getOrElse { error ->
                LocalWallpaperDeleteResult(
                    deleted = false,
                    message =
                        if (error is SecurityException) {
                            "目录授权已失效，请重新授权后重试"
                        } else {
                            error.message ?: "删除失败"
                        },
                )
            }
        }
    }

private fun failedSourceScan(
    plan: SourcePlan,
    error: Throwable,
    requiresAuthorization: Boolean,
): SourceScan =
    SourceScan(
        nodes = emptyList(),
        issue =
            LocalWallpaperScanIssue(
                sourceId = plan.source.id,
                message = error.message ?: "无法扫描目录",
                requiresAuthorization = requiresAuthorization,
            ),
    )
