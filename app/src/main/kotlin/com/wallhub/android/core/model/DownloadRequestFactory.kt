package com.wallhub.android.core.model

internal fun WorkshopSummary.toQuickDownloadRequest(
    preferences: AppPreferences,
    localizedTitle: String,
    taskId: String? = null,
    outputTreeUri: String? = preferences.outputTreeUri,
): DownloadRequest =
    DownloadRequest(
        workshopId = id,
        title = localizedTitle,
        type = type,
        taskId = taskId,
        previewUrl = previewUrl,
        expectedTotalBytes = fileSizeBytes ?: 0L,
        outputTreeUri = outputTreeUri,
        exportFormat = ExportFormat.AUTO,
        mpkgConversionPreset = preferences.mpkgConversionPreset,
        preserveAudio = preferences.preserveAudio,
    )
