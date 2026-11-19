package com.wallhub.android.core.format

import java.util.Locale

/**
 * Compact subscriber/favourite counts: 万 for Chinese locales, M/K otherwise. One implementation so
 * detail, library and filter surfaces cannot drift.
 */
fun formatCompactCount(
    value: Long?,
    locale: Locale = Locale.getDefault(),
): String {
    if (value == null) return "—"
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
