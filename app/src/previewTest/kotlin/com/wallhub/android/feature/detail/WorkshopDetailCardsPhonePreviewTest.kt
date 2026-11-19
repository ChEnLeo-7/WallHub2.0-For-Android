@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.BitmapFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubAppSurface
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.designsystem.localizedAuthor
import java.io.ByteArrayInputStream
import java.io.File
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopDetail
import com.wallhub.android.core.model.WorkshopSummary
import com.wallhub.android.core.model.WorkshopType
import org.junit.Rule
import org.junit.Test

class WorkshopDetailCardsPhonePreviewTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "zh-rCN"),
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun overviewCardsMd3Phone() {
        paparazzi.snapshot(name = "wallhub-detail-overview-cards-md3-phone") {
            DetailCardsPreview(VisualStyle.MATERIAL3) {
                OverviewPhonePage()
            }
        }
    }

    @Test
    fun overviewCardsMd3ExpressivePhone() {
        paparazzi.snapshot(name = "wallhub-detail-overview-cards-md3-expressive-phone") {
            DetailCardsPreview(VisualStyle.MATERIAL3_EXPRESSIVE) {
                OverviewPhonePage()
            }
        }
    }

    @Test
    fun commentCardsMd3Phone() {
        paparazzi.snapshot(name = "wallhub-detail-comment-cards-md3-phone") {
            DetailCardsPreview(VisualStyle.MATERIAL3) {
                DetailCommentsPage(
                    comments = previewComments,
                    commentsHasMore = false,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null,
                    canPostComment = true,
                    commentDraft = "",
                    isPostingComment = false,
                    commentPostError = null,
                    onRetry = {},
                    onLoadMore = {},
                    onCommentDraftChanged = {},
                    onSubmitComment = {},
                    onComposerHeightChanged = {},
                    isWallpaperHeaderCollapsed = false,
                    onReturnToWallpaperTop = {},
                )
            }
        }
    }

    @Test
    fun commentCardsMd3ExpressivePhone() {
        paparazzi.snapshot(name = "wallhub-detail-comment-cards-md3-expressive-phone") {
            DetailCardsPreview(VisualStyle.MATERIAL3_EXPRESSIVE) {
                DetailCommentsPage(
                    comments = previewComments,
                    commentsHasMore = false,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null,
                    canPostComment = true,
                    commentDraft = "",
                    isPostingComment = false,
                    commentPostError = null,
                    onRetry = {},
                    onLoadMore = {},
                    onCommentDraftChanged = {},
                    onSubmitComment = {},
                    onComposerHeightChanged = {},
                    isWallpaperHeaderCollapsed = false,
                    onReturnToWallpaperTop = {},
                )
            }
        }
    }
}

@Composable
private fun OverviewPhonePage() {
    val style = com.wallhub.android.core.designsystem.wallHubStyle()
    Column(Modifier.fillMaxSize()) {
        Image(
            bitmap = previewCoverBitmap,
            contentDescription = previewDetail.summary.title,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(horizontal = WallHubSpacing.md)
                    .clip(style.shapes.detailHero),
        )
        Column(
            modifier = Modifier.padding(horizontal = WallHubSpacing.md, vertical = WallHubSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
        ) {
            Text(
                text = previewDetail.summary.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
            Text(
                text = previewDetail.summary.localizedAuthor(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = WallHubSpacing.md),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text(
                    text = stringResource(R.string.detail_details),
                    modifier = Modifier.padding(horizontal = WallHubSpacing.content, vertical = WallHubSpacing.sm),
                )
            }
            Text(
                text = stringResource(R.string.detail_comments_count, previewComments.size),
                modifier = Modifier.padding(WallHubSpacing.sm),
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            DetailOverviewPage(
                detail = previewDetail,
                onCopyWorkshopId = {},
                onSearchAuthor = {},
                onSearchTag = {},
            )
        }
    }
}

@Composable
private fun DetailCardsPreview(
    visualStyle: VisualStyle,
    content: @Composable () -> Unit,
) {
    WallHubTheme(
        preference = ThemePreference.DARK,
        accent = AccentPreference.BLUE,
        useSystemMonet = false,
        visualStyle = visualStyle,
    ) {
        WallHubAppSurface(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

private val previewCoverBitmap =
    BitmapFactory
        .decodeStream(
            ByteArrayInputStream(
                listOf(
                    File("app/src/testFocused/res/drawable-nodpi/detail_icey_preview.png"),
                    File("src/testFocused/res/drawable-nodpi/detail_icey_preview.png"),
                ).first(File::isFile).readBytes(),
            ),
        ).asImageBitmap()

private val previewDetail =
    WorkshopDetail(
        summary =
            WorkshopSummary(
                id = 3_821_898_40L,
                title = "Deepseek 图鲸鲸 · 大肥鱼数字生命控制台",
                author = "wdsjdyx",
                creatorId = "76561198000000042",
                type = WorkshopType.WEB,
                tags = listOf("Web", "Anime", "Wallpaper", "Audio responsive", "Customizable", "Dynamic resolution", "Everyone"),
                subscriptions = 29_000,
                favorites = 1_200,
                views = 4_500,
                fileSizeBytes = 38_100_000,
            ),
        description = "把图鲸鲸接回桌面，摸摸头、喂口饭，再陪她听一首歌。支持动态分辨率与自定义控制。",
        fileSizeBytes = 38_100_000,
        updatedAt = 1_792_201_600L,
        subscriptions = 29_000,
    )

private val previewComments =
    listOf(
        WorkshopComment(author = "Akitoki", text = "很不错，终于可以把鲸鲸请回桌面了。", dateLabel = "16 小时以前"),
        WorkshopComment(author = "HayakawaOnion", text = "很喜欢，动态效果也很自然。", dateLabel = "23 小时以前"),
        WorkshopComment(author = "大橘", text = "可以弄个 AI 配音吗？", dateLabel = "2026/11/8 09:18:54"),
        WorkshopComment(author = "ckw", text = "不赖不赖。", dateLabel = "2026/11/7 19:46:15"),
    )
