package com.wallhub.android.core.database

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "formal_task_records",
    indices = [
        Index(value = ["workshopId"]),
        Index(value = ["updatedAt"]),
        Index(value = ["queuePosition"]),
    ],
)
data class FormalTaskRecordEntity(
    @PrimaryKey val taskId: String,
    val workshopId: Long,
    val title: String,
    val type: String,
    val status: String,
    val previewUrl: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val accountName: String? = null,
    val credentialMode: String = "LEGACY_UNKNOWN",
    val outputLabel: String? = null,
    val message: String? = null,
    val isResumable: Boolean = true,
    val contentManifestId: Long = 0L,
    val appId: Int = 0,
    val stagingDirectory: String? = null,
    @ColumnInfo(defaultValue = "0") val stagingRetainedBytes: Long = 0L,
    @ColumnInfo(defaultValue = "0") val stagingLastAccessedAt: Long = 0L,
    @ColumnInfo(defaultValue = "'NONE'") val stagingCacheState: String = "NONE",
    val outputTreeUri: String? = null,
    val outputUri: String? = null,
    val exportFormat: String = "AUTO",
    @ColumnInfo(defaultValue = "'balanced'") val conversionPreset: String = "balanced",
    @ColumnInfo(defaultValue = "0") val preserveAudio: Boolean = false,
    val conversionPlanJson: String? = null,
    /** Report from the most recent conversion attempt, whether it succeeded or failed. */
    val conversionReportJson: String? = null,
    /** Current successful report paired with the published output. */
    val successfulConversionReportJson: String? = null,
    @ColumnInfo(defaultValue = "1") val conversionPlanVersion: Int = 1,
    @ColumnInfo(defaultValue = "0") val conversionAttempt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val lastSuccessfulAttempt: Long = 0L,
    /** Small Room-owned pointer to the app-private per-resource checkpoint set. */
    val conversionCheckpointReference: String? = null,
    @ColumnInfo(defaultValue = "'NONE'") val exportTransactionState: String = "NONE",
    val exportTargetUri: String? = null,
    val exportBackupUri: String? = null,
    val exportOutputName: String? = null,
    val exportMimeType: String? = null,
    val queuePosition: Long = 0L,
    val createdAt: Long,
    val updatedAt: Long,
    val attempt: Long = 0L,
    val desiredIntent: String = "RUN",
    val desiredIntentRevision: Long = 0L,
    val appliedIntentRevision: Long = 0L,
    val quiescedIntentRevision: Long = 0L,
    val lastReceiptSequence: Long = 0L,
    val engineEpoch: Long? = null,
)

@Entity(
    tableName = "download_commands",
    indices = [
        Index(value = ["taskId"]),
        Index(value = ["taskId", "revision"], unique = true),
        Index(value = ["claimedEpoch", "acknowledgedAt"]),
    ],
)
data class DownloadCommandEntity(
    @PrimaryKey val commandId: String,
    val taskId: String,
    val revision: Long,
    val attempt: Long,
    val command: String,
    val createdAt: Long,
    val claimedEpoch: Long? = null,
    val claimedAt: Long? = null,
    val receiptSequence: Long? = null,
    val result: String? = null,
    val acknowledgedAt: Long? = null,
)

@Entity(tableName = "download_engine_state")
data class DownloadEngineStateEntity(
    @PrimaryKey val singletonId: Int = 1,
    val epoch: Long,
    val ownerId: String,
    val acquiredAt: Long,
)

@Entity(tableName = "local_wallpaper_states")
data class LocalWallpaperStateEntity(
    @PrimaryKey val resourceId: String,
    val isFavorite: Boolean = false,
    val importRequestedAt: Long? = null,
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "local_wallpaper_tags",
    primaryKeys = ["resourceId", "tag"],
    indices = [Index(value = ["tag"])],
)
data class LocalWallpaperTagEntity(
    val resourceId: String,
    val tag: String,
)

@Dao
interface FormalTaskRecordDao {
    @Query(
        "SELECT * FROM formal_task_records ORDER BY " +
            "CASE WHEN status IN ('QUEUED', 'RESOLVING', 'DOWNLOADING', 'PAUSED') THEN 0 ELSE 1 END, " +
            "CASE WHEN status IN ('QUEUED', 'RESOLVING', 'DOWNLOADING', 'PAUSED') " +
            "THEN queuePosition ELSE 0 END ASC, updatedAt DESC",
    )
    fun observeAll(): Flow<List<FormalTaskRecordEntity>>

    @Query("SELECT * FROM formal_task_records WHERE taskId = :taskId LIMIT 1")
    suspend fun find(taskId: String): FormalTaskRecordEntity?

    @Query(
        "SELECT * FROM formal_task_records WHERE status IN ('QUEUED') " +
            "ORDER BY queuePosition ASC LIMIT 1",
    )
    suspend fun findOldestQueued(): FormalTaskRecordEntity?

    @Query("SELECT * FROM formal_task_records")
    suspend fun listAll(): List<FormalTaskRecordEntity>

    @Query(
        "SELECT * FROM formal_task_records WHERE workshopId = :workshopId " +
            "AND status IN ('QUEUED', 'RESOLVING', 'DOWNLOADING', 'PAUSED', 'CONVERTING', 'EXPORTING') " +
            "ORDER BY updatedAt DESC LIMIT 1",
    )
    suspend fun findActiveForWorkshop(workshopId: Long): FormalTaskRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: FormalTaskRecordEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCommand(command: DownloadCommandEntity)

    /** Persists the observable task and its execution intent as one durable acceptance transaction. */
    @Transaction
    suspend fun upsertWithCommand(
        task: FormalTaskRecordEntity,
        command: DownloadCommandEntity,
    ) {
        upsert(task)
        insertCommand(command)
    }

    @Transaction
    suspend fun upsertPreservingExecutionIntent(
        task: FormalTaskRecordEntity,
        preserveExportTransaction: Boolean = true,
    ): FormalTaskRecordEntity {
        val current = find(task.taskId)
        if (current == null) {
            upsert(task)
            return task
        }
        val merged =
            task.copy(
                attempt = current.attempt,
                desiredIntent = current.desiredIntent,
                desiredIntentRevision = current.desiredIntentRevision,
                appliedIntentRevision = current.appliedIntentRevision,
                quiescedIntentRevision = current.quiescedIntentRevision,
                lastReceiptSequence = current.lastReceiptSequence,
                engineEpoch = current.engineEpoch,
                conversionPreset = current.conversionPreset,
                preserveAudio = current.preserveAudio,
                conversionPlanJson = current.conversionPlanJson,
                conversionReportJson = current.conversionReportJson,
                successfulConversionReportJson = current.successfulConversionReportJson,
                conversionPlanVersion = current.conversionPlanVersion,
                conversionAttempt = current.conversionAttempt,
                lastSuccessfulAttempt = current.lastSuccessfulAttempt,
                conversionCheckpointReference = current.conversionCheckpointReference,
                stagingRetainedBytes = current.stagingRetainedBytes,
                stagingLastAccessedAt = current.stagingLastAccessedAt,
                stagingCacheState = current.stagingCacheState,
                exportTransactionState = if (preserveExportTransaction) current.exportTransactionState else task.exportTransactionState,
                exportTargetUri = if (preserveExportTransaction) current.exportTargetUri else task.exportTargetUri,
                exportBackupUri = if (preserveExportTransaction) current.exportBackupUri else task.exportBackupUri,
                exportOutputName = if (preserveExportTransaction) current.exportOutputName else task.exportOutputName,
                exportMimeType = if (preserveExportTransaction) current.exportMimeType else task.exportMimeType,
            )
        upsert(merged)
        return merged
    }

    @Query(
        "UPDATE formal_task_records SET status = :status, message = :message, bytesPerSecond = 0, " +
            "updatedAt = :updatedAt WHERE taskId = :taskId AND desiredIntent = :expectedIntent",
    )
    suspend fun settleDesiredIntentProjection(
        taskId: String,
        expectedIntent: String,
        status: String,
        message: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE formal_task_records SET status = 'CONVERTING', downloadedBytes = :downloadedBytes, " +
            "totalBytes = :totalBytes, bytesPerSecond = 0, conversionAttempt = conversionAttempt + 1, " +
            "conversionPlanJson = NULL, conversionReportJson = NULL, conversionPlanVersion = 2, " +
            "conversionCheckpointReference = '{\"attempt\":' || (conversionAttempt + 1) || '}', message = :message, " +
            "updatedAt = :updatedAt WHERE taskId = :taskId AND desiredIntent = 'RUN' " +
            "AND status IN ('QUEUED', 'RESOLVING', 'DOWNLOADING')",
    )
    suspend fun transitionToConverting(
        taskId: String,
        downloadedBytes: Long,
        totalBytes: Long,
        message: String,
        updatedAt: Long,
    ): Int

    @Transaction
    suspend fun appendIntentCommand(
        taskId: String,
        desiredIntent: String,
        message: String,
        updatedAt: Long,
        allowedStatuses: List<String>,
        commandId: String,
    ): Boolean {
        val current = find(taskId) ?: return false
        if (current.status !in allowedStatuses) return false
        if (current.desiredIntent == desiredIntent) return false
        val revision = current.desiredIntentRevision + 1L
        upsert(
            current.copy(
                desiredIntent = desiredIntent,
                desiredIntentRevision = revision,
                message = message,
                updatedAt = updatedAt,
            ),
        )
        insertCommand(
            DownloadCommandEntity(
                commandId = commandId,
                taskId = taskId,
                revision = revision,
                attempt = current.attempt,
                command = desiredIntent,
                createdAt = updatedAt,
            ),
        )
        return true
    }

    /** Persists conversion cancellation without creating a download-host command. */
    @Transaction
    suspend fun requestConversionCancellation(
        taskId: String,
        message: String,
        updatedAt: Long,
    ): Boolean {
        val current = find(taskId) ?: return false
        if (current.status != "CONVERTING" || current.desiredIntent == "CANCEL") return false
        upsert(
            current.copy(
                desiredIntent = "CANCEL",
                desiredIntentRevision = current.desiredIntentRevision + 1L,
                message = message,
                updatedAt = updatedAt,
            ),
        )
        return true
    }

    @Query(
        "UPDATE formal_task_records SET desiredIntent = 'CANCEL', " +
            "desiredIntentRevision = CASE WHEN desiredIntentRevision < 1 THEN 1 ELSE desiredIntentRevision END, " +
            "appliedIntentRevision = CASE WHEN desiredIntentRevision < 1 THEN 1 ELSE desiredIntentRevision END, " +
            "quiescedIntentRevision = CASE WHEN desiredIntentRevision < 1 THEN 1 ELSE desiredIntentRevision END " +
            "WHERE taskId = :taskId AND status IN ('CANCELLED', 'COMPLETED') AND desiredIntent = 'CANCEL'",
    )
    suspend fun settleConversionCancellation(taskId: String): Int

    @Query("SELECT COALESCE(MAX(queuePosition), 0) + 1 FROM formal_task_records")
    suspend fun nextQueuePosition(): Long

    @Query("UPDATE formal_task_records SET queuePosition = :position WHERE taskId = :taskId")
    suspend fun updateQueuePosition(
        taskId: String,
        position: Long,
    )

    @Query("SELECT * FROM formal_task_records WHERE stagingCacheState = 'RETAINED' ORDER BY stagingLastAccessedAt ASC, taskId ASC")
    suspend fun listRetainedStaging(): List<FormalTaskRecordEntity>

    @Query(
        "UPDATE formal_task_records SET stagingRetainedBytes = :retainedBytes, " +
            "stagingLastAccessedAt = :accessedAt, stagingCacheState = 'RETAINED' WHERE taskId = :taskId",
    )
    suspend fun touchRetainedStaging(taskId: String, retainedBytes: Long, accessedAt: Long): Int

    @Query(
        "UPDATE formal_task_records SET stagingDirectory = NULL, stagingRetainedBytes = 0, " +
            "stagingLastAccessedAt = 0, stagingCacheState = 'NONE', conversionPlanJson = NULL " +
            "WHERE taskId = :taskId",
    )
    suspend fun clearStagingCache(taskId: String): Int

    @Query("UPDATE formal_task_records SET stagingDirectory = NULL WHERE taskId = :taskId")
    suspend fun clearStagingDirectory(taskId: String)

    @Query(
        "UPDATE formal_task_records SET conversionPlanJson = :planJson, " +
            "conversionPlanVersion = :planVersion WHERE taskId = :taskId",
    )
    suspend fun persistConversionPlan(
        taskId: String,
        planJson: String?,
        planVersion: Int,
    ): Int

    @Query(
        "UPDATE formal_task_records SET conversionReportJson = :reportJson " +
            "WHERE taskId = :taskId AND conversionAttempt = :expectedAttempt",
    )
    suspend fun persistConversionReport(
        taskId: String,
        expectedAttempt: Long,
        reportJson: String?,
    ): Int

    /**
     * Starts one conversion attempt without invalidating the last published output. The expected attempt is the
     * CAS token that prevents two UI taps or retry paths from accepting the same conversion intent.
     */
    @Query(
        "UPDATE formal_task_records SET status = 'CONVERTING', conversionPreset = :preset, " +
            "preserveAudio = :preserveAudio, conversionAttempt = conversionAttempt + 1, " +
            "conversionPlanJson = NULL, conversionReportJson = NULL, conversionPlanVersion = 2, " +
            "conversionCheckpointReference = '{\"attempt\":' || (conversionAttempt + 1) || '}', " +
            "exportTransactionState = 'NONE', exportTargetUri = NULL, exportBackupUri = NULL, " +
            "exportOutputName = NULL, exportMimeType = NULL, desiredIntent = 'RUN', message = :message, " +
            "updatedAt = :updatedAt WHERE taskId = :taskId AND conversionAttempt = :expectedAttempt " +
            "AND status IN (:allowedStatuses)",
    )
    suspend fun beginReconversion(
        taskId: String,
        expectedAttempt: Long,
        preset: String,
        preserveAudio: Boolean,
        message: String,
        updatedAt: Long,
        allowedStatuses: List<String>,
    ): Int

    /** Publishes output and its report together only after the durable export transaction committed. */
    @Query(
        "UPDATE formal_task_records SET status = 'COMPLETED', outputUri = :outputUri, " +
            "outputLabel = :outputLabel, successfulConversionReportJson = conversionReportJson, " +
            "lastSuccessfulAttempt = conversionAttempt, stagingDirectory = :stagingDirectory, message = :message, " +
            "updatedAt = :updatedAt WHERE taskId = :taskId AND conversionAttempt = :expectedAttempt " +
            "AND status = 'EXPORTING' AND exportTransactionState = 'COMMITTED'",
    )
    suspend fun commitSuccessfulConversion(
        taskId: String,
        expectedAttempt: Long,
        outputUri: String,
        outputLabel: String,
        stagingDirectory: String,
        message: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE formal_task_records SET conversionCheckpointReference = NULL WHERE taskId = :taskId " +
            "AND conversionAttempt = :expectedAttempt",
    )
    suspend fun clearConversionCheckpointReference(taskId: String, expectedAttempt: Long): Int

    @Transaction
    suspend fun updateQueueOrder(taskIds: List<String>) {
        taskIds.forEachIndexed { index, taskId ->
            updateQueuePosition(taskId, index.toLong())
        }
    }

    @Query("DELETE FROM formal_task_records WHERE taskId = :taskId")
    suspend fun delete(taskId: String): Int

    @Query(
        "DELETE FROM formal_task_records WHERE taskId = :taskId " +
            "AND status IN ('COMPLETED', 'FAILED', 'CANCELLED')",
    )
    suspend fun deleteTerminal(taskId: String): Int

    @Query("DELETE FROM download_commands WHERE taskId = :taskId")
    suspend fun deleteCommands(taskId: String): Int

    /** Removes one terminal job and every receipt/pending command atomically, so no orphan can poison reconcile. */
    @Transaction
    suspend fun deleteTerminalWithCommands(taskId: String): Int {
        val current = find(taskId) ?: return 0
        if (current.status !in setOf("COMPLETED", "FAILED", "CANCELLED")) return 0
        deleteCommands(taskId)
        return deleteTerminal(taskId)
    }

    @Query(
        "DELETE FROM formal_task_records " +
            "WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')",
    )
    suspend fun clearFinishedHistory(): Int

    @Query("DELETE FROM formal_task_records WHERE status = 'COMPLETED'")
    suspend fun clearCompletedHistory(): Int
}

@Dao
interface DownloadCommandDao {
    @Query(
        "SELECT taskId FROM download_commands WHERE acknowledgedAt IS NULL " +
            "GROUP BY taskId ORDER BY MIN(createdAt) ASC, MIN(revision) ASC LIMIT :limit",
    )
    suspend fun pendingTaskIds(limit: Int): List<String>

    @Query(
        "SELECT taskId FROM download_commands WHERE acknowledgedAt IS NULL AND taskId NOT IN (:excludedTaskIds) " +
            "GROUP BY taskId ORDER BY MIN(createdAt) ASC, MIN(revision) ASC LIMIT :limit",
    )
    suspend fun pendingTaskIdsExcluding(
        excludedTaskIds: Set<String>,
        limit: Int,
    ): List<String>

    @Query(
        "SELECT * FROM download_commands WHERE taskId = :taskId AND acknowledgedAt IS NULL " +
            "ORDER BY revision ASC LIMIT :limit",
    )
    suspend fun pendingForTask(
        taskId: String,
        limit: Int,
    ): List<DownloadCommandEntity>

    @Query(
        "SELECT * FROM download_commands WHERE taskId = :taskId AND acknowledgedAt IS NULL " +
            "ORDER BY revision DESC LIMIT 1",
    )
    suspend fun latestPendingForTask(taskId: String): DownloadCommandEntity?

    @Query(
        "UPDATE download_commands SET claimedEpoch = :epoch, claimedAt = :claimedAt " +
            "WHERE commandId = :commandId AND acknowledgedAt IS NULL " +
            "AND (claimedEpoch IS NULL OR claimedEpoch <= :epoch) " +
            "AND EXISTS (SELECT 1 FROM download_engine_state " +
            "WHERE singletonId = 1 AND epoch = :epoch)",
    )
    suspend fun claim(
        commandId: String,
        epoch: Long,
        claimedAt: Long,
    ): Int

    @Query(
        "UPDATE download_commands SET receiptSequence = :receiptSequence, result = :result, " +
            "acknowledgedAt = :acknowledgedAt WHERE commandId = :commandId " +
            "AND claimedEpoch = :epoch AND acknowledgedAt IS NULL " +
            "AND EXISTS (SELECT 1 FROM download_engine_state " +
            "WHERE singletonId = 1 AND epoch = :epoch)",
    )
    suspend fun acknowledge(
        commandId: String,
        epoch: Long,
        receiptSequence: Long,
        result: String,
        acknowledgedAt: Long,
    ): Int

    @Query("SELECT * FROM download_commands WHERE taskId = :taskId ORDER BY revision ASC")
    suspend fun forTask(taskId: String): List<DownloadCommandEntity>

    @Query(
        "SELECT CASE WHEN engineEpoch = :epoch THEN lastReceiptSequence ELSE 0 END " +
            "FROM formal_task_records WHERE taskId = :taskId LIMIT 1",
    )
    suspend fun receiptSequenceBase(
        taskId: String,
        epoch: Long,
    ): Long?

    @Query(
        "UPDATE formal_task_records SET " +
            "appliedIntentRevision = CASE WHEN appliedIntentRevision < :intentRevision THEN :intentRevision ELSE appliedIntentRevision END, " +
            "quiescedIntentRevision = CASE WHEN :quiesced = 1 AND quiescedIntentRevision < :intentRevision " +
            "THEN :intentRevision ELSE quiescedIntentRevision END, " +
            "lastReceiptSequence = :receiptSequence, engineEpoch = :engineEpoch, updatedAt = :acknowledgedAt " +
            "WHERE taskId = :taskId AND desiredIntentRevision >= :intentRevision " +
            "AND ((engineEpoch IS NULL AND :receiptSequence = 1) " +
            "OR (engineEpoch < :engineEpoch AND :receiptSequence = 1) " +
            "OR (engineEpoch = :engineEpoch AND lastReceiptSequence + 1 = :receiptSequence))",
    )
    suspend fun applyReceiptProjection(
        taskId: String,
        intentRevision: Long,
        receiptSequence: Long,
        engineEpoch: Long,
        quiesced: Boolean,
        acknowledgedAt: Long,
    ): Int

    @Transaction
    suspend fun acknowledgeAndProject(
        command: DownloadCommandEntity,
        epoch: Long,
        receiptSequence: Long,
        result: String,
        acknowledgedAt: Long,
        quiesced: Boolean,
    ): Boolean {
        if (
            acknowledge(
                commandId = command.commandId,
                epoch = epoch,
                receiptSequence = receiptSequence,
                result = result,
                acknowledgedAt = acknowledgedAt,
            ) != 1
        ) return false
        check(
            applyReceiptProjection(
                taskId = command.taskId,
                intentRevision = command.revision,
                receiptSequence = receiptSequence,
                engineEpoch = epoch,
                quiesced = quiesced,
                acknowledgedAt = acknowledgedAt,
            ) == 1,
        ) { "Receipt projection rejected an acknowledged command" }
        return true
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeEngineState(state: DownloadEngineStateEntity)

    @Query("SELECT * FROM download_engine_state WHERE singletonId = 1 LIMIT 1")
    suspend fun engineState(): DownloadEngineStateEntity?

    @Transaction
    suspend fun acquireEngineEpoch(
        ownerId: String,
        now: Long,
    ): DownloadEngineStateEntity {
        val next = (engineState()?.epoch ?: 0L) + 1L
        return DownloadEngineStateEntity(epoch = next, ownerId = ownerId, acquiredAt = now).also {
            writeEngineState(it)
        }
    }
}

@Dao
interface LocalWallpaperMetadataDao {
    @Query("SELECT * FROM local_wallpaper_states")
    suspend fun listStates(): List<LocalWallpaperStateEntity>

    @Query("SELECT * FROM local_wallpaper_tags")
    suspend fun listTags(): List<LocalWallpaperTagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: LocalWallpaperStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<LocalWallpaperTagEntity>)

    @Query("DELETE FROM local_wallpaper_tags WHERE resourceId = :resourceId")
    suspend fun deleteTagsForResource(resourceId: String)

    @Query("SELECT resourceId FROM local_wallpaper_tags WHERE tag = :tag")
    suspend fun resourceIdsForTag(tag: String): List<String>

    @Query("DELETE FROM local_wallpaper_tags WHERE tag = :tag")
    suspend fun deleteTag(tag: String)

    @Query("DELETE FROM local_wallpaper_states WHERE resourceId = :resourceId")
    suspend fun deleteState(resourceId: String)

    @Transaction
    suspend fun replaceTags(
        resourceId: String,
        tags: Set<String>,
    ) {
        deleteTagsForResource(resourceId)
        insertTags(tags.map { tag -> LocalWallpaperTagEntity(resourceId, tag) })
    }

    @Transaction
    suspend fun renameTag(
        oldTag: String,
        newTag: String,
    ) {
        val resourceIds = resourceIdsForTag(oldTag)
        insertTags(resourceIds.map { resourceId -> LocalWallpaperTagEntity(resourceId, newTag) })
        deleteTag(oldTag)
    }

    @Transaction
    suspend fun deleteMetadata(resourceId: String) {
        deleteTagsForResource(resourceId)
        deleteState(resourceId)
    }
}

@Database(
    entities = [
        FormalTaskRecordEntity::class,
        DownloadCommandEntity::class,
        DownloadEngineStateEntity::class,
        LocalWallpaperStateEntity::class,
        LocalWallpaperTagEntity::class,
    ],
    version = 14,
    exportSchema = true,
)
abstract class FormalTaskDatabase : RoomDatabase() {
    abstract fun taskRecordDao(): FormalTaskRecordDao

    abstract fun downloadCommandDao(): DownloadCommandDao

    abstract fun localWallpaperMetadataDao(): LocalWallpaperMetadataDao

    companion object {
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE formal_task_records " +
                            "ADD COLUMN bytesPerSecond INTEGER NOT NULL DEFAULT 0",
                    )
                    database.execSQL(
                        "ALTER TABLE formal_task_records ADD COLUMN accountName TEXT",
                    )
                    database.execSQL(
                        "ALTER TABLE formal_task_records ADD COLUMN requestedAction TEXT",
                    )
                    database.execSQL(
                        "ALTER TABLE formal_task_records " +
                            "ADD COLUMN isResumable INTEGER NOT NULL DEFAULT 1",
                    )
                }
            }

        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE formal_task_records " +
                            "ADD COLUMN contentManifestId INTEGER NOT NULL DEFAULT 0",
                    )
                    database.execSQL(
                        "ALTER TABLE formal_task_records ADD COLUMN appId INTEGER NOT NULL DEFAULT 0",
                    )
                    database.execSQL(
                        "ALTER TABLE formal_task_records ADD COLUMN stagingDirectory TEXT",
                    )
                    database.execSQL(
                        "ALTER TABLE formal_task_records ADD COLUMN outputTreeUri TEXT",
                    )
                }
            }

        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE formal_task_records ADD COLUMN outputUri TEXT",
                    )
                    database.execSQL(
                        "ALTER TABLE formal_task_records " +
                            "ADD COLUMN exportFormat TEXT NOT NULL DEFAULT 'AUTO'",
                    )
                }
            }

        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE formal_task_records ADD COLUMN previewUrl TEXT",
                    )
                    database.execSQL(
                        "ALTER TABLE formal_task_records " +
                            "ADD COLUMN queuePosition INTEGER NOT NULL DEFAULT 0",
                    )
                    database.execSQL(
                        "UPDATE formal_task_records SET queuePosition = createdAt",
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_formal_task_records_queuePosition " +
                            "ON formal_task_records(queuePosition)",
                    )
                }
            }

        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS local_wallpaper_states (" +
                            "resourceId TEXT NOT NULL, isFavorite INTEGER NOT NULL, " +
                            "importRequestedAt INTEGER, updatedAt INTEGER NOT NULL, " +
                            "PRIMARY KEY(resourceId))",
                    )
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS local_wallpaper_tags (" +
                            "resourceId TEXT NOT NULL, tag TEXT NOT NULL, " +
                            "PRIMARY KEY(resourceId, tag))",
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_local_wallpaper_tags_tag " +
                            "ON local_wallpaper_tags(tag)",
                    )
                }
            }

        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE formal_task_records " +
                            "ADD COLUMN credentialMode TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'",
                    )
                    database.execSQL(
                        "UPDATE formal_task_records SET credentialMode = " +
                            "CASE WHEN accountName IS NULL OR accountName = '' " +
                            "THEN 'LEGACY_UNKNOWN' ELSE 'ACCOUNT' END",
                    )
                }
            }

        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE formal_task_records ADD COLUMN executorVersion TEXT NOT NULL DEFAULT 'LEGACY'")
                    database.execSQL("ALTER TABLE formal_task_records ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE formal_task_records ADD COLUMN desiredIntent TEXT NOT NULL DEFAULT 'RUN'")
                    database.execSQL("ALTER TABLE formal_task_records ADD COLUMN desiredIntentRevision INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE formal_task_records ADD COLUMN appliedIntentRevision INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE formal_task_records ADD COLUMN quiescedIntentRevision INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE formal_task_records ADD COLUMN lastReceiptSequence INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE formal_task_records ADD COLUMN engineEpoch INTEGER")
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS download_commands (" +
                            "commandId TEXT NOT NULL, taskId TEXT NOT NULL, revision INTEGER NOT NULL, " +
                            "attempt INTEGER NOT NULL, command TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                            "claimedEpoch INTEGER, claimedAt INTEGER, receiptSequence INTEGER, " +
                            "result TEXT, acknowledgedAt INTEGER, PRIMARY KEY(commandId))",
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_download_commands_taskId ON download_commands(taskId)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_download_commands_taskId_revision ON download_commands(taskId, revision)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_download_commands_claimedEpoch_acknowledgedAt ON download_commands(claimedEpoch, acknowledgedAt)")
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS download_engine_state (" +
                            "singletonId INTEGER NOT NULL, epoch INTEGER NOT NULL, ownerId TEXT NOT NULL, " +
                            "acquiredAt INTEGER NOT NULL, PRIMARY KEY(singletonId))",
                    )
                }
            }

        internal val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    val migratedIntent =
                        "CASE " +
                            "WHEN requestedAction = 'CANCEL' THEN 'CANCEL' " +
                            "WHEN requestedAction = 'PAUSE' OR status = 'PAUSED' THEN 'PAUSE' " +
                            "ELSE desiredIntent END"
                    database.execSQL(
                        "UPDATE formal_task_records SET desiredIntent = $migratedIntent, " +
                            "desiredIntentRevision = CASE WHEN desiredIntentRevision < 1 " +
                            "THEN 1 ELSE desiredIntentRevision END WHERE executorVersion = 'LEGACY'",
                    )
                    database.execSQL(
                        "UPDATE formal_task_records SET appliedIntentRevision = desiredIntentRevision, " +
                            "quiescedIntentRevision = desiredIntentRevision " +
                            "WHERE executorVersion = 'LEGACY' " +
                            "AND status IN ('COMPLETED', 'FAILED', 'CANCELLED')",
                    )
                    database.execSQL(
                        "UPDATE formal_task_records SET desiredIntent = requestedAction, " +
                            "desiredIntentRevision = desiredIntentRevision + 1 " +
                            "WHERE requestedAction IN ('PAUSE', 'CANCEL') " +
                            "AND desiredIntent != requestedAction",
                    )
                    database.execSQL(
                        "UPDATE formal_task_records SET desiredIntentRevision = 1 " +
                            "WHERE requestedAction IN ('PAUSE', 'CANCEL') AND desiredIntentRevision < 1",
                    )
                    database.execSQL(
                        "INSERT OR IGNORE INTO download_commands " +
                            "(commandId, taskId, revision, attempt, command, createdAt) " +
                            "SELECT 'room-v9-' || taskId, taskId, desiredIntentRevision, attempt, " +
                            "desiredIntent, updatedAt FROM formal_task_records " +
                            "WHERE (executorVersion = 'LEGACY' OR requestedAction IS NOT NULL) " +
                            "AND status IN ('QUEUED', 'RESOLVING', 'DOWNLOADING', 'PAUSED')",
                    )
                    database.execSQL(
                        "CREATE TABLE formal_task_records_new (" +
                            "taskId TEXT NOT NULL, workshopId INTEGER NOT NULL, title TEXT NOT NULL, " +
                            "type TEXT NOT NULL, status TEXT NOT NULL, previewUrl TEXT, " +
                            "downloadedBytes INTEGER NOT NULL, totalBytes INTEGER NOT NULL, " +
                            "bytesPerSecond INTEGER NOT NULL, accountName TEXT, credentialMode TEXT NOT NULL, " +
                            "outputLabel TEXT, message TEXT, isResumable INTEGER NOT NULL, " +
                            "contentManifestId INTEGER NOT NULL, appId INTEGER NOT NULL, stagingDirectory TEXT, " +
                            "outputTreeUri TEXT, outputUri TEXT, exportFormat TEXT NOT NULL, " +
                            "queuePosition INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                            "attempt INTEGER NOT NULL, desiredIntent TEXT NOT NULL, " +
                            "desiredIntentRevision INTEGER NOT NULL, appliedIntentRevision INTEGER NOT NULL, " +
                            "quiescedIntentRevision INTEGER NOT NULL, lastReceiptSequence INTEGER NOT NULL, " +
                            "engineEpoch INTEGER, PRIMARY KEY(taskId))",
                    )
                    database.execSQL(
                        "INSERT INTO formal_task_records_new (" +
                            "taskId, workshopId, title, type, status, previewUrl, downloadedBytes, totalBytes, " +
                            "bytesPerSecond, accountName, credentialMode, outputLabel, message, isResumable, " +
                            "contentManifestId, appId, stagingDirectory, outputTreeUri, outputUri, exportFormat, " +
                            "queuePosition, createdAt, updatedAt, attempt, desiredIntent, desiredIntentRevision, " +
                            "appliedIntentRevision, quiescedIntentRevision, lastReceiptSequence, engineEpoch) " +
                            "SELECT taskId, workshopId, title, type, status, previewUrl, downloadedBytes, " +
                            "totalBytes, bytesPerSecond, accountName, credentialMode, outputLabel, message, " +
                            "isResumable, contentManifestId, appId, stagingDirectory, outputTreeUri, outputUri, " +
                            "exportFormat, queuePosition, createdAt, updatedAt, attempt, desiredIntent, " +
                            "desiredIntentRevision, appliedIntentRevision, quiescedIntentRevision, " +
                            "lastReceiptSequence, engineEpoch FROM formal_task_records",
                    )
                    database.execSQL("DROP TABLE formal_task_records")
                    database.execSQL("ALTER TABLE formal_task_records_new RENAME TO formal_task_records")
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_formal_task_records_workshopId " +
                            "ON formal_task_records(workshopId)",
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_formal_task_records_updatedAt " +
                            "ON formal_task_records(updatedAt)",
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_formal_task_records_queuePosition " +
                            "ON formal_task_records(queuePosition)",
                    )
                }
            }

        internal val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE formal_task_records " +
                            "ADD COLUMN exportTransactionState TEXT NOT NULL DEFAULT 'NONE'",
                    )
                    database.execSQL("ALTER TABLE formal_task_records ADD COLUMN exportTargetUri TEXT")
                    database.execSQL("ALTER TABLE formal_task_records ADD COLUMN exportBackupUri TEXT")
                    database.execSQL("ALTER TABLE formal_task_records ADD COLUMN exportOutputName TEXT")
                    database.execSQL("ALTER TABLE formal_task_records ADD COLUMN exportMimeType TEXT")
                }
            }

        internal val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE formal_task_records " +
                            "ADD COLUMN conversionPreset TEXT NOT NULL DEFAULT 'balanced'",
                    )
                    database.execSQL("ALTER TABLE formal_task_records ADD COLUMN conversionPlanJson TEXT")
                    database.execSQL("ALTER TABLE formal_task_records ADD COLUMN conversionReportJson TEXT")
                    database.execSQL(
                        "ALTER TABLE formal_task_records " +
                            "ADD COLUMN conversionPlanVersion INTEGER NOT NULL DEFAULT 1",
                    )
                }
            }

        internal val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE formal_task_records " +
                            "ADD COLUMN preserveAudio INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }

        internal val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE formal_task_records ADD COLUMN stagingRetainedBytes INTEGER NOT NULL DEFAULT 0",
                    )
                    database.execSQL(
                        "ALTER TABLE formal_task_records ADD COLUMN stagingLastAccessedAt INTEGER NOT NULL DEFAULT 0",
                    )
                    database.execSQL(
                        "ALTER TABLE formal_task_records ADD COLUMN stagingCacheState TEXT NOT NULL DEFAULT 'NONE'",
                    )
                }
            }

        internal val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE formal_task_records " +
                            "ADD COLUMN successfulConversionReportJson TEXT",
                    )
                    database.execSQL(
                        "ALTER TABLE formal_task_records " +
                            "ADD COLUMN conversionAttempt INTEGER NOT NULL DEFAULT 0",
                    )
                    database.execSQL(
                        "ALTER TABLE formal_task_records " +
                            "ADD COLUMN lastSuccessfulAttempt INTEGER NOT NULL DEFAULT 0",
                    )
                    database.execSQL("ALTER TABLE formal_task_records ADD COLUMN conversionCheckpointReference TEXT")
                    database.execSQL(
                        "UPDATE formal_task_records SET " +
                            "successfulConversionReportJson = CASE WHEN status = 'COMPLETED' THEN conversionReportJson ELSE NULL END, " +
                            "conversionAttempt = CASE WHEN conversionReportJson IS NULL THEN 0 ELSE 1 END, " +
                            "lastSuccessfulAttempt = CASE WHEN status = 'COMPLETED' AND conversionReportJson IS NOT NULL THEN 1 ELSE 0 END",
                    )
                }
            }

        internal val migrations: Array<Migration> =
            arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
            )

        @Volatile
        private var instance: FormalTaskDatabase? = null

        fun get(context: Context): FormalTaskDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        FormalTaskDatabase::class.java,
                        "wallhub-formal.db",
                    ).apply {
                        migrations.forEach { migration -> addMigrations(migration) }
                    }.build()
                    .also { database -> instance = database }
            }
    }
}
