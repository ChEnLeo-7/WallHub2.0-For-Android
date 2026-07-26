package com.wallhub.android.core.database

import android.content.Context
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
    val outputLabel: String? = null,
    val message: String? = null,
    val requestedAction: String? = null,
    val isResumable: Boolean = true,
    val contentManifestId: Long = 0L,
    val appId: Int = 0,
    val stagingDirectory: String? = null,
    val outputTreeUri: String? = null,
    val outputUri: String? = null,
    val exportFormat: String = "AUTO",
    val queuePosition: Long = 0L,
    val createdAt: Long,
    val updatedAt: Long,
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

    @Query("SELECT * FROM formal_task_records")
    suspend fun listAll(): List<FormalTaskRecordEntity>

    @Query(
        "SELECT * FROM formal_task_records WHERE workshopId = :workshopId " +
            "AND status IN ('QUEUED', 'RESOLVING', 'DOWNLOADING', 'PAUSED', 'CONVERTING') " +
            "ORDER BY updatedAt DESC LIMIT 1",
    )
    suspend fun findActiveForWorkshop(workshopId: Long): FormalTaskRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: FormalTaskRecordEntity)

    @Query("SELECT COALESCE(MAX(queuePosition), 0) + 1 FROM formal_task_records")
    suspend fun nextQueuePosition(): Long

    @Query("UPDATE formal_task_records SET queuePosition = :position WHERE taskId = :taskId")
    suspend fun updateQueuePosition(taskId: String, position: Long)

    @Transaction
    suspend fun updateQueueOrder(taskIds: List<String>) {
        taskIds.forEachIndexed { index, taskId ->
            updateQueuePosition(taskId, index.toLong())
        }
    }

    @Query("DELETE FROM formal_task_records WHERE taskId = :taskId")
    suspend fun delete(taskId: String): Int

    @Query(
        "DELETE FROM formal_task_records " +
            "WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')",
    )
    suspend fun clearFinishedHistory(): Int
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
    suspend fun replaceTags(resourceId: String, tags: Set<String>) {
        deleteTagsForResource(resourceId)
        insertTags(tags.map { tag -> LocalWallpaperTagEntity(resourceId, tag) })
    }

    @Transaction
    suspend fun renameTag(oldTag: String, newTag: String) {
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
        LocalWallpaperStateEntity::class,
        LocalWallpaperTagEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class FormalTaskDatabase : RoomDatabase() {
    abstract fun taskRecordDao(): FormalTaskRecordDao

    abstract fun localWallpaperMetadataDao(): LocalWallpaperMetadataDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
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

        private val MIGRATION_5_6 = object : Migration(5, 6) {
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

        @Volatile
        private var instance: FormalTaskDatabase? = null

        fun get(context: Context): FormalTaskDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FormalTaskDatabase::class.java,
                    "wallhub-formal.db",
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                ).build()
                    .also { database -> instance = database }
            }
        }
    }
}
