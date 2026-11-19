package com.wallhub.android.core.model

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * Product-facing ports. Feature modules depend on these contracts, not on Room,
 * DataStore, WorkManager, JavaSteam, or a concrete transport implementation.
 */
interface SettingsRepository {
    val preferences: Flow<AppPreferences>

    suspend fun setSetupWizardCompleted(completed: Boolean) = Unit

    suspend fun setTheme(theme: ThemePreference)

    suspend fun setVisualStyle(style: VisualStyle) = Unit

    suspend fun setSchemeStyle(style: ThemeSchemeStyle) = Unit

    suspend fun setContrastLevel(level: ThemeContrastLevel) = Unit

    suspend fun setPureBlackInDark(enabled: Boolean) = Unit

    suspend fun setAccent(
        accent: AccentPreference,
        customColor: String? = null,
    )

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

    suspend fun setHomeSearchFab(enabled: Boolean) = Unit

    suspend fun setHomeTopInfoVisible(visible: Boolean) = Unit

    suspend fun setHomeBottomBarAutoHide(enabled: Boolean) = Unit

    suspend fun setDiscoverResultsColumns(columns: Int) = Unit

    suspend fun setDownloadPreferences(
        maxConcurrentDownloads: Int,
        chunkDownloadConcurrency: Int,
        proxyUrl: String,
        mediaCacheLimitMb: Int,
    )

    suspend fun setMpkgConversionPreset(preset: MpkgConversionPreset) = Unit

    suspend fun setPreserveAudio(enabled: Boolean) = Unit

    suspend fun setWorkshopSourceCacheEnabled(enabled: Boolean) = Unit

    suspend fun setWorkshopSourceCacheBudgetGiB(budgetGiB: Int) = Unit

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

    suspend fun setOutputDirectory(
        treeUri: String,
        label: String,
    )

    suspend fun clearOutputDirectory()

    suspend fun setLocalManagementDirectory(
        treeUri: String,
        label: String,
    ) = Unit

    suspend fun clearLocalManagementDirectory() = Unit

    suspend fun setLocalWallpaperViewMode(mode: LocalWallpaperViewMode) = Unit
}

interface LauncherIconController {
    fun setThemedIconEnabled(enabled: Boolean)
}

interface AppUpdateRepository {
    val installedAppInfo: InstalledAppInfo

    /**
     * The latest published release, or `null` when the repository has none published yet.
     * GitHub answers 404 for `releases/latest` in that state; it is not a transport failure.
     */
    suspend fun latestRelease(): AppReleaseInfo?

    suspend fun downloadRelease(
        release: AppReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): String

    fun cancelDownload() = Unit
}

interface SteamAccessRepository {
    val state: StateFlow<SteamAccessState>

    /** Waits for the bounded automatic-route decision before a first network request. */
    suspend fun awaitRouteDecision() = Unit

    fun refresh()
}

interface SteamSessionRepository {
    val session: StateFlow<SteamSessionState>

    /** Begins a refresh-token restore on the repository-owned session scope. */
    fun restorePersistedSession()

    /** Requests an explicit reconnect, joining active recovery and honoring its retry deadline. */
    fun reconnectPersistedSession() = restorePersistedSession()

    /** Re-checks or restores the saved session when the application returns to the foreground. */
    fun onAppForegrounded() = restorePersistedSession()

    /** Records that the process entered the background without clearing the saved session. */
    fun onAppBackgrounded() = Unit

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

    /**
     * Whether an attempt is running right now, or is held to run next.
     *
     * This is the gate itself, not the published phase. The phase reports how the last attempt ended, and the
     * device showed the difference: a restore published FAILED and then kept a live-candidate retry running
     * for six more seconds, in which the phase said nobody was signing in while somebody was. A caller that
     * must not open a second session for the same account asks this instead.
     */
    fun isAttemptInFlight(): Boolean = false
}

data class SteamAppPlaytime(
    val appId: Int,
    val totalMinutes: Int,
    val recentMinutes: Int = 0,
    val lastPlayedEpochSeconds: Long? = null,
)

/** Reads the signed-in account's Steam playtime through the authenticated CM session. */
interface SteamPlaytimeRepository {
    suspend fun getAppPlaytime(appId: Int): SteamAppPlaytime?
}

/** Data-layer port for short-lived Steam content sessions. Feature modules never use it. */
interface SteamContentCredentialProvider {
    suspend fun loadContentCredential(): SteamContentCredential?

    /** Requests persisted-session restoration and returns a credential only after it becomes usable. */
    suspend fun restoreContentCredential(): SteamContentCredential? = loadContentCredential()
}

enum class WorkshopVideoReadKind {
    SEQUENTIAL,
    SEEK,
}

/** Privacy-safe CDN provider family; no hostname, address, URL, path, or credential crosses this boundary. */
enum class WorkshopVideoCdnProvider {
    VALVE,
    STEAM_CHINA,
    HUAWEI_CLOUD,
    BAISHAN_CLOUD,
    TENCENT_CLOUD,
    ALIBABA_CLOUD,
    NETEASE_CLOUD,
    CHINA_NET_CENTER,
    FASTLY,
    EDGECAST,
    SINA_CLOUD,
    OTHER,
}

/** Typed SDK range-stream outcomes that justify replacing the complete playback session. */
enum class WorkshopVideoStreamFailure {
    SESSION_INVALIDATED,
    CDN_ROUTE_EXHAUSTED,
}

/**
 * App-owned stream failure boundary. SDK adapters map their typed failure to [failure] and retain the original
 * throwable as [cause], so playback policy never classifies diagnostic text and diagnostics lose nothing.
 */
class WorkshopVideoStreamFailureException(
    val failure: WorkshopVideoStreamFailure,
    cause: Throwable,
) : IOException("Workshop video stream failed: ${failure.name}", cause)

/** App-owned byte demand; deliberately contains no SDK/AAR types. */
data class WorkshopVideoReadDemand(
    val position: Long,
    val bufferedBytes: Long,
    val kind: WorkshopVideoReadKind,
    val deliveredBytesPerSecond: Long = 0L,
)

/** A seekable video stream backed by Steam Depot chunks instead of a completed download. */
interface WorkshopVideoStreamSession : Closeable {
    val title: String

    val fileName: String

    val contentLength: Long

    val currentCdnProvider: WorkshopVideoCdnProvider?
        get() = null

    /** Bare transport hostname of the last successful CDN read; never includes credentials, a path, or a token. */
    val currentCdnHost: String?
        get() = null

    /** Reports Media3's actual logical-file cursor to the bounded Depot prefetcher. */
    fun updateReadDemand(demand: WorkshopVideoReadDemand) = Unit

    /** Marks one transition into rebuffering for the next rolling-demand update. */
    fun reportPlaybackRebuffer() = Unit

    /** Signals in-flight transport work to stop without waiting for a synchronized read or handle release. */
    fun cancelPendingReads() = Unit

    suspend fun readAt(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): Int

    /**
     * Blocking read seam for synchronous media data sources.
     *
     * Implementations backed by an already-synchronous transport should override this directly. The default
     * preserves the suspend-only contract for other implementations without forcing every caller to know which
     * transport it received.
     */
    fun readAtBlocking(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): Int = runBlocking { readAt(position, destination, destinationOffset, length) }

}

interface WorkshopVideoStreamRepository {
    suspend fun open(request: WorkshopVideoOpenRequest): WorkshopVideoStreamSession

    /** Standalone playback routes have only an item id and resolve public details once after the click. */
    suspend fun open(workshopId: Long): WorkshopVideoStreamSession =
        open(WorkshopVideoOpenRequest(workshopId = workshopId))

    /** Clears decrypted Depot chunks retained only for online playback. */
    suspend fun clearCache(): Long = 0L
}

/** A typed public CM detail result; nullable APIs cannot distinguish these states. */
sealed interface CmPublicDetailResult {
    data class Available(val detail: WorkshopDetail) : CmPublicDetailResult
    data object NotFound : CmPublicDetailResult
    data class Unavailable(val reason: String? = null) : CmPublicDetailResult
}

data class CmPublicDetailsBatch(
    val outcomes: List<Pair<Long, CmPublicDetailResult>>,
) {
    val available: List<WorkshopDetail> = outcomes.mapNotNull { (_, outcome) -> (outcome as? CmPublicDetailResult.Available)?.detail }
    val missingCount: Int = outcomes.count { (_, outcome) -> outcome !is CmPublicDetailResult.Available }
}

/** Public Workshop data over signed-in or anonymous Steam Connection Manager sessions. */
interface SteamUnifiedWorkshopRepository {
    /** Drops process-local list and persona data before a realtime Discover generation. */
    suspend fun invalidateDiscoverCache() = Unit

    /** Returns null only when a CM session cannot be established or the RPC is unavailable. */
    suspend fun browsePublic(query: WorkshopBrowseQuery): WorkshopPage?

    suspend fun getPublicDetail(workshopId: Long): WorkshopDetail?

    suspend fun getPublicDetailResult(workshopId: Long): CmPublicDetailResult =
        getPublicDetail(workshopId)?.let(CmPublicDetailResult::Available)
            ?: CmPublicDetailResult.Unavailable("CM detail result unavailable")

    suspend fun getPublicDetails(workshopIds: List<Long>): List<WorkshopDetail> =
        getPublicDetailsResult(workshopIds).available

    suspend fun getPublicDetailsResult(workshopIds: List<Long>): CmPublicDetailsBatch =
        CmPublicDetailsBatch(workshopIds.distinct().map { id -> id to getPublicDetailResult(id) })

    /** Resolves deduplicated public account identities in one bounded, cached request family. */
    suspend fun resolvePublicProfiles(accountIds: Collection<Long>): Map<Long, SteamPublicProfile>

    /** Returns the ordered direct children of a Workshop collection, or null when CM is unavailable. */
    suspend fun getPublicCollectionChildren(collectionId: Long): List<Long>? = null

    /** Returns null when no signed-in CM session is available for Community RPCs. */
    suspend fun getAuthenticatedComments(
        workshopId: Long,
        start: Int,
        count: Int,
        ownerId: String,
    ): WorkshopCommentPage?
}

interface WorkshopRepository {
    /** Drops process-local enrichment data before a realtime Discover generation. */
    suspend fun invalidateDiscoverCache() = Unit

    /** Primes the shared detail seed used by every entry point before the fresh detail read. */
    suspend fun primeDetailSeed(detail: WorkshopDetail) = Unit

    /** Returns a recent browse/detail value synchronously so detail UI can render before refresh. */
    fun peekDetailSeed(workshopId: Long): WorkshopDetail? = null

    /** Reads a public Wallpaper Engine Workshop page without requiring a Steam Web API key. */
    suspend fun browse(query: WorkshopBrowseQuery): WorkshopPage

    /** Reads a single public Workshop item and its metadata. */
    suspend fun getDetail(workshopId: Long): WorkshopDetail

    /** Resolves multiple public Workshop records in one transport request when supported. */
    suspend fun getDetails(workshopIds: List<Long>): List<WorkshopDetail> =
        workshopIds.distinct().map { getDetail(it) }

    /** Reads one page of public comments for a Workshop item. */
    suspend fun getComments(
        workshopId: Long,
        start: Int = 0,
        count: Int = 20,
        ownerId: String? = null,
    ): WorkshopCommentPage
}

data class AccountWorkshopCollectionChange(
    val accountId: Long,
    val collection: AccountWorkshopCollection,
    val workshopId: Long,
)

data class AccountWorkshopCounts(
    val accountName: String? = null,
    val subscriptions: Int? = null,
    val favorites: Int? = null,
    val isLoading: Boolean = false,
    val isValid: Boolean = false,
)

/**
 * Authenticated Steam Workshop operations. This is deliberately separate from the public
 * browser so public discovery remains usable without a Steam account.
 */
interface AccountWorkshopRepository {
    /** Process-scoped counts refreshed only by application startup or an authenticated-account change. */
    val collectionCounts: StateFlow<AccountWorkshopCounts>

    suspend fun refreshCollectionCounts(accountName: String) = Unit

    /** Numeric identity of the currently authenticated Steam account, when the SDK session is ready. */
    fun activeAccountId(): Long? = null

    fun invalidateCollectionCounts() = Unit

    /** Emits the stable ID after a confirmed account collection mutation. */
    val collectionChanges: Flow<AccountWorkshopCollectionChange>
        get() = kotlinx.coroutines.flow.emptyFlow()

    /** Reads one current-generation page; personal collection pages are never restored from a list cache. */
    suspend fun browseCollection(query: AccountWorkshopQuery): WorkshopPage

    /** Starts a new cold collection generation and reads the requested page from Steam. */
    suspend fun refreshCollection(query: AccountWorkshopQuery): WorkshopPage = browseCollection(query)

    /** Resolves the public Steam display name for a Workshop item's creator on demand. */
    suspend fun resolveAuthorDisplayName(workshopId: Long): String?

    /** Resolves a known creator account without repeating the Workshop details request. */
    suspend fun resolveAuthorDisplayNameByAccountId(accountId: String): String? = null

    suspend fun getInteraction(workshopId: Long): WorkshopInteraction

    suspend fun setSubscribed(
        workshopId: Long,
        subscribed: Boolean,
        summary: WorkshopSummary? = null,
    ): WorkshopInteraction

    /** Stable, non-sensitive account identity published by the authenticated-session owner. */
    fun activeAccountName(): String? = null

    /** Resolves and pins the signed-in account on the repository IO path before mutating. */
    suspend fun setSubscribedForCurrentAccount(
        expectedAccountName: String,
        workshopId: Long,
        subscribed: Boolean,
        summary: WorkshopSummary? = null,
    ): WorkshopInteraction = setSubscribed(workshopId, subscribed, summary)

    suspend fun setSubscribedForAccount(
        expectedAccountId: Long,
        workshopId: Long,
        subscribed: Boolean,
        summary: WorkshopSummary? = null,
    ): WorkshopInteraction {
        check(activeAccountId() == expectedAccountId) { "The signed-in Steam account changed before the mutation" }
        return setSubscribed(workshopId, subscribed, summary)
    }

    suspend fun setFavorited(
        workshopId: Long,
        favorited: Boolean,
        summary: WorkshopSummary? = null,
    ): WorkshopInteraction

    /** Resolves and pins the signed-in account on the repository IO path before mutating. */
    suspend fun setFavoritedForCurrentAccount(
        expectedAccountName: String,
        workshopId: Long,
        favorited: Boolean,
        summary: WorkshopSummary? = null,
    ): WorkshopInteraction = setFavorited(workshopId, favorited, summary)

    suspend fun setFavoritedForAccount(
        expectedAccountId: Long,
        workshopId: Long,
        favorited: Boolean,
        summary: WorkshopSummary? = null,
    ): WorkshopInteraction {
        check(activeAccountId() == expectedAccountId) { "The signed-in Steam account changed before the mutation" }
        return setFavorited(workshopId, favorited, summary)
    }

    suspend fun postComment(
        workshopId: Long,
        ownerId: String,
        text: String,
    )
}

/** The single deep download intent, query and administration module used by product callers. */
interface WorkshopDownloads {
    val tasks: Flow<List<DownloadTask>>

    suspend fun find(taskId: String): DownloadTask?

    suspend fun enqueue(request: DownloadRequest): DownloadEnqueueResult

    /** Persists the exact conversion plan selected for this task before converter execution. */
    suspend fun persistConversionPlan(
        taskId: String,
        planJson: String?,
        planVersion: Int = 1,
    )

    /** Persists the converter report without replacing the task's preset or plan snapshot. */
    suspend fun persistConversionReport(
        taskId: String,
        reportJson: String?,
    )

    suspend fun prewarm(workshopId: Long)

    suspend fun requestAction(
        taskId: String,
        action: DownloadAction,
    )

    /** Persists the export option before accepting the conversion intent under one mutation authority. */
    suspend fun requestExport(
        taskId: String,
        format: ExportFormat,
    )

    /** Reuses a complete retained Scene source to atomically replace its successful MPKG output. */
    suspend fun requestReconversion(
        taskId: String,
        preset: MpkgConversionPreset,
        preserveAudio: Boolean,
    )

    suspend fun reorder(taskIds: List<String>)

    suspend fun clearFinishedHistory(): Int

    suspend fun clearCompletedHistory(): Int

    suspend fun retryFailedTasks(): Int
}

interface LocalWallpaperRepository {
    fun scan(): Flow<LocalWallpaperScanSnapshot>

    suspend fun setFavorite(
        resourceId: String,
        favorite: Boolean,
    )

    suspend fun replaceTags(
        resourceId: String,
        tags: Set<String>,
    )

    suspend fun renameTag(
        oldTag: String,
        newTag: String,
    )

    suspend fun deleteTag(tag: String)

    suspend fun markImportRequested(
        resourceId: String,
        requestedAt: Long,
    )

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

/** Writes redacted diagnostic content to a user-selected document without exposing Android APIs to features. */
interface DiagnosticExportRepository {
    suspend fun exportTo(destinationUri: String)
}
