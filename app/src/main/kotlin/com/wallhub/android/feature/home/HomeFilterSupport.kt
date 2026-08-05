@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.home

import androidx.annotation.StringRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubShapeTokens
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.WorkshopType
import java.util.Locale
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

internal fun <T> Set<T>.toggleBounded(value: T, allOptions: Set<T>): Set<T> {
    val current = if (isEmpty() || this == allOptions) allOptions else this
    if (current == allOptions) return setOf(value)
    val next = if (value in current) current - value else current + value
    return if (next.isEmpty() || next == allOptions) allOptions else next
}

internal fun Set<WorkshopRating>.toggleRating(
    rating: WorkshopRating,
    multiSelect: Boolean,
    matureContentEnabled: Boolean,
): Set<WorkshopRating> =
    when {
        rating == WorkshopRating.ALL -> if (matureContentEnabled) setOf(WorkshopRating.ALL) else SAFE_HOME_RATING_SELECTION
        !multiSelect -> setOf(rating)
        !matureContentEnabled && normalizedRatings(false) == SAFE_HOME_RATING_SELECTION -> setOf(rating)
        rating in normalizedRatings(matureContentEnabled) ->
            (normalizedRatings(matureContentEnabled) - rating).ifEmpty { DEFAULT_HOME_RATING_SELECTION }
        else -> (normalizedRatings(matureContentEnabled) - WorkshopRating.ALL + rating).normalizedRatings(matureContentEnabled)
    }

internal fun Set<WorkshopRating>.normalizedRatings(matureContentEnabled: Boolean): Set<WorkshopRating> =
    if (WorkshopRating.ALL in this) {
        if (matureContentEnabled) setOf(WorkshopRating.ALL) else SAFE_HOME_RATING_SELECTION
    } else {
        filterNot { it == WorkshopRating.MATURE && !matureContentEnabled }.toSet().ifEmpty { DEFAULT_HOME_RATING_SELECTION }
    }

@Composable
internal fun WorkshopSort.label(): String =
    stringResource(
        when (this) {
            WorkshopSort.TRENDING -> R.string.home_sort_popular
            WorkshopSort.MOST_RECENT -> R.string.home_sort_most_recent
            WorkshopSort.TOP_RATED -> R.string.home_sort_top_rated
            WorkshopSort.MOST_VOTES -> R.string.home_sort_most_votes
            WorkshopSort.MOST_SUBSCRIBERS -> R.string.home_sort_most_subscribers
        },
    )

@Composable
internal fun Int.label(): String =
    when (this) {
        0 -> stringResource(R.string.home_all_time)
        1 -> stringResource(R.string.home_today)
        7 -> stringResource(R.string.home_7_days)
        30 -> stringResource(R.string.home_30_days)
        90 -> stringResource(R.string.home_3_months)
        180 -> stringResource(R.string.home_6_months)
        365 -> stringResource(R.string.home_1_year)
        else -> pluralStringResource(R.plurals.home_days, this, this)
    }

@Composable
internal fun timeRangeOptions(currentDays: Int): List<Pair<Int, String>> =
    ((listOf(1, 7, 30, 90, 180, 365) + currentDays).filter { it > 0 }.distinct().sorted() + 0).map { it to it.label() }

@Composable
internal fun WorkshopType.label(): String =
    stringResource(
        when (this) {
            WorkshopType.VIDEO -> R.string.home_video
            WorkshopType.SCENE -> R.string.home_scene
            WorkshopType.WEB -> R.string.home_web
            WorkshopType.UNKNOWN -> R.string.home_wallpaper
        },
    )

@Composable
internal fun WorkshopRating.label(): String =
    stringResource(
        when (this) {
            WorkshopRating.ALL -> R.string.home_all
            WorkshopRating.EVERYONE -> R.string.home_rating_everyone
            WorkshopRating.QUESTIONABLE -> R.string.home_rating_questionable
            WorkshopRating.MATURE -> R.string.home_rating_mature
        },
    )

@Composable
internal fun HomeCardAction.label(): String =
    stringResource(
        when (this) {
            HomeCardAction.DOWNLOAD -> R.string.home_download
            HomeCardAction.PLAY_VIDEO -> R.string.home_play
            HomeCardAction.OPEN_STEAM -> R.string.home_open_steam
        },
    )

internal fun HomeCardAction.icon() =
    when (this) {
        HomeCardAction.DOWNLOAD -> Icons.Outlined.Download
        HomeCardAction.PLAY_VIDEO -> Icons.Outlined.PlayArrow
        HomeCardAction.OPEN_STEAM -> Icons.Outlined.OpenInNew
    }

internal fun formatCompact(value: Long): String {
    val locale = Locale.getDefault()
    val isChinese = locale.language == Locale.CHINESE.language
    return when {
        (isChinese && value >= 10_000) || (!isChinese && value >= 1_000_000) ->
            String.format(locale, if (isChinese) "%.1f 万" else "%.1fM", if (isChinese) value / 10_000.0 else value / 1_000_000.0)
        value >= 1_000 -> String.format(locale, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}

@Composable
internal fun String.localizedGenre(): String = genreLabelRes()?.let { stringResource(it) } ?: this

@StringRes
private fun String.genreLabelRes(): Int? =
    mapOf(
        "Abstract" to R.string.home_genre_abstract, "Animal" to R.string.home_genre_animal,
        "Anime" to R.string.home_genre_anime, "Cartoon" to R.string.home_genre_cartoon,
        "CGI" to R.string.home_genre_cgi, "Cyberpunk" to R.string.home_genre_cyberpunk,
        "Fantasy" to R.string.home_genre_fantasy, "Game" to R.string.home_genre_game,
        "Girls" to R.string.home_genre_girls, "Guys" to R.string.home_genre_guys,
        "Landscape" to R.string.home_genre_landscape, "Medieval" to R.string.home_genre_medieval,
        "Memes" to R.string.home_genre_memes, "MMD" to R.string.home_genre_mmd,
        "Music" to R.string.home_genre_music, "Nature" to R.string.home_genre_nature,
        "Pixel art" to R.string.home_genre_pixel_art, "Relaxing" to R.string.home_genre_relaxing,
        "Retro" to R.string.home_genre_retro, "Sci-Fi" to R.string.home_genre_sci_fi,
        "Sports" to R.string.home_genre_sports, "Technology" to R.string.home_genre_technology,
        "Television" to R.string.home_genre_television, "Vehicle" to R.string.home_genre_vehicle,
        "Unspecified" to R.string.home_genre_unspecified,
    )[this]

@Composable
internal fun String.localizedResolution(): String = resolutionLabelRes()?.let { stringResource(it) } ?: this

@StringRes
private fun String.resolutionLabelRes(): Int? =
    mapOf(
        "Standard" to R.string.home_resolution_standard, "Ultrawide" to R.string.home_resolution_ultrawide,
        "Dual monitor" to R.string.home_resolution_dual_monitor, "Triple monitor" to R.string.home_resolution_triple_monitor,
        "Portrait" to R.string.home_resolution_portrait, "Other resolution" to R.string.home_resolution_other,
        "Dynamic resolution" to R.string.home_resolution_dynamic,
    )[this]

internal const val FILTER_COLLAPSE_OFFSET_PX = 24
internal val HOME_GRID_HORIZONTAL_PADDING = WallHubSpacing.md
internal val HOME_GRID_ITEM_SPACING = WallHubSpacing.compact
internal const val HOME_AUTO_LOAD_MORE_THRESHOLD = 4
internal const val HOME_VIEW_LAYOUT_ANIMATION_DURATION_MS = 400
internal const val HOME_VIEW_CARD_LAYOUT_DURATION_MS = HOME_VIEW_LAYOUT_ANIMATION_DURATION_MS
internal const val HOME_VIEW_LAYOUT_POSITION_EPSILON_PX = 0.5f
internal const val HOME_VIEW_LAYOUT_SCALE_EPSILON = 0.005f
internal const val HOME_VIEW_LAYOUT_MIN_SCALE = 0.01f
internal const val HOME_CARD_PREVIEW_Z_INDEX = 1f
internal val HOME_COVER_CORNER_RADIUS = WallHubSpacing.sm
internal val HOME_WALLPAPER_CARD_SHAPE = WallHubShapeTokens.medium
internal val HOME_TYPE_TAG_HORIZONTAL_PADDING = WallHubSpacing.xs
internal val HOME_TYPE_TAG_VERTICAL_PADDING = WallHubSpacing.xxs
internal val HOME_CONTEXT_MENU_PRESS_TRANSLATION_Y = WallHubSpacing.hairline
internal const val HOME_CONTEXT_MENU_GRID_PRESS_SCALE = 0.985f
internal const val HOME_CONTEXT_MENU_LIST_PRESS_SCALE = 0.99f
internal const val HOME_CONTEXT_MENU_PRESS_STIFFNESS = 500f
internal val HOME_CONTEXT_MENU_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
internal val HOME_VIEW_LAYOUT_EASING = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
internal val CARD_TITLE_HEIGHT = WallHubSizeTokens.cardTitleHeight
internal val CARD_ACTION_HEIGHT = WallHubSizeTokens.compactActionHeight
internal val TWO_COLUMN_CARD_COPY_TOP_PADDING = WallHubSpacing.xs
internal val TWO_COLUMN_CARD_TITLE_STATISTICS_SPACING = WallHubSpacing.xxs
internal val TWO_COLUMN_CARD_STATISTICS_LINE_HEIGHT = 14.sp
internal val GRID_CARD_STATISTICS_LINE_HEIGHT = 16.sp
internal val TWO_COLUMN_CARD_STATISTICS_ICON_SIZE = 13.dp
internal val TWO_COLUMN_CARD_STATISTICS_ICON_SPACING = 2.5.dp
internal val TWO_COLUMN_CARD_STATISTICS_ITEM_SPACING = 5.dp
internal val LIST_CARD_ACTION_TOP_PADDING = 7.dp
internal val LIST_CARD_TITLE_STATISTICS_SPACING = 7.dp
internal val VIEW_MODE_TOGGLE_LABEL_INSET = 5.dp
internal val TWO_COLUMN_CARD_STATISTICS_ROW_SPACING = WallHubSpacing.xxxs
internal val TWO_COLUMN_CARD_ACTION_TOP_PADDING = WallHubSpacing.dense
internal const val TWO_COLUMN_CARD_STATISTICS_MIN_FONT_SIZE = 10.5f
internal const val TWO_COLUMN_CARD_STATISTICS_MAX_FONT_SIZE = 11.5f
internal const val TWO_COLUMN_CARD_STATISTICS_FONT_WIDTH_DIVISOR = 4.6f
internal val HOME_VIEW_MODE_TOGGLE_INSET = 3.dp
internal val HOME_VIEW_MODE_TOGGLE_BUTTON_SIZE = 42.dp
internal val HOME_VIEW_MODE_TOGGLE_HEIGHT = 48.dp
internal val HOME_VIEW_MODE_TOGGLE_WIDTH = 90.dp
internal const val HOME_VIEW_MODE_TOGGLE_DURATION_MS = HOME_VIEW_LAYOUT_ANIMATION_DURATION_MS
internal val GRID_CARD_ACTION_CORNER_RADIUS = WallHubSpacing.sm
internal val LIST_CARD_ACTION_CORNER_RADIUS = WallHubSpacing.sm
internal val LIST_CARD_MEDIA_SIZE = 104.dp
internal val LIST_CARD_ACTION_SIZE = WallHubSizeTokens.compactActionHeight
internal val LIST_CARD_ACTION_END_PADDING = WallHubSpacing.compact
internal val LIST_CARD_COPY_HORIZONTAL_PADDING = 20.dp
internal val GRID_CARD_COPY_HORIZONTAL_PADDING = 20.dp
