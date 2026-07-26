package com.wallhub.android

import android.content.Context
import android.os.Process
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

internal object CrashDiagnostics {
    private val isInstalled = AtomicBoolean(false)

    fun install(context: Context) {
        if (!isInstalled.compareAndSet(false, true)) return
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(context.applicationContext, thread, throwable) }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun writeReport(
        context: Context,
        thread: Thread,
        throwable: Throwable,
    ) {
        val file = File(context.filesDir, "diagnostics/wallhub-crash.log")
        file.parentFile?.mkdirs()
        if (file.isFile && file.length() > MAX_FILE_SIZE_BYTES) {
            file.writeText(file.readText(Charsets.UTF_8).takeLast(RETAINED_CHARACTERS), Charsets.UTF_8)
        }
        val stackFrames = throwable.stackTrace
            .take(MAX_STACK_FRAMES)
            .joinToString(separator = "\n") { frame ->
                "at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})"
            }
        file.appendText(
            buildString {
                append(System.currentTimeMillis())
                append('\t')
                append("thread=")
                append(thread.name)
                append('\t')
                append("exception=")
                append(throwable.javaClass.name)
                append('\n')
                append(stackFrames)
                append("\n\n")
            },
            Charsets.UTF_8,
        )
    }

    private const val MAX_FILE_SIZE_BYTES = 64 * 1024L
    private const val RETAINED_CHARACTERS = 48 * 1024
    private const val MAX_STACK_FRAMES = 48
}
