package com.wallhub.android.data.downloads

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Owns every app-private directory decision and destructive staging operation for formal downloads. */
@Singleton
internal class DownloadStagingManager
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        val persistentRoot: File = File(context.filesDir, WORKSHOP_STAGING_DIRECTORY_NAME).canonicalFile
        val legacyRoot: File = File(context.cacheDir, WORKSHOP_STAGING_DIRECTORY_NAME).canonicalFile
        private val storageRoot: File = context.filesDir.canonicalFile

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
        ) {
            stagingDirectory?.takeIf(::isManaged)?.deleteRecursively()
            sdkDirectory(taskId)?.deleteRecursively()
        }
    }
