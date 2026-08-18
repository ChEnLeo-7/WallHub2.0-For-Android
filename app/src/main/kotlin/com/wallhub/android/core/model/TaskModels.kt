package com.wallhub.android.core.model

enum class DownloadStatus {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    PAUSED,
    CONVERTING,
    EXPORTING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class DownloadAction {
    PAUSE,
    RESUME,
    RETRY,
    EXPORT,
    CANCEL,
    DELETE,
}

enum class ExportFormat {
    /** Uses MPKG for scene/video projects and ZIP for web projects. */
    AUTO,
    MPKG,
    ZIP,
}

enum class DownloadCredentialMode {
    ANONYMOUS,
    ACCOUNT,
    LEGACY_UNKNOWN,
}

data class DownloadRequest(
    val workshopId: Long,
    val title: String,
    val type: WorkshopType,
    val previewUrl: String? = null,
    val expectedTotalBytes: Long = 0L,
    val outputTreeUri: String? = null,
    val exportFormat: ExportFormat = ExportFormat.AUTO,
)

data class DownloadTask(
    val id: String,
    val workshopId: Long,
    val title: String,
    val type: WorkshopType,
    val status: DownloadStatus,
    val previewUrl: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val accountName: String? = null,
    val credentialMode: DownloadCredentialMode =
        if (accountName.isNullOrBlank()) DownloadCredentialMode.LEGACY_UNKNOWN else DownloadCredentialMode.ACCOUNT,
    val message: String? = null,
    val outputLabel: String? = null,
    val stagingDirectory: String? = null,
    val contentManifestId: Long = 0L,
    val appId: Int = 0,
    val outputTreeUri: String? = null,
    val outputUri: String? = null,
    val exportFormat: ExportFormat = ExportFormat.AUTO,
    val requestedAction: DownloadAction? = null,
    val isResumable: Boolean = true,
    val queuePosition: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val progress: Float
        get() =
            if (totalBytes <= 0L) {
                0f
            } else {
                (downloadedBytes.toDouble() / totalBytes)
                    .toFloat()
                    .coerceIn(0f, 1f)
            }

    val availableActions: Set<DownloadAction>
        get() =
            when (status) {
                DownloadStatus.QUEUED,
                DownloadStatus.RESOLVING,
                DownloadStatus.DOWNLOADING,
                -> setOf(DownloadAction.PAUSE, DownloadAction.CANCEL)

                DownloadStatus.CONVERTING -> setOf(DownloadAction.CANCEL)
                DownloadStatus.EXPORTING -> emptySet()
                DownloadStatus.PAUSED -> setOf(DownloadAction.RESUME, DownloadAction.CANCEL)
                DownloadStatus.FAILED -> setOf(DownloadAction.RETRY, DownloadAction.DELETE)
                DownloadStatus.CANCELLED -> setOf(DownloadAction.RETRY, DownloadAction.DELETE)
                DownloadStatus.COMPLETED -> {
                    buildSet {
                        if (stagingDirectory != null && outputUri == null) add(DownloadAction.EXPORT)
                        add(DownloadAction.DELETE)
                    }
                }
            }
}

data class ConversionWarning(
    val title: String,
    val detail: String,
)

data class ConversionTask(
    val id: String,
    val downloadTaskId: String?,
    val status: DownloadStatus,
    val outputLabel: String? = null,
    val warnings: List<ConversionWarning> = emptyList(),
    val message: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)
