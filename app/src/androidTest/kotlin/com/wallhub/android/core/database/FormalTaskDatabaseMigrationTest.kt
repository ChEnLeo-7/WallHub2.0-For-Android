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
            assertEquals(6, migrated.version)
            migrated.query("SELECT title, queuePosition FROM formal_task_records WHERE taskId = 'task-1'").use {
                assertEquals(true, it.moveToFirst())
                assertEquals("Migration sample", it.getString(0))
                assertEquals(100L, it.getLong(1))
            }
            assertNotNull(
                migrated.query("SELECT resourceId FROM local_wallpaper_states").use { it.columnNames },
            )
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

    private companion object {
        const val DATABASE_NAME = "migration-test.db"
    }
}
