@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.detail

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.MpkgConversionOptions
import com.wallhub.android.core.designsystem.WallHubSecondaryButton
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.model.ExportFormat
import com.wallhub.android.core.model.FavoriteState
import com.wallhub.android.core.model.MpkgConversionPreset
import com.wallhub.android.core.model.SubscriptionState
import com.wallhub.android.core.model.WorkshopComment
import com.wallhub.android.core.model.WorkshopInteraction
import com.wallhub.android.core.model.WorkshopType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.StarBorder

@Composable
internal fun DetailActionBar(
    interaction: WorkshopInteraction,
    isLoadingInteraction: Boolean,
    isUpdatingInteraction: Boolean,
    isEnqueuingDownload: Boolean,
    onToggleSubscription: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subscriptionEnabled = interaction.canToggleSubscription(isLoadingInteraction, isUpdatingInteraction)
    val favoriteEnabled = interaction.canToggleFavorite(isLoadingInteraction, isUpdatingInteraction)
    val subscribed = interaction.subscriptionState == SubscriptionState.SUBSCRIBED
    val favorited = interaction.favoriteState == FavoriteState.FAVORITED
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .navigationBarsPadding()
                .padding(start = WallHubSpacing.md, top = WallHubSpacing.compact, end = WallHubSpacing.md, bottom = WallHubSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
    ) {
        DetailToggleActionButton(
            icon = Icons.Outlined.Notifications,
            contentDescription =
                if (subscribed) {
                    stringResource(R.string.detail_unsubscribe)
                } else {
                    stringResource(R.string.detail_subscribe)
                },
            selected = subscribed,
            onClick = onToggleSubscription,
            enabled = subscriptionEnabled,
        )
        DetailToggleActionButton(
            icon = Icons.Outlined.StarBorder,
            contentDescription =
                if (favorited) {
                    stringResource(R.string.detail_unfavorite)
                } else {
                    stringResource(R.string.detail_favorite)
                },
            selected = favorited,
            onClick = onToggleFavorite,
            enabled = favoriteEnabled,
        )
        val downloadContentDescription =
            if (isEnqueuingDownload) {
                stringResource(R.string.detail_adding_to_download_queue)
            } else {
                stringResource(R.string.detail_download)
            }
        Button(
            onClick = onDownload,
            modifier = Modifier.weight(1f).height(56.dp),
            enabled = !isEnqueuingDownload,
            shape = RoundedCornerShape(100.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = downloadContentDescription,
            )
        }
    }
}

@Composable
private fun DetailToggleActionButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    if (selected) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                Icon(imageVector = icon, contentDescription = contentDescription)
            }
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(28.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
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
    mpkgConversionPreset: MpkgConversionPreset,
    onMpkgConversionPresetSelected: (MpkgConversionPreset) -> Unit,
    preserveAudio: Boolean,
    onPreserveAudioChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onDownload: (ExportFormat) -> Unit,
) {
    var step by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val duration = if (reduceMotion) 120 else 260
    val easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                val enter = if (reduceMotion) fadeIn(tween(duration)) else slideInHorizontally(tween(duration, easing = easing)) { if (forward) it else -it } + fadeIn(tween(duration))
                val exit = if (reduceMotion) fadeOut(tween(duration)) else slideOutHorizontally(tween(duration, easing = easing)) { if (forward) -it else it } + fadeOut(tween(duration))
                enter.togetherWith(exit).using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(duration, easing = easing) }))
            },
            label = "download-sheet-pages",
        ) { page ->
            when (page) {
                0 -> DownloadTypePage(type, exportFormats, onMpkg = { step = 1 }, onDownload = onDownload, onDismiss = onDismiss)
                else -> MpkgQualityPage(
                    preset = mpkgConversionPreset,
                    onPreset = onMpkgConversionPresetSelected,
                    preserveAudio = preserveAudio,
                    onPreserveAudio = onPreserveAudioChanged,
                    onBack = { step = 0 },
                    onDownload = { onDownload(ExportFormat.MPKG) },
                )
            }
        }
    }
}

@Composable
private fun DownloadTypePage(
    type: WorkshopType,
    exportFormats: List<ExportFormat>,
    onMpkg: () -> Unit,
    onDownload: (ExportFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = WallHubSpacing.lg, vertical = WallHubSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
    ) {
        Text(stringResource(R.string.detail_download_options), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.detail_download_queue_explanation, type.label()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ExportFormat.MPKG in exportFormats) {
            Button(onClick = onMpkg, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(50)) {
                Text(stringResource(R.string.detail_download_mpkg))
            }
        }
        if (ExportFormat.ZIP in exportFormats) {
            OutlinedButton(onClick = { onDownload(ExportFormat.ZIP) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(50)) {
                Text(stringResource(R.string.detail_download_zip))
            }
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface),
        ) {
            Text(stringResource(R.string.detail_close))
        }
        Spacer(modifier = Modifier.height(WallHubSpacing.sm))
    }
}

@Composable
private fun MpkgQualityPage(
    preset: MpkgConversionPreset,
    onPreset: (MpkgConversionPreset) -> Unit,
    preserveAudio: Boolean,
    onPreserveAudio: (Boolean) -> Unit,
    onBack: () -> Unit,
    onDownload: () -> Unit,
) {
    var dragDistance = 0f
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragDistance = 0f },
                        onHorizontalDrag = { _, amount -> dragDistance += amount },
                        onDragEnd = { if (dragDistance > 96.dp.toPx()) onBack() },
                    )
                }.padding(horizontal = WallHubSpacing.lg, vertical = WallHubSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.detail_back_to_download_type)) }
            Text(stringResource(R.string.detail_mpkg_conversion_quality), style = MaterialTheme.typography.titleLarge)
        }
        MpkgConversionOptions(
            preset = preset,
            onPresetSelected = onPreset,
            preserveAudio = preserveAudio,
            onPreserveAudioChanged = onPreserveAudio,
        )
        Button(onClick = onDownload, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(50)) {
            Text(stringResource(R.string.detail_start_download_mpkg))
        }
        Spacer(modifier = Modifier.height(WallHubSpacing.sm))
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

internal fun shouldShowMpkgPreset(
    type: WorkshopType,
    exportFormats: List<ExportFormat>,
): Boolean = type == WorkshopType.SCENE && ExportFormat.MPKG in exportFormats

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
