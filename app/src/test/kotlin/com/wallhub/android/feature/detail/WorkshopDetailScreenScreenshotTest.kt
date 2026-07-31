package com.wallhub.android.feature.detail

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import org.junit.Rule
import org.junit.Test

class WorkshopDetailCompactScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, showSystemUi = false)

    @Test
    fun loadedDarkEnglish() {
        paparazzi.snapshot { workshopDetailScreenshotContent() }
    }
}

class WorkshopDetailScreenScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_7, showSystemUi = false)

    @Test
    fun loadedDarkEnglish() {
        paparazzi.snapshot { workshopDetailScreenshotContent() }
    }
}

class WorkshopDetailExpandedScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_10, showSystemUi = false)

    @Test
    fun loadedDarkEnglish() {
        paparazzi.snapshot { workshopDetailScreenshotContent() }
    }
}

@Composable
private fun workshopDetailScreenshotContent() {
    WallHubTheme(
        preference = ThemePreference.DARK,
        language = AppLanguage.EN,
        useSystemMonet = false,
    ) {
        WorkshopDetailScreen(
            state = workshopDetailScreenshotState,
            onBack = {},
            onRetry = {},
            onToggleSubscription = {},
            onToggleFavorite = {},
            onStartInlineVideo = {},
            onExportFormatSelected = {},
            onDownload = {},
            onConvertExisting = {},
            onRetryComments = {},
            onLoadMoreComments = {},
            onCommentDraftChanged = {},
            onSubmitComment = {},
            onInlineFullscreenChange = {},
            onSearchAuthor = {},
            onCopyText = { _, _ -> },
            onOpenSteam = {},
        )
    }
}

private val workshopDetailScreenshotState =
    WorkshopDetailUiState(
        detail =
            WorkshopDetail(
                summary =
                    WorkshopSummary(
                        id = 42L,
                        title = "Midnight Observatory",
                        author = "Deep Orbit",
                        creatorId = "76561198000000042",
                        type = WorkshopType.SCENE,
                        tags = listOf("Sci-Fi", "Relaxing", "4K"),
                        subscriptions = 12_840,
                        favorites = 3_210,
                    ),
                description = "A quiet observatory above the clouds with a procedural night sky.",
                fileSizeBytes = 214_000_000L,
                canPlay = false,
                subscriptions = 12_840,
            ),
        isLoading = false,
        interaction =
            WorkshopInteraction(
                subscriptionState = SubscriptionState.SUBSCRIBED,
                favoriteState = FavoriteState.FAVORITED,
            ),
        comments =
            listOf(
                WorkshopComment(author = "Lena", text = "The lighting is excellent.", dateLabel = "Today"),
                WorkshopComment(author = "Kai", text = "Looks great on an ultrawide display.", dateLabel = "Yesterday"),
            ),
        commentsTotal = 2,
    )
