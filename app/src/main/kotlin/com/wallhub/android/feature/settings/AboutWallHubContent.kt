@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.mikepenz.markdown.m3.Markdown
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.model.InstalledAppInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

internal data class WallHubPerson(
    val displayName: String,
    val githubAccount: String,
    @StringRes val roleRes: Int,
) {
    val githubUrl: String
        get() = "https://github.com/$githubAccount"

    val avatarUrl: String
        get() = "https://github.com/$githubAccount.png?size=160"
}

internal const val WALLHUB_QQ_GROUP_NUMBER = "1082323527"
internal const val WALLHUB_QQ_GROUP_JOIN_URI =
    "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$WALLHUB_QQ_GROUP_NUMBER&card_type=group&source=qrcode"
internal const val WALLHUB_REPOSITORY_LABEL = "ChEnLeo-7/WallHub2.0-For-Android"
internal const val WALLHUB_REPOSITORY_URL =
    "https://github.com/ChEnLeo-7/WallHub2.0-For-Android"

internal val WALLHUB_AUTHOR =
    WallHubPerson(
        displayName = "CHENLEO_7",
        githubAccount = "ChEnLeo-7",
        roleRes = R.string.settings_about_role_author,
    )

internal val WALLHUB_CONTRIBUTORS =
    listOf(
        WallHubPerson(
            displayName = "uwugl",
            githubAccount = "uwu-gl",
            roleRes = R.string.settings_about_role_logo_guidance,
        ),
        WallHubPerson(
            displayName = "cccp114",
            githubAccount = "cccp114",
            roleRes = R.string.settings_about_role_logo_ui,
        ),
        WallHubPerson(
            displayName = "hf5203344",
            githubAccount = "hf5203344",
            roleRes = R.string.settings_about_role_early_testing,
        ),
    )

@Composable
internal fun AboutWallHubContent(
    installed: InstalledAppInfo,
    appUpdateState: AppUpdateUiState,
    onCheckForAppUpdate: () -> Unit,
    onDownloadLatestRelease: () -> Unit,
    onCancelAppUpdateDownload: () -> Unit,
    onInstallDownloadedRelease: (String) -> Unit,
    onOpenExternalUri: (String, String) -> Unit,
) {
    val release = appUpdateState.release
    val releaseNotes = release?.notes?.trim()?.takeIf(String::isNotEmpty)
    var releaseNotesVisible by rememberSaveable(release?.tagName) { mutableStateOf(false) }
    val openAuthorFailure = stringResource(R.string.settings_error_open_author_profile)
    val openContributorFailure = stringResource(R.string.settings_error_open_contributor_profile)
    val openQqGroupFailure = stringResource(R.string.settings_error_open_qq_group)
    val openRepositoryFailure = stringResource(R.string.settings_error_open_github_repository)
    val openReleaseFailure = stringResource(R.string.settings_error_open_release_page)

    WallHubProjectHeader(
        installed = installed,
    )
    AboutDivider()
    WallHubPersonRow(
        person = WALLHUB_AUTHOR,
        onClick = {
            onOpenExternalUri(
                WALLHUB_AUTHOR.githubUrl,
                openAuthorFailure,
            )
        },
    )
    WALLHUB_CONTRIBUTORS.forEach { contributor ->
        AboutDivider()
        WallHubPersonRow(
            person = contributor,
            onClick = {
                onOpenExternalUri(
                    contributor.githubUrl,
                    openContributorFailure,
                )
            },
        )
    }
    AboutDivider()
    AboutListItem(
        headline = stringResource(R.string.settings_about_qq_group),
        supporting = stringResource(R.string.settings_about_qq_group_description, WALLHUB_QQ_GROUP_NUMBER),
        leadingContent = { AboutIcon(Icons.Outlined.ChatBubbleOutline) },
        trailingContent = {
            Icon(imageVector = Icons.Outlined.OpenInNew, contentDescription = null)
        },
        modifier =
            Modifier.clickable {
                onOpenExternalUri(
                    WALLHUB_QQ_GROUP_JOIN_URI,
                    openQqGroupFailure,
                )
            },
    )
    AboutDivider()
    AboutListItem(
        headline = stringResource(R.string.settings_about_github_repository),
        supporting = WALLHUB_REPOSITORY_LABEL,
        leadingContent = { AboutIcon(Icons.Outlined.OpenInNew) },
        trailingContent = {
            Icon(imageVector = Icons.Outlined.OpenInNew, contentDescription = null)
        },
        modifier =
            Modifier.clickable {
                onOpenExternalUri(
                    WALLHUB_REPOSITORY_URL,
                    openRepositoryFailure,
                )
            },
    )
    AboutDivider()
    AboutListItem(
        headline = appUpdateState.statusLabel(),
        supporting =
            release?.let {
                stringResource(
                    R.string.settings_about_latest_version,
                    it.versionName,
                    formatAboutUpdateSize(it.assetSizeBytes),
                    it.publishedAt.take(10),
                )
            } ?: stringResource(R.string.settings_about_current_version, installed.versionName, installed.versionCode),
        leadingContent = { AboutIcon(Icons.Outlined.Download) },
        trailingContent = {
            if (appUpdateState.phase == AppUpdatePhase.CHECKING) {
                Box(modifier = Modifier.size(WallHubSpacing.xxl), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(WallHubSpacing.lg), strokeWidth = WallHubSpacing.xxxs)
                }
            } else {
                IconButton(
                    onClick = onCheckForAppUpdate,
                    enabled = appUpdateState.phase != AppUpdatePhase.DOWNLOADING,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.settings_action_check_for_updates),
                    )
                }
            }
        },
    )
    if (appUpdateState.phase == AppUpdatePhase.DOWNLOADING) {
        Column(
            modifier = Modifier.padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.dense),
        ) {
            LinearProgressIndicator(
                progress = { appUpdateState.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text =
                    "${formatAboutUpdateSize(appUpdateState.downloadedBytes)} / " +
                        formatAboutUpdateSize(appUpdateState.totalBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (releaseNotes != null) {
        AboutDivider()
        AboutListItem(
            headline = stringResource(R.string.settings_about_release_notes),
            supporting = stringResource(R.string.settings_about_release_notes_description),
            leadingContent = { AboutIcon(Icons.Outlined.Info) },
            trailingContent = {
                Icon(imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.clickable { releaseNotesVisible = true },
        )
    }
    AboutUpdateActions(
        appUpdateState = appUpdateState,
        onDownloadLatestRelease = onDownloadLatestRelease,
        onCancelAppUpdateDownload = onCancelAppUpdateDownload,
        onInstallDownloadedRelease = onInstallDownloadedRelease,
    )

    if (releaseNotesVisible && releaseNotes != null) {
        ReleaseNotesDialog(
            title =
                stringResource(R.string.settings_about_release_notes_title, release.versionName),
            markdown = releaseNotes,
            onOpenGitHub = {
                onOpenExternalUri(
                    release.htmlUrl,
                    openReleaseFailure,
                )
            },
            onDismiss = { releaseNotesVisible = false },
        )
    }
}

@Composable
private fun WallHubProjectHeader(installed: InstalledAppInfo) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = WallHubSpacing.lg, vertical = WallHubSpacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.compact),
    ) {
        Image(
            painter = painterResource(R.drawable.wallhub_logo),
            contentDescription = stringResource(R.string.settings_about_wallhub_logo),
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .size(156.dp)
                    .clip(MaterialTheme.shapes.extraLarge),
        )
        Text(
            text = stringResource(R.string.settings_wallhub_project_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text =
                stringResource(
                    R.string.settings_about_version_updated,
                    installed.versionName,
                    installed.versionCode,
                    formatAppUpdateDate(installed.lastUpdateTimeMillis),
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text =
                stringResource(R.string.settings_about_project_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp),
        )
    }
}

@Composable
private fun WallHubPersonRow(
    person: WallHubPerson,
    onClick: () -> Unit,
) {
    AboutListItem(
        headline = person.displayName,
        supporting = stringResource(person.roleRes),
        leadingContent = { GitHubAvatar(person = person) },
        trailingContent = {
            Icon(imageVector = Icons.Outlined.OpenInNew, contentDescription = null)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun GitHubAvatar(person: WallHubPerson) {
    SubcomposeAsyncImage(
        model =
            ImageRequest
                .Builder(LocalContext.current)
                .data(person.avatarUrl)
                .crossfade(true)
                .build(),
        contentDescription =
            stringResource(R.string.settings_about_github_avatar, person.displayName),
        contentScale = ContentScale.Crop,
        modifier =
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (painter.state is AsyncImagePainter.State.Success) {
            SubcomposeAsyncImageContent()
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.PersonOutline,
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
    appUpdateState: AppUpdateUiState,
    onDownloadLatestRelease: () -> Unit,
    onCancelAppUpdateDownload: () -> Unit,
    onInstallDownloadedRelease: (String) -> Unit,
) {
    val downloadedPath = appUpdateState.downloadedApkPath
    val hasActions =
        appUpdateState.phase == AppUpdatePhase.DOWNLOADING ||
            appUpdateState.canDownloadRelease ||
            downloadedPath != null ||
            appUpdateState.message != null
    if (!hasActions) return

    Column(
        modifier = Modifier.padding(start = WallHubSpacing.md, end = WallHubSpacing.md, bottom = WallHubSpacing.md),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
    ) {
        when {
            appUpdateState.phase == AppUpdatePhase.DOWNLOADING ->
                OutlinedButton(
                    onClick = onCancelAppUpdateDownload,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_action_cancel_download))
                }

            downloadedPath != null ->
                Button(
                    onClick = { onInstallDownloadedRelease(downloadedPath) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(imageVector = Icons.Outlined.Download, contentDescription = null)
                    Text(
                        text = stringResource(R.string.settings_action_install_with_android_installer),
                        modifier = Modifier.padding(start = WallHubSpacing.xs),
                    )
                }

            appUpdateState.canDownloadRelease ->
                FilledTonalButton(
                    onClick = onDownloadLatestRelease,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(imageVector = Icons.Outlined.Download, contentDescription = null)
                    Text(
                        text =
                            stringResource(R.string.settings_action_download_latest_apk),
                        modifier = Modifier.padding(start = WallHubSpacing.xs),
                    )
                }
        }
        appUpdateState.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = WallHubSpacing.xxs, vertical = WallHubSpacing.xxs),
            )
        }
    }
}

@Composable
private fun ReleaseNotesDialog(
    title: String,
    markdown: String,
    onOpenGitHub: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .widthIn(max = 720.dp)
                    .navigationBarsPadding(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = WallHubSpacing.none,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = WallHubSpacing.lg,
                                top = WallHubSpacing.sm,
                                end = WallHubSpacing.xs,
                                bottom = WallHubSpacing.xs,
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xxs),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onOpenGitHub) {
                        Icon(
                            imageVector = Icons.Outlined.OpenInNew,
                            contentDescription =
                                stringResource(R.string.settings_action_open_on_github),
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Cancel,
                            contentDescription = stringResource(R.string.settings_action_close),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val scrollState = rememberScrollState()
                Markdown(
                    content = markdown,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(horizontal = WallHubSpacing.lg, vertical = WallHubSpacing.content),
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
        colors =
            ListItemDefaults.colors(
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
        modifier = Modifier.size(WallHubSpacing.xxl),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(WallHubSpacing.lg))
        }
    }
}

@Composable
private fun AboutDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = WallHubSpacing.md),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun AppUpdateUiState.statusLabel(): String =
    when (phase) {
        AppUpdatePhase.IDLE -> stringResource(R.string.settings_about_version_update)
        AppUpdatePhase.CHECKING -> stringResource(R.string.settings_about_checking_updates)
        AppUpdatePhase.AVAILABLE ->
            stringResource(R.string.settings_about_update_available, release?.versionName.orEmpty())
        AppUpdatePhase.UP_TO_DATE -> stringResource(R.string.settings_about_up_to_date)
        AppUpdatePhase.DOWNLOADING -> stringResource(R.string.settings_about_downloading_apk)
        AppUpdatePhase.DOWNLOADED ->
            stringResource(R.string.settings_about_apk_verified)
        AppUpdatePhase.FAILED -> stringResource(R.string.settings_about_update_failed)
    }

internal fun formatAppUpdateDate(
    epochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String =
    if (epochMillis > 0L) {
        Instant
            .ofEpochMilli(epochMillis)
            .atZone(zoneId)
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
    } else {
        "—"
    }

private fun formatAboutUpdateSize(bytes: Long): String =
    String.format(
        Locale.getDefault(),
        "%.1f MB",
        bytes.coerceAtLeast(0L) / (1024.0 * 1024.0),
    )
