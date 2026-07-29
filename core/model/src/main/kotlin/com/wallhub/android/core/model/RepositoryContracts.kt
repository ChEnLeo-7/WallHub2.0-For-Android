package com.wallhub.android.core.model

import java.io.Closeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Product-facing ports. Feature modules depend on these contracts, not on Room,
 * DataStore, WorkManager, JavaSteam, or a concrete transport implementation.
 */
interface SettingsRepository {
    val preferences: Flow<AppPreferences>

    suspend fun setTheme(theme: ThemePreference)

    suspend fun setLanguage(language: AppLanguage)

    suspend fun setAccent(accent: AccentPreference, customColor: String? = null)

    suspend fun setSystemMonetEnabled(enabled: Boolean) = Unit

    suspend fun setThemedLauncherIconEnabled(enabled: Boolean) = Unit

    suspend fun setHomePreferences(
        pageSize: Int,
        columns: Int,
        multiSelect: Boolean,
        cardAction: HomeCardAction,
        matureContentEnabled: Boolean,
    )

    suspend fun setHomePaginationMode(mode: HomePaginationMode) = Unit

    suspend fun setDownloadPreferences(
        maxConcurrentDownloads: Int,
        chunkDownloadConcurrency: Int,
        proxyUrl: String,
        mediaCacheLimitMb: Int,
    )

    suspend fun setOnlineStreamCacheLimitMb(limitMb: Int) = Unit

    suspend fun setDownloadProxyEnabled(enabled: Boolean) = Unit

    suspend fun setSteamAccessEnabled(enabled: Boolean) = Unit

    suspend fun setSteamAccessMode(mode: SteamAccessMode) = Unit

    suspend fun setSteamAccessDohEndpoints(
        endpoints: List<String>,
        disabledEndpoints: Set<String> = emptySet(),
    ) = Unit

    suspend fun setSteamAccessHosts(hosts: String) = Unit

    suspend fun setSteamApiKey(apiKey: String) = Unit

    suspend fun setSteamWorkshopDataSource(source: SteamWorkshopDataSource) = Unit

    suspend fun setOnlineChunkPlaybackEnabled(enabled: Boolean) = Unit

    suspend fun setOutputDirectory(treeUri: String, label: String)

    suspend fun clearOutputDirectory()

    suspend fun setLocalManagementDirectory(treeUri: String, label: String) = Unit

    suspend fun clearLocalManagementDirectory() = Unit

    suspend fun setLocalWallpaperViewMode(mode: LocalWallpaperViewMode) = Unit
}

interface LauncherIconController {
    fun setThemedIconEnabled(enabled: Boolean)
}

interface AppUpdateRepository {
    val installedAppInfo: InstalledAppInfo

    suspend fun latestRelease(): AppReleaseInfo

    suspend fun downloadRelease(
        release: AppReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): String

    fun cancelDownload() = Unit
}

interface SteamAccessRepository {
    val state: StateFlow<SteamAccessState>

    suspend fun prewarmSteamIp(dataSource: SteamWorkshopDataSource): Boolean = true

    fun refresh()
}

interface SteamSessionRepository {
    val session: StateFlow<SteamSessionState>

    /** Begins a refresh-token restore on the repository-owned session scope. */
    fun restorePersistedSession()

    /** Starts an interactive credential login. The password is not persisted. */
    fun login(
        accountName: String,
        password: String,
    )

    /** Supplies a Steam Guard or email code only while the current login is waiting for one. */
    fun submitSteamGuardCode(code: String)

    /** Cancels device-confirmation polling and retries the same in-memory login with a code prompt. */
    fun useManualSteamGuardFallback()

    /** Closes the live CM session and deletes the encrypted refresh token. */
    fun logout()
}

/** Data-layer port for short-lived Steam content sessions. Feature modules never use it. */
interface SteamContentCredentialProvider {
    suspend fun loadContentCredential(): SteamContentCredential?
}

/** A seekable video stream backed by Steam Depot chunks instead of a completed download. */
interface WorkshopVideoStreamSession : Closeable {
    val title: String

    val fileName: String

    val contentLength: Long

    val currentCdnHost: String?
        get() = null

    suspend fun readAt(position: Long, length: Int): ByteArray
}

interface WorkshopVideoStreamRepository {
    suspend fun open(workshopId: Long): WorkshopVideoStreamSession
}

/** Public Workshop data over signed-in or anonymous Steam Connection Manager sessions. */
interface SteamUnifiedWorkshopRepository {
    /** Returns null only when a CM session cannot be established or the RPC is unavailable. */
    suspend fun browsePublic(query: WorkshopBrowseQuery): WorkshopPage?

    suspend fun getPublicDetail(workshopId: Long): WorkshopDetail?

    /** Returns null when no signed-in CM session is available for Community RPCs. */
    suspend fun getAuthenticatedComments(
        workshopId: Long,
        start: Int,
        count: Int,
        ownerId: String,
    ): WorkshopCommentPage?
}

interface WorkshopRepository {
    /** Reads a public Wallpaper Engine Workshop page without requiring a Steam Web API key. */
    suspend fun browse(query: WorkshopBrowseQuery): WorkshopPage

    /** Reads a single public Workshop item and its metadata. */
    suspend fun getDetail(workshopId: Long): WorkshopDetail

    /** Reads one page of public comments for a Workshop item. */
    suspend fun getComments(
        workshopId: Long,
        start: Int = 0,
        count: Int = 20,
        ownerId: String? = null,
    ): WorkshopCommentPage
}

/**
 * Authenticated Steam Workshop operations.  This is deliberately separate from the public
 * browser so public discovery remains usable without a Steam account.
 */
interface AccountWorkshopRepository {
    suspend fun browseCollection(query: AccountWorkshopQuery): WorkshopPage

    /** Resolves the public Steam display name for a Workshop item's creator on demand. */
    suspend fun resolveAuthorDisplayName(workshopId: Long): String?

    suspend fun getInteraction(workshopId: Long): WorkshopInteraction

    suspend fun setSubscribed(workshopId: Long, subscribed: Boolean): WorkshopInteraction

    suspend fun setFavorited(workshopId: Long, favorited: Boolean): WorkshopInteraction

    suspend fun postComment(workshopId: Long, ownerId: String, text: String)
}

interface DownloadTaskRepository {
    val tasks: Flow<List<DownloadTask>>

    suspend fun find(taskId: String): DownloadTask?

    suspend fun upsert(task: DownloadTask)

    suspend fun enqueue(request: DownloadRequest): DownloadTask

    suspend fun requestAction(taskId: String, action: DownloadAction)

    suspend fun reorder(taskIds: List<String>)

    suspend fun clearFinishedHistory(): Int
}

interface LocalWallpaperRepository {
    fun scan(): Flow<LocalWallpaperScanSnapshot>

    suspend fun setFavorite(resourceId: String, favorite: Boolean)

    suspend fun replaceTags(resourceId: String, tags: Set<String>)

    suspend fun renameTag(oldTag: String, newTag: String)

    suspend fun deleteTag(tag: String)

    suspend fun markImportRequested(resourceId: String, requestedAt: Long)

    suspend fun delete(resource: LocalWallpaperResource): LocalWallpaperDeleteResult
}

interface ConversionTaskRepository {
    val tasks: Flow<List<ConversionTask>>

    suspend fun find(taskId: String): ConversionTask?

    suspend fun upsert(task: ConversionTask)

    suspend fun clearFinishedHistory(): Int
}

interface DiagnosticRepository {
    suspend fun record(event: DiagnosticEvent)

    suspend fun readRecent(limit: Int = 200): List<DiagnosticEvent>

    suspend fun exportRedactedText(): String

    suspend fun clear()
}
