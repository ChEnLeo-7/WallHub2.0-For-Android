package com.wallhub.android.feature.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.AppReleaseInfo
import com.wallhub.android.core.model.AppUpdateRepository
import com.wallhub.android.core.model.InstalledAppInfo
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.DEFAULT_STEAM_ACCESS_DOH_ENDPOINTS
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.LauncherIconController
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.STEAM_ACCESS_DOH_ENDPOINT_LIMIT
import com.wallhub.android.core.model.SteamAccessPhase
import com.wallhub.android.core.model.SteamAccessRepository
import com.wallhub.android.core.model.SteamAccessState
import com.wallhub.android.core.model.SteamWorkshopDataSource
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.isSupportedDownloadProxyUrl
import com.wallhub.android.core.model.normalizeSteamAccessDohEndpoint
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.designsystem.WallHubPageScaffold
import com.wallhub.android.core.designsystem.WallHubAnimatedSelectionCheck
import com.wallhub.android.core.designsystem.WallHubSurfaceCard
import com.wallhub.android.core.designsystem.WallHubIcons as Icons
import com.wallhub.android.core.designsystem.text
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class DiagnosticExportUiState(
    val isExporting: Boolean = false,
    val message: String? = null,
    val isFailure: Boolean = false,
)

enum class AppUpdatePhase {
    IDLE,
    CHECKING,
    AVAILABLE,
    UP_TO_DATE,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
}

data class AppUpdateUiState(
    val installed: InstalledAppInfo,
    val phase: AppUpdatePhase = AppUpdatePhase.IDLE,
    val release: AppReleaseInfo? = null,
    val downloadedApkPath: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String? = null,
) {
    val progress: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
}

private enum class SettingsCategory(
    val labelZh: String,
    val labelEn: String,
    val descriptionZh: String,
    val descriptionEn: String,
    val icon: ImageVector,
) {
    BASIC(
        labelZh = "基本设置",
        labelEn = "Basic settings",
        descriptionZh = "内容访问、应用信息、更新与诊断",
        descriptionEn = "Content access, app information, updates, and diagnostics",
        icon = Icons.Outlined.Tune,
    ),
    DOWNLOAD(
        labelZh = "下载",
        labelEn = "Downloads",
        descriptionZh = "导出目录、下载并发与代理",
        descriptionEn = "Export directory, concurrency, and proxy",
        icon = Icons.Outlined.Download,
    ),
    STEAM(
        labelZh = "Steam",
        labelEn = "Steam",
        descriptionZh = "账户、服务访问与创意工坊数据源",
        descriptionEn = "Account, service access, and Workshop data source",
        icon = Icons.Outlined.PersonOutline,
    ),
    APPEARANCE(
        labelZh = "外观",
        labelEn = "Appearance",
        descriptionZh = "语言、主题、强调色与发现页偏好",
        descriptionEn = "Language, theme, accent color, and Discover preferences",
        icon = Icons.Outlined.Palette,
    ),
    EXPERIMENTAL(
        labelZh = "实验功能",
        labelEn = "Experimental",
        descriptionZh = "在线分块播放与系统权限",
        descriptionEn = "Online chunk streaming and system permissions",
        icon = Icons.Outlined.Notifications,
    ),
    ;

    fun label(language: AppLanguage): String = if (language == AppLanguage.EN) labelEn else labelZh

    fun description(language: AppLanguage): String =
        if (language == AppLanguage.EN) descriptionEn else descriptionZh
}

private enum class SteamStreamCachePreset(val limitMb: Int?) {
    MB_512(512),
    GB_1(1024),
    GB_2(2048),
    GB_5(5120),
    GB_8(8192),
    CUSTOM(null),
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val launcherIconController: LauncherIconController,
    private val steamSessionRepository: SteamSessionRepository,
    private val diagnosticRepository: DiagnosticRepository,
    private val steamAccessRepository: SteamAccessRepository,
    private val appUpdateRepository: AppUpdateRepository,
) : ViewModel() {
    private val mutableDiagnosticExportState = MutableStateFlow(DiagnosticExportUiState())
    private val mutableAppUpdateState = MutableStateFlow(
        AppUpdateUiState(installed = appUpdateRepository.installedAppInfo),
    )
    private var appUpdateJob: Job? = null

    val preferences: StateFlow<AppPreferences> = settingsRepository.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppPreferences(),
    )

    val session: StateFlow<SteamSessionState> = steamSessionRepository.session.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SteamSessionState(),
    )

    val diagnosticExportState: StateFlow<DiagnosticExportUiState> =
        mutableDiagnosticExportState.asStateFlow()

    val appUpdateState: StateFlow<AppUpdateUiState> = mutableAppUpdateState.asStateFlow()

    val steamAccessState: StateFlow<SteamAccessState> = steamAccessRepository.state

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch { settingsRepository.setTheme(theme) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { settingsRepository.setLanguage(language) }
    }

    fun setAccent(accent: AccentPreference, customColor: String? = null) {
        viewModelScope.launch {
            settingsRepository.setSystemMonetEnabled(false)
            settingsRepository.setAccent(accent, customColor)
        }
    }

    fun setSystemMonetEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled && preferences.value.accent == AccentPreference.MONET) {
                settingsRepository.setAccent(AccentPreference.DEFAULT)
            }
            settingsRepository.setSystemMonetEnabled(enabled)
        }
    }

    fun setThemedLauncherIconEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setThemedLauncherIconEnabled(enabled)
            runCatching { launcherIconController.setThemedIconEnabled(enabled) }
        }
    }

    fun setHomePreferences(
        pageSize: Int,
        columns: Int,
        multiSelect: Boolean,
        cardAction: HomeCardAction,
        matureContentEnabled: Boolean,
    ) {
        viewModelScope.launch {
            settingsRepository.setHomePreferences(
                pageSize = pageSize,
                columns = columns,
                multiSelect = multiSelect,
                cardAction = cardAction,
                matureContentEnabled = matureContentEnabled,
            )
        }
    }

    fun setHomePaginationMode(mode: HomePaginationMode) {
        viewModelScope.launch { settingsRepository.setHomePaginationMode(mode) }
    }

    fun setDownloadPreferences(
        maxConcurrentDownloads: Int,
        chunkDownloadConcurrency: Int,
        proxyUrl: String,
        mediaCacheLimitMb: Int,
    ) {
        viewModelScope.launch {
            settingsRepository.setDownloadPreferences(
                maxConcurrentDownloads = maxConcurrentDownloads,
                chunkDownloadConcurrency = chunkDownloadConcurrency,
                proxyUrl = proxyUrl,
                mediaCacheLimitMb = mediaCacheLimitMb,
            )
        }
    }

    fun setOnlineStreamCacheLimitMb(limitMb: Int) {
        viewModelScope.launch { settingsRepository.setOnlineStreamCacheLimitMb(limitMb) }
    }

    fun setDownloadProxyEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDownloadProxyEnabled(enabled) }
    }

    fun setSteamAccessEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSteamAccessEnabled(enabled) }
    }

    fun setSteamAccessDohEndpoints(
        endpoints: List<String>,
        disabledEndpoints: Set<String>,
    ) {
        viewModelScope.launch {
            settingsRepository.setSteamAccessDohEndpoints(endpoints, disabledEndpoints)
        }
    }

    fun refreshSteamAccess() {
        steamAccessRepository.refresh()
    }

    fun setSteamApiKey(apiKey: String) {
        viewModelScope.launch { settingsRepository.setSteamApiKey(apiKey) }
    }

    fun setSteamWorkshopDataSource(source: SteamWorkshopDataSource) {
        viewModelScope.launch { settingsRepository.setSteamWorkshopDataSource(source) }
    }

    fun setOnlineChunkPlaybackEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setOnlineChunkPlaybackEnabled(enabled) }
    }

    fun setOutputDirectory(treeUri: String, label: String) {
        viewModelScope.launch { settingsRepository.setOutputDirectory(treeUri, label) }
    }

    fun clearOutputDirectory() {
        viewModelScope.launch { settingsRepository.clearOutputDirectory() }
    }

    fun logoutSteam() {
        steamSessionRepository.logout()
    }

    fun checkForAppUpdate() {
        if (appUpdateJob != null) return
        mutableAppUpdateState.value = mutableAppUpdateState.value.copy(
            phase = AppUpdatePhase.CHECKING,
            message = null,
            downloadedApkPath = null,
        )
        appUpdateJob = viewModelScope.launch {
            try {
                val release = appUpdateRepository.latestRelease()
                mutableAppUpdateState.value = mutableAppUpdateState.value.copy(
                    phase = if (release.isNewer) AppUpdatePhase.AVAILABLE else AppUpdatePhase.UP_TO_DATE,
                    release = release,
                    downloadedBytes = 0L,
                    totalBytes = release.assetSizeBytes,
                    message = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableAppUpdateState.value = mutableAppUpdateState.value.copy(
                    phase = AppUpdatePhase.FAILED,
                    message = error.message ?: "无法检查 GitHub Release",
                )
            } finally {
                appUpdateJob = null
            }
        }
    }

    fun downloadLatestRelease() {
        val release = mutableAppUpdateState.value.release ?: return
        if (appUpdateJob != null) return
        mutableAppUpdateState.value = mutableAppUpdateState.value.copy(
            phase = AppUpdatePhase.DOWNLOADING,
            downloadedApkPath = null,
            downloadedBytes = 0L,
            totalBytes = release.assetSizeBytes,
            message = null,
        )
        appUpdateJob = viewModelScope.launch {
            try {
                val path = appUpdateRepository.downloadRelease(release) { downloaded, total ->
                    if (mutableAppUpdateState.value.phase == AppUpdatePhase.DOWNLOADING) {
                        mutableAppUpdateState.value = mutableAppUpdateState.value.copy(
                            downloadedBytes = downloaded,
                            totalBytes = total,
                        )
                    }
                }
                mutableAppUpdateState.value = mutableAppUpdateState.value.copy(
                    phase = AppUpdatePhase.DOWNLOADED,
                    downloadedApkPath = path,
                    downloadedBytes = release.assetSizeBytes,
                    totalBytes = release.assetSizeBytes,
                    message = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableAppUpdateState.value = mutableAppUpdateState.value.copy(
                    phase = AppUpdatePhase.FAILED,
                    downloadedApkPath = null,
                    message = error.message ?: "Release APK 下载或校验失败",
                )
            } finally {
                appUpdateJob = null
            }
        }
    }

    fun cancelAppUpdateDownload() {
        val current = mutableAppUpdateState.value
        val release = current.release ?: return
        if (current.phase != AppUpdatePhase.DOWNLOADING) return
        appUpdateRepository.cancelDownload()
        appUpdateJob?.cancel()
        mutableAppUpdateState.value = current.copy(
            phase = if (release.isNewer) AppUpdatePhase.AVAILABLE else AppUpdatePhase.UP_TO_DATE,
            downloadedApkPath = null,
            downloadedBytes = 0L,
            totalBytes = release.assetSizeBytes,
            message = null,
        )
    }

    override fun onCleared() {
        appUpdateRepository.cancelDownload()
        super.onCleared()
    }

    fun reportInstallerError(message: String) {
        mutableAppUpdateState.value = mutableAppUpdateState.value.copy(
            phase = AppUpdatePhase.FAILED,
            message = message,
        )
    }

    fun exportDiagnostics(
        contentResolver: ContentResolver,
        destination: Uri,
    ) {
        mutableDiagnosticExportState.value = DiagnosticExportUiState(isExporting = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val content = diagnosticRepository.exportRedactedText()
                    val output = contentResolver.openOutputStream(destination, "wt")
                        ?: error("无法创建诊断日志文件")
                    output.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(content) }
                }
            }.onSuccess {
                mutableDiagnosticExportState.value = DiagnosticExportUiState(
                    message = "诊断日志已导出",
                )
            }.onFailure { error ->
                mutableDiagnosticExportState.value = DiagnosticExportUiState(
                    message = "导出失败：${error.javaClass.simpleName}",
                    isFailure = true,
                )
            }
        }
    }
}

@Composable
fun SettingsRoute(
    onOpenSteamLogin: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val preferences by viewModel.preferences.collectAsState()
    val session by viewModel.session.collectAsState()
    val diagnosticExportState by viewModel.diagnosticExportState.collectAsState()
    val appUpdateState by viewModel.appUpdateState.collectAsState()
    val steamAccessState by viewModel.steamAccessState.collectAsState()
    val outputDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
                .onSuccess {
                    viewModel.setOutputDirectory(
                        treeUri = treeUri.toString(),
                        label = treeUri.lastPathSegment
                            ?.substringAfterLast(':')
                            ?.ifBlank { null }
                            ?: "已选择导出目录",
                    )
                }
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }
    val diagnosticExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { documentUri ->
        if (documentUri != null) {
            viewModel.exportDiagnostics(context.contentResolver, documentUri)
        }
    }
    var pendingUpdateApkPath by rememberSaveable { mutableStateOf<String?>(null) }
    val launchSystemInstaller: (String) -> Unit = { path ->
        runCatching {
            val apk = File(path)
            check(apk.isFile) { "已验证的安装包不存在" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure { error ->
            viewModel.reportInstallerError(error.message ?: "无法打开 Android 系统安装器")
        }
    }
    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val path = pendingUpdateApkPath
        pendingUpdateApkPath = null
        if (
            path != null &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls())
        ) {
            launchSystemInstaller(path)
        } else if (path != null) {
            viewModel.reportInstallerError("需要允许 WallHub 安装未知应用后才能继续")
        }
    }
    val requestReleaseInstall: (String) -> Unit = { path ->
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            pendingUpdateApkPath = path
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
            runCatching { unknownSourcesLauncher.launch(intent) }
                .onFailure { error ->
                    pendingUpdateApkPath = null
                    viewModel.reportInstallerError(error.message ?: "无法打开安装未知应用设置")
                }
        } else {
            launchSystemInstaller(path)
        }
    }
    SettingsScreen(
        preferences = preferences,
        onThemePreferenceChange = viewModel::setTheme,
        onLanguageChange = viewModel::setLanguage,
        onAccentChange = viewModel::setAccent,
        onSystemMonetEnabledChange = viewModel::setSystemMonetEnabled,
        onThemedLauncherIconEnabledChange = viewModel::setThemedLauncherIconEnabled,
        onHomePreferencesChange = viewModel::setHomePreferences,
        onHomePaginationModeChange = viewModel::setHomePaginationMode,
        onDownloadPreferencesChange = viewModel::setDownloadPreferences,
        onDownloadProxyEnabledChange = viewModel::setDownloadProxyEnabled,
        onOnlineStreamCacheLimitChange = viewModel::setOnlineStreamCacheLimitMb,
        onSteamApiKeyChange = viewModel::setSteamApiKey,
        onSteamWorkshopDataSourceChange = viewModel::setSteamWorkshopDataSource,
        onOnlineChunkPlaybackEnabledChange = viewModel::setOnlineChunkPlaybackEnabled,
        steamAccessState = steamAccessState,
        onSteamAccessEnabledChange = viewModel::setSteamAccessEnabled,
        onSteamAccessDohEndpointsChange = viewModel::setSteamAccessDohEndpoints,
        onRefreshSteamAccess = viewModel::refreshSteamAccess,
        session = session,
        onOpenSteamLogin = onOpenSteamLogin,
        onLogoutSteam = viewModel::logoutSteam,
        onSelectOutputDirectory = { outputDirectoryLauncher.launch(null) },
        onClearOutputDirectory = viewModel::clearOutputDirectory,
        diagnosticExportState = diagnosticExportState,
        appUpdateState = appUpdateState,
        onCheckForAppUpdate = viewModel::checkForAppUpdate,
        onDownloadLatestRelease = viewModel::downloadLatestRelease,
        onCancelAppUpdateDownload = viewModel::cancelAppUpdateDownload,
        onInstallDownloadedRelease = requestReleaseInstall,
        onExportDiagnostics = {
            diagnosticExportLauncher.launch("wallhub-diagnostics-${System.currentTimeMillis()}.txt")
        },
        onRequestNotifications = {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    onThemePreferenceChange: (ThemePreference) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onAccentChange: (AccentPreference, String?) -> Unit,
    onSystemMonetEnabledChange: (Boolean) -> Unit,
    onThemedLauncherIconEnabledChange: (Boolean) -> Unit,
    onHomePreferencesChange: (Int, Int, Boolean, HomeCardAction, Boolean) -> Unit,
    onHomePaginationModeChange: (HomePaginationMode) -> Unit,
    onDownloadPreferencesChange: (Int, Int, String, Int) -> Unit,
    onDownloadProxyEnabledChange: (Boolean) -> Unit,
    onOnlineStreamCacheLimitChange: (Int) -> Unit,
    onSteamApiKeyChange: (String) -> Unit,
    onSteamWorkshopDataSourceChange: (SteamWorkshopDataSource) -> Unit,
    onOnlineChunkPlaybackEnabledChange: (Boolean) -> Unit,
    steamAccessState: SteamAccessState,
    onSteamAccessEnabledChange: (Boolean) -> Unit,
    onSteamAccessDohEndpointsChange: (List<String>, Set<String>) -> Unit,
    onRefreshSteamAccess: () -> Unit,
    session: SteamSessionState,
    onOpenSteamLogin: () -> Unit,
    onLogoutSteam: () -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onClearOutputDirectory: () -> Unit,
    diagnosticExportState: DiagnosticExportUiState,
    appUpdateState: AppUpdateUiState,
    onCheckForAppUpdate: () -> Unit,
    onDownloadLatestRelease: () -> Unit,
    onCancelAppUpdateDownload: () -> Unit,
    onInstallDownloadedRelease: (String) -> Unit,
    onExportDiagnostics: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    fun text(zh: String, en: String): String = if (preferences.language == AppLanguage.EN) en else zh
    val context = LocalContext.current
    var selectedCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedCategory = selectedCategoryName
        ?.let { categoryName -> SettingsCategory.entries.firstOrNull { it.name == categoryName } }
    val availableAccents = AccentPreference.entries.filter { accent ->
        accent != AccentPreference.MONET
    }
    var customAccentColor by remember(preferences.customAccentColor) {
        mutableStateOf(preferences.customAccentColor)
    }
    var proxyUrl by remember(preferences.downloadProxyUrl) {
        mutableStateOf(preferences.downloadProxyUrl)
    }
    var steamApiKey by remember(preferences.steamApiKey) {
        mutableStateOf(preferences.steamApiKey)
    }
    fun saveHomePreferences(
        pageSize: Int = preferences.homePageSize,
        columns: Int = preferences.homeColumns,
        multiSelect: Boolean = preferences.homeFilterMultiSelect,
        cardAction: HomeCardAction = preferences.homeCardAction,
        matureContentEnabled: Boolean = preferences.matureContentEnabled,
    ) {
        onHomePreferencesChange(pageSize, columns, multiSelect, cardAction, matureContentEnabled)
    }
    BackHandler(enabled = selectedCategory != null) {
        selectedCategoryName = null
    }
    AnimatedContent(
        targetState = selectedCategory,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val direction = if (targetState == null) -1 else 1
            val enterOffsetDivisor = if (targetState == null) {
                SETTINGS_PAGE_EXIT_OFFSET_DIVISOR
            } else {
                SETTINGS_PAGE_ENTER_OFFSET_DIVISOR
            }
            val exitOffsetDivisor = if (targetState == null) {
                SETTINGS_PAGE_ENTER_OFFSET_DIVISOR
            } else {
                SETTINGS_PAGE_EXIT_OFFSET_DIVISOR
            }
            (fadeIn(
                animationSpec = tween(
                    durationMillis = SETTINGS_PAGE_ENTER_DURATION_MS,
                    easing = SETTINGS_PAGE_EASING,
                ),
            ) + slideInHorizontally(
                initialOffsetX = { width -> direction * width / enterOffsetDivisor },
                animationSpec = tween(
                    durationMillis = SETTINGS_PAGE_ENTER_DURATION_MS,
                    easing = SETTINGS_PAGE_EASING,
                ),
            )) togetherWith (fadeOut(
                animationSpec = tween(
                    durationMillis = SETTINGS_PAGE_EXIT_DURATION_MS,
                    easing = SETTINGS_PAGE_EASING,
                ),
            ) + slideOutHorizontally(
                targetOffsetX = { width -> -direction * width / exitOffsetDivisor },
                animationSpec = tween(
                    durationMillis = SETTINGS_PAGE_EXIT_DURATION_MS,
                    easing = SETTINGS_PAGE_EASING,
                ),
            ))
        },
        contentKey = { category -> category?.name ?: SETTINGS_CATEGORY_INDEX_KEY },
        label = "SettingsCategoryPage",
    ) { displayedCategory ->
        WallHubPageScaffold(
            title = text("设置", "Settings"),
            topBarContent = displayedCategory?.let { category ->
                {
                    TopAppBar(
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = category.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(category.label(preferences.language))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                        navigationIcon = {
                    IconButton(onClick = { selectedCategoryName = null }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = text("返回设置", "Back to Settings"),
                        )
                    }
                        },
                    )
                }
            },
        ) { padding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                val horizontalPadding = if (maxWidth >= SETTINGS_MEDIUM_WIDTH) 24.dp else 16.dp
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = SETTINGS_CONTENT_MAX_WIDTH)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = horizontalPadding, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
            if (displayedCategory == null) {
                SettingsCategoryIndex(
                    language = preferences.language,
                    onOpenCategory = { category -> selectedCategoryName = category.name },
                )
            } else {
                if (displayedCategory == SettingsCategory.STEAM) {
                    SteamSettingsContent(
                        language = preferences.language,
                        session = session,
                        steamAccessEnabled = preferences.steamAccessEnabled,
                        steamAccessState = steamAccessState,
                        steamAccessDohEndpoints = preferences.steamAccessDohEndpoints,
                        steamAccessDisabledDohEndpoints = preferences.steamAccessDisabledDohEndpoints,
                        steamWorkshopDataSource = preferences.steamWorkshopDataSource,
                        onSteamAccessEnabledChange = onSteamAccessEnabledChange,
                        onSteamAccessDohEndpointsChange = onSteamAccessDohEndpointsChange,
                        onSteamWorkshopDataSourceChange = onSteamWorkshopDataSourceChange,
                        onRefreshSteamAccess = onRefreshSteamAccess,
                        savedApiKey = preferences.steamApiKey,
                        apiKey = steamApiKey,
                        onApiKeyChanged = { steamApiKey = it },
                        onSaveApiKey = { onSteamApiKeyChange(steamApiKey) },
                        onOpenApiKeyPage = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(STEAM_API_KEY_URL),
                            )
                            runCatching { context.startActivity(intent) }
                        },
                        onOpenSteamLogin = onOpenSteamLogin,
                        onLogoutSteam = onLogoutSteam,
                    )
                }
                if (displayedCategory == SettingsCategory.BASIC) {
                    BasicSettingsContent(
                        language = preferences.language,
                        matureContentEnabled = preferences.matureContentEnabled,
                        diagnosticExportState = diagnosticExportState,
                        appUpdateState = appUpdateState,
                        onMatureContentEnabledChange = { enabled ->
                            saveHomePreferences(matureContentEnabled = enabled)
                        },
                        onCheckForAppUpdate = onCheckForAppUpdate,
                        onDownloadLatestRelease = onDownloadLatestRelease,
                        onCancelAppUpdateDownload = onCancelAppUpdateDownload,
                        onInstallDownloadedRelease = onInstallDownloadedRelease,
                        onExportDiagnostics = onExportDiagnostics,
                    )
                }
                if (displayedCategory == SettingsCategory.DOWNLOAD) {
                    DownloadSettingsContent(
                        preferences = preferences,
                        proxyUrl = proxyUrl,
                        onProxyUrlChanged = { proxyUrl = it },
                        onSelectOutputDirectory = onSelectOutputDirectory,
                        onClearOutputDirectory = onClearOutputDirectory,
                        onDownloadPreferencesChange = onDownloadPreferencesChange,
                        onDownloadProxyEnabledChange = onDownloadProxyEnabledChange,
                    )
                }
                if (displayedCategory == SettingsCategory.APPEARANCE) {
                    AppearanceSettingsContent(
                        preferences = preferences,
                        availableAccents = availableAccents,
                        customAccentColor = customAccentColor,
                        onCustomAccentColorChanged = { customAccentColor = it },
                        onLanguageChange = onLanguageChange,
                        onThemePreferenceChange = onThemePreferenceChange,
                        onAccentChange = onAccentChange,
                        onSystemMonetEnabledChange = onSystemMonetEnabledChange,
                        onThemedLauncherIconEnabledChange = onThemedLauncherIconEnabledChange,
                        onHomePreferencesChange = onHomePreferencesChange,
                        onHomePaginationModeChange = onHomePaginationModeChange,
                    )
                }
                if (displayedCategory == SettingsCategory.EXPERIMENTAL) {
                    ExperimentalSettingsContent(
                        preferences = preferences,
                        onOnlineChunkPlaybackEnabledChange = onOnlineChunkPlaybackEnabledChange,
                        onOnlineStreamCacheLimitChange = onOnlineStreamCacheLimitChange,
                        onRequestNotifications = onRequestNotifications,
                    )
                }
            }
        }
    }
}
}
}

@Composable
private fun SettingsListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = headlineContent,
        modifier = modifier.heightIn(min = SETTINGS_ITEM_MIN_HEIGHT),
        supportingContent = supportingContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSurface,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
            leadingIconColor = MaterialTheme.colorScheme.primary,
            trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun SettingsSectionSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    WallHubSurfaceCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        content = content,
    )
}

@Composable
internal fun SettingsFilledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = MaterialTheme.shapes.large,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                supportingText?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SettingsSectionSurface(modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

@Composable
private fun BasicSettingsContent(
    language: AppLanguage,
    matureContentEnabled: Boolean,
    diagnosticExportState: DiagnosticExportUiState,
    appUpdateState: AppUpdateUiState,
    onMatureContentEnabledChange: (Boolean) -> Unit,
    onCheckForAppUpdate: () -> Unit,
    onDownloadLatestRelease: () -> Unit,
    onCancelAppUpdateDownload: () -> Unit,
    onInstallDownloadedRelease: (String) -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val installed = appUpdateState.installed
    val release = appUpdateState.release
    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    SettingsSection(
        title = language.text("内容访问", "Content access"),
        supportingText = language.text(
            "管理可能不适合所有用户的内容",
            "Manage content that may not be suitable for everyone",
        ),
        icon = Icons.Outlined.Visibility,
    ) {
        SettingsSwitchRow(
            title = language.text("NSFW 内容", "NSFW content"),
            supportingText = language.text(
                "开启前请确认你已了解这类内容的含义",
                "Enable only if you understand what this content category includes",
            ),
            checked = matureContentEnabled,
            onCheckedChange = onMatureContentEnabledChange,
        )
    }

    SettingsSection(
        title = language.text("关于 WallHub", "About WallHub"),
        supportingText = language.text(
            "应用身份、版本与社区入口",
            "App identity, version, and community links",
        ),
        icon = Icons.Outlined.Info,
    ) {
        SettingsListItem(
            headlineContent = { Text(installed.appName) },
            supportingContent = {
                Text(
                    language.text(
                        "版本 ${installed.versionName} (${installed.versionCode})\n${installed.packageName}",
                        "Version ${installed.versionName} (${installed.versionCode})\n${installed.packageName}",
                    ),
                )
            },
        )
        SettingsItemDivider()
        SettingsExternalLinkRow(
            title = language.text("作者", "Author"),
            value = "CHENLEO_7",
            onClick = { openUrl(AUTHOR_GITHUB_URL) },
        )
        SettingsItemDivider()
        SettingsListItem(
            headlineContent = { Text(language.text("QQ 交流群", "QQ community group")) },
            supportingContent = { Text(QQ_GROUP_NUMBER) },
            trailingContent = {
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("WallHub QQ", QQ_GROUP_NUMBER))
                        Toast.makeText(
                            context,
                            language.text("群号已复制", "Group number copied"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = language.text("复制群号", "Copy group number"),
                    )
                }
            },
        )
        SettingsItemDivider()
        SettingsExternalLinkRow(
            title = language.text("GitHub 仓库", "GitHub repository"),
            value = GITHUB_REPOSITORY_LABEL,
            onClick = { openUrl(GITHUB_REPOSITORY_URL) },
        )
    }

    SettingsSection(
        title = language.text("贡献人员", "Contributors"),
        supportingText = language.text(
            "感谢参与 WallHub 开发与改进的贡献者",
            "Thanks to the people who help build and improve WallHub",
        ),
        icon = Icons.Outlined.PersonOutline,
    ) {
        WALLHUB_CONTRIBUTORS.forEachIndexed { index, contributor ->
            if (index > 0) SettingsItemDivider()
            SettingsExternalLinkRow(
                title = contributor.first,
                value = contributor.second.removePrefix("https://github.com/"),
                onClick = { openUrl(contributor.second) },
            )
        }
    }

    SettingsSection(
        title = language.text("版本更新", "Version update"),
        supportingText = language.text(
            "从官方 GitHub Release 获取经过校验的 universal APK",
            "Get a verified universal APK from the official GitHub Release",
        ),
        icon = Icons.Outlined.Download,
    ) {
        SettingsListItem(
            headlineContent = {
                Text(
                    when (appUpdateState.phase) {
                        AppUpdatePhase.IDLE -> language.text("尚未检查更新", "Not checked yet")
                        AppUpdatePhase.CHECKING -> language.text("正在检查更新…", "Checking for updates…")
                        AppUpdatePhase.AVAILABLE -> language.text(
                            "发现新版本 ${release?.versionName.orEmpty()}",
                            "Version ${release?.versionName.orEmpty()} is available",
                        )
                        AppUpdatePhase.UP_TO_DATE -> language.text("当前已是最新版", "WallHub is up to date")
                        AppUpdatePhase.DOWNLOADING -> language.text(
                            "正在下载 ${release?.assetName.orEmpty()}",
                            "Downloading ${release?.assetName.orEmpty()}",
                        )
                        AppUpdatePhase.DOWNLOADED -> language.text(
                            "安装包已下载并通过校验",
                            "APK downloaded and verified",
                        )
                        AppUpdatePhase.FAILED -> language.text("更新操作失败", "Update operation failed")
                    },
                )
            },
            supportingContent = {
                Text(
                    release?.let {
                        language.text(
                            "最新 ${it.versionName} · ${formatUpdateSize(it.assetSizeBytes)} · ${it.publishedAt.take(10)}",
                            "Latest ${it.versionName} · ${formatUpdateSize(it.assetSizeBytes)} · ${it.publishedAt.take(10)}",
                        )
                    } ?: language.text(
                        "当前 ${installed.versionName} (${installed.versionCode})",
                        "Current ${installed.versionName} (${installed.versionCode})",
                    ),
                )
            },
        )
        if (appUpdateState.phase == AppUpdatePhase.DOWNLOADING) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LinearProgressIndicator(
                    progress = { appUpdateState.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${formatUpdateSize(appUpdateState.downloadedBytes)} / " +
                        formatUpdateSize(appUpdateState.totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        release?.notes?.trim()?.takeIf(String::isNotEmpty)?.let { notes ->
            SettingsItemDivider()
            SettingsListItem(
                headlineContent = { Text(language.text("Release 说明", "Release notes")) },
                supportingContent = {
                    Text(
                        text = notes,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    IconButton(onClick = { openUrl(release.htmlUrl) }) {
                        Icon(
                            imageVector = Icons.Outlined.OpenInNew,
                            contentDescription = language.text("打开 Release", "Open Release"),
                        )
                    }
                },
            )
        }
        SettingsActionArea {
            OutlinedButton(
                onClick = onCheckForAppUpdate,
                enabled = appUpdateState.phase !in setOf(
                    AppUpdatePhase.CHECKING,
                    AppUpdatePhase.DOWNLOADING,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null)
                Text(
                    text = language.text("检查更新", "Check for updates"),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (release != null) {
                TextButton(
                    onClick = { openUrl(release.htmlUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(imageVector = Icons.Outlined.OpenInNew, contentDescription = null)
                    Text(
                        text = language.text("打开 GitHub Release", "Open GitHub Release"),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (appUpdateState.phase == AppUpdatePhase.DOWNLOADING) {
                    OutlinedButton(
                        onClick = onCancelAppUpdateDownload,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(language.text("取消下载", "Cancel download"))
                    }
                } else {
                    FilledTonalButton(
                        onClick = onDownloadLatestRelease,
                        enabled = appUpdateState.phase != AppUpdatePhase.CHECKING,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(imageVector = Icons.Outlined.Download, contentDescription = null)
                        Text(
                            text = if (appUpdateState.downloadedApkPath == null) {
                                language.text("下载最新版 universal APK", "Download latest universal APK")
                            } else {
                                language.text("重新下载安装包", "Download APK again")
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            appUpdateState.downloadedApkPath?.let { path ->
                Button(
                    onClick = { onInstallDownloadedRelease(path) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(imageVector = Icons.Outlined.Download, contentDescription = null)
                    Text(
                        text = language.text("使用系统安装器安装", "Install with Android installer"),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            appUpdateState.message?.let { message ->
                SettingsStatusMessage(message = message, isFailure = true)
            }
        }
    }

    SettingsSection(
        title = language.text("诊断与支持", "Diagnostics & support"),
        supportingText = language.text(
            "导出经过脱敏处理的运行信息",
            "Export redacted runtime information",
        ),
        icon = Icons.Outlined.FolderOpen,
    ) {
        SettingsListItem(
            headlineContent = { Text(language.text("诊断日志", "Diagnostic log")) },
            supportingContent = {
                Text(
                    language.text(
                        "包含业务日志与崩溃调用栈，不包含登录凭据",
                        "Includes app logs and crash traces without sign-in credentials",
                    ),
                )
            },
        )
        SettingsActionArea {
            Button(
                onClick = onExportDiagnostics,
                enabled = !diagnosticExportState.isExporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(imageVector = Icons.Outlined.FileUpload, contentDescription = null)
                Text(
                    text = if (diagnosticExportState.isExporting) {
                        language.text("正在导出…", "Exporting…")
                    } else {
                        language.text("导出诊断日志", "Export diagnostic log")
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            diagnosticExportState.message?.let { message ->
                SettingsStatusMessage(
                    message = message,
                    isFailure = diagnosticExportState.isFailure,
                )
            }
        }
    }
}

@Composable
private fun SettingsExternalLinkRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    SettingsListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        trailingContent = {
            Icon(
                imageVector = Icons.Outlined.OpenInNew,
                contentDescription = null,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun DownloadSettingsContent(
    preferences: AppPreferences,
    proxyUrl: String,
    onProxyUrlChanged: (String) -> Unit,
    onSelectOutputDirectory: () -> Unit,
    onClearOutputDirectory: () -> Unit,
    onDownloadPreferencesChange: (Int, Int, String, Int) -> Unit,
    onDownloadProxyEnabledChange: (Boolean) -> Unit,
) {
    fun text(zh: String, en: String): String = if (preferences.language == AppLanguage.EN) en else zh
    fun saveDownloadPreferences(
        maxDownloads: Int = preferences.maxConcurrentDownloads,
        chunkConcurrency: Int = preferences.chunkDownloadConcurrency,
        nextProxyUrl: String = preferences.downloadProxyUrl,
    ) {
        onDownloadPreferencesChange(
            maxDownloads,
            chunkConcurrency,
            nextProxyUrl,
            preferences.mediaCacheLimitMb,
        )
    }

    SettingsSection(
        title = text("存储位置", "Storage location"),
        supportingText = text(
            "选择转换完成后的文件导出位置",
            "Choose where converted files are exported",
        ),
        icon = Icons.Outlined.FolderOpen,
    ) {
        SettingsListItem(
            headlineContent = { Text(text("当前导出目录", "Current export directory")) },
            supportingContent = {
                Text(
                    preferences.outputDirectoryLabel ?: text(
                        "默认：Download/WallHub",
                        "Default: Download/WallHub",
                    ),
                )
            },
        )
        SettingsActionArea {
            FilledTonalButton(
                onClick = onSelectOutputDirectory,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                )
                Text(
                    text = if (preferences.outputTreeUri == null) {
                        text("选择自定义目录", "Choose custom directory")
                    } else {
                        text("更改自定义目录", "Change custom directory")
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (preferences.outputTreeUri != null) {
                TextButton(
                    onClick = onClearOutputDirectory,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text("恢复默认目录", "Restore default directory"))
                }
            }
        }
    }

    SettingsSection(
        title = text("下载性能", "Download performance"),
        supportingText = text(
            "调整任务数量与单任务分块并发",
            "Adjust task count and per-download chunk concurrency",
        ),
        icon = Icons.Outlined.Download,
    ) {
        SettingChoiceRow(
            title = text("同时下载项目数", "Concurrent downloads"),
            selectedValue = preferences.maxConcurrentDownloads,
            values = listOf(1, 2, 3, 4),
            label = { text("$it 项", "$it tasks") },
            onSelected = { value -> saveDownloadPreferences(maxDownloads = value) },
        )
        SettingsItemDivider()
        SettingChoiceRow(
            title = text("单项目分块并发", "Chunks per download"),
            selectedValue = preferences.chunkDownloadConcurrency,
            values = listOf(12, 16, 24, 32, 48),
            label = { value -> text("$value 个", "$value chunks") },
            onSelected = { value -> saveDownloadPreferences(chunkConcurrency = value) },
        )
    }

    SettingsSection(
        title = text("网络代理", "Network proxy"),
        supportingText = text(
            "仅用于下载和在线播放，不影响 Steam 社区内置访问线路",
            "Used only by downloads and online playback; independent from built-in Steam service access",
        ),
        icon = Icons.Outlined.Tune,
    ) {
        if (preferences.downloadProxyRequiresConfirmation) {
            SettingsNotice(
                title = text("旧版代理需要确认", "Legacy proxy needs confirmation"),
                message = text(
                    "已保留旧版代理地址，但不会自动启用。请确认地址后再开启代理。",
                    "The saved legacy address was kept but is not enabled automatically. Confirm it before enabling the proxy.",
                ),
            )
        }
        SettingsSwitchRow(
            title = text("使用网络代理", "Use network proxy"),
            supportingText = text(
                "仅下载客户端使用此地址；失败时不会切换其他代理",
                "Only download clients use this address; failures do not switch to another proxy",
            ),
            checked = preferences.downloadProxyEnabled,
            enabled = isSupportedDownloadProxyUrl(preferences.downloadProxyUrl),
            onCheckedChange = onDownloadProxyEnabledChange,
        )
        SettingsItemDivider()
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsFilledTextField(
                value = proxyUrl,
                onValueChange = onProxyUrlChanged,
                label = { Text(text("HTTP(S) / SOCKS5 代理", "HTTP(S) / SOCKS5 proxy")) },
                placeholder = { Text("socks5://127.0.0.1:1080") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { saveDownloadPreferences(nextProxyUrl = proxyUrl) },
                enabled = proxyUrl != preferences.downloadProxyUrl,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text("保存代理设置", "Save proxy settings"))
            }
        }
    }
}

@Composable
private fun SteamSettingsContent(
    language: AppLanguage,
    session: SteamSessionState,
    steamAccessEnabled: Boolean,
    steamAccessState: SteamAccessState,
    steamAccessDohEndpoints: List<String>,
    steamAccessDisabledDohEndpoints: Set<String>,
    steamWorkshopDataSource: SteamWorkshopDataSource,
    onSteamAccessEnabledChange: (Boolean) -> Unit,
    onSteamAccessDohEndpointsChange: (List<String>, Set<String>) -> Unit,
    onSteamWorkshopDataSourceChange: (SteamWorkshopDataSource) -> Unit,
    onRefreshSteamAccess: () -> Unit,
    savedApiKey: String,
    apiKey: String,
    onApiKeyChanged: (String) -> Unit,
    onSaveApiKey: () -> Unit,
    onOpenApiKeyPage: () -> Unit,
    onOpenSteamLogin: () -> Unit,
    onLogoutSteam: () -> Unit,
) {
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    val apiKeyChanged = apiKey != savedApiKey

    SettingsSection(
        title = language.text("Steam 账户", "Steam account"),
        supportingText = language.text(
            "管理资料库使用的登录会话",
            "Manage the sign-in session used by Library",
        ),
        icon = Icons.Outlined.PersonOutline,
    ) {
        SettingsListItem(
            headlineContent = {
                Text(
                    if (session.phase == SteamSessionPhase.SIGNED_IN) {
                        session.accountName.orEmpty().ifBlank { "Steam" }
                    } else {
                        language.text("登录状态", "Sign-in status")
                    },
                )
            },
            supportingContent = {
                Text(session.settingsSummary(language))
            },
        )
        SettingsActionArea {
            if (session.phase == SteamSessionPhase.SIGNED_IN) {
                OutlinedButton(
                    onClick = onLogoutSteam,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Logout,
                        contentDescription = null,
                    )
                    Text(
                        text = language.text("退出 Steam", "Sign out of Steam"),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            } else {
                Button(
                    onClick = onOpenSteamLogin,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PersonOutline,
                        contentDescription = null,
                    )
                    Text(
                        text = when (session.phase) {
                            SteamSessionPhase.RESTORABLE -> language.text(
                                "恢复 Steam 登录",
                                "Restore Steam sign-in",
                            )

                            SteamSessionPhase.SIGNING_IN,
                            SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
                            SteamSessionPhase.WAITING_FOR_CODE,
                            -> language.text("查看登录进度", "View sign-in progress")

                            else -> language.text("登录 Steam", "Sign in to Steam")
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }

    SettingsSection(
        title = language.text("Steam 服务访问", "Steam service access"),
        supportingText = language.text(
            "仅在社区与 API 直连异常时启用内置无 SNI 线路",
            "Uses the built-in no-SNI route only when Community or API direct access fails",
        ),
        icon = Icons.Outlined.Language,
    ) {
        SettingsSwitchRow(
            title = language.text("自动防阻断", "Automatic anti-blocking"),
            supportingText = steamAccessState.summary(language),
            checked = steamAccessEnabled,
            onCheckedChange = onSteamAccessEnabledChange,
        )
        SettingsItemDivider()
        SteamAccessDohEndpointsSetting(
            endpoints = steamAccessDohEndpoints,
            disabledEndpoints = steamAccessDisabledDohEndpoints,
            language = language,
            onSave = onSteamAccessDohEndpointsChange,
        )
        SettingsActionArea {
            FilledTonalButton(
                onClick = onRefreshSteamAccess,
                enabled = steamAccessEnabled && steamAccessState.phase != SteamAccessPhase.RESOLVING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                )
                Text(
                    text = language.text("重新检测线路", "Check routes again"),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }

    SettingsSection(
        title = language.text("创意工坊数据源", "Workshop data source"),
        supportingText = language.text(
            "为发现、详情与评论选择严格的数据通道",
            "Choose the strict data channel used by discovery, details, and comments",
        ),
        icon = Icons.Outlined.Language,
    ) {
        SettingChoiceRow(
            title = language.text("数据获取源", "Data source"),
            selectedValue = steamWorkshopDataSource,
            values = SteamWorkshopDataSource.entries,
            label = { source ->
                when (source) {
                    SteamWorkshopDataSource.COMMUNITY_HTML -> "Steam Community HTML"
                    SteamWorkshopDataSource.WEB_API -> "Steam Web API"
                    SteamWorkshopDataSource.CM_WEBSOCKET -> "Steam CM WebSocket"
                }
            },
            supportingText = when (steamWorkshopDataSource) {
                SteamWorkshopDataSource.COMMUNITY_HTML -> language.text(
                    "使用 Steam Community 页面获取公开数据",
                    "Use Steam Community pages for public data",
                )

                SteamWorkshopDataSource.WEB_API -> language.text(
                    "发现页需要有效的 Steam API Key",
                    "Discovery requires a valid Steam API key",
                )

                SteamWorkshopDataSource.CM_WEBSOCKET -> language.text(
                    "公开发现与详情支持匿名 CM；评论需要登录",
                    "Public discovery and details support anonymous CM; comments require sign-in",
                )
            },
            onSelected = onSteamWorkshopDataSourceChange,
        )
    }

    SettingsSection(
        title = "Steam Web API",
        supportingText = language.text(
            "供 Web API 数据源与匿名昵称补全使用",
            "Used by the Web API source and anonymous profile enrichment",
        ),
        icon = Icons.Outlined.Tune,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsFilledTextField(
                value = apiKey,
                onValueChange = onApiKeyChanged,
                label = { Text("Steam API Key") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                visualTransformation = if (apiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Icon(
                            imageVector = if (apiKeyVisible) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = if (apiKeyVisible) {
                                language.text("隐藏 API Key", "Hide API key")
                            } else {
                                language.text("显示 API Key", "Show API key")
                            },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(
                onClick = onOpenApiKeyPage,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.OpenInNew,
                    contentDescription = null,
                )
                Text(
                    text = language.text("获取 Steam API Key", "Get Steam API Key"),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Button(
                onClick = onSaveApiKey,
                enabled = apiKeyChanged,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (apiKey.isBlank() && savedApiKey.isNotBlank()) {
                        language.text("清除 Steam API Key", "Clear Steam API Key")
                    } else {
                        language.text("保存 Steam API Key", "Save Steam API Key")
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SteamAccessDohEndpointsSetting(
    endpoints: List<String>,
    disabledEndpoints: Set<String>,
    language: AppLanguage,
    onSave: (List<String>, Set<String>) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var editorVisible by rememberSaveable { mutableStateOf(false) }
    var confirmDiscardVisible by rememberSaveable { mutableStateOf(false) }
    var draftEndpoints by rememberSaveable { mutableStateOf(endpoints) }
    var draftDisabledEndpoints by rememberSaveable { mutableStateOf(disabledEndpoints) }
    var endpointText by rememberSaveable { mutableStateOf("") }
    var endpointError by rememberSaveable { mutableStateOf<String?>(null) }
    val enabledCount = endpoints.count { endpoint -> endpoint !in disabledEndpoints }
    val draftChanged = draftEndpoints != endpoints || draftDisabledEndpoints != disabledEndpoints
    val hasUnsavedWork = draftChanged || endpointText.isNotBlank()

    fun openEditor() {
        draftEndpoints = endpoints
        draftDisabledEndpoints = disabledEndpoints
        endpointText = ""
        endpointError = null
        confirmDiscardVisible = false
        editorVisible = true
    }

    fun requestClose() {
        focusManager.clearFocus()
        if (hasUnsavedWork) {
            confirmDiscardVisible = true
        } else {
            editorVisible = false
        }
    }

    fun addEndpoint() {
        val normalized = normalizeSteamAccessDohEndpoint(endpointText)
        endpointError = when {
            normalized == null -> language.text(
                "请输入有效的 HTTPS DoH 地址",
                "Enter a valid HTTPS DoH URL",
            )

            normalized in draftEndpoints -> language.text(
                "此 DoH 地址已在列表中",
                "This DoH URL is already in the list",
            )

            draftEndpoints.size >= STEAM_ACCESS_DOH_ENDPOINT_LIMIT -> language.text(
                "最多可配置 $STEAM_ACCESS_DOH_ENDPOINT_LIMIT 个 DoH 地址",
                "Up to $STEAM_ACCESS_DOH_ENDPOINT_LIMIT DoH URLs are supported",
            )

            else -> null
        }
        if (endpointError == null && normalized != null) {
            draftEndpoints = draftEndpoints + normalized
            draftDisabledEndpoints = draftDisabledEndpoints - normalized
            endpointText = ""
            focusManager.clearFocus()
        }
    }

    SettingsListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = ::openEditor),
        headlineContent = {
            Text(language.text("DoH 地址", "DoH URLs"))
        },
        supportingContent = {
            Text(
                language.text(
                    "已启用 $enabledCount/${endpoints.size}，拖动可调整优先级",
                    "$enabledCount/${endpoints.size} enabled; drag to change priority",
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
            )
        },
    )

    if (editorVisible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = ::requestClose,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            sheetMaxWidth = 920.dp,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .widthIn(max = 760.dp)
                        .heightIn(max = maxHeight * 0.92f)
                        .imePadding()
                        .navigationBarsPadding(),
                ) {
                    SteamAccessDohEditor(
                        modifier = Modifier.fillMaxSize(),
                        endpoints = draftEndpoints,
                        disabledEndpoints = draftDisabledEndpoints,
                        endpointText = endpointText,
                        endpointError = endpointError,
                        language = language,
                        hasChanges = draftChanged,
                        onEndpointTextChange = { value ->
                            endpointText = value
                            endpointError = null
                        },
                        onAddEndpoint = ::addEndpoint,
                        onReorder = { reordered -> draftEndpoints = reordered },
                        onEnabledChange = { endpoint, enabled ->
                            draftDisabledEndpoints = if (enabled) {
                                draftDisabledEndpoints - endpoint
                            } else {
                                draftDisabledEndpoints + endpoint
                            }
                        },
                        onDelete = { endpoint ->
                            draftEndpoints = draftEndpoints - endpoint
                            draftDisabledEndpoints = draftDisabledEndpoints - endpoint
                        },
                        onRestoreDefaults = {
                            draftEndpoints = DEFAULT_STEAM_ACCESS_DOH_ENDPOINTS
                            draftDisabledEndpoints = emptySet()
                            endpointText = ""
                            endpointError = null
                            focusManager.clearFocus()
                        },
                        onCancel = ::requestClose,
                        onSave = {
                            focusManager.clearFocus()
                            onSave(draftEndpoints, draftDisabledEndpoints)
                            editorVisible = false
                        },
                    )
                }
            }
        }
    }

    if (confirmDiscardVisible) {
        AlertDialog(
            onDismissRequest = { confirmDiscardVisible = false },
            title = { Text(language.text("放弃更改？", "Discard changes?")) },
            text = {
                Text(
                    language.text(
                        "尚未保存的 DoH 地址、启用状态和优先级调整将丢失。",
                        "Unsaved DoH URLs, enabled states, and priority changes will be lost.",
                    ),
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscardVisible = false }) {
                    Text(language.text("继续编辑", "Keep editing"))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscardVisible = false
                        editorVisible = false
                        endpointText = ""
                        endpointError = null
                    },
                ) {
                    Text(language.text("放弃", "Discard"))
                }
            },
        )
    }
}

@Composable
private fun SteamAccessDohEditor(
    endpoints: List<String>,
    disabledEndpoints: Set<String>,
    endpointText: String,
    endpointError: String?,
    language: AppLanguage,
    hasChanges: Boolean,
    onEndpointTextChange: (String) -> Unit,
    onAddEndpoint: () -> Unit,
    onReorder: (List<String>) -> Unit,
    onEnabledChange: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onRestoreDefaults: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var draggedEndpoint by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var dragOrder by remember { mutableStateOf(endpoints) }
    val itemExtentPx = with(density) { (STEAM_DOH_ITEM_HEIGHT + STEAM_DOH_ITEM_SPACING).toPx() }
    val enabledCount = endpoints.count { endpoint -> endpoint !in disabledEndpoints }
    val secondaryButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    )
    LaunchedEffect(endpoints, draggedEndpoint) {
        if (draggedEndpoint == null) dragOrder = endpoints
    }

    Column(modifier = modifier) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = language.text("自定义 DoH", "Custom DoH"),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = language.text("已启用 $enabledCount/${endpoints.size}", "$enabledCount/${endpoints.size} enabled"),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hasChanges) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = language.text(
                    "长按手柄并拖动调整优先级，顶部地址优先使用。",
                    "Long-press a handle and drag to change priority; top URLs are preferred.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(STEAM_DOH_ITEM_SPACING),
        ) {
            itemsIndexed(
                items = endpoints,
                key = { _, endpoint -> endpoint },
            ) { _, endpoint ->
                val isDragging = draggedEndpoint == endpoint
                val itemModifier = Modifier
                    .then(if (isDragging) Modifier else Modifier.animateItem())
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        if (isDragging) {
                            translationY = dragOffsetPx
                            scaleX = 1.02f
                            scaleY = 1.02f
                            shadowElevation = 8.dp.toPx()
                        }
                    }
                val dragHandleModifier = Modifier.pointerInput(endpoint) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            draggedEndpoint = endpoint
                            dragOrder = endpoints
                            dragOffsetPx = 0f
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragCancel = {
                            draggedEndpoint = null
                            dragOffsetPx = 0f
                        },
                        onDragEnd = {
                            draggedEndpoint = null
                            dragOffsetPx = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetPx += dragAmount.y
                            var currentIndex = dragOrder.indexOf(endpoint)
                            while (dragOffsetPx > itemExtentPx / 2f && currentIndex < dragOrder.lastIndex) {
                                val nextIndex = currentIndex + 1
                                dragOrder = dragOrder.toMutableList().apply {
                                    this[currentIndex] = this[nextIndex]
                                    this[nextIndex] = endpoint
                                }
                                dragOffsetPx -= itemExtentPx
                                currentIndex = nextIndex
                                onReorder(dragOrder)
                            }
                            while (dragOffsetPx < -itemExtentPx / 2f && currentIndex > 0) {
                                val previousIndex = currentIndex - 1
                                dragOrder = dragOrder.toMutableList().apply {
                                    this[currentIndex] = this[previousIndex]
                                    this[previousIndex] = endpoint
                                }
                                dragOffsetPx += itemExtentPx
                                currentIndex = previousIndex
                                onReorder(dragOrder)
                            }
                            val itemInfo = listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { item -> item.key == endpoint }
                            if (itemInfo != null) {
                                val translatedTop = itemInfo.offset + dragOffsetPx
                                val translatedBottom = translatedTop + itemInfo.size
                                val viewportStart = listState.layoutInfo.viewportStartOffset + 48f
                                val viewportEnd = listState.layoutInfo.viewportEndOffset - 48f
                                val scrollDelta = when {
                                    translatedTop < viewportStart -> -18f
                                    translatedBottom > viewportEnd -> 18f
                                    else -> 0f
                                }
                                if (scrollDelta != 0f) {
                                    coroutineScope.launch { listState.scrollBy(scrollDelta) }
                                }
                            }
                        },
                    )
                }
                SteamAccessDohEndpointItem(
                    endpoint = endpoint,
                    enabled = endpoint !in disabledEndpoints,
                    canDelete = endpoints.size > 1,
                    language = language,
                    onEnabledChange = { enabled -> onEnabledChange(endpoint, enabled) },
                    onDelete = { onDelete(endpoint) },
                    dragHandleModifier = dragHandleModifier,
                    modifier = itemModifier,
                )
            }
            item(key = "add-endpoint") {
                WallHubSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = language.text("添加地址", "Add URL"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        SettingsFilledTextField(
                            value = endpointText,
                            onValueChange = onEndpointTextChange,
                            label = { Text(language.text("HTTPS DoH 地址", "HTTPS DoH URL")) },
                            placeholder = { Text("https://dns.example/dns-query") },
                            supportingText = {
                                Text(
                                    endpointError ?: language.text(
                                        "新地址默认开启，并添加到列表末尾",
                                        "New URLs are enabled and added at the end",
                                    ),
                                )
                            },
                            isError = endpointError != null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { if (endpointText.isNotBlank()) onAddEndpoint() },
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = onAddEndpoint,
                            enabled = endpointText.isNotBlank(),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(imageVector = Icons.Outlined.Add, contentDescription = null)
                            Text(
                                text = language.text("添加", "Add"),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onRestoreDefaults,
                    enabled = endpoints != DEFAULT_STEAM_ACCESS_DOH_ENDPOINTS || disabledEndpoints.isNotEmpty(),
                    shape = MaterialTheme.shapes.large,
                    colors = secondaryButtonColors,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(language.text("恢复默认", "Reset"))
                }
                Button(
                    onClick = onCancel,
                    shape = MaterialTheme.shapes.large,
                    colors = secondaryButtonColors,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(language.text("取消", "Cancel"))
                }
            }
            Button(
                onClick = onSave,
                enabled = hasChanges,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(language.text("保存更改", "Save changes"))
            }
        }
    }
}

@Composable
private fun SteamAccessDohEndpointItem(
    endpoint: String,
    enabled: Boolean,
    canDelete: Boolean,
    language: AppLanguage,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    WallHubSurfaceCard(
        modifier = modifier
            .fillMaxWidth()
            .height(STEAM_DOH_ITEM_HEIGHT),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .then(dragHandleModifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DragIndicator,
                    contentDescription = language.text(
                        "拖动 $endpoint 调整优先级",
                        "Drag $endpoint to change priority",
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = endpoint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (enabled) {
                        language.text("已开启", "Enabled")
                    } else {
                        language.text("已关闭", "Disabled")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
            IconButton(
                onClick = onDelete,
                enabled = canDelete,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = language.text("删除 $endpoint", "Delete $endpoint"),
                )
            }
        }
    }
}

@Composable
private fun ExperimentalSettingsContent(
    preferences: AppPreferences,
    onOnlineChunkPlaybackEnabledChange: (Boolean) -> Unit,
    onOnlineStreamCacheLimitChange: (Int) -> Unit,
    onRequestNotifications: () -> Unit,
) {
    fun text(zh: String, en: String): String = if (preferences.language == AppLanguage.EN) en else zh

    SettingsNotice(
        title = text("实验功能可能改变网络与播放行为", "Experimental features may change networking and playback"),
        message = text(
            "遇到稳定性问题时，可关闭相关开关恢复默认流程。",
            "Turn off the related option to return to the default flow if stability issues occur.",
        ),
    )

    SettingsSection(
        title = text("在线播放", "Online playback"),
        supportingText = text(
            "控制 Steam 分块播放及本地缓存",
            "Control Steam chunk streaming and local cache",
        ),
        icon = Icons.Outlined.PlayArrow,
    ) {
        SettingsSwitchRow(
            title = text("SteamKit 在线分块播放", "SteamKit chunk streaming"),
            supportingText = text(
                "开启后直接从 Steam 分块播放；关闭后先下载再播放",
                "Stream directly from Steam when on; download before playback when off",
            ),
            checked = preferences.onlineChunkPlaybackEnabled,
            onCheckedChange = onOnlineChunkPlaybackEnabledChange,
        )
        SettingsItemDivider()
        SteamStreamCacheSetting(
            cacheLimitMb = preferences.mediaCacheLimitMb,
            language = preferences.language,
            onCacheLimitChange = onOnlineStreamCacheLimitChange,
        )
    }

    SettingsSection(
        title = text("系统权限", "System permissions"),
        supportingText = text(
            "管理后台任务需要的 Android 权限",
            "Manage Android permissions used by background work",
        ),
        icon = Icons.Outlined.Notifications,
    ) {
        SettingsListItem(
            headlineContent = { Text(text("后台任务通知", "Background task notifications")) },
            supportingContent = {
                Text(
                    text(
                        "用于显示下载、转换和导出的实时进度",
                        "Shows live progress for downloads, conversion, and export",
                    ),
                )
            },
        )
        SettingsActionArea {
            FilledTonalButton(
                onClick = onRequestNotifications,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = null,
                )
                Text(
                    text = text("允许后台通知", "Allow background notifications"),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsActionArea(
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SettingsStatusMessage(
    message: String,
    isFailure: Boolean,
) {
    val containerColor = if (isFailure) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isFailure) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SettingsNotice(
    title: String,
    message: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AppearanceSettingsContent(
    preferences: AppPreferences,
    availableAccents: List<AccentPreference>,
    customAccentColor: String,
    onCustomAccentColorChanged: (String) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onThemePreferenceChange: (ThemePreference) -> Unit,
    onAccentChange: (AccentPreference, String?) -> Unit,
    onSystemMonetEnabledChange: (Boolean) -> Unit,
    onThemedLauncherIconEnabledChange: (Boolean) -> Unit,
    onHomePreferencesChange: (Int, Int, Boolean, HomeCardAction, Boolean) -> Unit,
    onHomePaginationModeChange: (HomePaginationMode) -> Unit,
) {
    fun text(zh: String, en: String): String = if (preferences.language == AppLanguage.EN) en else zh
    fun saveHomePreferences(
        pageSize: Int = preferences.homePageSize,
        columns: Int = preferences.homeColumns,
        multiSelect: Boolean = preferences.homeFilterMultiSelect,
        cardAction: HomeCardAction = preferences.homeCardAction,
    ) {
        onHomePreferencesChange(
            pageSize,
            columns,
            multiSelect,
            cardAction,
            preferences.matureContentEnabled,
        )
    }

    SettingsSection(
        title = text("语言与主题", "Language & theme"),
        icon = Icons.Outlined.Language,
    ) {
        SettingChoiceRow(
            title = text("显示语言", "Display language"),
            supportingText = text(
                "用于发现页与原生界面的显示语言",
                "Language used by Discover and native screens",
            ),
            selectedValue = preferences.language,
            values = listOf(AppLanguage.ZH, AppLanguage.EN),
            label = { language -> if (language == AppLanguage.ZH) "中文" else "English" },
            onSelected = onLanguageChange,
        )
        SettingsItemDivider()
        SettingChoiceRow(
            title = text("主题模式", "Theme mode"),
            supportingText = text(
                "选择浅色、深色或跟随系统",
                "Use light, dark, or the system setting",
            ),
            selectedValue = preferences.theme,
            values = ThemePreference.entries,
            label = { theme -> theme.label(preferences.language) },
            onSelected = onThemePreferenceChange,
        )
    }

    SettingsSection(
        title = text("个性化配色", "Personalized color"),
        supportingText = text(
            "使用壁纸取色或选择完整的静态 Material 色板",
            "Use wallpaper colors or a complete static Material palette",
        ),
        icon = Icons.Outlined.Palette,
    ) {
        MomentThemeCard(
            enabled = preferences.useSystemMonet,
            language = preferences.language,
            onEnabledChange = onSystemMonetEnabledChange,
        )
        SettingsItemDivider()
        val themedIconsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        SettingsSwitchRow(
            title = text("图标跟随系统取色", "Themed app icon"),
            supportingText = if (themedIconsSupported) {
                text(
                    "允许系统启动器按壁纸莫奈色显示应用图标",
                    "Let the system launcher tint the app icon from wallpaper colors",
                )
            } else {
                text(
                    "需要 Android 13 或更高版本",
                    "Requires Android 13 or newer",
                )
            },
            checked = themedIconsSupported && preferences.useThemedLauncherIcon,
            enabled = themedIconsSupported,
            onCheckedChange = onThemedLauncherIconEnabledChange,
        )
        SettingsItemDivider()
        AccentPreferenceChoiceRow(
            title = text("静态色板", "Static palette"),
            supportingText = text(
                "关闭系统动态取色时使用",
                "Used when system dynamic color is off",
            ),
            selectedValue = preferences.accent.takeUnless {
                it == AccentPreference.MONET
            } ?: AccentPreference.DEFAULT,
            values = availableAccents,
            customColor = customAccentColor,
            language = preferences.language,
            systemMonetColor = MaterialTheme.colorScheme.primary,
            onSelected = { accent ->
                onAccentChange(
                    accent,
                    if (accent == AccentPreference.CUSTOM) customAccentColor else null,
                )
            },
            onCustomColorChanged = onCustomAccentColorChanged,
            onApplyCustom = {
                onAccentChange(AccentPreference.CUSTOM, customAccentColor)
            },
        )
    }

    SettingsSection(
        title = text("发现页", "Discover"),
        supportingText = text(
            "控制内容密度、筛选方式与卡片默认操作",
            "Control content density, filters, and the default card action",
        ),
        icon = Icons.Outlined.Tune,
    ) {
        SettingChoiceRow(
            title = text("每页项目数", "Items per page"),
            selectedValue = preferences.homePageSize,
            values = listOf(10, 15, 24, 30, 50),
            label = { "$it" },
            onSelected = { value -> saveHomePreferences(pageSize = value) },
        )
        SettingsItemDivider()
        SettingChoiceRow(
            title = text("分页方式", "Pagination"),
            selectedValue = preferences.homePaginationMode,
            values = HomePaginationMode.entries,
            label = { mode -> mode.label(preferences.language) },
            onSelected = onHomePaginationModeChange,
        )
        SettingsItemDivider()
        SettingChoiceRow(
            title = text("移动端列数", "Phone columns"),
            selectedValue = preferences.homeColumns,
            values = listOf(1, 2, 3, 4),
            label = { text("$it 列", "$it columns") },
            onSelected = { value -> saveHomePreferences(columns = value) },
        )
        SettingsItemDivider()
        SettingsSwitchRow(
            title = text("类型和评级多选", "Multi-select types and ratings"),
            supportingText = text(
                "关闭后，类型和年龄评级使用互斥选择",
                "When off, type and age rating filters are exclusive",
            ),
            checked = preferences.homeFilterMultiSelect,
            onCheckedChange = { enabled -> saveHomePreferences(multiSelect = enabled) },
        )
        SettingsItemDivider()
        SettingChoiceRow(
            title = text("卡片默认操作", "Default card action"),
            selectedValue = preferences.homeCardAction,
            values = HomeCardAction.entries,
            label = { action -> action.label(preferences.language) },
            onSelected = { action -> saveHomePreferences(cardAction = action) },
        )
    }
}

@Composable
private fun MomentThemeCard(
    enabled: Boolean,
    language: AppLanguage,
    onEnabledChange: (Boolean) -> Unit,
) {
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val headlineColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val supportingColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(6.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(containerColor)
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = onEnabledChange,
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsLeadingIcon(
            icon = Icons.Outlined.Palette,
            prominent = enabled,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = language.text("系统动态取色", "System dynamic color"),
                style = MaterialTheme.typography.titleMedium,
                color = headlineColor,
            )
            Text(
                text = when {
                    enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> language.text(
                        "色板跟随当前系统壁纸",
                        "Palette follows the current system wallpaper",
                    )

                    enabled -> language.text(
                        "当前系统不支持壁纸取色，使用兼容色板",
                        "Wallpaper colors are unavailable; using a compatible palette",
                    )

                    else -> language.text(
                        "当前使用下方选择的静态色板",
                        "Using the static palette selected below",
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = supportingColor,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.secondary,
                    MaterialTheme.colorScheme.tertiary,
                ).forEach { color ->
                    Surface(
                        modifier = Modifier.size(width = 24.dp, height = 8.dp),
                        shape = CircleShape,
                        color = color,
                        content = {},
                    )
                }
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    supportingText: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        headlineContent = { Text(title) },
        supportingContent = { Text(supportingText) },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = null,
            )
        },
    )
}

@Composable
private fun SettingsItemDivider() {
    Spacer(modifier = Modifier.height(2.dp))
}

@Composable
private fun SettingsLeadingIcon(
    icon: ImageVector,
    prominent: Boolean = false,
) {
    val containerColor = if (prominent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (prominent) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(10.dp).size(20.dp),
        )
    }
}

@Composable
private fun SettingsCategoryIndex(
    language: AppLanguage,
    onOpenCategory: (SettingsCategory) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCategory.entries.forEach { category ->
            SettingsSectionSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onOpenCategory(category) },
            ) {
                SettingsListItem(
                    modifier = Modifier.fillMaxWidth(),
                    headlineContent = { Text(category.label(language)) },
                    supportingContent = { Text(category.description(language)) },
                    leadingContent = {
                        SettingsLeadingIcon(icon = category.icon)
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                )
            }
        }
    }
}

private fun SteamSessionState.settingsSummary(language: AppLanguage): String = when (phase) {
    SteamSessionPhase.SIGNED_IN -> language.text("已登录：${accountName.orEmpty()}", "Signed in: ${accountName.orEmpty()}")
    SteamSessionPhase.RESTORABLE -> language.text("已保存登录状态", "Saved sign-in available")
    SteamSessionPhase.SIGNING_IN,
    SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
    SteamSessionPhase.WAITING_FOR_CODE,
    -> message ?: language.text("正在登录 Steam", "Signing in to Steam")

    SteamSessionPhase.EXPIRED,
    SteamSessionPhase.FAILED,
    -> message ?: language.text("Steam 登录需要重新验证", "Steam sign-in needs verification")

    SteamSessionPhase.SIGNED_OUT -> language.text("未登录", "Not signed in")
}

private fun SteamAccessState.summary(language: AppLanguage): String {
    val phaseLabel = when (phase) {
        SteamAccessPhase.DISABLED -> language.text("已关闭", "Disabled")
        SteamAccessPhase.RESOLVING -> language.text("正在检测直连与内置线路", "Checking direct and built-in routes")
        SteamAccessPhase.READY -> language.text("线路可用", "Route available")
        SteamAccessPhase.DEGRADED -> language.text("线路不稳定，等待重新检测", "Route unstable; waiting to check again")
        SteamAccessPhase.FAILED -> language.text("当前没有可用线路", "No route is currently available")
    }
    return phaseLabel + message?.let { "\n$it" }.orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingChoiceRow(
    title: String,
    selectedValue: T,
    values: List<T>,
    label: (T) -> String,
    onSelected: (T) -> Unit,
    supportingText: String? = null,
) {
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    SettingsListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { sheetVisible = true },
        headlineContent = { Text(title) },
        supportingContent = supportingText?.let { description ->
            { Text(description) }
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = label(selectedValue),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = SETTINGS_TRAILING_VALUE_MAX_WIDTH),
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
    if (sheetVisible) {
        ModalBottomSheet(onDismissRequest = { sheetVisible = false }) {
            SettingChoiceSheet(
                title = title,
                selectedValue = selectedValue,
                values = values,
                label = label,
                onSelected = { value ->
                    onSelected(value)
                    sheetVisible = false
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SteamStreamCacheSetting(
    cacheLimitMb: Int,
    language: AppLanguage,
    onCacheLimitChange: (Int) -> Unit,
) {
    fun text(zh: String, en: String): String = if (language == AppLanguage.EN) en else zh
    var customSheetVisible by rememberSaveable { mutableStateOf(false) }
    var customLimitText by remember(cacheLimitMb) { mutableStateOf(cacheLimitMb.toString()) }
    var customLimitError by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedPreset = SteamStreamCachePreset.entries.firstOrNull { preset ->
        preset.limitMb == cacheLimitMb
    } ?: SteamStreamCachePreset.CUSTOM

    SettingChoiceRow(
        title = text("SteamKit 在线播放缓存", "SteamKit streaming cache"),
        selectedValue = selectedPreset,
        values = SteamStreamCachePreset.entries.toList(),
        label = { preset ->
            preset.limitMb?.let(::formatSteamStreamCacheLimit) ?: text(
                "自定义：${formatSteamStreamCacheLimit(cacheLimitMb)}",
                "Custom: ${formatSteamStreamCacheLimit(cacheLimitMb)}",
            )
        },
        supportingText = text(
            "限制在线播放的已解密 Steam 分块缓存；默认 512 MB。",
            "Limits cached decrypted Steam chunks for streaming. Default: 512 MB.",
        ),
        onSelected = { preset ->
            preset.limitMb?.let(onCacheLimitChange) ?: run {
                customLimitText = cacheLimitMb.toString()
                customLimitError = null
                customSheetVisible = true
            }
        },
    )

    if (customSheetVisible) {
        ModalBottomSheet(onDismissRequest = { customSheetVisible = false }) {
            SettingsSheetContent {
                Text(
                    text = text("自定义在线播放缓存", "Custom streaming cache"),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = text(
                        "输入不低于 128 MB 的缓存大小。",
                        "Enter a cache size of at least 128 MB.",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsFilledTextField(
                    value = customLimitText,
                    onValueChange = { value ->
                        customLimitText = value.filter(Char::isDigit)
                        customLimitError = null
                    },
                    label = { Text(text("缓存大小 (MB)", "Cache size (MB)")) },
                    singleLine = true,
                    isError = customLimitError != null,
                    supportingText = customLimitError?.let { error -> { Text(error) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val limitMb = customLimitText.toIntOrNull()
                        if (limitMb == null || limitMb < 128) {
                            customLimitError = text(
                                "请输入不低于 128 的有效数值",
                                "Enter a valid value of at least 128",
                            )
                        } else {
                            onCacheLimitChange(limitMb)
                            customSheetVisible = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text("应用", "Apply"))
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

private fun formatSteamStreamCacheLimit(limitMb: Int): String =
    if (limitMb >= 1024 && limitMb % 1024 == 0) "${limitMb / 1024} GB" else "$limitMb MB"

@Composable
private fun <T> SettingChoiceSheet(
    title: String,
    selectedValue: T,
    values: List<T>,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    SettingsSheetContent {
        Text(title, style = MaterialTheme.typography.titleLarge)
        values.forEach { value ->
            SettingChoiceSheetOption(
                label = label(value),
                selected = value == selectedValue,
                onClick = { onSelected(value) },
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsSheetContent(
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = SETTINGS_SHEET_CONTENT_MAX_HEIGHT)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SettingChoiceSheetOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leadingContent: @Composable (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = SETTINGS_CHOICE_OPTION_MIN_HEIGHT)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            leadingContent?.invoke()
            Text(text = label, modifier = Modifier.weight(1f))
            WallHubAnimatedSelectionCheck(selected = selected)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccentPreferenceChoiceRow(
    title: String,
    supportingText: String,
    selectedValue: AccentPreference,
    values: List<AccentPreference>,
    customColor: String,
    language: AppLanguage,
    systemMonetColor: Color,
    onSelected: (AccentPreference) -> Unit,
    onCustomColorChanged: (String) -> Unit,
    onApplyCustom: () -> Unit,
) {
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    var draftAccent by remember { mutableStateOf(selectedValue) }
    val selectedColor = selectedValue.previewColor(
        customColor = customColor,
        systemMonetColor = systemMonetColor,
    )
    SettingsListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable {
                draftAccent = selectedValue
                sheetVisible = true
            },
        headlineContent = { Text(title) },
        supportingContent = { Text(supportingText) },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = selectedValue.label(language),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = SETTINGS_ACCENT_LABEL_MAX_WIDTH),
                )
                AccentColorDot(color = selectedColor)
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
    if (sheetVisible) {
        ModalBottomSheet(onDismissRequest = { sheetVisible = false }) {
            SettingsSheetContent {
                Text(title, style = MaterialTheme.typography.titleLarge)
                values.forEach { accent ->
                    SettingChoiceSheetOption(
                        label = accent.label(language),
                        selected = accent == draftAccent,
                        onClick = {
                            if (accent == AccentPreference.CUSTOM) {
                                draftAccent = AccentPreference.CUSTOM
                            } else {
                                onSelected(accent)
                                sheetVisible = false
                            }
                        },
                        leadingContent = {
                            AccentColorDot(
                                color = accent.previewColor(
                                    customColor = customColor,
                                    systemMonetColor = systemMonetColor,
                                ),
                            )
                        },
                    )
                }
                if (draftAccent == AccentPreference.CUSTOM) {
                    MonetColorPicker(
                        colorHex = customColor,
                        language = language,
                        onColorChanged = onCustomColorChanged,
                    )
                    Button(
                        onClick = {
                            onApplyCustom()
                            sheetVisible = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(language.text("应用此莫奈色", "Apply this Monet color"))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun AccentColorDot(color: Color) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonetColorPicker(
    colorHex: String,
    language: AppLanguage,
    onColorChanged: (String) -> Unit,
) {
    val hsv = colorHex.toMonetHsv()
    val previewColor = hsv.toComposeColor()
    val previewContentColor = if (previewColor.luminance() > 0.45f) {
        Color(0xFF171C19)
    } else {
        Color.White
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = language.text("自定义莫奈种子色", "Custom Monet seed color"),
            style = MaterialTheme.typography.labelLarge,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            color = previewColor,
            contentColor = previewContentColor,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = hsv.toHex(),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MONET_HUE_PRESETS.forEach { hue ->
                val swatchColor = MonetHsv(hue, 0.82f, 0.82f).toComposeColor()
                val selected = circularHueDistance(hsv.hue, hue) < 10f
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(swatchColor)
                        .clickable {
                            onColorChanged(hsv.copy(hue = hue).toHex())
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides if (swatchColor.luminance() > 0.45f) {
                            Color(0xFF171C19)
                        } else {
                            Color.White
                        },
                    ) {
                        WallHubAnimatedSelectionCheck(
                            selected = selected,
                            size = 18.dp,
                        )
                    }
                }
            }
        }
        MonetColorSlider(
            label = language.text("色相 ${hsv.hue.toInt()}°", "Hue ${hsv.hue.toInt()}°"),
            value = hsv.hue,
            valueRange = 0f..360f,
            color = previewColor,
            onValueChange = { hue -> onColorChanged(hsv.copy(hue = hue).toHex()) },
        )
        MonetColorSlider(
            label = language.text("饱和度 ${(hsv.saturation * 100).toInt()}%", "Saturation ${(hsv.saturation * 100).toInt()}%"),
            value = hsv.saturation,
            valueRange = 0f..1f,
            color = previewColor,
            onValueChange = { saturation -> onColorChanged(hsv.copy(saturation = saturation).toHex()) },
        )
        MonetColorSlider(
            label = language.text("亮度 ${(hsv.brightness * 100).toInt()}%", "Brightness ${(hsv.brightness * 100).toInt()}%"),
            value = hsv.brightness,
            valueRange = 0f..1f,
            color = previewColor,
            onValueChange = { brightness -> onColorChanged(hsv.copy(brightness = brightness).toHex()) },
        )
    }
}

@Composable
private fun MonetColorSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    color: Color,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
            ),
        )
    }
}

private data class MonetHsv(
    val hue: Float,
    val saturation: Float,
    val brightness: Float,
) {
    fun toComposeColor(): Color = Color(
        AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)),
    )

    fun toHex(): String = String.format(
        Locale.US,
        "#%06X",
        AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)) and 0x00FFFFFF,
    )
}

private fun String.toMonetHsv(): MonetHsv {
    val parsed = runCatching {
        AndroidColor.parseColor(if (startsWith("#")) this else "#$this")
    }.getOrElse { AndroidColor.parseColor(DEFAULT_CUSTOM_MONET_HEX) }
    val values = FloatArray(3)
    AndroidColor.colorToHSV(parsed, values)
    return MonetHsv(
        hue = values[0],
        saturation = values[1],
        brightness = values[2],
    )
}

private fun AccentPreference.previewColor(
    customColor: String,
    systemMonetColor: Color,
): Color = when (this) {
    AccentPreference.DEFAULT -> Color(0xFF242424)
    AccentPreference.MONET -> systemMonetColor

    AccentPreference.BLUE -> Color(0xFF2B6CB0)
    AccentPreference.GREEN -> Color(0xFF2F855A)
    AccentPreference.ROSE -> Color(0xFFC53030)
    AccentPreference.VIOLET -> Color(0xFF805AD5)
    AccentPreference.CUSTOM -> customColor.toMonetHsv().toComposeColor()
}

private fun circularHueDistance(first: Float, second: Float): Float {
    val difference = kotlin.math.abs(first - second) % 360f
    return minOf(difference, 360f - difference)
}

private fun ThemePreference.label(language: AppLanguage): String = when (this) {
    ThemePreference.SYSTEM -> language.text("跟随系统", "System")
    ThemePreference.LIGHT -> language.text("浅色", "Light")
    ThemePreference.DARK -> language.text("深色", "Dark")
}

private fun AccentPreference.label(language: AppLanguage): String = when (this) {
    AccentPreference.DEFAULT -> language.text("默认", "Default")
    AccentPreference.MONET -> language.text("系统莫奈", "System Monet")
    AccentPreference.BLUE -> language.text("蓝色", "Blue")
    AccentPreference.GREEN -> language.text("绿色", "Green")
    AccentPreference.ROSE -> language.text("红色", "Red")
    AccentPreference.VIOLET -> language.text("紫色", "Purple")
    AccentPreference.CUSTOM -> language.text("自定义", "Custom")
}

private fun HomeCardAction.label(language: AppLanguage): String = when (this) {
    HomeCardAction.DOWNLOAD -> language.text("下载", "Download")
    HomeCardAction.PLAY_VIDEO -> language.text("播放", "Play")
    HomeCardAction.OPEN_STEAM -> "Steam"
}

private fun HomePaginationMode.label(language: AppLanguage): String = when (this) {
    HomePaginationMode.INFINITE_SCROLL -> language.text("瀑布流拼接", "Infinite scroll")
    HomePaginationMode.PAGED -> language.text("Web 页码模式", "Web-style pages")
}

private fun formatUpdateSize(bytes: Long): String = String.format(
    Locale.getDefault(),
    "%.1f MB",
    bytes.coerceAtLeast(0L) / (1024.0 * 1024.0),
)

private const val DEFAULT_CUSTOM_MONET_HEX = "#5B7AA0"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val AUTHOR_GITHUB_URL = "https://github.com/ChEnLeo-7"
private const val QQ_GROUP_NUMBER = "3936095138"
private const val GITHUB_REPOSITORY_LABEL = "ChEnLeo-7/WallHub2.0-For-Android"
private const val GITHUB_REPOSITORY_URL =
    "https://github.com/ChEnLeo-7/WallHub2.0-For-Android"
private val WALLHUB_CONTRIBUTORS = listOf(
    "uwugl" to "https://github.com/uwu-gl",
    "cccp114" to "https://github.com/cccp114",
    "hf5203344" to "https://github.com/hf5203344",
)
private const val STEAM_API_KEY_URL = "https://steamcommunity.com/dev/apikey"
private const val SETTINGS_CATEGORY_INDEX_KEY = "settings-index"
private const val SETTINGS_PAGE_ENTER_DURATION_MS = 340
private const val SETTINGS_PAGE_EXIT_DURATION_MS = 230
private const val SETTINGS_PAGE_ENTER_OFFSET_DIVISOR = 9
private const val SETTINGS_PAGE_EXIT_OFFSET_DIVISOR = 18
private val SETTINGS_MEDIUM_WIDTH = 600.dp
private val SETTINGS_CONTENT_MAX_WIDTH = 760.dp
private val SETTINGS_SHEET_CONTENT_MAX_HEIGHT = 560.dp
private val SETTINGS_ITEM_MIN_HEIGHT = 64.dp
private val SETTINGS_CHOICE_OPTION_MIN_HEIGHT = 56.dp
private val SETTINGS_TRAILING_VALUE_MAX_WIDTH = 136.dp
private val SETTINGS_ACCENT_LABEL_MAX_WIDTH = 72.dp
private val STEAM_DOH_ITEM_HEIGHT = 84.dp
private val STEAM_DOH_ITEM_SPACING = 8.dp
private val SETTINGS_PAGE_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val MONET_HUE_PRESETS = listOf(
    0f,
    24f,
    48f,
    78f,
    120f,
    158f,
    194f,
    220f,
    254f,
    286f,
    318f,
    342f,
)
