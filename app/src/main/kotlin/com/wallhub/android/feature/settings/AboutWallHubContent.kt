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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.model.InstalledAppInfo
import org.uwuaosp.compose.settingslib.SettingsScaffold
import org.uwuaosp.compose.settingslib.PreferenceGroupSpacer
import org.uwuaosp.compose.settingslib.PreferencePosition
import org.uwuaosp.compose.settingslib.PreferenceRow
import org.uwuaosp.compose.settingslib.rememberSettingsTypography
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
internal fun AboutWallHubScreen(
    installed: InstalledAppInfo,
    appUpdateState: AppUpdateUiState,
    onBack: () -> Unit,
    onCheckForAppUpdate: () -> Unit,
    onDownloadLatestRelease: () -> Unit,
    onCancelAppUpdateDownload: () -> Unit,
    onInstallDownloadedRelease: (String) -> Unit,
    onOpenExternalUri: (String, String) -> Unit,
    onRestartSetupWizard: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme =
            colorScheme.copy(
                surfaceContainer = colorScheme.surfaceContainerLowest,
                surfaceBright = colorScheme.surfaceContainerLow,
            ),
        typography = rememberSettingsTypography(),
    ) {
        SettingsScaffold(
            title = stringResource(R.string.settings_about_wallhub_for_android),
            showBackButton = true,
            onNavigateUp = onBack,
            useCollapsingToolbar = false,
        ) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = SETTINGS_CONTENT_MAX_WIDTH)
                        .fillMaxWidth(),
            ) {
                SettingsSectionSurface(modifier = Modifier.fillMaxWidth()) {
                    WallHubProjectHeader()
                }
                Spacer(modifier = Modifier.height(WallHubSpacing.sm))
                AboutWallHubPreferences(
                    installed = installed,
                    appUpdateState = appUpdateState,
                    onCheckForAppUpdate = onCheckForAppUpdate,
                    onDownloadLatestRelease = onDownloadLatestRelease,
                    onCancelAppUpdateDownload = onCancelAppUpdateDownload,
                    onInstallDownloadedRelease = onInstallDownloadedRelease,
                    onOpenExternalUri = onOpenExternalUri,
                    onRestartSetupWizard = onRestartSetupWizard,
                )
            }
        }
    }
}

@Composable
internal fun AboutWallHubPreferences(
    installed: InstalledAppInfo,
    appUpdateState: AppUpdateUiState,
    onCheckForAppUpdate: () -> Unit,
    onDownloadLatestRelease: () -> Unit,
    onCancelAppUpdateDownload: () -> Unit,
    onInstallDownloadedRelease: (String) -> Unit,
    onOpenExternalUri: (String, String) -> Unit,
    onRestartSetupWizard: () -> Unit,
) {
    val openAuthorFailure = stringResource(R.string.settings_error_open_author_profile)
    val openContributorFailure = stringResource(R.string.settings_error_open_contributor_profile)
    val openQqGroupFailure = stringResource(R.string.settings_error_open_qq_group)
    val openRepositoryFailure = stringResource(R.string.settings_error_open_github_repository)

    WallHubPersonRow(
        person = WALLHUB_AUTHOR,
        position = PreferencePosition.Top,
        onClick = {
            onOpenExternalUri(
                WALLHUB_AUTHOR.githubUrl,
                openAuthorFailure,
            )
        },
    )
    WALLHUB_CONTRIBUTORS.forEachIndexed { index, contributor ->
        PreferenceGroupSpacer()
        WallHubPersonRow(
            person = contributor,
            position =
                if (index == WALLHUB_CONTRIBUTORS.lastIndex) {
                    PreferencePosition.Bottom
                } else {
                    PreferencePosition.Middle
                },
            onClick = {
                onOpenExternalUri(
                    contributor.githubUrl,
                    openContributorFailure,
                )
            },
        )
    }
    Spacer(modifier = Modifier.height(WallHubSpacing.sm))
    PreferenceRow(
        title = stringResource(R.string.settings_about_version),
        summary = stringResource(R.string.settings_about_current_version, installed.versionName, installed.versionCode),
        icon = Icons.Outlined.Refresh,
        position = PreferencePosition.Top,
        enabled = appUpdateState.phase != AppUpdatePhase.DOWNLOADING,
        onClick = onCheckForAppUpdate,
    )
    PreferenceGroupSpacer()
    PreferenceRow(
        title = stringResource(R.string.settings_about_update_date),
        summary = formatAppUpdateDate(installed.lastUpdateTimeMillis),
        icon = Icons.Outlined.Schedule,
        position = PreferencePosition.Middle,
        onClick = {},
    )
    PreferenceGroupSpacer()
    PreferenceRow(
        title = stringResource(R.string.settings_about_github_repository),
        summary = WALLHUB_REPOSITORY_LABEL,
        icon = Icons.Outlined.OpenInNew,
        position = PreferencePosition.Middle,
        onClick = {
            onOpenExternalUri(
                WALLHUB_REPOSITORY_URL,
                openRepositoryFailure,
            )
        },
    )
    PreferenceGroupSpacer()
    PreferenceRow(
        title = stringResource(R.string.settings_about_qq_group),
        summary = stringResource(R.string.settings_about_qq_group_description, WALLHUB_QQ_GROUP_NUMBER),
        icon = Icons.Outlined.ChatBubbleOutline,
        position = PreferencePosition.Bottom,
        onClick = {
            onOpenExternalUri(
                WALLHUB_QQ_GROUP_JOIN_URI,
                openQqGroupFailure,
            )
        },
    )
    Spacer(modifier = Modifier.height(WallHubSpacing.sm))
    PreferenceRow(
        title = stringResource(R.string.settings_restart_setup_wizard),
        summary = stringResource(R.string.settings_restart_setup_wizard_description),
        icon = Icons.Outlined.Settings,
        position = PreferencePosition.Single,
        onClick = onRestartSetupWizard,
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
    AboutUpdateActions(
        appUpdateState = appUpdateState,
        onDownloadLatestRelease = onDownloadLatestRelease,
        onCancelAppUpdateDownload = onCancelAppUpdateDownload,
        onInstallDownloadedRelease = onInstallDownloadedRelease,
    )
}

@Composable
private fun WallHubProjectHeader() {
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
    position: PreferencePosition,
    onClick: () -> Unit,
) {
    PreferenceRow(
        title = person.displayName,
        summary = stringResource(person.roleRes),
        iconContent = { GitHubAvatar(person = person) },
        position = position,
        onClick = onClick,
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
