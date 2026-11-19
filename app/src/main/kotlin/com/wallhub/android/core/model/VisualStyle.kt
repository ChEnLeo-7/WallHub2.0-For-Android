package com.wallhub.android.core.model

/**
 * The visual skin of the app, deliberately separate from theme mode and color source.
 *
 * Persisted identifiers are stable strings. Never persist [Enum.name] or [Enum.ordinal]:
 * renaming a Kotlin enum constant must not reset an existing user's choice.
 */
enum class VisualStyle(
    val stableId: String,
) {
    MATERIAL3("material3"),
    MATERIAL3_EXPRESSIVE("material3_expressive"),
    ;

    companion object {
        val DEFAULT: VisualStyle = MATERIAL3

        fun fromStableId(value: String?): VisualStyle =
            when (value) {
                MATERIAL3.stableId, "classic_wallhub" -> MATERIAL3
                MATERIAL3_EXPRESSIVE.stableId, "bettbox_inspired" -> MATERIAL3_EXPRESSIVE
                else -> DEFAULT
            }
    }
}
