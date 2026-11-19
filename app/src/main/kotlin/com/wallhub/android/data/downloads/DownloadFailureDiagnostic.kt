package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DownloadStatus
import com.wallhub.android.data.diagnostics.DiagnosticSanitizer

internal data class DownloadFailureDiagnostic(
    val stage: String,
    val type: String,
    val detail: String,
)

internal fun downloadFailureDiagnostic(
    stage: String,
    error: Throwable,
): DownloadFailureDiagnostic {
    val causes = generateSequence(error) { it.cause }.take(MAX_FAILURE_CAUSES).toList()
    val type = causes.first().javaClass.simpleName.ifBlank { "Throwable" }
    val detail =
        causes.joinToString(separator = " <- ") { cause ->
            val causeType = cause.javaClass.simpleName.ifBlank { "Throwable" }
            val message = cause.message?.takeIf(String::isNotBlank)
            DiagnosticSanitizer.sanitizeText(if (message == null) causeType else "$causeType: $message")
        }.take(MAX_FAILURE_DETAIL_LENGTH)
    return DownloadFailureDiagnostic(
        stage = stage.toFailureStage(),
        type = type,
        detail = detail.ifBlank { type },
    )
}

internal fun String.toFailureStage(): String =
    when (this) {
        DownloadStatus.QUEUED.name -> "QUEUE"
        DownloadStatus.RESOLVING.name -> "RESOLUTION"
        DownloadStatus.DOWNLOADING.name -> "TRANSFER_OR_VERIFY"
        DownloadStatus.CONVERTING.name -> "CONVERSION"
        DownloadStatus.EXPORTING.name -> "EXPORT"
        else -> "DOWNLOAD"
    }

private const val MAX_FAILURE_CAUSES = 8
private const val MAX_FAILURE_DETAIL_LENGTH = 4_000
