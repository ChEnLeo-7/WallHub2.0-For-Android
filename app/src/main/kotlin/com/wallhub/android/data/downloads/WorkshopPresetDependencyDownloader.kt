package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import com.wallhub.prototype.mpkg.MAX_PRESET_DEPENDENCY_DEPTH
import com.wallhub.prototype.mpkg.PresetDependencyResolver
import java.io.File
import javax.inject.Inject

/** Resolves and downloads the bounded dependency chain declared by Workshop preset projects. */
internal class WorkshopPresetDependencyDownloader
    @Inject
    constructor(
        private val contentClient: SteamWorkshopContentClient,
    ) {
        suspend fun download(
            sourceDirectory: File,
            rootWorkshopId: Long,
            credential: SteamContentCredential?,
            options: SteamContentDownloadOptions,
            proxyUrl: String,
            control: suspend () -> SteamDownloadControl,
            onProgress: suspend (SteamDownloadProgress) -> Unit,
            onCommittedProgress: suspend (
                completedBytes: Long,
                totalBytes: Long,
                dependencyTitle: String,
            ) -> Unit,
        ): DependencyDownloadResult {
            var currentDirectory = sourceDirectory
            var downloadedBytes = 0L
            var totalBytes = 0L
            val visited = linkedSetOf(rootWorkshopId)
            repeat(MAX_PRESET_DEPENDENCY_DEPTH) {
                val dependencyId = readDependencyId(currentDirectory)
                    ?: return DependencyDownloadResult(downloadedBytes, totalBytes)
                check(visited.add(dependencyId)) { "Workshop preset dependency cycle detected: $dependencyId" }
                val target = contentClient.fetchContentTarget(dependencyId, proxyUrl)
                check(target.contentTypeHint.equals("scene", ignoreCase = true)) {
                    "Workshop preset dependency $dependencyId is not a scene wallpaper"
                }
                val dependencyDirectory = resolvePresetDependencyDirectory(currentDirectory, dependencyId)
                val completedBefore = downloadedBytes
                val totalBefore = totalBytes
                val result =
                    contentClient.download(
                        target = target,
                        destinationDirectory = dependencyDirectory,
                        credential = credential,
                        options = options,
                        control = control,
                    ) { progress ->
                        onProgress(progress)
                        if (progress.phase == SteamDownloadPhase.DOWNLOADING) {
                            onCommittedProgress(
                                completedBefore + progress.completedBytes,
                                totalBefore + progress.totalBytes,
                                target.title,
                            )
                        }
                    }
                downloadedBytes += result.downloadedBytes
                totalBytes += result.totalBytes
                currentDirectory = dependencyDirectory
            }
            check(readDependencyId(currentDirectory) == null) {
                "Workshop preset dependency depth exceeds $MAX_PRESET_DEPENDENCY_DEPTH"
            }
            return DependencyDownloadResult(downloadedBytes, totalBytes)
        }

        private fun readDependencyId(directory: File): Long? =
            PresetDependencyResolver.readDependencyId(directory)
    }

internal data class DependencyDownloadResult(
    val downloadedBytes: Long,
    val totalBytes: Long,
)
