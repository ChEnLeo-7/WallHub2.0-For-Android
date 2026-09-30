package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import java.io.File
import javax.inject.Inject
import org.json.JSONObject

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
            onProgress: suspend (completedBytes: Long, totalBytes: Long, dependencyTitle: String) -> Unit,
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
                        if (progress.phase == SteamDownloadPhase.DOWNLOADING) {
                            onProgress(
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

        private fun readDependencyId(directory: File): Long? {
            val projectFile = File(directory, "project.json")
            if (!projectFile.isFile) return null
            val project = JSONObject(readProjectJson(projectFile))
            if (!project.has("preset")) return null
            return project.opt("dependency")
                ?.toString()
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: error("Workshop preset has no valid dependency ID")
        }

        private companion object {
            const val MAX_PRESET_DEPENDENCY_DEPTH = 4
        }
    }

internal data class DependencyDownloadResult(
    val downloadedBytes: Long,
    val totalBytes: Long,
)
