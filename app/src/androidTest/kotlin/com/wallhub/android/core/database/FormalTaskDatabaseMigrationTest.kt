package com.wallhub.android.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
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
            database.query("SELECT COUNT(*) FROM download_engine_state").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "formal-task-migration-test"
    }
}
