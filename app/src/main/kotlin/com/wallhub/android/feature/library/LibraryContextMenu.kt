package com.wallhub.android.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubActionSheetAction
import com.wallhub.android.core.designsystem.WallHubActionSheetActionEmphasis
import com.wallhub.android.core.designsystem.WallHubActionSheetHeader
import com.wallhub.android.core.designsystem.WallHubActionSheetMetadataItem
import com.wallhub.android.core.designsystem.WallHubActionSheetStat
import com.wallhub.android.core.designsystem.WallHubActionSheetState
import com.wallhub.android.core.designsystem.WallHubActionSheetTrigger
import com.wallhub.android.core.designsystem.WallHubModalBottomSheet
import com.wallhub.android.core.designsystem.WallHubSurfaceCard
import com.wallhub.android.core.designsystem.localizedTitle
import com.wallhub.android.core.designsystem.rememberWallHubActionSheetState
import com.wallhub.android.core.format.formatCompactCount
import com.wallhub.android.core.model.WorkshopAuthorPlaceholder
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType

internal typealias LibraryContextMenuCoordinator = WallHubActionSheetState<WorkshopSummary>

@Composable
internal fun rememberLibraryContextMenuCoordinator(): LibraryContextMenuCoordinator =
    rememberWallHubActionSheetState()

@Composable
internal fun LibraryContextMenuLayer(
    coordinator: LibraryContextMenuCoordinator,
    onActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(coordinator, coordinator.activeItem) {
        onActiveChanged(coordinator.activeItem != null)
    }
    DisposableEffect(coordinator) {
        onDispose { onActiveChanged(false) }
    }
    Column(modifier = modifier) { content() }
}

@Composable
internal fun LibraryContextMenuCard(
    item: WorkshopSummary,
    coordinator: LibraryContextMenuCoordinator,
    onOpen: () -> Unit,
    onSearchAuthor: () -> Unit,
    authorDisplayName: String?,
    onAuthorDisplayNameRequested: () -> Unit,
    onDownload: () -> Unit,
    removeActionLabel: String?,
    onRemoveFromCollection: () -> Unit,
    onPlayVideo: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onOpenSteam: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    WallHubActionSheetTrigger(
        state = coordinator,
        item = item,
        onClick = onOpen,
        clickLabel = stringResource(R.string.library_view_details),
        longClickLabel = stringResource(R.string.library_open_actions_menu),
        onLongPress = onAuthorDisplayNameRequested,
        modifier = modifier.fillMaxWidth().testTag("library-workshop-${item.id}"),
    ) {
        WallHubSurfaceCard(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            content = content,
        )
    }
    if (coordinator.activeItem?.id == item.id) {
        LibraryContextMenuSheet(
            item = item,
            coordinator = coordinator,
            onSearchAuthor = onSearchAuthor,
            authorDisplayName = authorDisplayName,
            onDownload = onDownload,
            removeActionLabel = removeActionLabel,
            onRemoveFromCollection = onRemoveFromCollection,
            onPlayVideo = onPlayVideo,
            onCopyText = onCopyText,
            onOpenSteam = onOpenSteam,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContextMenuSheet(
    item: WorkshopSummary,
    coordinator: LibraryContextMenuCoordinator,
    onSearchAuthor: () -> Unit,
    authorDisplayName: String?,
    onDownload: () -> Unit,
    removeActionLabel: String?,
    onRemoveFromCollection: () -> Unit,
    onPlayVideo: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onOpenSteam: () -> Unit,
) {
    val title = item.localizedTitle()
    val author =
        authorDisplayName
            ?: item.author.takeIf { item.authorPlaceholder == WorkshopAuthorPlaceholder.NONE }
            ?: stringResource(R.string.library_loading_steam_username)
    val wallpaperTitleCopiedMessage = stringResource(R.string.library_wallpaper_title_copied)
    val projectIdCopiedMessage = stringResource(R.string.library_project_id_copied)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dismiss = coordinator::dismiss

    WallHubModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            WallHubActionSheetHeader(
                previewModel = item.previewUrl,
                title = title,
                subtitle = author,
                contentDescription = stringResource(R.string.library_preview, title),
                stats =
                    buildList {
                        item.subscriptions?.let { count ->
                            add(
                                WallHubActionSheetStat(
                                    value = formatCompactCount(count),
                                    icon = Icons.Outlined.Download,
                                ),
                            )
                        }
                        item.favorites?.let { count ->
                            add(
                                WallHubActionSheetStat(
                                    value = formatCompactCount(count),
                                    icon = Icons.Outlined.FavoriteBorder,
                                ),
                            )
                        }
                        item.views?.let { count ->
                            add(
                                WallHubActionSheetStat(
                                    value = formatCompactCount(count),
                                    icon = Icons.Outlined.Visibility,
                                ),
                            )
                        }
                    },
            )
            WallHubActionSheetMetadataItem(
                label = stringResource(R.string.library_wallpaper_title),
                value = title,
                icon = Icons.Outlined.ContentCopy,
                onClick = {
                    onCopyText(title, wallpaperTitleCopiedMessage)
                    dismiss()
                },
            )
            WallHubActionSheetMetadataItem(
                label = stringResource(R.string.library_author),
                value = author,
                icon = Icons.Outlined.PersonOutline,
                onClick = {
                    dismiss()
                    onSearchAuthor()
                },
            )
            WallHubActionSheetMetadataItem(
                label = stringResource(R.string.library_project_id),
                value = item.id.toString(),
                icon = Icons.Outlined.ContentCopy,
                onClick = {
                    onCopyText(item.id.toString(), projectIdCopiedMessage)
                    dismiss()
                },
            )
            WallHubActionSheetAction(
                text = stringResource(R.string.library_download),
                icon = Icons.Outlined.Download,
                emphasis = WallHubActionSheetActionEmphasis.Primary,
                onClick = {
                    dismiss()
                    onDownload()
                },
            )
            if (removeActionLabel != null) {
                WallHubActionSheetAction(
                    text = removeActionLabel,
                    icon = Icons.Outlined.Cancel,
                    onClick = {
                        dismiss()
                        onRemoveFromCollection()
                    },
                )
            }
            if (item.type == WorkshopType.VIDEO) {
                WallHubActionSheetAction(
                    text = stringResource(R.string.library_open_video_details),
                    icon = Icons.Outlined.PlayArrow,
                    onClick = {
                        dismiss()
                        onPlayVideo()
                    },
                )
            }
            WallHubActionSheetAction(
                text = stringResource(R.string.library_open_in_steam),
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                onClick = {
                    dismiss()
                    onOpenSteam()
                },
            )
        }
    }
}
