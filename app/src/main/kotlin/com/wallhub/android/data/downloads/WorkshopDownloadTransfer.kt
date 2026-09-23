package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import java.io.File

/**
 * The one contract both per-item transfer implementations obey.
 *
 * `SteamContentDownloader` serves it with kSteam content access plus the product-local `wallhub-rust`
 * depot core; `SteamContentSdkWorkshopTransfer` serves it with the Steam Content SDK's own resolution
 * and data plane. Keeping the contract in one place is what makes the switch below one expression and
 * the later deletion of the old arm mechanical.
 */
internal interface WorkshopContentTransfer {
    suspend fun download(
        target: WorkshopContentTarget,
        destinationDirectory: File,
        credential: SteamContentCredential?,
        options: SteamContentDownloadOptions,
        control: suspend () -> SteamDownloadControl,
        onProgress: suspend (SteamDownloadProgress) -> Unit,
    ): SteamContentDownloadResult
}

/** The two implementations of [WorkshopContentTransfer] this build can choose between. */
internal enum class WorkshopDownloadPath {
    /** kSteam content access and the product-local `wallhub-rust` depot core: the default path. */
    KSTEAM_AND_WALLHUB_RUST,

    /** The Steam Content SDK resolves the item and owns the download data plane. */
    STEAM_CONTENT_SDK,
}

/**
 * The single decision point for a manifest-backed Workshop download.
 *
 * `wallhub.useSdkDepotCore` (surfaced as `BuildConfig.USE_SDK_DEPOT_CORE`) stays off by default, so the
 * unchecked value selects the existing kSteam + `wallhub-rust` path byte for byte. Every caller routes
 * through [WorkshopDownloadTransferRouter] rather than testing the flag itself, so a later comparison
 * and the deletion of the old arm touch this one place.
 */
internal fun selectWorkshopDownloadPath(useSdkDepotCore: Boolean): WorkshopDownloadPath =
    if (useSdkDepotCore) {
        WorkshopDownloadPath.STEAM_CONTENT_SDK
    } else {
        WorkshopDownloadPath.KSTEAM_AND_WALLHUB_RUST
    }

/**
 * Holds both transfers and answers with the one the flag selects.
 *
 * Both collaborators are constructed by the dependency graph, but only the selected one is invoked:
 * a failure on the SDK path never falls through to the old backend, and the unchecked default never
 * touches SDK state.
 */
internal class WorkshopDownloadTransferRouter(
    private val legacyTransfer: WorkshopContentTransfer,
    private val sdkTransfer: WorkshopContentTransfer,
    private val useSdkDepotCore: Boolean,
) {
    val path: WorkshopDownloadPath
        get() = selectWorkshopDownloadPath(useSdkDepotCore)

    fun transfer(): WorkshopContentTransfer =
        when (path) {
            WorkshopDownloadPath.KSTEAM_AND_WALLHUB_RUST -> legacyTransfer
            WorkshopDownloadPath.STEAM_CONTENT_SDK -> sdkTransfer
        }
}
