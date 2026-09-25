package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.SteamContentCredential
import java.io.File

/**
 * The one contract a manifest-backed per-item transfer obeys.
 *
 * `SteamContentSdkWorkshopTransfer` is its only implementation: the Steam Content SDK resolves the item to
 * its app, depot, and manifest and owns the download data plane. Direct-file items never reach it - they are
 * a Steam-published URL and are fetched by the product's own HTTP path in
 * [FormalSteamWorkshopContentGateway] - so this seam is about depot content only.
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
