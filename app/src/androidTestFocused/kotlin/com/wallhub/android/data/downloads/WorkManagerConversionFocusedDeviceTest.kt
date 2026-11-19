package com.wallhub.android.data.downloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkManagerConversionFocusedDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val workManager = WorkManager.getInstance(context)
    private val taskId = "focused-${UUID.randomUUID()}"

    @After
    fun cleanUp() {
        runBlocking { WorkManagerConversionWorkScheduler(context).cancel(taskId) }
    }

    @Test
    fun conversionSchedulerPersistsUniqueTaggedRequestWithTaskId() = runBlocking {
        WorkManagerConversionWorkScheduler(context).enqueue(taskId)

        val work =
            workManager
                .getWorkInfosForUniqueWork(FormalWorkshopConversionWorker.UNIQUE_WORK_NAME_PREFIX + taskId)
                .get(10, TimeUnit.SECONDS)
                .single()

        assertTrue(work.tags.contains(FormalWorkshopConversionWorker::class.java.name))
        assertTrue(work.tags.contains(FormalWorkshopConversionWorker.WORK_TAG_PREFIX + taskId))
    }
}
