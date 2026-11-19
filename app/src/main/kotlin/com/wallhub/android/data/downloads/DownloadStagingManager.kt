package com.wallhub.android.data.downloads

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Owns every app-private directory decision and destructive staging operation for formal downloads. */
@Singleton
internal class DownloadStagingManager private constructor(
    val persistentRoot: File,
    val legacyRoot: File,
    private val storageRoot: File,
    private val deleteDirectory: (File) -> Boolean,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        persistentRoot = File(context.filesDir, WORKSHOP_STAGING_DIRECTORY_NAME).canonicalFile,
        legacyRoot = File(context.cacheDir, WORKSHOP_STAGING_DIRECTORY_NAME).canonicalFile,
        storageRoot = context.filesDir.canonicalFile,
        deleteDirectory = File::deleteRecursively,
    )

    internal constructor(
        persistentRoot: File,
        legacyRoot: File,
        storageRoot: File,
        deleteDirectory: (File) -> Boolean,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit,
    ) : this(persistentRoot.canonicalFile, legacyRoot.canonicalFile, storageRoot.canonicalFile, deleteDirectory)

    fun isManaged(directory: File): Boolean =
        isManagedWorkshopStagingDirectory(
            persistentRoot = persistentRoot,
            legacyRoot = legacyRoot,
            directory = directory,
        )

    fun sdkDirectory(taskId: String): File? = sdkStagingDirectory(storageRoot, taskId)

    fun discardTask(
        taskId: String,
        stagingDirectory: File?,
    ): Boolean {
        val destinationRemoved =
            stagingDirectory
                ?.takeIf(::isManaged)
                ?.let { directory -> !directory.exists() || deleteDirectory(directory) }
                ?: true
        val sdkRemoved = sdkDirectory(taskId)?.let { directory -> !directory.exists() || deleteDirectory(directory) } ?: true
        return destinationRemoved && sdkRemoved
    }
}
