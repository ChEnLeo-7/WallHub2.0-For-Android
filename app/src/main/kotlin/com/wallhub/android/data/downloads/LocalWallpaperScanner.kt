package com.wallhub.android.data.downloads

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.wallhub.android.R
import com.wallhub.android.core.database.FormalTaskRecordEntity
import com.wallhub.android.core.database.LocalWallpaperStateEntity
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.LocalWallpaperFormat
import com.wallhub.android.core.model.LocalWallpaperResource
import com.wallhub.android.core.model.LocalWallpaperScanIssue
import com.wallhub.android.core.model.LocalWallpaperScanSnapshot
import com.wallhub.android.core.model.LocalWallpaperSource
import com.wallhub.android.core.model.WorkshopType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

internal fun LocalWallpaperFileRepository.sourcePlans(preferences: AppPreferences): List<SourcePlan> {
    val output =
        preferences.outputTreeUri?.takeIf(String::isNotBlank)?.let { treeUri ->
            SourcePlan.Tree(
                source =
                    LocalWallpaperSource(
                        id = DOWNLOAD_SOURCE_ID,
                        label = preferences.outputDirectoryLabel ?: applicationContext.getString(R.string.backend_local_download_directory),
                        rootUri = treeUri,
                        isDownloadDirectory = true,
                    ),
                treeUri = treeUri,
            )
        } ?: SourcePlan.PublicDownloads(
            source =
                LocalWallpaperSource(
                    id = DOWNLOAD_SOURCE_ID,
                    label = DEFAULT_DOWNLOAD_LABEL,
                    rootUri = DEFAULT_DOWNLOAD_ROOT_URI,
                    isDownloadDirectory = true,
                ),
        )
    val plans = mutableListOf<SourcePlan>(output)
    preferences.localManagementTreeUri
        ?.takeIf(String::isNotBlank)
        ?.takeUnless { treeUri -> treeUri == preferences.outputTreeUri }
        ?.let { treeUri ->
            plans +=
                SourcePlan.Tree(
                    source =
                        LocalWallpaperSource(
                            id = LOCAL_SOURCE_ID,
                            label =
                                preferences.localManagementDirectoryLabel
                                    ?: applicationContext.getString(R.string.backend_local_management_directory),
                            rootUri = treeUri,
                            isDownloadDirectory = false,
                        ),
                    treeUri = treeUri,
                )
        }
    return plans
}

internal suspend fun LocalWallpaperFileRepository.scanSource(plan: SourcePlan): SourceScan =
    when (plan) {
        is SourcePlan.PublicDownloads -> scanPublicDownloads(plan.source)
        is SourcePlan.Tree -> scanTree(plan.source, Uri.parse(plan.treeUri))
    }

internal suspend fun LocalWallpaperFileRepository.scanPublicDownloads(source: LocalWallpaperSource): SourceScan =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        scanMediaStoreDownloads(source)
    } else {
        scanLegacyDownloads(source)
    }

@RequiresApi(Build.VERSION_CODES.Q)
internal fun LocalWallpaperFileRepository.scanMediaStoreDownloads(source: LocalWallpaperSource): SourceScan {
    val projection =
        arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.RELATIVE_PATH,
        )
    val nodes = mutableListOf<ScanNode>()
    resolver
        .query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_DOWNLOAD_DIRECTORY/%"),
            "${MediaStore.Downloads.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val uri =
                    ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn),
                    )
                val rootPrefix = "${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_DOWNLOAD_DIRECTORY/"
                val parent =
                    cursor
                        .getString(pathColumn)
                        .orEmpty()
                        .removePrefix(rootPrefix)
                        .trim('/')
                val name = cursor.getString(nameColumn).orEmpty()
                nodes +=
                    ScanNode(
                        uri = uri,
                        name = name,
                        relativePath = listOf(parent, name).filter(String::isNotBlank).joinToString("/"),
                        sizeBytes = cursor.getLong(sizeColumn).coerceAtLeast(0L),
                        modifiedAt = cursor.getLong(modifiedColumn).coerceAtLeast(0L) * 1_000L,
                        mimeType = cursor.getString(mimeColumn),
                        source = source,
                    )
            }
        }
    return SourceScan(nodes)
}

@Suppress("DEPRECATION")
internal suspend fun LocalWallpaperFileRepository.scanLegacyDownloads(source: LocalWallpaperSource): SourceScan {
    if (
        ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return SourceScan(
            nodes = emptyList(),
            issue =
                LocalWallpaperScanIssue(
                    sourceId = source.id,
                    message = applicationContext.getString(R.string.backend_local_storage_permission, DEFAULT_DOWNLOAD_LABEL),
                    requiresAuthorization = true,
                ),
        )
    }
    val root =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DEFAULT_DOWNLOAD_DIRECTORY,
        )
    if (!root.exists()) return SourceScan(emptyList())
    val canonicalRoot = root.canonicalFile
    val nodes =
        canonicalRoot
            .walkTopDown()
            .filter(File::isFile)
            .mapNotNull { file ->
                val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@mapNotNull null
                if (!canonical.toPath().startsWith(canonicalRoot.toPath())) return@mapNotNull null
                ScanNode(
                    uri = Uri.fromFile(canonical),
                    name = canonical.name,
                    relativePath =
                        canonicalRoot
                            .toPath()
                            .relativize(canonical.toPath())
                            .toString()
                            .replace(File.separatorChar, '/'),
                    sizeBytes = canonical.length().coerceAtLeast(0L),
                    modifiedAt = canonical.lastModified().coerceAtLeast(0L),
                    mimeType = null,
                    source = source,
                )
            }.toList()
    return SourceScan(nodes)
}

internal suspend fun LocalWallpaperFileRepository.scanTree(
    source: LocalWallpaperSource,
    treeUri: Uri,
): SourceScan {
    val root = DocumentFile.fromTreeUri(applicationContext, treeUri)
    if (root == null || !root.exists() || !root.canRead()) {
        return SourceScan(
            nodes = emptyList(),
            issue =
                LocalWallpaperScanIssue(
                    sourceId = source.id,
                    message = applicationContext.getString(R.string.backend_local_read_failed, source.label),
                    requiresAuthorization = true,
                ),
        )
    }
    val nodes = mutableListOf<ScanNode>()
    val pending = ArrayDeque<Pair<DocumentFile, String>>()
    pending += root to ""
    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (directory, parentPath) = pending.removeFirst()
        val children =
            runCatching { directory.listFiles().toList() }
                .getOrElse { error ->
                    if (error is SecurityException) throw error
                    emptyList()
                }
        children.forEach { child ->
            val name = child.name?.trim().orEmpty()
            if (name.isBlank() || name.startsWith('.')) return@forEach
            val relativePath =
                listOf(parentPath, name)
                    .filter(String::isNotBlank)
                    .joinToString("/")
            when {
                child.isDirectory -> pending += child to relativePath
                child.isFile ->
                    nodes +=
                        ScanNode(
                            uri = child.uri,
                            name = name,
                            relativePath = relativePath,
                            sizeBytes = child.length().coerceAtLeast(0L),
                            modifiedAt = child.lastModified().coerceAtLeast(0L),
                            mimeType = child.type,
                            source = source,
                        )
            }
        }
    }
    return SourceScan(nodes)
}

internal suspend fun LocalWallpaperFileRepository.inspectNodes(
    nodes: List<ScanNode>,
    tasks: List<FormalTaskRecordEntity>,
    states: Map<String, LocalWallpaperStateEntity>,
    tags: Map<String, List<String>>,
    onResource: suspend (LocalWallpaperResource) -> Unit,
) {
    val sortedNodes = nodes.sortedByDescending(ScanNode::modifiedAt)
    val nodesByParent = sortedNodes.groupBy(ScanNode::parentPath)
    val projectParents =
        nodesByParent
            .mapNotNull { (parent, siblings) ->
                val hasIndex = siblings.any { node -> node.name.equals("index.html", ignoreCase = true) }
                val hasProject = siblings.any { node -> node.name.equals("project.json", ignoreCase = true) }
                parent.takeIf { hasIndex || hasProject }
            }.sortedByDescending(String::length)
    val emittedIds = mutableSetOf<String>()
    for (parent in projectParents) {
        currentCoroutineContext().ensureActive()
        val projectNodes = sortedNodes.filter { node -> node.isInside(parent) }
        val resource = inspectProjectDirectory(parent, projectNodes, tasks, states, tags)
        if (resource != null && emittedIds.add(resource.id)) onResource(resource)
    }
    for (node in sortedNodes) {
        currentCoroutineContext().ensureActive()
        if (
            node.isTemporary() ||
            projectParents.any { parent -> node.isInside(parent) } ||
            node.extension in SIDECAR_EXTENSIONS
        ) {
            continue
        }
        val resource =
            inspectStandalone(
                node,
                tasks,
                states,
                tags,
            )
        if (resource != null && emittedIds.add(resource.id)) onResource(resource)
    }
}

internal fun LocalWallpaperFileRepository.inspectProjectDirectory(
    parent: String,
    siblings: List<ScanNode>,
    tasks: List<FormalTaskRecordEntity>,
    states: Map<String, LocalWallpaperStateEntity>,
    tags: Map<String, List<String>>,
): LocalWallpaperResource? {
    val projectNode = siblings.firstOrNull { node -> node.name.equals("project.json", ignoreCase = true) }
    val indexNode = siblings.firstOrNull { node -> node.name.equals("index.html", ignoreCase = true) }
    val packageNode = siblings.firstOrNull { node -> node.extension == "pkg" }
    val videoNode = siblings.firstOrNull { node -> node.extension in VIDEO_EXTENSIONS }
    val metadata = projectNode?.let(::readProjectMetadata)
    val format =
        when {
            indexNode != null || metadata?.type == WorkshopType.WEB -> LocalWallpaperFormat.HTML
            packageNode != null || metadata?.type == WorkshopType.SCENE -> LocalWallpaperFormat.PKG
            videoNode != null || metadata?.type == WorkshopType.VIDEO -> LocalWallpaperFormat.VIDEO
            else -> LocalWallpaperFormat.UNKNOWN
        }
    val anchor = indexNode ?: projectNode ?: packageNode ?: videoNode ?: return null
    val workshopId =
        siblings.asSequence().mapNotNull(::workshopIdFromNode).firstOrNull()
            ?: workshopIdFromNode(anchor)
    val task = matchTask(anchor, workshopId, tasks)
    val title =
        metadata
            ?.title
            ?.takeIf(String::isNotBlank)
            ?: task?.title
            ?: parent.substringAfterLast('/').ifBlank { anchor.name.substringBeforeLast('.') }
    val id = resourceId(anchor.source.id, anchor.uri.toString())
    val state = states[id]
    val resource =
        LocalWallpaperResource(
            id = id,
            contentUri = anchor.uri.toString(),
            displayName = anchor.name,
            title = title,
            format = format,
            workshopType = metadata?.type ?: task?.type?.toWorkshopType() ?: WorkshopType.UNKNOWN,
            sourceId = anchor.source.id,
            sourceLabel = anchor.source.label,
            relativePath = parent.ifBlank { anchor.relativePath },
            sizeBytes = siblings.sumOf(ScanNode::sizeBytes),
            modifiedAt = siblings.maxOfOrNull(ScanNode::modifiedAt) ?: anchor.modifiedAt,
            mimeType = anchor.mimeType,
            workshopId = workshopId ?: task?.workshopId,
            detectionReason =
                when (format) {
                    LocalWallpaperFormat.HTML -> applicationContext.getString(R.string.backend_local_reason_web_project)
                    LocalWallpaperFormat.PKG -> applicationContext.getString(R.string.backend_local_reason_scene_project)
                    LocalWallpaperFormat.VIDEO -> applicationContext.getString(R.string.backend_local_reason_video_project)
                    else -> applicationContext.getString(R.string.backend_local_reason_unknown_project)
                },
            isDirectoryProject = true,
            isFavorite = state?.isFavorite ?: false,
            tags = tags[id].orEmpty().toSet(),
            importRequestedAt = state?.importRequestedAt,
        )
    projectContentUris[id] = siblings.map(ScanNode::uri).distinct().map(Uri::toString)
    return resource
}

internal fun LocalWallpaperFileRepository.inspectStandalone(
    node: ScanNode,
    tasks: List<FormalTaskRecordEntity>,
    states: Map<String, LocalWallpaperStateEntity>,
    tags: Map<String, List<String>>,
): LocalWallpaperResource? {
    val packageInspection =
        when (node.extension) {
            "mpkg" -> inspectMpkg(node)
            "zip" -> inspectZip(node)
            else -> null
        }
    val format =
        packageInspection?.format ?: when {
            node.extension == "mpkg" -> LocalWallpaperFormat.MPKG
            node.extension == "pkg" -> LocalWallpaperFormat.PKG
            node.extension in VIDEO_EXTENSIONS -> LocalWallpaperFormat.VIDEO
            node.extension in HTML_EXTENSIONS -> LocalWallpaperFormat.HTML
            node.extension in UNKNOWN_ARCHIVE_EXTENSIONS -> LocalWallpaperFormat.UNKNOWN
            node.mimeType?.startsWith("video/") == true -> LocalWallpaperFormat.VIDEO
            else -> LocalWallpaperFormat.UNKNOWN
        }
    val workshopId = workshopIdFromNode(node)
    val task = matchTask(node, workshopId, tasks)
    val id = resourceId(node.source.id, node.uri.toString())
    val state = states[id]
    val typeFromPackage = packageInspection?.workshopType ?: WorkshopType.UNKNOWN
    val workshopType =
        when {
            typeFromPackage != WorkshopType.UNKNOWN -> typeFromPackage
            task != null -> task.type.toWorkshopType()
            format == LocalWallpaperFormat.HTML -> WorkshopType.WEB
            format == LocalWallpaperFormat.VIDEO -> WorkshopType.VIDEO
            format == LocalWallpaperFormat.PKG -> WorkshopType.SCENE
            else -> WorkshopType.UNKNOWN
        }
    return LocalWallpaperResource(
        id = id,
        contentUri = node.uri.toString(),
        displayName = node.name,
        title = task?.title ?: packageInspection?.title ?: node.name.substringBeforeLast('.'),
        format = format,
        workshopType = workshopType,
        sourceId = node.source.id,
        sourceLabel = node.source.label,
        relativePath = node.relativePath,
        sizeBytes = node.sizeBytes,
        modifiedAt = node.modifiedAt,
        mimeType = node.mimeType,
        workshopId = workshopId ?: task?.workshopId,
        detectionReason =
            packageInspection?.reason ?: when (format) {
                LocalWallpaperFormat.MPKG -> applicationContext.getString(R.string.backend_local_reason_mpkg_extension)
                LocalWallpaperFormat.PKG -> applicationContext.getString(R.string.backend_local_reason_pkg_extension)
                LocalWallpaperFormat.VIDEO -> applicationContext.getString(R.string.backend_local_reason_video_type)
                LocalWallpaperFormat.HTML -> applicationContext.getString(R.string.backend_local_reason_html_extension)
                LocalWallpaperFormat.UNKNOWN -> applicationContext.getString(R.string.backend_local_reason_unknown_type)
            },
        isFavorite = state?.isFavorite ?: false,
        tags = tags[id].orEmpty().toSet(),
        importRequestedAt = state?.importRequestedAt,
    )
}

internal fun LocalWallpaperFileRepository.inspectMpkg(node: ScanNode): PackageInspection =
    runCatching {
        resolver.openFileDescriptor(node.uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                val magic = channel.readLengthString(MAX_MAGIC_LENGTH)
                require(magic.startsWith("PKGM")) { "Invalid MPKG magic" }
                val entryCount = channel.readUnsignedIntLe()
                require(entryCount <= MAX_PACKAGE_ENTRY_COUNT) { "MPKG index is too large" }
                repeat(entryCount.toInt()) {
                    channel.readLengthString(MAX_PACKAGE_PATH_LENGTH)
                    channel.readUnsignedIntLe()
                    channel.readUnsignedIntLe()
                }
                val workshopType =
                    when (magic) {
                        SCENE_MPKG_MAGIC -> WorkshopType.SCENE
                        VIDEO_MPKG_MAGIC -> WorkshopType.VIDEO
                        else -> WorkshopType.UNKNOWN
                    }
                PackageInspection(
                    format = LocalWallpaperFormat.MPKG,
                    workshopType = workshopType,
                    reason =
                        applicationContext.resources.getQuantityString(
                            R.plurals.backend_local_reason_mpkg_inspected,
                            entryCount.toInt(),
                            magic,
                            entryCount,
                        ),
                )
            }
        } ?: error("Failed to open MPKG")
    }.getOrElse { error ->
        PackageInspection(
            format = LocalWallpaperFormat.MPKG,
            reason =
                applicationContext.getString(
                    R.string.backend_local_reason_mpkg_inspection_failed,
                    error.message ?: error.javaClass.simpleName,
                ),
        )
    }

internal fun LocalWallpaperFileRepository.inspectZip(node: ScanNode): PackageInspection {
    var hasHtml = false
    var title: String? = null
    var workshopType = WorkshopType.UNKNOWN
    return runCatching {
        resolver.openInputStream(node.uri)?.use { rawInput ->
            ZipInputStream(BufferedInputStream(rawInput)).use { zip ->
                var entries = 0
                while (entries < MAX_ZIP_ENTRIES_TO_INSPECT) {
                    val entry = zip.nextEntry ?: break
                    entries += 1
                    val normalized = entry.name.replace('\\', '/').lowercase(Locale.ROOT)
                    if (normalized.endsWith(".html")) hasHtml = true
                    if (normalized.endsWith("project.json") && entry.size <= MAX_PROJECT_JSON_BYTES) {
                        val bytes = zip.readLimited(MAX_PROJECT_JSON_BYTES)
                        parseProjectMetadata(bytes.toString(Charsets.UTF_8))?.let { metadata ->
                            title = metadata.title ?: title
                            workshopType = metadata.type
                            if (metadata.type == WorkshopType.WEB) hasHtml = true
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("Failed to open ZIP")
        PackageInspection(
            format = if (hasHtml) LocalWallpaperFormat.HTML else LocalWallpaperFormat.UNKNOWN,
            workshopType =
                if (hasHtml && workshopType == WorkshopType.UNKNOWN) {
                    WorkshopType.WEB
                } else {
                    workshopType
                },
            title = title,
            reason =
                if (hasHtml) {
                    applicationContext.getString(R.string.backend_local_reason_zip_web)
                } else {
                    applicationContext.getString(R.string.backend_local_reason_zip_not_web)
                },
        )
    }.getOrElse { error ->
        PackageInspection(
            format = LocalWallpaperFormat.UNKNOWN,
            reason =
                applicationContext.getString(
                    R.string.backend_local_reason_zip_inspection_failed,
                    error.message ?: error.javaClass.simpleName,
                ),
        )
    }
}

internal fun LocalWallpaperFileRepository.readProjectMetadata(node: ScanNode): ProjectMetadata? =
    runCatching {
        resolver.openInputStream(node.uri)?.use { input ->
            parseProjectMetadata(input.readLimited(MAX_PROJECT_JSON_BYTES).toString(Charsets.UTF_8))
        }
    }.getOrNull()

internal fun LocalWallpaperFileRepository.parseProjectMetadata(json: String): ProjectMetadata? =
    runCatching {
        val objectValue = JSONObject(json)
        ProjectMetadata(
            title = objectValue.optString("title").takeIf(String::isNotBlank),
            type =
                when (objectValue.optString("type").lowercase(Locale.ROOT)) {
                    "video" -> WorkshopType.VIDEO
                    "scene" -> WorkshopType.SCENE
                    "web", "website" -> WorkshopType.WEB
                    else -> WorkshopType.UNKNOWN
                },
        )
    }.getOrNull()

internal fun LocalWallpaperFileRepository.matchTask(
    node: ScanNode,
    workshopId: Long?,
    tasks: List<FormalTaskRecordEntity>,
): FormalTaskRecordEntity? =
    tasks.firstOrNull { task -> task.outputUri == node.uri.toString() }
        ?: workshopId?.let { id ->
            tasks.filter { task -> task.workshopId == id }.maxByOrNull(FormalTaskRecordEntity::updatedAt)
        }

internal fun LocalWallpaperFileRepository.workshopIdFromNode(node: ScanNode): Long? {
    val baseName = node.name.substringBeforeLast('.')
    return WORKSHOP_ID_PATTERN
        .findAll(baseName)
        .lastOrNull()
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
}

internal fun LocalWallpaperFileRepository.snapshot(
    resources: Collection<LocalWallpaperResource>,
    sources: List<LocalWallpaperSource>,
    discoveredCount: Int,
    currentSourceLabel: String?,
    issues: List<LocalWallpaperScanIssue>,
    isScanning: Boolean,
): LocalWallpaperScanSnapshot =
    LocalWallpaperScanSnapshot(
        resources =
            resources.sortedWith(
                compareByDescending<LocalWallpaperResource> { resource -> resource.modifiedAt }
                    .thenBy { resource -> resource.title.lowercase(Locale.ROOT) },
            ),
        sources = sources,
        discoveredCount = discoveredCount,
        currentSourceLabel = currentSourceLabel,
        isScanning = isScanning,
        issues = issues.toList(),
    )

internal fun LocalWallpaperFileRepository.normalizeTag(tag: String): String? =
    tag
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(MAX_TAG_LENGTH)
        .takeIf(String::isNotBlank)

internal fun LocalWallpaperFileRepository.resourceId(
    sourceId: String,
    uri: String,
): String {
    val digest =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$sourceId|$uri".toByteArray(Charsets.UTF_8))
    return digest.take(RESOURCE_ID_BYTES).joinToString("") { byte -> "%02x".format(byte) }
}

internal sealed interface SourcePlan {
    val source: LocalWallpaperSource

    data class PublicDownloads(
        override val source: LocalWallpaperSource,
    ) : SourcePlan

    data class Tree(
        override val source: LocalWallpaperSource,
        val treeUri: String,
    ) : SourcePlan
}

internal data class SourceScan(
    val nodes: List<ScanNode>,
    val issue: LocalWallpaperScanIssue? = null,
)

internal data class ScanNode(
    val uri: Uri,
    val name: String,
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val mimeType: String?,
    val source: LocalWallpaperSource,
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    val parentPath: String
        get() = relativePath.substringBeforeLast('/', "")

    fun isInside(parent: String): Boolean =
        parent.isBlank() ||
            relativePath == parent ||
            relativePath.startsWith("$parent/")

    fun isTemporary(): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".part") ||
            lower.endsWith(".wallhub.part") ||
            lower.endsWith(".tmp") ||
            lower.startsWith('.')
    }
}

internal data class ProjectMetadata(
    val title: String?,
    val type: WorkshopType,
)

internal data class PackageInspection(
    val format: LocalWallpaperFormat,
    val workshopType: WorkshopType = WorkshopType.UNKNOWN,
    val title: String? = null,
    val reason: String,
)

internal const val DOWNLOAD_SOURCE_ID = "download"
internal const val LOCAL_SOURCE_ID = "local"
internal const val DEFAULT_DOWNLOAD_DIRECTORY = "WallHub"
internal const val DEFAULT_DOWNLOAD_LABEL = "Download/WallHub"
internal const val DEFAULT_DOWNLOAD_ROOT_URI = "content://media/external/downloads/Download/WallHub"
internal const val RESULT_EMIT_BATCH_SIZE = 8
internal const val MAX_TAG_LENGTH = 40
internal const val RESOURCE_ID_BYTES = 12
internal const val MAX_MAGIC_LENGTH = 64
internal const val MAX_PACKAGE_PATH_LENGTH = 16 * 1024
internal const val MAX_PACKAGE_ENTRY_COUNT = 200_000L
internal const val MAX_ZIP_ENTRIES_TO_INSPECT = 4_096
internal const val MAX_PROJECT_JSON_BYTES = 256 * 1024
internal const val SCENE_MPKG_MAGIC = "PKGM0020"
internal const val VIDEO_MPKG_MAGIC = "PKGM0014"
internal val WORKSHOP_ID_PATTERN = Regex("(?:^|[-_ ])(\\d{5,})(?=$|[-_ ])")
internal val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "avi", "m4v")
internal val HTML_EXTENSIONS = setOf("html", "htm")
internal val UNKNOWN_ARCHIVE_EXTENSIONS = setOf("bin", "dat", "pak", "archive")
internal val SIDECAR_EXTENSIONS =
    setOf(
        "jpg",
        "jpeg",
        "png",
        "webp",
        "gif",
        "json",
        "css",
        "js",
        "txt",
        "md",
    )

private fun String.toWorkshopType(): WorkshopType =
    enumValues<WorkshopType>()
        .firstOrNull { type -> type.name.equals(this, ignoreCase = true) }
        ?: WorkshopType.UNKNOWN

internal fun java.io.InputStream.readLimited(maxBytes: Int): ByteArray {
    val buffer = ByteArray(maxBytes + 1)
    var offset = 0
    while (offset < buffer.size) {
        val read = read(buffer, offset, buffer.size - offset)
        if (read < 0) break
        offset += read
    }
    require(offset <= maxBytes) { "Content exceeds the inspection limit" }
    return buffer.copyOf(offset)
}

internal fun FileChannel.readUnsignedIntLe(): Long = Integer.toUnsignedLong(readIntLe())

internal fun FileChannel.readIntLe(): Int {
    val buffer = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    readFully(buffer)
    buffer.flip()
    return buffer.int
}

internal fun FileChannel.readLengthString(maxLength: Int): String {
    val length = readIntLe()
    require(length in 0..maxLength) { "Invalid string length: $length" }
    return readBytes(length).toString(Charsets.UTF_8)
}

internal fun FileChannel.readBytes(length: Int): ByteArray {
    require(length >= 0) { "Invalid read length" }
    val buffer = ByteBuffer.allocate(length)
    readFully(buffer)
    return buffer.array()
}

internal fun FileChannel.readFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) {
        require(read(buffer) >= 0) { "Incomplete file content" }
    }
}
