@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.wallhub.android.core.designsystem.WallHubPrimaryAction
import com.wallhub.android.core.designsystem.WallHubSecondaryButton
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.text
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

@Composable
internal fun DetailActionBar(
    language: AppLanguage,
    interaction: WorkshopInteraction,
    isLoadingInteraction: Boolean,
    isUpdatingInteraction: Boolean,
    interactionMessage: String?,
    isEnqueuingDownload: Boolean,
    downloadMessage: String?,
    onToggleSubscription: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
) {
    val interactionEnabled = !isLoadingInteraction && !isUpdatingInteraction
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(start = WallHubSpacing.md, top = WallHubSpacing.compact, end = WallHubSpacing.md, bottom = WallHubSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.dense),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
        ) {
            WallHubSecondaryButton(
                onClick = onToggleSubscription,
                modifier = Modifier.weight(1f),
                enabled = interactionEnabled,
            ) {
                Icon(imageVector = Icons.Bell, contentDescription = null)
                Text(
                    text =
                        if (interaction.subscriptionState == SubscriptionState.SUBSCRIBED) {
                            language.text("取消订阅", "Unsubscribe")
                        } else {
                            language.text("订阅", "Subscribe")
                        },
                    modifier = Modifier.padding(start = WallHubSpacing.dense),
                )
            }
            WallHubSecondaryButton(
                onClick = onToggleFavorite,
                modifier = Modifier.weight(1f),
                enabled = interactionEnabled,
            ) {
                Icon(imageVector = Icons.Star, contentDescription = null)
                Text(
                    text =
                        if (interaction.favoriteState == FavoriteState.FAVORITED) {
                            language.text("取消收藏", "Unfavorite")
                        } else {
                            language.text("收藏", "Favorite")
                        },
                    modifier = Modifier.padding(start = WallHubSpacing.dense),
                )
            }
        }
        WallHubPrimaryAction(
            label =
                if (isEnqueuingDownload) {
                    language.text("正在加入下载队列…", "Adding to download queue…")
                } else {
                    language.text("下载", "Download")
                },
            onClick = onDownload,
            icon = Icons.Download,
            enabled = !isEnqueuingDownload,
        )
        val status =
            when {
                isLoadingInteraction -> language.text("正在读取 Steam 账户状态…", "Loading Steam account state…")
                isUpdatingInteraction -> language.text("正在向 Steam 提交请求…", "Sending request to Steam…")
                interactionMessage != null -> interactionMessage
                downloadMessage != null -> downloadMessage
                else -> ""
            }
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(WallHubSpacing.md),
        )
    }
}

internal fun formatCompactCount(value: Long?): String {
    if (value == null) return "—"
    return when {
        value >= 1_000_000L ->
            String
                .format(Locale.getDefault(), "%.1fM", value / 1_000_000.0)
                .replace(".0M", "M")
        value >= 1_000L ->
            String
                .format(Locale.getDefault(), "%.1fK", value / 1_000.0)
                .replace(".0K", "K")
        else -> value.toString()
    }
}

internal fun formatWorkshopDate(
    timestamp: Long?,
    language: AppLanguage,
): String {
    if (timestamp == null || timestamp <= 0L) return language.text("未知", "Unknown")
    val pattern = if (language == AppLanguage.EN) "MMM d, yyyy" else "yyyy年M月d日"
    return runCatching {
        DateTimeFormatter
            .ofPattern(pattern, Locale.getDefault())
            .format(Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault()))
    }.getOrDefault(language.text("未知", "Unknown"))
}

internal fun formatCommentDate(
    comment: WorkshopComment,
    language: AppLanguage,
    nowMillis: Long = System.currentTimeMillis(),
): String {
    comment.timestamp?.takeIf { it > 0L }?.let { timestamp ->
        val timestampMillis = if (timestamp > 100_000_000_000L) timestamp else timestamp * 1_000L
        val difference = nowMillis - timestampMillis
        if (difference in 0 until COMMENT_HOUR_MS) {
            val minutes = (difference / COMMENT_MINUTE_MS).coerceAtLeast(1L)
            return language.text("$minutes 分钟以前", "$minutes minutes ago")
        }
        if (difference in 0 until COMMENT_DAY_MS) {
            val hours = difference / COMMENT_HOUR_MS
            return language.text("$hours 小时以前", "$hours hours ago")
        }
        return runCatching {
            val dateTime = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())
            val currentYear = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).year
            val pattern =
                when (language) {
                    AppLanguage.EN ->
                        if (dateTime.year == currentYear) {
                            "MMM dd, hh:mm:ss a"
                        } else {
                            "yyyy MMM dd, hh:mm:ss a"
                        }

                    AppLanguage.ZH ->
                        if (dateTime.year == currentYear) {
                            "MM 月 dd 日 a hh:mm:ss"
                        } else {
                            "yyyy 年 MM 月 dd 日 a hh:mm:ss"
                        }
                }
            val locale = if (language == AppLanguage.EN) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
            DateTimeFormatter.ofPattern(pattern, locale).format(dateTime)
        }.getOrDefault(comment.dateLabel.orEmpty())
    }
    return comment.dateLabel.orEmpty()
}

internal const val DETAIL_OVERVIEW_PAGE = 0
internal const val DETAIL_COMMENTS_PAGE = 1
internal const val DETAIL_PAGE_COUNT = 2
internal const val DESCRIPTION_COLLAPSED_MAX_LINES = 3
internal const val DESCRIPTION_RIPPLE_SETTLE_DURATION_MS = 110L
internal const val DETAIL_DIVIDER_ALPHA = 0.32f
internal const val COMMENT_MINUTE_MS = 60_000L
internal const val COMMENT_HOUR_MS = 60L * COMMENT_MINUTE_MS
internal const val COMMENT_DAY_MS = 24L * COMMENT_HOUR_MS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadChoiceSheet(
    type: WorkshopType,
    language: AppLanguage,
    exportFormats: List<ExportFormat>,
    onDismiss: () -> Unit,
    onDownload: (ExportFormat) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = WallHubSpacing.lg, vertical = WallHubSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
        ) {
            Text(language.text("下载选项", "Download options"), style = MaterialTheme.typography.titleLarge)
            Text(
                language.text(
                    "${type.label(language)} 项目会加入下载队列，可在下载页面查看进度。",
                    "${type.label(language)} tasks are added to the download queue. Track their progress on Downloads.",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            exportFormats.forEach { format ->
                Button(
                    onClick = { onDownload(format) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when (format) {
                            ExportFormat.MPKG -> language.text("下载 MPKG 文件（手机）", "Download MPKG (mobile)")
                            ExportFormat.ZIP -> language.text("下载 ZIP 压缩包", "Download ZIP archive")
                            ExportFormat.AUTO -> language.text("自动选择格式", "Automatically choose format")
                        },
                    )
                }
            }
            WallHubSecondaryButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(language.text("关闭", "Close"))
            }
            Spacer(modifier = Modifier.height(WallHubSpacing.sm))
        }
    }
}

internal fun WorkshopType.label(language: AppLanguage): String =
    when (this) {
        WorkshopType.VIDEO -> language.text("视频", "Video")
        WorkshopType.SCENE -> language.text("场景", "Scene")
        WorkshopType.WEB -> language.text("网站", "Web")
        WorkshopType.UNKNOWN -> language.text("未知", "Unknown")
    }

internal fun WorkshopType.defaultExportFormat(): ExportFormat =
    when (this) {
        WorkshopType.WEB -> ExportFormat.ZIP
        WorkshopType.VIDEO,
        WorkshopType.SCENE,
        WorkshopType.UNKNOWN,
        -> ExportFormat.MPKG
    }

internal fun WorkshopType.availableExportFormats(): List<ExportFormat> =
    when (this) {
        WorkshopType.WEB -> listOf(ExportFormat.ZIP)
        WorkshopType.VIDEO,
        WorkshopType.SCENE,
        WorkshopType.UNKNOWN,
        -> listOf(ExportFormat.MPKG, ExportFormat.ZIP)
    }

internal fun ExportFormat.label(language: AppLanguage): String =
    when (this) {
        ExportFormat.AUTO -> language.text("自动", "Automatic")
        ExportFormat.MPKG -> language.text("MPKG（移动端）", "MPKG (mobile)")
        ExportFormat.ZIP -> language.text("ZIP 压缩包", "ZIP archive")
    }
