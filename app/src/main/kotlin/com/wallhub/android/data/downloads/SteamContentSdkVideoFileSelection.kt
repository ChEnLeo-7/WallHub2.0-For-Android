package com.wallhub.android.data.downloads

import com.wallhub.android.core.model.DepotFileFlag

/**
 * The streamable file of a decoded manifest, chosen by the product's own rule.
 *
 * The SDK names what the manifest contains; which of those files is the video the product should play is the
 * product's rule, and this is the rule the kSteam path had: the largest file that is neither a directory nor a
 * symlink and whose extension is one the product renders. Keeping it here is what lets the SDK stay neutral
 * about what a video is.
 */
internal fun selectStreamableVideoFile(
    files: List<SteamContentSdkManifestFile>,
): SteamContentSdkManifestFile? =
    files
        .asSequence()
        .filterNot { file -> file.flags and DepotFileFlag.Directory.code != 0 }
        .filterNot { file -> file.flags and DepotFileFlag.Symlink.code != 0 }
        .filter { file -> file.path.videoFileExtension() in VIDEO_FILE_EXTENSIONS }
        .maxByOrNull(SteamContentSdkManifestFile::size)
