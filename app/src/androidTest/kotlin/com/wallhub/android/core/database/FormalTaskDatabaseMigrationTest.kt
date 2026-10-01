package com.wallhub.android.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormalTaskDatabaseMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            FormalTaskDatabase::class.java,
        )

    @After
    fun deleteDatabase() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migration7To8PreservesLegacyAuthorityAndCreatesCommandTables() {
        helper.createDatabase(TEST_DATABASE, 7).close()

        helper.runMigrationsAndValidate(TEST_DATABASE, 8, true, *FormalTaskDatabase.migrations).use { database ->
            database.query("PRAGMA table_info(formal_task_records)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                assertTrue("executorVersion" in columns)
                assertTrue("desiredIntentRevision" in columns)
                assertTrue("lastReceiptSequence" in columns)
                assertTrue("engineEpoch" in columns)
            }
            database.query("SELECT COUNT(*) FROM download_commands").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migration8To9AbsorbsLegacyAuthorityAndDropsCompatibilityColumns() {
        helper.createDatabase(TEST_DATABASE, 8).use { database ->
            insertLegacyTask(database, taskId = "queued", status = "QUEUED", requestedAction = "CANCEL")
            insertLegacyTask(database, taskId = "conversion", status = "CONVERTING", requestedAction = "CANCEL")
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            9,
            true,
            FormalTaskDatabase.MIGRATION_8_9,
        ).use { database ->
            database.query("PRAGMA table_info(formal_task_records)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                assertFalse("requestedAction" in columns)
                assertFalse("executorVersion" in columns)
                assertTrue("desiredIntentRevision" in columns)
            }
            database.query(
                "SELECT desiredIntent, desiredIntentRevision FROM formal_task_records WHERE taskId = 'queued'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("CANCEL", cursor.getString(0))
                assertEquals(1L, cursor.getLong(1))
            }
            database.query(
                "SELECT command, revision FROM download_commands WHERE taskId = 'queued'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("CANCEL", cursor.getString(0))
                assertEquals(1L, cursor.getLong(1))
            }
            database.query("SELECT COUNT(*) FROM download_commands WHERE taskId = 'conversion'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            database.query(
                "SELECT desiredIntent, desiredIntentRevision FROM formal_task_records WHERE taskId = 'conversion'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("CANCEL", cursor.getString(0))
                assertEquals(1L, cursor.getLong(1))
            }
        }
    }

    @Test
    fun migration9To10AddsDurableExportTransactionColumns() {
        helper.createDatabase(TEST_DATABASE, 9).close()

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            10,
            true,
            FormalTaskDatabase.MIGRATION_9_10,
        ).use { database ->
            database.query("PRAGMA table_info(formal_task_records)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                assertTrue("exportTransactionState" in columns)
                assertTrue("exportTargetUri" in columns)
                assertTrue("exportBackupUri" in columns)
                assertTrue("exportOutputName" in columns)
                assertTrue("exportMimeType" in columns)
            }
        }
    }

    @Test
    fun deletingATerminalTaskAlsoDeletesItsPendingCommands() {
        val database = Room.databaseBuilder(context, FormalTaskDatabase::class.java, TEST_DATABASE).build()
        try {
            val dao = database.taskRecordDao()
            kotlinx.coroutines.runBlocking {
                dao.upsertWithCommand(
                    task = task("task", "FAILED"),
                    command =
                        DownloadCommandEntity(
                            commandId = "command",
                            taskId = "task",
                            revision = 1,
                            attempt = 0,
                            command = "RUN",
                            createdAt = 1,
                        ),
                )
                assertEquals(1, dao.deleteTerminalWithCommands("task"))
                assertEquals(null, dao.find("task"))
                assertTrue(database.downloadCommandDao().pending(10).isEmpty())
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun conversionCancellationSurvivesProjectionWritesAndSettlesItsRevision() {
        val database = Room.databaseBuilder(context, FormalTaskDatabase::class.java, TEST_DATABASE).build()
        try {
            val dao = database.taskRecordDao()
            kotlinx.coroutines.runBlocking {
                val converting = task("conversion", "CONVERTING")
                dao.upsert(converting)
                assertTrue(dao.requestConversionCancellation("conversion", "cancelling", 2))
                dao.upsertPreservingExecutionIntent(
                    converting.copy(status = "CANCELLED", message = "cancelled", updatedAt = 3),
                )
                val cancelled = requireNotNull(dao.find("conversion"))
                assertEquals("CANCEL", cancelled.desiredIntent)
                assertEquals(1L, cancelled.desiredIntentRevision)
                assertEquals(0L, cancelled.appliedIntentRevision)

                assertEquals(1, dao.settleConversionCancellation("conversion"))
                val settled = requireNotNull(dao.find("conversion"))
                assertEquals(1L, settled.appliedIntentRevision)
                assertEquals(1L, settled.quiescedIntentRevision)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun newerEngineEpochImmediatelyFencesOldCommandClaims() {
        val database =
            Room.databaseBuilder(context, FormalTaskDatabase::class.java, TEST_DATABASE)
                .addMigrations(*FormalTaskDatabase.migrations)
                .build()
        try {
            val dao = database.downloadCommandDao()
            val first = kotlinx.coroutines.runBlocking { dao.acquireEngineEpoch("first", 1) }
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO download_commands " +
                    "(commandId, taskId, revision, attempt, command, createdAt) " +
                    "VALUES ('command', 'task', 1, 0, 'RUN', 1)",
            )
            assertEquals(1, kotlinx.coroutines.runBlocking { dao.claim("command", first.epoch, 2) })
            val second = kotlinx.coroutines.runBlocking { dao.acquireEngineEpoch("second", 3) }
            assertEquals(2L, second.epoch)
            assertEquals(0, kotlinx.coroutines.runBlocking { dao.claim("command", first.epoch, 4) })
        } finally {
            database.close()
        }
    }

    private fun task(
        taskId: String,
        status: String,
    ) =
        FormalTaskRecordEntity(
            taskId = taskId,
            workshopId = 1,
            title = taskId,
            type = "scene",
            status = status,
            createdAt = 1,
            updatedAt = 1,
        )

    private fun insertLegacyTask(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        taskId: String,
        status: String,
        requestedAction: String?,
    ) {
        database.execSQL(
            "INSERT INTO formal_task_records (" +
                "taskId, workshopId, title, type, status, downloadedBytes, totalBytes, bytesPerSecond, " +
                "credentialMode, requestedAction, isResumable, contentManifestId, appId, exportFormat, " +
                "queuePosition, createdAt, updatedAt, executorVersion, attempt, desiredIntent, " +
                "desiredIntentRevision, appliedIntentRevision, quiescedIntentRevision, lastReceiptSequence) " +
                "VALUES (?, 1, ?, 'scene', ?, 0, 0, 0, 'LEGACY_UNKNOWN', ?, 1, 0, 0, 'AUTO', " +
                "0, 1, 1, 'LEGACY', 0, 'RUN', 0, 0, 0, 0)",
            arrayOf(taskId, taskId, status, requestedAction),
        )
    }

    private companion object {
        const val TEST_DATABASE = "formal-task-migration-test"
    }
}
