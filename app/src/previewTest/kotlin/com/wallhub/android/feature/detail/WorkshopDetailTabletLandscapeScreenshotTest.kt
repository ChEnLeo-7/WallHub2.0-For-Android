package com.wallhub.android.feature.detail

import app.cash.paparazzi.Paparazzi
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import com.wallhub.android.testing.PadLandscapeAdaptiveContent
import com.wallhub.android.testing.PadLandscapeDevice
import org.junit.Rule
import org.junit.Test
import java.io.File

class WorkshopDetailTabletLandscapeScreenshotTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = PadLandscapeDevice,
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun monetLargePadUsesEmbeddedCommentsPane() {
        paparazzi.snapshot(name = "wallhub-detail-monet-large-pad-embedded-comments-2800x1968") {
            MonetLargePadDetailPreview()
        }
    }
}

@androidx.compose.runtime.Composable
private fun MonetLargePadDetailPreview() {
    WallHubTheme(
        preference = ThemePreference.DARK,
        useSystemMonet = false,
        visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
    ) {
        PadLandscapeAdaptiveContent {
            TabletDetailScreenPreview()
        }
    }
}

@androidx.compose.runtime.Composable
internal fun TabletDetailScreenPreview() {
    WorkshopDetailScreen(
        state = tabletDetailState,
        onBack = {},
        onRetry = {},
        onToggleSubscription = {},
        onToggleFavorite = {},
        onReconnectSteam = {},
        onStartInlineVideo = {},
        onRetryInlineVideo = {},
        onExportFormatSelected = {},
        onMpkgConversionPresetSelected = {},
        onPreserveAudioChanged = {},
        onDownload = {},
        onCommentsVisible = {},
        onRetryComments = {},
        onLoadMoreComments = {},
        onCommentDraftChanged = {},
        onSubmitComment = {},
        onInlineFullscreenChange = {},
        onSearchAuthor = {},
        onSearchTag = {},
        onCopyText = { _, _ -> },
        onOpenSteam = {},
        onOpenDownloads = {},
        onOpenSettings = {},
    )
}

private val tabletDetailPreviewFile =
    listOf(
        File("app/src/testFocused/res/drawable-nodpi/detail_icey_preview.png"),
        File("src/testFocused/res/drawable-nodpi/detail_icey_preview.png"),
    ).first(File::isFile).absoluteFile

private val tabletDetailPreviewUrl = tabletDetailPreviewFile.toURI().toString()

private val tabletDetailState =
    WorkshopDetailUiState(
        detail =
            WorkshopDetail(
                summary =
                    WorkshopSummary(
                        id = 3818033498L,
                        title = "ICEY艾希：续",
                        author = "Steam 创作者",
                        creatorId = "76561198000000042",
                        previewUrl = tabletDetailPreviewUrl,
                        type = WorkshopType.VIDEO,
                        tags = listOf("动画", "角色", "科幻", "16:9", "视频壁纸"),
                        subscriptions = 16_240,
                        favorites = 945,
                        views = 86_700,
                        fileSizeBytes = 64_100_000L,
                    ),
                description =
                    "高速战斗中的艾希停驻在冷色星光之间。动态粒子、发丝与环境光会随画面节奏变化，适合作为横屏桌面的视频壁纸。",
                fileSizeBytes = 64_100_000L,
                canPlay = false,
                updatedAt = 1_793_491_200L,
                subscriptions = 16_240,
            ),
        isLoading = false,
        interaction =
            WorkshopInteraction(
                subscriptionState = SubscriptionState.SUBSCRIBED,
                favoriteState = FavoriteState.FAVORITED,
            ),
        steamSession =
            SteamSessionState(
                phase = SteamSessionPhase.SIGNED_IN,
                accountName = "wallhub-preview",
                personaName = "WallHub Preview",
                isConnected = true,
            ),
        comments =
            listOf(
                WorkshopComment(author = "Lena", text = "灯光效果很好，蓝色粒子与角色动作衔接得很自然。", dateLabel = "今天"),
                WorkshopComment(author = "Kai", text = "横屏显示也很舒服，期待更多分辨率版本。", dateLabel = "昨天"),
                WorkshopComment(author = "Mira", text = "视频循环的首尾过渡很顺滑。", dateLabel = "3 天前"),
                WorkshopComment(author = "Noah", text = "在 OLED 屏幕上的冷色层次很漂亮。", dateLabel = "上周"),
            ),
        commentsTotal = 4,
    )

