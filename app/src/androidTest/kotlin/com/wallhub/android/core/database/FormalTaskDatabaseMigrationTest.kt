package com.wallhub.android.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormalTaskDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun cleanUp() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migratesVersionOneDatabaseToCurrentSchemaWithoutLosingTasks() {
        createVersionOneDatabase()

        val database =
            Room
                .databaseBuilder(context, FormalTaskDatabase::class.java, DATABASE_NAME)
                .addMigrations(*FormalTaskDatabase.migrations)
                .build()
        try {
            val migrated = database.openHelper.writableDatabase
            assertEquals(7, migrated.version)
            migrated.query(
                "SELECT title, queuePosition, credentialMode FROM formal_task_records WHERE taskId = 'task-1'",
            ).use {
                assertEquals(true, it.moveToFirst())
                assertEquals("Migration sample", it.getString(0))
                assertEquals(100L, it.getLong(1))
                assertEquals("LEGACY_UNKNOWN", it.getString(2))
            }
            assertNotNull(
                migrated.query("SELECT resourceId FROM local_wallpaper_states").use { it.columnNames },
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun migratesVersionSixCredentialModesWithoutGuessingAnonymousOwnership() {
        createVersionSixDatabase()

        val database =
            Room
                .databaseBuilder(context, FormalTaskDatabase::class.java, DATABASE_NAME)
                .addMigrations(*FormalTaskDatabase.migrations)
                .build()
        try {
            val migrated = database.openHelper.writableDatabase
            migrated.query("SELECT taskId, credentialMode FROM formal_task_records ORDER BY taskId").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("account", it.getString(0))
                assertEquals("ACCOUNT", it.getString(1))
                assertEquals(true, it.moveToNext())
                assertEquals("unknown", it.getString(0))
                assertEquals("LEGACY_UNKNOWN", it.getString(1))
            }
        } finally {
            database.close()
        }
    }

    private fun createVersionOneDatabase() {
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(DATABASE_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(database: SupportSQLiteDatabase) {
                            database.execSQL(
                                "CREATE TABLE IF NOT EXISTS formal_task_records (" +
                                    "taskId TEXT NOT NULL, workshopId INTEGER NOT NULL, " +
                                    "title TEXT NOT NULL, type TEXT NOT NULL, status TEXT NOT NULL, " +
                                    "downloadedBytes INTEGER NOT NULL, totalBytes INTEGER NOT NULL, " +
                                    "outputLabel TEXT, message TEXT, createdAt INTEGER NOT NULL, " +
                                    "updatedAt INTEGER NOT NULL, PRIMARY KEY(taskId))",
                            )
                            database.execSQL(
                                "CREATE INDEX IF NOT EXISTS index_formal_task_records_workshopId " +
                                    "ON formal_task_records(workshopId)",
                            )
                            database.execSQL(
                                "CREATE INDEX IF NOT EXISTS index_formal_task_records_updatedAt " +
                                    "ON formal_task_records(updatedAt)",
                            )
                            database.execSQL(
                                "INSERT INTO formal_task_records (" +
                                    "taskId, workshopId, title, type, status, downloadedBytes, " +
                                    "totalBytes, outputLabel, message, createdAt, updatedAt) " +
                                    "VALUES ('task-1', 123, 'Migration sample', 'VIDEO', 'COMPLETED', " +
                                    "10, 10, NULL, NULL, 100, 100)",
                            )
                        }

                        override fun onUpgrade(
                            database: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            helper.writableDatabase
        }
    }

    private fun createVersionSixDatabase() {
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(DATABASE_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(database: SupportSQLiteDatabase) {
                            database.execSQL(
                                "CREATE TABLE formal_task_records (" +
                                    "taskId TEXT NOT NULL, workshopId INTEGER NOT NULL, title TEXT NOT NULL, " +
                                    "type TEXT NOT NULL, status TEXT NOT NULL, previewUrl TEXT, " +
                                    "downloadedBytes INTEGER NOT NULL, totalBytes INTEGER NOT NULL, " +
                                    "bytesPerSecond INTEGER NOT NULL, accountName TEXT, outputLabel TEXT, " +
                                    "stagingDirectory TEXT, contentManifestId INTEGER NOT NULL, appId INTEGER NOT NULL, " +
                                    "outputTreeUri TEXT, outputUri TEXT, exportFormat TEXT NOT NULL, requestedAction TEXT, " +
                                    "isResumable INTEGER NOT NULL, " +
                                    "message TEXT, queuePosition INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                                    "updatedAt INTEGER NOT NULL, PRIMARY KEY(taskId))",
                            )
                            database.execSQL(
                                "INSERT INTO formal_task_records VALUES " +
                                    "('account', 1, 'Account', 'VIDEO', 'PAUSED', NULL, 0, 0, 0, 'alice', NULL, " +
                                    "NULL, 0, 0, NULL, NULL, 'AUTO', NULL, 1, NULL, 1, 1, 1), " +
                                    "('unknown', 2, 'Unknown', 'VIDEO', 'PAUSED', NULL, 0, 0, 0, NULL, NULL, " +
                                    "NULL, 0, 0, NULL, NULL, 'AUTO', NULL, 1, NULL, 2, 2, 2)",
                            )
                            database.execSQL(
                                "CREATE INDEX index_formal_task_records_workshopId ON formal_task_records(workshopId)",
                            )
                            database.execSQL(
                                "CREATE INDEX index_formal_task_records_updatedAt ON formal_task_records(updatedAt)",
                            )
                            database.execSQL(
                                "CREATE INDEX index_formal_task_records_queuePosition ON formal_task_records(queuePosition)",
                            )
                            database.execSQL(
                                "CREATE TABLE local_wallpaper_states (resourceId TEXT NOT NULL, isFavorite INTEGER NOT NULL, " +
                                    "importRequestedAt INTEGER, updatedAt INTEGER NOT NULL, PRIMARY KEY(resourceId))",
                            )
                            database.execSQL(
                                "CREATE TABLE local_wallpaper_tags (resourceId TEXT NOT NULL, tag TEXT NOT NULL, " +
                                    "PRIMARY KEY(resourceId, tag))",
                            )
                            database.execSQL("CREATE INDEX index_local_wallpaper_tags_tag ON local_wallpaper_tags(tag)")
                        }

                        override fun onUpgrade(
                            database: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper -> helper.writableDatabase }
    }

    private companion object {
        const val DATABASE_NAME = "migration-test.db"
    }
}
