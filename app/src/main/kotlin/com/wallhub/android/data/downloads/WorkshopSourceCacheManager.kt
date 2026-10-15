package com.wallhub.android.data.downloads

import android.content.Context
import android.util.Log
import com.wallhub.android.core.database.FormalTaskRecordDao
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.model.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns accounting, reconciliation and destructive operations for retained Workshop source bytes. */
@Singleton
internal class WorkshopSourceCacheManager private constructor(
    private val filesDir: File,
    private val stagingRoot: File,
    private val taskDao: FormalTaskRecordDao,
    private val settingsRepository: SettingsRepository,
    private val isPlaybackActive: () -> Boolean,
    private val now: () -> Long,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        taskDao: FormalTaskRecordDao,
        settingsRepository: SettingsRepository,
        playbackCoordinator: PlaybackDownloadCoordinator,
    ) : this(
        filesDir = context.filesDir.canonicalFile,
        stagingRoot = File(context.filesDir, WORKSHOP_STAGING_DIRECTORY_NAME).canonicalFile,
        taskDao = taskDao,
        settingsRepository = settingsRepository,
        isPlaybackActive = { playbackCoordinator.isPlaybackActive },
        now = System::currentTimeMillis,
    )

    internal constructor(
        filesDir: File,
        taskDao: FormalTaskRecordDao,
        settingsRepository: SettingsRepository,
        isPlaybackActive: () -> Boolean = { false },
        now: () -> Long = System::currentTimeMillis,
    ) : this(
        filesDir = filesDir.canonicalFile,
        stagingRoot = File(filesDir, WORKSHOP_STAGING_DIRECTORY_NAME).canonicalFile,
        taskDao = taskDao,
        settingsRepository = settingsRepository,
        isPlaybackActive = isPlaybackActive,
        now = now,
    )

        private val mutex = Mutex()

        suspend fun retainCompleted(taskId: String) = mutex.withLock {
            val task = taskDao.find(taskId) ?: return@withLock
            val directory = managedDirectory(task.stagingDirectory) ?: return@withLock
            val bytes = recursiveBytes(directory)
            check(taskDao.touchRetainedStaging(taskId, bytes, now()) == 1) {
                "Download task disappeared while retaining Workshop source"
            }
            enforceBudgetLocked()
        }

        suspend fun touch(taskId: String) = mutex.withLock {
            val task = taskDao.find(taskId) ?: return@withLock
            val directory = managedDirectory(task.stagingDirectory) ?: return@withLock
            val bytes = recursiveBytes(directory)
            taskDao.touchRetainedStaging(taskId, bytes, now())
        }

        suspend fun reconcileAndEnforceBudget() = mutex.withLock {
            taskDao.listRetainedStaging().forEach { task ->
                val directory = managedDirectory(task.stagingDirectory)
                if (directory == null || !directory.isDirectory) {
                    clearMissingRecord(task)
                } else {
                    val actualBytes = runCatching { recursiveBytes(directory) }.getOrNull()
                    if (actualBytes == null) {
                        Log.w(TAG, "Unable to account retained Workshop source taskId=${task.taskId}")
                    } else if (actualBytes != task.stagingRetainedBytes) {
                        taskDao.touchRetainedStaging(task.taskId, actualBytes, task.stagingLastAccessedAt)
                    }
                }
            }
            enforceBudgetLocked()
        }

        suspend fun discard(task: FormalTaskRecordEntity): Boolean = mutex.withLock {
            discardLocked(task)
        }

        /** Returns bytes retained under the app-private Workshop staging root. */
        suspend fun retainedBytes(): Long = mutex.withLock {
            taskDao.listRetainedStaging().fold(0L) { total, task ->
                Math.addExact(total, task.stagingRetainedBytes.coerceAtLeast(0L))
            }
        }

        /** Clears retained app-private staging, plans, and checkpoints without touching exported output. */
        suspend fun clearRetained(): Long = mutex.withLock {
            check(!isPlaybackActive()) { "Workshop source cache is in use" }
            var clearedBytes = 0L
            taskDao.listRetainedStaging()
                .filterNot { it.status in PROTECTED_STATUSES }
                .forEach { task ->
                    if (discardLocked(task)) {
                        clearedBytes = Math.addExact(clearedBytes, task.stagingRetainedBytes.coerceAtLeast(0L))
                    }
                }
            clearedBytes
        }

        private suspend fun enforceBudgetLocked() {
            val preferences = settingsRepository.preferences.first()
            val userLimitBytes = Math.multiplyExact(preferences.workshopSourceCacheBudgetGiB.toLong(), GIB)
            val records = taskDao.listRetainedStaging()
            val globallyActive = isPlaybackActive()
            val decision =
                WorkshopSourceCachePolicy.decide(
                    entries =
                        records.map { task ->
                            WorkshopSourceCacheEntry(
                                taskId = task.taskId,
                                retainedBytes = task.stagingRetainedBytes,
                                lastAccessedAt = task.stagingLastAccessedAt,
                                active = globallyActive || task.status in PROTECTED_STATUSES,
                            )
                        },
                    enabled = preferences.workshopSourceCacheEnabled,
                    userLimitBytes = userLimitBytes,
                    usableSpaceBytes = filesDir.usableSpace.coerceAtLeast(0L),
                )
            val recordsById = records.associateBy(FormalTaskRecordEntity::taskId)
            decision.evictTaskIds.forEach { taskId ->
                val task = recordsById[taskId] ?: return@forEach
                if (!discardLocked(task)) {
                    Log.w(TAG, "Retained Workshop source eviction failed taskId=$taskId")
                }
            }
        }

        private suspend fun discardLocked(task: FormalTaskRecordEntity): Boolean {
            val directory = managedDirectory(task.stagingDirectory)
            if (task.stagingDirectory != null && directory == null) return false
            if (directory != null && directory.exists() && !directory.deleteRecursively()) return false
            val sdkDirectory = sdkStagingDirectory(filesDir, task.taskId)
            if (sdkDirectory != null && sdkDirectory.exists() && !sdkDirectory.deleteRecursively()) return false
            if (!ConversionPlanStore(filesDir).delete(task.taskId)) return false
            if (!ConversionCheckpointStore(filesDir).deleteTask(task.taskId)) return false
            return taskDao.clearStagingCache(task.taskId) == 1
        }

        private suspend fun clearMissingRecord(task: FormalTaskRecordEntity) {
            val sdkDirectory = sdkStagingDirectory(filesDir, task.taskId)
            if (sdkDirectory != null && sdkDirectory.exists() && !sdkDirectory.deleteRecursively()) return
            if (!ConversionPlanStore(filesDir).delete(task.taskId)) return
            if (!ConversionCheckpointStore(filesDir).deleteTask(task.taskId)) return
            taskDao.clearStagingCache(task.taskId)
        }

        private fun managedDirectory(path: String?): File? {
            if (path.isNullOrBlank()) return null
            val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
            return candidate.takeIf { it.toPath().startsWith(stagingRoot.toPath()) && it != stagingRoot }
        }

        internal fun recursiveBytes(directory: File): Long {
            val root = requireNotNull(managedDirectory(directory.path)) { "Workshop source is outside the staging root" }
            require(root.isDirectory) { "Workshop source directory is missing" }
            var total = 0L
            root.walkTopDown().forEach { entry ->
                val canonical = entry.canonicalFile
                require(canonical.toPath().startsWith(root.toPath())) { "Workshop source entry escapes the staging directory" }
                if (canonical.isFile) total = Math.addExact(total, canonical.length().coerceAtLeast(0L))
            }
            return total
        }

        private companion object {
            const val TAG = "WorkshopSourceCache"
            const val GIB = 1024L * 1024L * 1024L
            val PROTECTED_STATUSES = setOf("CONVERTING", "EXPORTING")
        }
    }
