@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.wallhub.android.core.designsystem.WallHubAppSurface
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.AccentPreference
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
import com.wallhub.android.preview.DeviceStatusBar
import com.wallhub.android.preview.GestureHandle
import java.io.File
import org.junit.Rule
import org.junit.Test

class WorkshopDetailPhysicalDeviceScreenshotTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = PhysicalPhoneDevice,
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun detailMd3PhysicalPhone() {
        paparazzi.gif("wallhub-detail-md3-1440x3168-560dpi", 0L, 2_400L, 30) {
            PhysicalDetailPreview(VisualStyle.MATERIAL3) {
                FullDetailScreenPreview()
            }
        }
    }

    @Test
    fun detailMd3ExpressivePhysicalPhone() {
        paparazzi.snapshot(name = "wallhub-detail-md3e-1440x3168-560dpi") {
            PhysicalDetailPreview(VisualStyle.MATERIAL3_EXPRESSIVE) {
                FullDetailScreenPreview()
            }
        }
    }

    @Test
    fun commentsMd3PhysicalPhone() {
        paparazzi.gif("wallhub-comments-md3-1440x3168-560dpi", 0L, 2_400L, 30) {
            PhysicalDetailPreview(VisualStyle.MATERIAL3) {
                CommentsScreenPreview()
            }
        }
    }

    @Test
    fun commentsMd3ExpressivePhysicalPhone() {
        paparazzi.snapshot(name = "wallhub-comments-md3e-1440x3168-560dpi") {
            PhysicalDetailPreview(VisualStyle.MATERIAL3_EXPRESSIVE) {
                CommentsScreenPreview()
            }
        }
    }
}

private val PhysicalPhoneDevice =
    DeviceConfig.PIXEL_5.copy(
        screenWidth = 1440,
        screenHeight = 3168,
        xdpi = 560,
        ydpi = 560,
        density = Density.create(560),
        fontScale = 1.0f,
        locale = "zh-rCN",
    )

@Composable
private fun PhysicalDetailPreview(
    visualStyle: VisualStyle,
    content: @Composable () -> Unit,
) {
    WallHubTheme(
        preference = ThemePreference.DARK,
        accent = AccentPreference.BLUE,
        useSystemMonet = false,
        visualStyle = visualStyle,
    ) {
        val density = LocalDensity.current
        WallHubAppSurface(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize().padding(
                        top = with(density) { 160.toDp() },
                        bottom = with(density) { 160.toDp() },
                    ),
                ) {
                    content()
                }
                DeviceStatusBar(modifier = Modifier.align(Alignment.TopCenter), dark = true)
                GestureHandle(modifier = Modifier.align(Alignment.BottomCenter), dark = true)
            }
        }
    }
}

@Composable
private fun FullDetailScreenPreview() {
    WorkshopDetailScreen(
        state = physicalDetailState,
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

@Composable
private fun CommentsScreenPreview() {
    FullDetailScreenPreview()
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.LaunchedEffect(view) {
        kotlinx.coroutines.delay(600)
        val owners = mutableListOf<androidx.compose.ui.semantics.SemanticsOwner>()
        fun visit(candidate: android.view.View) {
            candidate.javaClass.methods.firstOrNull {
                it.name == "getSemanticsOwner" && it.parameterCount == 0
            }?.let { method ->
                (method.invoke(candidate) as? androidx.compose.ui.semantics.SemanticsOwner)?.let(owners::add)
            }
            if (candidate is android.view.ViewGroup) {
                repeat(candidate.childCount) { visit(candidate.getChildAt(it)) }
            }
        }
        visit(view.rootView)
        fun findCommentTab(node: androidx.compose.ui.semantics.SemanticsNode): androidx.compose.ui.semantics.SemanticsNode? {
            val text = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
            if (text?.any { it.text.startsWith("评论") } == true &&
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null
            ) return node
            return node.children.firstNotNullOfOrNull(::findCommentTab)
        }
        val tab = owners.firstNotNullOfOrNull { findCommentTab(it.rootSemanticsNode) }
        checkNotNull(tab) { "Actual comments Tab not found in WorkshopDetailScreen" }
        check(tab.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke() == true)
    }
}

private val physicalCoverFile =
    listOf(
        File("app/src/testFocused/res/drawable-nodpi/detail_icey_preview.png"),
        File("src/testFocused/res/drawable-nodpi/detail_icey_preview.png"),
    ).first(File::isFile).absoluteFile

private val physicalDetail =
    WorkshopDetail(
        summary =
            WorkshopSummary(
                id = 3_818_033_498L,
                title = "ICEY 艾希：续",
                author = "Steam 创作者",
                creatorId = "76561198000000042",
                previewUrl = physicalCoverFile.toURI().toString(),
                type = WorkshopType.VIDEO,
                tags = listOf("动画", "角色", "科幻", "16:9", "视频壁纸"),
                subscriptions = 16_240,
                favorites = 945,
                views = 86_700,
                fileSizeBytes = 64_100_000L,
            ),
        description = "高速战斗中的艾希停驻在冷色星光之间。动态粒子、发丝与环境光会随画面节奏变化，适合作为横屏桌面的视频壁纸。",
        fileSizeBytes = 64_100_000L,
        canPlay = false,
        updatedAt = 1_793_491_200L,
        subscriptions = 16_240,
    )

private val physicalComments =
    listOf(
        WorkshopComment(author = "Lena", text = "灯光效果很好，蓝色粒子与角色动作衔接得很自然。", dateLabel = "今天"),
        WorkshopComment(author = "Kai", text = "横屏显示也很舒服，期待更多分辨率版本。", dateLabel = "昨天"),
        WorkshopComment(author = "Mira", text = "视频循环的首尾过渡很顺滑。", dateLabel = "3 天前"),
        WorkshopComment(author = "Noah", text = "在 OLED 屏幕上的冷色层次很漂亮。", dateLabel = "上周"),
    )

private val physicalDetailState =
    WorkshopDetailUiState(
        detail = physicalDetail,
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
        comments = physicalComments,
        commentsTotal = physicalComments.size,
    )
