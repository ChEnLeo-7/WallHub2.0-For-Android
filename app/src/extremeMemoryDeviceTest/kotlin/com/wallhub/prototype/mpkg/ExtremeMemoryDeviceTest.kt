package com.wallhub.prototype.mpkg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import net.jpountz.lz4.LZ4Factory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtremeMemoryDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val targets = listOf(
        "7368c80e-cf29-43e7-96d8-c3073757f7b7" to "materials/(1).tex",
        "3c6d2575-d197-4126-a2e5-78a79e049abc" to "materials/jadegirl.tex",
    )

    @Test fun measureActualLosslessTextures() {
        val report = StringBuilder()
        for ((task, path) in targets) {
            val source = File(context.filesDir, "wallhub-workshop/$task/scene.pkg")
            val entry = PkgReader.readIndex(source).entries.single { it.path == path }
            val output = File(context.cacheDir, "extreme-measure-$task.tex")
            val stop = AtomicBoolean(false)
            val peaks = mutableMapOf<String, LongArray>()
            val sampler = Thread {
                while (!stop.get()) {
                    val stage = when {
                        File(output.path + ".lz4").exists() -> "compress"
                        File(output.path + ".rgba").exists() -> "decode"
                        else -> "copy_or_finish"
                    }
                    val runtime = Runtime.getRuntime()
                    val heap = runtime.totalMemory() - runtime.freeMemory()
                    val native = Debug.getNativeHeapAllocatedSize()
                    val rss = File("/proc/self/status").useLines { lines ->
                        lines.firstOrNull { it.startsWith("VmRSS:") }?.trim()?.split(Regex("\\s+"))?.get(1)?.toLong()?.times(1024) ?: 0
                    }
                    synchronized(peaks) {
                        val peak = peaks.getOrPut(stage) { LongArray(3) }
                        peak[0] = maxOf(peak[0], heap)
                        peak[1] = maxOf(peak[1], native)
                        peak[2] = maxOf(peak[2], rss)
                    }
                    Thread.sleep(10)
                }
            }
            val started = System.nanoTime()
            sampler.start()
            try {
                val result = TexMobileConverter.convertImageToRgbaChunkedFile(
                    source, entry.offset, entry.length, output, 256L * 1024 * 1024,
                )
                assertTrue(result.reason, result.converted)
            } finally {
                stop.set(true)
                sampler.join()
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val line = "$task $path elapsedMs=$elapsed outputBytes=${output.length()} peaks=" +
                peaks.map { (stage, values) -> "$stage(java=${values[0]},native=${values[1]},rss=${values[2]})" }.joinToString(" ")
            Log.i("ExtremeMemoryMeasure", line)
            report.appendLine(line)
            verifyPixels(source, entry.offset, entry.length, output)
            assertFalse(File(output.path + ".rgba").exists())
            assertFalse(File(output.path + ".lz4").exists())
            assertFalse(File(output.path + ".image").exists())
            output.delete()
        }
        File(context.filesDir, "extreme-memory-measurement.txt").writeText(report.toString())
    }

    @Test fun restoreTasksAndRunProductionWorkers() = kotlinx.coroutines.runBlocking {
        val dao = com.wallhub.android.core.database.FormalTaskDatabase.get(context).taskRecordDao()
        val manager = androidx.work.WorkManager.getInstance(context)
        val projectIds = listOf(3830760058L, 3810943704L)
        for ((index, target) in targets.withIndex()) {
            val (taskId, _) = target
            val source = File(context.filesDir, "wallhub-workshop/$taskId")
            val project = org.json.JSONObject(File(source, "project.json").readText())
            val now = System.currentTimeMillis()
            val bytes = source.walkTopDown().filter(File::isFile).sumOf { it.length() }
            val task = com.wallhub.android.core.database.FormalTaskRecordEntity(
                taskId = taskId,
                workshopId = projectIds[index],
                title = project.optString("title", taskId),
                type = "SCENE",
                status = "CONVERTING",
                downloadedBytes = bytes,
                totalBytes = bytes,
                stagingDirectory = source.absolutePath,
                stagingRetainedBytes = bytes,
                stagingLastAccessedAt = now,
                stagingCacheState = "RETAINED",
                exportFormat = "MPKG",
                conversionPreset = "extreme_quality",
                conversionAttempt = 1L,
                conversionPlanVersion = 2,
                createdAt = now,
                updatedAt = now,
            )
            dao.upsert(task)
            val request = androidx.work.OneTimeWorkRequestBuilder<com.wallhub.android.data.downloads.FormalWorkshopConversionWorker>()
                .setInputData(androidx.work.workDataOf("task_id" to taskId)).build()
            manager.enqueue(request).result.get(10, java.util.concurrent.TimeUnit.SECONDS)
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5)
            while (manager.getWorkInfoById(request.id).get()?.state?.isFinished != true) {
                check(System.nanoTime() < deadline) { "Worker timed out: $taskId" }
                Thread.sleep(500)
            }
            val settled = requireNotNull(dao.find(taskId))
            File(context.filesDir, "extreme-production-$taskId.txt").writeText(
                "title=${settled.title}\nstatus=${settled.status}\noutputUri=${settled.outputUri}\n" +
                    "failure=${settled.failureDetail}\nreport=${settled.conversionReportJson}",
            )
            assertEquals(settled.failureDetail, "COMPLETED", settled.status)
            assertNotNull(settled.outputUri)
            Log.i("ExtremeMemoryMeasure", "production $taskId COMPLETED output=${settled.outputUri}")
        }
    }

    @Test fun transparentPngPreservesOriginalStraightAlphaSamples() {
        val root = File(context.cacheDir, "extreme-alpha").apply { mkdirs() }
        val width = 513
        val height = 1031
        val expected = ByteArray(width * height * 4) { index ->
            val pixel = index / 4
            when (index % 4) {
                0 -> (pixel * 37).toByte()
                1 -> (pixel * 53 + 128).toByte()
                2 -> (pixel * 91 + 17).toByte()
                else -> intArrayOf(0, 1, 127, 254, 255)[pixel % 5].toByte()
            }
        }
        val png = java.io.ByteArrayOutputStream()
        val data = java.io.DataOutputStream(png)
        data.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))
        fun chunk(type: String, bytes: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            data.writeInt(bytes.size); data.write(name); data.write(bytes)
            val crc = java.util.zip.CRC32().apply { update(name); update(bytes) }
            data.writeInt(crc.value.toInt())
        }
        val ihdr = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(ihdr).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 6, 0, 0, 0)) }
        chunk("IHDR", ihdr.toByteArray())
        val image = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(image).use { compressed ->
            for (row in 0 until height) { compressed.write(0); compressed.write(expected, row * width * 4, width * 4) }
        }
        chunk("IDAT", image.toByteArray()); chunk("IEND", byteArrayOf())
        val source = File(root, "source.tex")
        val tex = java.io.ByteArrayOutputStream()
        fun text(value: String) { tex.write(value.toByteArray(Charsets.US_ASCII)); tex.write(0) }
        fun le(value: Int) { repeat(4) { tex.write(value ushr (it * 8) and 255) } }
        text("TEXV0005"); text("TEXI0001")
        listOf(0, 2, width, height, width, height, 0).forEach(::le)
        text("TEXB0004")
        listOf(1, 13, 0, 1, width, height, 0, 0, png.size()).forEach(::le)
        tex.write(png.toByteArray()); source.writeBytes(tex.toByteArray())
        val output = File(root, "output.tex")
        try {
            assertTrue(TexMobileConverter.convertImageToRgbaChunkedFile(source, 0, source.length(), output, expected.size.toLong()).converted)
            val compressed = output.readBytes().copyOfRange(91, output.length().toInt())
            val actual = ByteArray(expected.size)
            LZ4Factory.fastestJavaInstance().safeDecompressor().decompress(compressed, 0, compressed.size, actual, 0, actual.size)
            assertArrayEquals(expected, actual)
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationCleansLosslessTemporaryFiles() {
        val (task, path) = targets.first()
        val source = File(context.filesDir, "wallhub-workshop/$task/scene.pkg")
        val entry = PkgReader.readIndex(source).entries.single { it.path == path }
        val output = File(context.cacheDir, "extreme-cancel.tex")
        for (stage in listOf(".rgba", ".lz4")) {
            var cancelled = false
            try {
                TexMobileConverter.convertImageToRgbaChunkedFile(source, entry.offset, entry.length, output, 256L * 1024 * 1024) {
                    if (File(output.path + stage).length() > 0) throw CancellationException("test cancellation")
                }
            } catch (_: CancellationException) { cancelled = true }
            assertTrue("Cancellation did not reach $stage", cancelled)
            for (suffix in listOf("", ".image", ".rgba", ".lz4")) assertFalse(File(output.path + suffix).exists())
        }
    }

    private fun verifyPixels(source: File, offset: Long, length: Long, output: File) {
        val image = File(context.cacheDir, "extreme-reference.image")
        val header = ByteArray(91)
        RandomAccessFile(source, "r").use { input ->
            input.seek(offset)
            input.readFully(header)
            // Real fixtures use standard TEXB0004 header and uncompressed embedded image.
            assertTrue(length > 91)
            image.outputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length - 91
                while (remaining > 0) {
                    val count = minOf(buffer.size.toLong(), remaining).toInt()
                    input.readFully(buffer, 0, count)
                    stream.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
        RandomAccessFile(output, "r").use { input ->
            input.readFully(header)
            fun intAt(index: Int): Int = (0..3).fold(0) { value, byte -> value or ((header[index + byte].toInt() and 255) shl (byte * 8)) }
            val expectedBytes = intAt(83)
            val compressedBytes = intAt(87)
            assertEquals(91L + compressedBytes, output.length())
            val compressed = ByteArray(compressedBytes)
            input.readFully(compressed)
            val rgba = ByteArray(expectedBytes)
            assertEquals(expectedBytes, LZ4Factory.fastestJavaInstance().safeDecompressor().decompress(compressed, 0, compressed.size, rgba, 0, rgba.size))
            val decoder = BitmapRegionDecoder.newInstance(image.path, false)
            try {
                assertEquals(decoder.width * decoder.height * 4, rgba.size)
                val pixels = IntArray(decoder.width * 16)
                val reference = MessageDigest.getInstance("SHA-256")
                val strip = ByteArray(pixels.size * 4)
                for (top in 0 until decoder.height step 16) {
                    val rows = minOf(16, decoder.height - top)
                    val bitmap = decoder.decodeRegion(Rect(0, top, decoder.width, top + rows), BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = false })
                    try { bitmap.getPixels(pixels, 0, decoder.width, 0, 0, decoder.width, rows) } finally { bitmap.recycle() }
                    var position = 0
                    for (index in 0 until decoder.width * rows) {
                        val argb = pixels[index]
                        strip[position++] = (argb ushr 16).toByte()
                        strip[position++] = (argb ushr 8).toByte()
                        strip[position++] = argb.toByte()
                        strip[position++] = (argb ushr 24).toByte()
                    }
                    reference.update(strip, 0, position)
                }
                assertArrayEquals(reference.digest(), MessageDigest.getInstance("SHA-256").digest(rgba))
            } finally { decoder.recycle() }
        }
        image.delete()
    }
}
