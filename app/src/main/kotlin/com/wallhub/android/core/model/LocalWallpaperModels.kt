package com.wallhub.android.core.model

enum class LocalWallpaperFormat {
    MPKG,
    PKG,
    VIDEO,
    HTML,
    UNKNOWN,
}

enum class LocalWallpaperViewMode {
    LIST,
    GRID,
    DETAIL,
}

enum class LocalWallpaperImportState {
    NOT_IMPORTED,
    IMPORT_REQUESTED,
}

data class LocalWallpaperResource(
    val id: String,
    val contentUri: String,
    val displayName: String,
    val title: String,
    val format: LocalWallpaperFormat,
    val workshopType: WorkshopType = WorkshopType.UNKNOWN,
    val sourceId: String,
    val sourceLabel: String,
    val relativePath: String,
    val sizeBytes: Long = 0L,
    val modifiedAt: Long = 0L,
    val mimeType: String? = null,
    val thumbnailUri: String? = null,
    val workshopId: Long? = null,
    val detectionReason: String,
    val isDirectoryProject: Boolean = false,
    val isFavorite: Boolean = false,
    val tags: Set<String> = emptySet(),
    val importRequestedAt: Long? = null,
) {
    val importState: LocalWallpaperImportState
        get() =
            if (importRequestedAt == null) {
                LocalWallpaperImportState.NOT_IMPORTED
            } else {
                LocalWallpaperImportState.IMPORT_REQUESTED
            }
}

data class LocalWallpaperSource(
    val id: String,
    val label: String,
    val rootUri: String,
    val isDownloadDirectory: Boolean,
)

data class LocalWallpaperScanIssue(
    val sourceId: String,
    val message: String,
    val requiresAuthorization: Boolean = false,
)

data class LocalWallpaperScanSnapshot(
    val resources: List<LocalWallpaperResource> = emptyList(),
    val sources: List<LocalWallpaperSource> = emptyList(),
    val discoveredCount: Int = 0,
    val currentSourceLabel: String? = null,
    val isScanning: Boolean = false,
    val issues: List<LocalWallpaperScanIssue> = emptyList(),
)

data class LocalWallpaperDeleteResult(
    val deleted: Boolean,
    val message: String,
)
