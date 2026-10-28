package com.wallhub.shared.ui

enum class WallHubWindowSizeClass {
    Compact,
    Medium,
    Expanded,
}

fun wallHubWindowSizeClass(widthDp: Float): WallHubWindowSizeClass =
    when {
        widthDp < 600f -> WallHubWindowSizeClass.Compact
        widthDp < 840f -> WallHubWindowSizeClass.Medium
        else -> WallHubWindowSizeClass.Expanded
    }
