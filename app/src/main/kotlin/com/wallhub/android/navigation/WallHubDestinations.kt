package com.wallhub.android

import kotlinx.serialization.Serializable

@Serializable
internal sealed interface WallHubDestination

@Serializable internal data object HomeDestination : WallHubDestination
@Serializable internal data object DownloadsDestination : WallHubDestination
@Serializable internal data object LibraryDestination : WallHubDestination
@Serializable internal data object LocalDestination : WallHubDestination
@Serializable internal data object SettingsDestination : WallHubDestination

// Preserves the legacy route while providing a direct entry to the Steam sign-in settings.
@Serializable internal data object SteamLoginDestination : WallHubDestination

@Serializable
internal data class AuthorSearchDestination(
    val authorSearchCreator: String,
) : WallHubDestination

@Serializable
internal data class TagSearchDestination(
    val tagSearchTag: String,
) : WallHubDestination

@Serializable
internal data class WorkshopDetailDestination(
    val workshopId: Long,
) : WallHubDestination

@Serializable
internal data class LocalVideoPlayerDestination(
    val taskId: String,
) : WallHubDestination

@Serializable
internal data class OnlineVideoPlayerDestination(
    val workshopId: Long,
) : WallHubDestination

internal fun String.authorSearchDestinationOrNull(): AuthorSearchDestination? =
    filter(Char::isDigit)
        .takeIf(String::isNotBlank)
        ?.let(::AuthorSearchDestination)
