package com.wallhub.prototype.mpkg

import com.wallhub.android.data.downloads.readProjectJson
import java.io.File
import org.json.JSONObject

const val PRESET_DEPENDENCY_DIRECTORY_NAME = ".wallhub-dependencies"
const val MAX_PRESET_DEPENDENCY_DEPTH = 4

data class PresetDependencyResolution(
    val sceneRoot: File,
    val presetRoots: List<File>,
)

/** Shared validation and traversal contract for downloaded Workshop preset chains. */
object PresetDependencyResolver {
    fun resolve(root: File): Result<PresetDependencyResolution?> = runCatching { resolveOrThrow(root) }

    fun dependencyDirectory(
        currentDirectory: File,
        dependencyId: Long,
    ): File {
        require(dependencyId > 0L) { "Invalid Workshop preset dependency ID" }
        val dependencyRoot = File(currentDirectory, PRESET_DEPENDENCY_DIRECTORY_NAME).canonicalFile
        val dependencyDirectory = File(dependencyRoot, dependencyId.toString()).canonicalFile
        require(dependencyDirectory.toPath().startsWith(dependencyRoot.toPath())) {
            "Invalid Workshop preset dependency directory"
        }
        return dependencyDirectory
    }

    internal fun readDependencyId(directory: File): Long? {
        val projectFile = File(directory, "project.json")
        if (!projectFile.isFile) return null
        val project = JSONObject(readProjectJson(projectFile))
        if (!project.has("preset")) return null
        check(project.opt("preset") is JSONObject) { "Workshop preset payload is malformed" }
        return project.opt("dependency")
            ?.toString()
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: error("Workshop preset has no valid dependency ID")
    }

    private fun resolveOrThrow(root: File): PresetDependencyResolution? {
        val presetRoots = mutableListOf<File>()
        val visited = mutableSetOf<String>()
        var current = root.canonicalFile

        while (true) {
            val projectFile = File(current, "project.json")
            if (!projectFile.isFile) {
                check(presetRoots.isEmpty()) { "Workshop preset dependency is missing project.json" }
                return null
            }
            val dependencyId = readDependencyId(current)
                ?: return if (presetRoots.isEmpty()) null else PresetDependencyResolution(current, presetRoots)
            check(visited.add(current.canonicalPath)) { "Workshop preset dependency cycle detected" }
            check(presetRoots.size < MAX_PRESET_DEPENDENCY_DEPTH) {
                "Workshop preset dependency depth exceeds $MAX_PRESET_DEPENDENCY_DEPTH"
            }
            presetRoots += current
            val dependency = dependencyDirectory(current, dependencyId)
            require(dependency.isDirectory) { "Workshop preset dependency $dependencyId is missing" }
            current = dependency
        }
    }
}
