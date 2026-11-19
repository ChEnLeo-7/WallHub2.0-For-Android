@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.home

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.LocalWallHubOverlayBottomClearance
import com.wallhub.android.core.designsystem.requiresLegacyPublicDownloadPermission
import kotlinx.coroutines.launch

@Composable
fun HomeRoute(
    onOpenDetail: (Long) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onOpenDownloads: () -> Unit = {},
    onSearchAuthor: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    scrollToTopRequest: Int = 0,
    resetRequest: Int = 0,
    onContextMenuActiveChanged: (Boolean) -> Unit = {},
    onBottomNavigationCollapsedChanged: (Boolean) -> Unit = {},
    floatingBottomBarAutoHideActive: Boolean = false,
    floatingNavigation: @Composable BoxScope.() -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    var handledResetRequest by rememberSaveable { mutableIntStateOf(resetRequest) }
    LaunchedEffect(resetRequest) {
        if (resetRequest > handledResetRequest) {
            focusManager.clearFocus(force = true)
            viewModel.onAction(HomeAction.ResetAndRefresh)
            handledResetRequest = resetRequest
        }
    }
    val onHomeAction: (HomeAction) -> Unit = { action ->
        if (action == HomeAction.SubmitSearch) {
            val requestedWorkshopId = state.query.workshopIdOrNull()
            val requestedCreatorId = state.query.creatorIdOrNull()
            if (requestedWorkshopId != null) {
                viewModel.onAction(HomeAction.OpenDetail(requestedWorkshopId))
            } else if (requestedCreatorId != null && (onBack == null || state.creatorId != requestedCreatorId)) {
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
        onOpenDownloads = onOpenDownloads,
        snackbarHostState = snackbarHostState,
    )
    Box(modifier = Modifier.fillMaxSize()) {
        HomeScreen(
            state = state,
            onAction = onHomeAction,
            onOpenSettings = onOpenSettings,
            onBack = onBack,
            scrollToTopRequest = scrollToTopRequest,
            onContextMenuActiveChanged = onContextMenuActiveChanged,
            onBottomNavigationCollapsedChanged = onBottomNavigationCollapsedChanged,
            floatingBottomBarAutoHideActive = floatingBottomBarAutoHideActive,
            floatingNavigation = floatingNavigation,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = LocalWallHubOverlayBottomClearance.current + 16.dp)
                    .padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun HomeEffectHandler(
    viewModel: HomeViewModel,
    onOpenDetail: (Long) -> Unit,
    onSearchAuthor: (String) -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val unableToQueueDownload = stringResource(R.string.home_unable_to_queue_download)
    val clipboard = LocalClipboardManager.current
    val currentOnOpenDetail by rememberUpdatedState(onOpenDetail)
    val currentOnSearchAuthor by rememberUpdatedState(onSearchAuthor)
    val currentOnOpenDownloads by rememberUpdatedState(onOpenDownloads)
    var pendingLegacyStorageDownload by remember { mutableStateOf<HomeAction.RequestDownload?>(null) }
    val legacyStoragePermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val pendingRequest = pendingLegacyStorageDownload ?: return@rememberLauncherForActivityResult
            pendingLegacyStorageDownload = null
            viewModel.onAction(HomeAction.LegacyStoragePermissionResult(pendingRequest, granted))
        }
    LaunchedEffect(viewModel, context, resources, unableToQueueDownload) {
        viewModel.effects.collectHomeEffects(
            showDownloadConfirmation = { effect ->
                val message = resources.getString(effect.messageRes, *effect.formatArgs.toTypedArray())
                snackbarHostState.currentSnackbarData?.dismiss()
                val result =
                    snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = resources.getString(R.string.downloads_view_queue),
                        duration = SnackbarDuration.Long,
                    )
                if (result == SnackbarResult.ActionPerformed) currentOnOpenDownloads()
            },
        ) { effect ->
            when (effect) {
                is HomeEffect.ResolveLegacyStoragePermission -> {
                    if (context.requiresLegacyPublicDownloadPermission()) {
                        pendingLegacyStorageDownload = effect.request
                        legacyStoragePermissionLauncher.launch(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        )
                    } else {
                        viewModel.onAction(
                            HomeAction.LegacyStoragePermissionResult(
                                request = effect.request,
                                granted = true,
                            ),
                        )
                    }
                }
                is HomeEffect.ShowMessage -> {
                    val message = resources.getString(effect.messageRes, *effect.formatArgs.toTypedArray())
                    Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
                }
                is HomeEffect.ShowMessageText ->
                    Toast.makeText(context.applicationContext, unableToQueueDownload, Toast.LENGTH_SHORT).show()
                is HomeEffect.OpenDetail -> currentOnOpenDetail(effect.workshopId)
                is HomeEffect.SearchAuthor -> currentOnSearchAuthor(effect.creator)
                is HomeEffect.CopyText -> {
                    clipboard.setText(AnnotatedString(effect.text))
                    Toast.makeText(
                        context.applicationContext,
                        resources.getString(effect.messageRes),
                        Toast.LENGTH_SHORT,
                    ).show()
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
