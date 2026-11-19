package com.wallhub.android.core.model

/**
 * Persisted Material color-scheme construction style.
 *
 * Keep the enum order stable: [AppPreferencesStore] stores the enum name.
 */
enum class ThemeSchemeStyle {
    TONAL_SPOT,
    CONTENT,
    VIBRANT,
    EXPRESSIVE,
    MONOCHROME,
    ;

    companion object {
        fun fromStored(value: String?): ThemeSchemeStyle =
            entries.firstOrNull { it.name == value } ?: TONAL_SPOT
    }
}

/**
 * Persisted user contrast preference. Material color utilities consume the
 * contrast value in the range 0.0 (standard) to 1.0 (high).
 */
enum class ThemeContrastLevel(
    val contrast: Double,
) {
    STANDARD(0.0),
    MEDIUM(0.5),
    HIGH(1.0),
    ;

    companion object {
        fun fromStored(value: String?): ThemeContrastLevel =
            entries.firstOrNull { it.name == value } ?: STANDARD
    }
}
