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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubPrimaryAction
import com.wallhub.android.core.designsystem.WallHubSecondaryButton
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

@Composable
internal fun DetailActionBar(
    interaction: WorkshopInteraction,
    isLoadingInteraction: Boolean,
    isUpdatingInteraction: Boolean,
    interactionMessage: DetailUiText?,
    isEnqueuingDownload: Boolean,
    downloadMessage: DetailUiText?,
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
                            stringResource(R.string.detail_unsubscribe)
                        } else {
                            stringResource(R.string.detail_subscribe)
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
                            stringResource(R.string.detail_unfavorite)
                        } else {
                            stringResource(R.string.detail_favorite)
                        },
                    modifier = Modifier.padding(start = WallHubSpacing.dense),
                )
            }
        }
        WallHubPrimaryAction(
            label =
                if (isEnqueuingDownload) {
                    stringResource(R.string.detail_adding_to_download_queue)
                } else {
                    stringResource(R.string.detail_download)
                },
            onClick = onDownload,
            icon = Icons.Download,
            enabled = !isEnqueuingDownload,
        )
        val status =
            when {
                isLoadingInteraction -> stringResource(R.string.detail_loading_steam_account)
                isUpdatingInteraction -> stringResource(R.string.detail_sending_steam_request)
                interactionMessage != null -> interactionMessage.resolve()
                downloadMessage != null -> downloadMessage.resolve()
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
    val locale = Locale.getDefault()
    val isChinese = locale.language == Locale.CHINESE.language
    return when {
        (isChinese && value >= 10_000L) || (!isChinese && value >= 1_000_000L) ->
            String
                .format(
                    locale,
                    if (isChinese) "%.1f 万" else "%.1fM",
                    if (isChinese) value / 10_000.0 else value / 1_000_000.0,
                ).let { formatted ->
                    if (isChinese) formatted.replace(".0 万", " 万") else formatted.replace(".0M", "M")
                }
        value >= 1_000L ->
            String
                .format(locale, "%.1fK", value / 1_000.0)
                .replace(".0K", "K")
        else -> value.toString()
    }
}

@Composable
internal fun formatWorkshopDate(timestamp: Long?): String {
    val unknown = stringResource(R.string.detail_unknown)
    if (timestamp == null || timestamp <= 0L) return unknown
    val locale = LocalConfiguration.current.locales[0]
    return runCatching {
        DateTimeFormatter
            .ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault()))
    }.getOrDefault(unknown)
}

@Composable
internal fun formatCommentDate(
    comment: WorkshopComment,
    nowMillis: Long = System.currentTimeMillis(),
): String {
    val locale = LocalConfiguration.current.locales[0]
    comment.timestamp?.takeIf { it > 0L }?.let { timestamp ->
        val timestampMillis = if (timestamp > 100_000_000_000L) timestamp else timestamp * 1_000L
        val difference = nowMillis - timestampMillis
        if (difference in 0 until COMMENT_HOUR_MS) {
            val minutes = (difference / COMMENT_MINUTE_MS).coerceAtLeast(1L)
            return pluralStringResource(R.plurals.detail_minutes_ago, minutes.toInt(), minutes)
        }
        if (difference in 0 until COMMENT_DAY_MS) {
            val hours = difference / COMMENT_HOUR_MS
            return pluralStringResource(R.plurals.detail_hours_ago, hours.toInt(), hours)
        }
        return runCatching {
            val dateTime = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())
            val currentYear = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).year
            val dateStyle = if (dateTime.year == currentYear) FormatStyle.SHORT else FormatStyle.MEDIUM
            DateTimeFormatter
                .ofLocalizedDateTime(dateStyle, FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(dateTime)
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
            Text(stringResource(R.string.detail_download_options), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.detail_download_queue_explanation, type.label()),
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
                            ExportFormat.MPKG -> stringResource(R.string.detail_download_mpkg)
                            ExportFormat.ZIP -> stringResource(R.string.detail_download_zip)
                            ExportFormat.AUTO -> stringResource(R.string.detail_choose_format_automatically)
                        },
                    )
                }
            }
            WallHubSecondaryButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.detail_close))
            }
            Spacer(modifier = Modifier.height(WallHubSpacing.sm))
        }
    }
}

@Composable
internal fun WorkshopType.label(): String =
    when (this) {
        WorkshopType.VIDEO -> stringResource(R.string.detail_workshop_type_video)
        WorkshopType.SCENE -> stringResource(R.string.detail_workshop_type_scene)
        WorkshopType.WEB -> stringResource(R.string.detail_workshop_type_web)
        WorkshopType.UNKNOWN -> stringResource(R.string.detail_unknown)
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

@Composable
internal fun ExportFormat.label(): String =
    when (this) {
        ExportFormat.AUTO -> stringResource(R.string.detail_export_format_automatic)
        ExportFormat.MPKG -> stringResource(R.string.detail_export_format_mpkg)
        ExportFormat.ZIP -> stringResource(R.string.detail_export_format_zip)
    }
