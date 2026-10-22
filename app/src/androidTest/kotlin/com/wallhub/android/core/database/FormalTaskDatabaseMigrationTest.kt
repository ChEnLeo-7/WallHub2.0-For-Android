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
    fun migration10To11AddsConversionSnapshotColumnsWithStableDefaults() {
        helper.createDatabase(TEST_DATABASE, 10).use { database ->
            database.execSQL(
                "INSERT INTO formal_task_records (" +
                    "taskId, workshopId, title, type, status, downloadedBytes, totalBytes, bytesPerSecond, " +
                    "credentialMode, isResumable, contentManifestId, appId, exportFormat, exportTransactionState, " +
                    "queuePosition, createdAt, updatedAt, attempt, desiredIntent, desiredIntentRevision, " +
                    "appliedIntentRevision, quiescedIntentRevision, lastReceiptSequence) " +
                    "VALUES ('legacy', 1, 'Legacy', 'SCENE', 'FAILED', 0, 0, 0, 'ANONYMOUS', 1, 0, 0, " +
                    "'AUTO', 'NONE', 0, 1, 1, 0, 'RUN', 0, 0, 0, 0)",
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            11,
            true,
            FormalTaskDatabase.MIGRATION_10_11,
        ).use { database ->
            database.query(
                "SELECT conversionPreset, conversionPlanJson, conversionReportJson, conversionPlanVersion " +
                    "FROM formal_task_records WHERE taskId = 'legacy'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("balanced", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
                assertEquals(1, cursor.getInt(3))
            }
        }
    }

    @Test
    fun migration11To12AddsPreserveAudioSnapshotDefaultingToFalse() {
        helper.createDatabase(TEST_DATABASE, 11).use { database ->
            database.execSQL(
                "INSERT INTO formal_task_records (" +
                    "taskId, workshopId, title, type, status, downloadedBytes, totalBytes, bytesPerSecond, " +
                    "credentialMode, isResumable, contentManifestId, appId, exportFormat, conversionPreset, " +
                    "conversionPlanVersion, exportTransactionState, queuePosition, createdAt, updatedAt, attempt, " +
                    "desiredIntent, desiredIntentRevision, appliedIntentRevision, quiescedIntentRevision, " +
                    "lastReceiptSequence) VALUES ('legacy-audio', 1, 'Legacy', 'SCENE', 'FAILED', 0, 0, 0, " +
                    "'ANONYMOUS', 1, 0, 0, 'AUTO', 'balanced', 1, 'NONE', 0, 1, 1, 0, 'RUN', 0, 0, 0, 0)",
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            12,
            true,
            FormalTaskDatabase.MIGRATION_11_12,
        ).use { database ->
            database
                .query("SELECT preserveAudio FROM formal_task_records WHERE taskId = 'legacy-audio'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
        }
    }

    @Test
    fun migration12To13AddsWorkshopSourceCacheColumnsWithStableDefaults() {
        helper.createDatabase(TEST_DATABASE, 12).use { database ->
            database.execSQL(
                "INSERT INTO formal_task_records (" +
                    "taskId, workshopId, title, type, status, downloadedBytes, totalBytes, bytesPerSecond, " +
                    "credentialMode, isResumable, contentManifestId, appId, stagingDirectory, exportFormat, conversionPreset, " +
                    "preserveAudio, conversionPlanVersion, exportTransactionState, queuePosition, createdAt, updatedAt, attempt, " +
                    "desiredIntent, desiredIntentRevision, appliedIntentRevision, quiescedIntentRevision, lastReceiptSequence) VALUES " +
                    "('legacy-staging', 1, 'Legacy', 'SCENE', 'COMPLETED', 1, 1, 0, 'ANONYMOUS', 1, 0, 0, " +
                    "'/private/staging', 'MPKG', 'balanced', 0, 1, 'NONE', 0, 1, 99, 0, 'RUN', 0, 0, 0, 0)",
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            13,
            true,
            FormalTaskDatabase.MIGRATION_12_13,
        ).use { database ->
            database.query("PRAGMA table_info(formal_task_records)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                assertTrue("stagingRetainedBytes" in columns)
                assertTrue("stagingLastAccessedAt" in columns)
                assertTrue("stagingCacheState" in columns)
            }
            database.query(
                "SELECT stagingRetainedBytes, stagingLastAccessedAt, stagingCacheState " +
                    "FROM formal_task_records WHERE taskId = 'legacy-staging'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
                assertEquals(99L, cursor.getLong(1))
                assertEquals("RETAINED", cursor.getString(2))
            }
        }
    }

    @Test
    fun migration13To14SeparatesSuccessfulReportFromMostRecentAttempt() {
        helper.createDatabase(TEST_DATABASE, 13).use { database ->
            database.execSQL(
                "INSERT INTO formal_task_records (taskId, workshopId, title, type, status, downloadedBytes, " +
                    "totalBytes, bytesPerSecond, credentialMode, isResumable, contentManifestId, appId, " +
                    "outputUri, outputLabel, exportFormat, conversionPreset, preserveAudio, conversionReportJson, conversionPlanVersion, " +
                    "stagingRetainedBytes, stagingLastAccessedAt, stagingCacheState, exportTransactionState, " +
                    "queuePosition, createdAt, updatedAt, attempt, desiredIntent, desiredIntentRevision, " +
                    "appliedIntentRevision, quiescedIntentRevision, lastReceiptSequence) VALUES " +
                    "('completed', 1, 'Completed', 'SCENE', 'COMPLETED', 1, 1, 0, 'ANONYMOUS', 1, 0, 0, " +
                    "'content://old-output', 'MPKG', 'MPKG', 'balanced', 0, '{\"ok\":1}', 1, 1, 1, 'RETAINED', 'COMMITTED', 0, 1, 1, 0, " +
                    "'RUN', 0, 0, 0, 0), " +
                    "('failed', 2, 'Failed', 'SCENE', 'FAILED', 1, 1, 0, 'ANONYMOUS', 1, 0, 0, " +
                    "NULL, NULL, 'MPKG', 'balanced', 0, '{\"failed\":1}', 1, 1, 1, 'RETAINED', 'NONE', 0, 1, 1, 0, " +
                    "'RUN', 0, 0, 0, 0)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 14, true, FormalTaskDatabase.MIGRATION_13_14).use { database ->
            database.query(
                "SELECT successfulConversionReportJson, conversionReportJson, conversionAttempt, " +
                    "lastSuccessfulAttempt, outputUri FROM formal_task_records ORDER BY taskId",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{\"ok\":1}", cursor.getString(0))
                assertEquals("{\"ok\":1}", cursor.getString(1))
                assertEquals(1L, cursor.getLong(2))
                assertEquals(1L, cursor.getLong(3))
                assertEquals("content://old-output", cursor.getString(4))
                assertTrue(cursor.moveToNext())
                assertTrue(cursor.isNull(0))
                assertEquals("{\"failed\":1}", cursor.getString(1))
                assertEquals(1L, cursor.getLong(2))
                assertEquals(0L, cursor.getLong(3))
            }
        }
    }

    @Test
    fun projectionUpsertPreservesTheTaskAudioSnapshot() {
        val database = Room.databaseBuilder(context, FormalTaskDatabase::class.java, TEST_DATABASE).build()
        try {
            val dao = database.taskRecordDao()
            kotlinx.coroutines.runBlocking {
                dao.upsert(task("audio-snapshot", "CONVERTING").copy(preserveAudio = true))

                val merged =
                    dao.upsertPreservingExecutionIntent(
                        task("audio-snapshot", "FAILED").copy(preserveAudio = false),
                    )

                assertTrue(merged.preserveAudio)
                assertTrue(requireNotNull(dao.find("audio-snapshot")).preserveAudio)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun projectionUpsertPreservesWorkshopSourceCacheAccounting() {
        val database = Room.databaseBuilder(context, FormalTaskDatabase::class.java, TEST_DATABASE).build()
        try {
            val dao = database.taskRecordDao()
            kotlinx.coroutines.runBlocking {
                dao.upsert(
                    task("cache-snapshot", "COMPLETED").copy(
                        stagingRetainedBytes = 42,
                        stagingLastAccessedAt = 7,
                        stagingCacheState = "RETAINED",
                    ),
                )
                val merged = dao.upsertPreservingExecutionIntent(task("cache-snapshot", "FAILED"))
                assertEquals(42L, merged.stagingRetainedBytes)
                assertEquals(7L, merged.stagingLastAccessedAt)
                assertEquals("RETAINED", merged.stagingCacheState)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun conversionArtifactUpdatesDoNotReplaceTheOtherSnapshot() {
        val database = Room.databaseBuilder(context, FormalTaskDatabase::class.java, TEST_DATABASE).build()
        try {
            val dao = database.taskRecordDao()
            kotlinx.coroutines.runBlocking {
                dao.upsert(task("conversion-snapshot", "CONVERTING"))
                assertEquals(1, dao.persistConversionPlan("conversion-snapshot", "{\"plan\":1}", 2))
                assertEquals(1, dao.persistConversionReport("conversion-snapshot", 0, "{\"report\":1}"))

                val persisted = requireNotNull(dao.find("conversion-snapshot"))
                assertEquals("{\"plan\":1}", persisted.conversionPlanJson)
                assertEquals(2, persisted.conversionPlanVersion)
                assertEquals("{\"report\":1}", persisted.conversionReportJson)
                assertEquals(null, persisted.successfulConversionReportJson)
            }
        } finally {
            database.close()
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
                assertTrue(database.downloadCommandDao().pendingTaskIds(10).isEmpty())
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
