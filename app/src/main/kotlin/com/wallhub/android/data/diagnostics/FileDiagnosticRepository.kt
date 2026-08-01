package com.wallhub.android.data.diagnostics

import android.content.Context
import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticExportRepository
import com.wallhub.android.core.model.DiagnosticLevel
import com.wallhub.android.core.model.DiagnosticRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileDiagnosticRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : DiagnosticRepository,
        DiagnosticExportRepository {
        private val file = File(context.filesDir, "diagnostics/wallhub-formal.log")
        private val crashFile = File(context.filesDir, "diagnostics/wallhub-crash.log")
        private val writeMutex = Mutex()

        override suspend fun record(event: DiagnosticEvent) =
            withContext(Dispatchers.IO) {
                writeMutex.withLock {
                    val safeEvent = DiagnosticSanitizer.sanitize(event)
                    file.parentFile?.mkdirs()
                    file.appendText(safeEvent.toLogLine() + "\n", Charsets.UTF_8)
                }
            }

        override suspend fun readRecent(limit: Int): List<DiagnosticEvent> =
            withContext(Dispatchers.IO) {
                val safeLimit = limit.coerceIn(1, MAX_EVENTS)
                writeMutex.withLock {
                    if (!file.isFile) return@withLock emptyList()
                    file
                        .readLines(Charsets.UTF_8)
                        .takeLast(safeLimit)
                        .mapNotNull(::parseLogLine)
                }
            }

        override suspend fun exportRedactedText(): String =
            withContext(Dispatchers.IO) {
                writeMutex.withLock {
                    val content = if (file.isFile) file.readText(Charsets.UTF_8) else ""
                    val crashContent = if (crashFile.isFile) crashFile.readText(Charsets.UTF_8) else ""
                    buildString {
                        append("# WallHub Android diagnostics (redacted)\n")
                        append(content)
                        if (crashContent.isNotBlank()) {
                            append("\n# Uncaught crash stack traces\n")
                            append(crashContent)
                        }
                    }
                }
            }

        override suspend fun exportTo(destinationUri: String) =
            withContext(Dispatchers.IO) {
                val content = exportRedactedText()
                val output =
                    context.contentResolver.openOutputStream(android.net.Uri.parse(destinationUri), "wt")
                        ?: error("Failed to create diagnostic log file")
                output.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(content) }
            }

        override suspend fun clear() =
            withContext(Dispatchers.IO) {
                writeMutex.withLock {
                    if (file.exists()) file.delete()
                    if (crashFile.exists()) crashFile.delete()
                }
            }

        private fun DiagnosticEvent.toLogLine(): String =
            listOf(
                timestamp.toString(),
                level.name,
                source.cleanLogField(),
                message.cleanLogField(),
                attributes.entries
                    .sortedBy { it.key }
                    .joinToString(";") { (key, value) -> "${key.cleanLogField()}=${value.cleanLogField()}" },
            ).joinToString("\t")

        private fun parseLogLine(line: String): DiagnosticEvent? {
            val fields = line.split("\t", limit = 5)
            if (fields.size < 4) return null
            val level = runCatching { DiagnosticLevel.valueOf(fields[1]) }.getOrNull() ?: return null
            return DiagnosticEvent(
                timestamp = fields[0].toLongOrNull() ?: return null,
                source = fields[2],
                level = level,
                message = fields[3],
                attributes = emptyMap(),
            )
        }

        private fun String.cleanLogField(): String =
            replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')

        private companion object {
            const val MAX_EVENTS = 500
        }
    }

internal object DiagnosticSanitizer {
    private val sensitiveAttributeNames =
        setOf(
            "password",
            "passwd",
            "pwd",
            "token",
            "access_token",
            "id_token",
            "refresh_token",
            "refreshtoken",
            "authorization",
            "api_key",
            "secret",
            "credential",
            "cookie",
            "sessionid",
            "steamloginsecure",
            "clientsessionid",
            "steam_guard_code",
            "guard_code",
            "code",
        )

    private val assignmentPattern =
        Regex(
            "(?i)\\b(password|passwd|pwd|token|refresh[_-]?token|authorization|cookie|sessionid|steamloginsecure|clientsessionid|steam[_-]?guard[_-]?code|guard[_-]?code|code)\\s*([:=])\\s*([^\\s,;]+)",
        )
    private val authorizationBearerPattern =
        Regex(
            "(?i)\\bauthorization\\s*([:=])\\s*bearer\\s+[^\\s,;]+",
        )
    private val bearerPattern = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+")

    fun sanitize(event: DiagnosticEvent): DiagnosticEvent =
        event.copy(
            message = sanitizeText(event.message),
            attributes =
                event.attributes.mapValues { (key, value) ->
                    if (key.normalizedDiagnosticKey() in sensitiveAttributeNames) REDACTED else sanitizeText(value)
                },
        )

    fun sanitizeText(value: String): String =
        value
            .replace(authorizationBearerPattern) { match -> "Authorization${match.groupValues[1]} Bearer $REDACTED" }
            .replace(assignmentPattern) { match -> "${match.groupValues[1]}${match.groupValues[2]}$REDACTED" }
            .replace(bearerPattern, "Bearer $REDACTED")

    private fun String.normalizedDiagnosticKey(): String =
        trim()
            .lowercase()
            .replace('-', '_')

    private const val REDACTED = "[REDACTED]"
}
