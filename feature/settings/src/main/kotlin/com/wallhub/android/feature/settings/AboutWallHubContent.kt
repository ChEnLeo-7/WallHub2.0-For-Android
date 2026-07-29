package com.wallhub.android.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.mikepenz.markdown.m3.Markdown
import com.wallhub.android.core.designsystem.WallHubIcons as Icons
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.InstalledAppInfo
import java.util.Locale

internal data class WallHubPerson(
    val displayName: String,
    val githubAccount: String,
    val roleZh: String,
    val roleEn: String,
) {
    val githubUrl: String
        get() = "https://github.com/$githubAccount"

    val avatarUrl: String
        get() = "https://github.com/$githubAccount.png?size=160"

    fun role(language: AppLanguage): String = if (language == AppLanguage.EN) roleEn else roleZh
}

internal const val WALLHUB_QQ_GROUP_NUMBER = "1082323527"
internal const val WALLHUB_REPOSITORY_LABEL = "ChEnLeo-7/WallHub2.0-For-Android"
internal const val WALLHUB_REPOSITORY_URL =
    "https://github.com/ChEnLeo-7/WallHub2.0-For-Android"

internal val WALLHUB_AUTHOR = WallHubPerson(
    displayName = "CHENLEO_7",
    githubAccount = "ChEnLeo-7",
    roleZh = "作者 · 项目开发与维护",
    roleEn = "Author · Development and maintenance",
)

internal val WALLHUB_CONTRIBUTORS = listOf(
    WallHubPerson(
        displayName = "uwugl",
        githubAccount = "uwu-gl",
        roleZh = "LOGO 设计、技术指导",
        roleEn = "Logo design and technical guidance",
    ),
    WallHubPerson(
        displayName = "cccp114",
        githubAccount = "cccp114",
        roleZh = "LOGO 修改、UI 建议",
        roleEn = "Logo refinements and UI suggestions",
    ),
    WallHubPerson(
        displayName = "hf5203344",
        githubAccount = "hf5203344",
        roleZh = "参与了早期开发的深度内测",
        roleEn = "In-depth testing during early development",
    ),
)

@Composable
internal fun AboutWallHubContent(
    language: AppLanguage,
    installed: InstalledAppInfo,
    appUpdateState: AppUpdateUiState,
    onCheckForAppUpdate: () -> Unit,
    onDownloadLatestRelease: () -> Unit,
    onCancelAppUpdateDownload: () -> Unit,
    onInstallDownloadedRelease: (String) -> Unit,
) {
    val context = LocalContext.current
    val release = appUpdateState.release
    val releaseNotes = release?.notes?.trim()?.takeIf(String::isNotEmpty)
    var releaseNotesVisible by rememberSaveable(release?.tagName) { mutableStateOf(false) }
    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    AboutListItem(
        headline = installed.appName,
        supporting = language.aboutText(
            "版本 ${installed.versionName} (${installed.versionCode}) · ${installed.packageName}",
            "Version ${installed.versionName} (${installed.versionCode}) · ${installed.packageName}",
        ),
        leadingContent = { AboutIcon(Icons.Info) },
    )
    AboutDivider()
    WallHubPersonRow(
        person = WALLHUB_AUTHOR,
        language = language,
        onClick = { openUrl(WALLHUB_AUTHOR.githubUrl) },
    )
    WALLHUB_CONTRIBUTORS.forEach { contributor ->
        AboutDivider()
        WallHubPersonRow(
            person = contributor,
            language = language,
            onClick = { openUrl(contributor.githubUrl) },
        )
    }
    AboutDivider()
    AboutListItem(
        headline = language.aboutText("QQ 交流群", "QQ community group"),
        supporting = WALLHUB_QQ_GROUP_NUMBER,
        leadingContent = { AboutIcon(Icons.MessageCircle) },
        trailingContent = {
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("WallHub QQ", WALLHUB_QQ_GROUP_NUMBER),
                    )
                    Toast.makeText(
                        context,
                        language.aboutText("群号已复制", "Group number copied"),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            ) {
                Icon(
                    imageVector = Icons.Copy,
                    contentDescription = language.aboutText("复制群号", "Copy group number"),
                )
            }
        },
    )
    AboutDivider()
    AboutListItem(
        headline = language.aboutText("GitHub 仓库", "GitHub repository"),
        supporting = WALLHUB_REPOSITORY_LABEL,
        leadingContent = { AboutIcon(Icons.ExternalLink) },
        trailingContent = {
            Icon(imageVector = Icons.ExternalLink, contentDescription = null)
        },
        modifier = Modifier.clickable { openUrl(WALLHUB_REPOSITORY_URL) },
    )
    AboutDivider()
    AboutListItem(
        headline = appUpdateState.statusLabel(language),
        supporting = release?.let {
            language.aboutText(
                "最新 ${it.versionName} · ${formatAboutUpdateSize(it.assetSizeBytes)} · ${it.publishedAt.take(10)}",
                "Latest ${it.versionName} · ${formatAboutUpdateSize(it.assetSizeBytes)} · ${it.publishedAt.take(10)}",
            )
        } ?: language.aboutText(
            "当前 ${installed.versionName} (${installed.versionCode})",
            "Current ${installed.versionName} (${installed.versionCode})",
        ),
        leadingContent = { AboutIcon(Icons.Download) },
        trailingContent = {
            if (appUpdateState.phase == AppUpdatePhase.CHECKING) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(
                    onClick = onCheckForAppUpdate,
                    enabled = appUpdateState.phase != AppUpdatePhase.DOWNLOADING,
                ) {
                    Icon(
                        imageVector = Icons.RotateCw,
                        contentDescription = language.aboutText("检查更新", "Check for updates"),
                    )
                }
            }
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
                text = "${formatAboutUpdateSize(appUpdateState.downloadedBytes)} / " +
                    formatAboutUpdateSize(appUpdateState.totalBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (releaseNotes != null) {
        AboutDivider()
        AboutListItem(
            headline = language.aboutText("Release 说明", "Release notes"),
            supporting = language.aboutText("点击查看完整更新内容", "Open the complete update notes"),
            leadingContent = { AboutIcon(Icons.Info) },
            trailingContent = {
                Icon(imageVector = Icons.ChevronRight, contentDescription = null)
            },
            modifier = Modifier.clickable { releaseNotesVisible = true },
        )
    }
    AboutUpdateActions(
        language = language,
        appUpdateState = appUpdateState,
        onDownloadLatestRelease = onDownloadLatestRelease,
        onCancelAppUpdateDownload = onCancelAppUpdateDownload,
        onInstallDownloadedRelease = onInstallDownloadedRelease,
    )

    if (releaseNotesVisible && releaseNotes != null && release != null) {
        ReleaseNotesDialog(
            title = language.aboutText(
                "WallHub ${release.versionName} Release 说明",
                "WallHub ${release.versionName} release notes",
            ),
            markdown = releaseNotes,
            language = language,
            onOpenGitHub = { openUrl(release.htmlUrl) },
            onDismiss = { releaseNotesVisible = false },
        )
    }
}

@Composable
private fun WallHubPersonRow(
    person: WallHubPerson,
    language: AppLanguage,
    onClick: () -> Unit,
) {
    AboutListItem(
        headline = person.displayName,
        supporting = person.role(language),
        leadingContent = { GitHubAvatar(person = person, language = language) },
        trailingContent = {
            Icon(imageVector = Icons.ExternalLink, contentDescription = null)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun GitHubAvatar(
    person: WallHubPerson,
    language: AppLanguage,
) {
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(person.avatarUrl)
            .crossfade(true)
            .build(),
        contentDescription = language.aboutText(
            "${person.displayName} 的 GitHub 头像",
            "${person.displayName} GitHub avatar",
        ),
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (painter.state is AsyncImagePainter.State.Success) {
            SubcomposeAsyncImageContent()
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.UserRound,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun AboutUpdateActions(
    language: AppLanguage,
    appUpdateState: AppUpdateUiState,
    onDownloadLatestRelease: () -> Unit,
    onCancelAppUpdateDownload: () -> Unit,
    onInstallDownloadedRelease: (String) -> Unit,
) {
    val downloadedPath = appUpdateState.downloadedApkPath
    val hasActions = appUpdateState.phase == AppUpdatePhase.DOWNLOADING ||
        appUpdateState.canDownloadRelease || downloadedPath != null ||
        appUpdateState.message != null
    if (!hasActions) return

    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            appUpdateState.phase == AppUpdatePhase.DOWNLOADING -> OutlinedButton(
                onClick = onCancelAppUpdateDownload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(language.aboutText("取消下载", "Cancel download"))
            }

            downloadedPath != null -> Button(
                onClick = { onInstallDownloadedRelease(downloadedPath) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(imageVector = Icons.Download, contentDescription = null)
                Text(
                    text = language.aboutText("使用系统安装器安装", "Install with Android installer"),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            appUpdateState.canDownloadRelease -> FilledTonalButton(
                onClick = onDownloadLatestRelease,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(imageVector = Icons.Download, contentDescription = null)
                Text(
                    text = language.aboutText(
                        "下载最新版 universal APK",
                        "Download latest universal APK",
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        appUpdateState.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ReleaseNotesDialog(
    title: String,
    markdown: String,
    language: AppLanguage,
    onOpenGitHub: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .widthIn(max = 720.dp)
                .navigationBarsPadding(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, top = 12.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onOpenGitHub) {
                        Icon(
                            imageVector = Icons.ExternalLink,
                            contentDescription = language.aboutText(
                                "在 GitHub 打开",
                                "Open on GitHub",
                            ),
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.CircleX,
                            contentDescription = language.aboutText("关闭", "Close"),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val scrollState = rememberScrollState()
                Markdown(
                    content = markdown,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun AboutListItem(
    headline: String,
    supporting: String,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        modifier = modifier.heightIn(min = 72.dp),
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
private fun AboutIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun AboutDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

private fun AppUpdateUiState.statusLabel(language: AppLanguage): String = when (phase) {
    AppUpdatePhase.IDLE -> language.aboutText("版本更新", "Version update")
    AppUpdatePhase.CHECKING -> language.aboutText("正在检查更新…", "Checking for updates…")
    AppUpdatePhase.AVAILABLE -> language.aboutText(
        "发现新版本 ${release?.versionName.orEmpty()}",
        "Version ${release?.versionName.orEmpty()} is available",
    )
    AppUpdatePhase.UP_TO_DATE -> language.aboutText("当前已是最新版", "WallHub is up to date")
    AppUpdatePhase.DOWNLOADING -> language.aboutText("正在下载安装包", "Downloading APK")
    AppUpdatePhase.DOWNLOADED -> language.aboutText(
        "安装包已下载并通过校验",
        "APK downloaded and verified",
    )
    AppUpdatePhase.FAILED -> language.aboutText("更新操作失败", "Update operation failed")
}

private fun AppLanguage.aboutText(zh: String, en: String): String =
    if (this == AppLanguage.EN) en else zh

private fun formatAboutUpdateSize(bytes: Long): String = String.format(
    Locale.getDefault(),
    "%.1f MB",
    bytes.coerceAtLeast(0L) / (1024.0 * 1024.0),
)
