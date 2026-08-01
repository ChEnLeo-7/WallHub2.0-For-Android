@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.home

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.LocalWallHubToastState
import com.wallhub.android.core.designsystem.requiresLegacyPublicDownloadPermission
import com.wallhub.android.core.model.WorkshopSummary
import kotlinx.coroutines.launch

@Composable
fun HomeRoute(
    onOpenDetail: (Long) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onSearchAuthor: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    scrollToTopRequest: Int = 0,
    onContextMenuActiveChanged: (Boolean) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val onHomeAction: (HomeAction) -> Unit = { action ->
        if (action == HomeAction.SubmitSearch) {
            val requestedCreatorId = state.query.creatorIdOrNull()
            if (requestedCreatorId != null && (onBack == null || state.creatorId != requestedCreatorId)) {
                viewModel.onAction(HomeAction.RestoreUnsubmittedQuery)
                viewModel.onAction(HomeAction.SearchAuthor(requestedCreatorId))
            } else {
                viewModel.onAction(action)
            }
        } else {
            viewModel.onAction(action)
        }
    }
    HomeEffectHandler(
        viewModel = viewModel,
        onOpenDetail = onOpenDetail,
        onSearchAuthor = onSearchAuthor,
    )
    HomeScreen(
        state = state,
        onAction = onHomeAction,
        onOpenSettings = onOpenSettings,
        onBack = onBack,
        scrollToTopRequest = scrollToTopRequest,
        onContextMenuActiveChanged = onContextMenuActiveChanged,
    )
}

@Composable
fun HomeEffectHandler(
    viewModel: HomeViewModel,
    onOpenDetail: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val toast = LocalWallHubToastState.current
    val currentOnOpenDetail by rememberUpdatedState(onOpenDetail)
    val currentOnSearchAuthor by rememberUpdatedState(onSearchAuthor)
    var pendingLegacyStorageDownload by remember { mutableStateOf<WorkshopSummary?>(null) }
    val legacyStoragePermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val pendingItem = pendingLegacyStorageDownload ?: return@rememberLauncherForActivityResult
            pendingLegacyStorageDownload = null
            viewModel.onAction(HomeAction.LegacyStoragePermissionResult(pendingItem, granted))
        }
    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HomeEffect.ResolveLegacyStoragePermission -> {
                    if (context.requiresLegacyPublicDownloadPermission()) {
                        pendingLegacyStorageDownload = effect.item
                        legacyStoragePermissionLauncher.launch(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        )
                    } else {
                        viewModel.onAction(
                            HomeAction.LegacyStoragePermissionResult(
                                item = effect.item,
                                granted = true,
                            ),
                        )
                    }
                }
                is HomeEffect.ShowMessage ->
                    toast.show(context.getString(effect.messageRes, *effect.formatArgs.toTypedArray()))
                is HomeEffect.ShowMessageText -> toast.show(context.getString(R.string.home_unable_to_queue_download))
                is HomeEffect.OpenDetail -> currentOnOpenDetail(effect.workshopId)
                is HomeEffect.SearchAuthor -> currentOnSearchAuthor(effect.creator)
                is HomeEffect.CopyText -> {
                    clipboard.setText(AnnotatedString(effect.text))
                    toast.show(context.getString(effect.messageRes))
                }
                is HomeEffect.OpenSteam -> {
                    val intent =
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://steamcommunity.com/sharedfiles/filedetails/?id=${effect.workshopId}"),
                        )
                    runCatching { context.startActivity(intent) }
                        .onFailure { currentOnOpenDetail(effect.workshopId) }
                }
            }
        }
    }
}
