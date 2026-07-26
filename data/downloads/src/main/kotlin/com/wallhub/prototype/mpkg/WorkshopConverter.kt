package com.wallhub.prototype.mpkg

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class WorkshopKind(val outputExtension: String) {
    SCENE("mpkg"),
    VIDEO("mpkg"),
    WEB("zip"),
}

data class WorkshopConversionReport(
    val kind: WorkshopKind,
    val outputFile: File,
    val convertedTextures: Int = 0,
    val copiedTextures: Int = 0,
    val removedAudio: Int = 0,
    val rewrittenShaders: Int = 0,
    val warnings: List<String> = emptyList(),
)

object WorkshopConverter {
    private val previewExtensions = listOf("gif", "jpg", "jpeg", "png", "webp")
    private val audioExtensions = setOf("mp3", "wav", "ogg", "flac", "aac", "m4a")

    fun detect(inputDir: File, contentTypeHint: String? = null): WorkshopKind {
        contentTypeHint?.trim()?.lowercase(Locale.ROOT)?.let { hint ->
            when (hint) {
                "video" -> return WorkshopKind.VIDEO
                "web", "website" -> return WorkshopKind.WEB
                "scene" -> return WorkshopKind.SCENE
            }
        }
        val project = readProject(inputDir)
        return when (project.string("type")?.lowercase(Locale.ROOT)) {
            "video" -> WorkshopKind.VIDEO
            "web", "website" -> WorkshopKind.WEB
            else -> WorkshopKind.SCENE
        }
    }

    fun convert(
        inputDir: File,
        outputFile: File,
        contentTypeHint: String? = null,
    ): WorkshopConversionReport {
        require(inputDir.isDirectory) { "Workshop input directory is missing" }
        return when (detect(inputDir, contentTypeHint)) {
            WorkshopKind.VIDEO -> convertVideo(inputDir, outputFile)
            WorkshopKind.SCENE -> convertScene(inputDir, outputFile)
            WorkshopKind.WEB -> convertWeb(inputDir, outputFile)
        }
    }

    private fun convertVideo(inputDir: File, outputFile: File): WorkshopConversionReport {
        val project = readProject(inputDir)
        val preview = findPreview(inputDir) ?: error("Video workshop is missing preview.*")
        val rawVideoName = project.string("file") ?: error("Video project.json is missing file")
        val videoFile = resolveLocalProjectFile(inputDir, rawVideoName)
        val normalizedVideoName = normalizeRelativePath(rawVideoName)
        val title = project.string("title").orEmpty().ifBlank { inputDir.name }
        val projectJson = buildVideoProjectJson(title, normalizedVideoName, preview.name)
        MpkgWriter.write(
            entries = listOf(
                MpkgInputEntry(normalizedVideoName, FilePayload(videoFile)),
                MpkgInputEntry(preview.name, FilePayload(preview)),
                MpkgInputEntry("project.json", ByteArrayPayload(projectJson)),
            ),
            outputFile = outputFile,
            magic = VIDEO_MPKG_MAGIC,
        )
        return WorkshopConversionReport(WorkshopKind.VIDEO, outputFile)
    }

    private fun convertScene(inputDir: File, outputFile: File): WorkshopConversionReport {
        val projectFile = File(inputDir, "project.json")
        require(projectFile.isFile) { "Scene workshop is missing project.json" }
        val preview = findPreview(inputDir) ?: error("Scene workshop is missing preview.*")
        val scenePackage = File(inputDir, "scene.pkg")
        require(scenePackage.isFile) { "Scene workshop is missing scene.pkg" }

        var convertedTextures = 0
        var copiedTextures = 0
        var removedAudio = 0
        var rewrittenShaders = 0
        val warnings = mutableListOf<String>()
        val archive = PkgReader.readIndex(scenePackage)
        val transformedDirectory = createConversionDirectory(outputFile)
        try {
            val entries = mutableListOf<MpkgInputEntry>()
            archive.entries.forEachIndexed { index, entry ->
                val extension = entry.path.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (extension in audioExtensions) {
                    removedAudio += 1
                    return@forEachIndexed
                }
                when (extension) {
                    "tex" -> {
                        require(entry.length <= MAX_DESKTOP_TEX_BYTES) {
                            "无法转换移动端纹理 ${entry.path}：纹理文件超过移动端转换内存限制"
                        }
                        val transformed = File(transformedDirectory, "$index.tex")
                        val result = TexMobileConverter.convertToFile(archive.readBytes(entry), transformed)
                        if (!result.converted) {
                            error("无法转换移动端纹理 ${entry.path}：${result.reason}")
                        }
                        convertedTextures += 1
                        entries += MpkgInputEntry(entry.path, FilePayload(transformed))
                    }
                    "frag", "vert" -> {
                        val original = archive.readBytes(entry).toString(Charsets.UTF_8)
                        val rewritten = ShaderCompatibility.rewrite(original)
                        if (rewritten != original) rewrittenShaders += 1
                        val transformed = File(transformedDirectory, "$index.$extension")
                        transformed.writeText(rewritten, Charsets.UTF_8)
                        entries += MpkgInputEntry(entry.path, FilePayload(transformed))
                    }
                    else -> entries += MpkgInputEntry(
                        entry.path,
                        FileSlicePayload(scenePackage, entry.offset, entry.length),
                    )
                }
            }
            entries += MpkgInputEntry("project.json", FilePayload(projectFile))
            entries += MpkgInputEntry(preview.name, FilePayload(preview))
            MpkgWriter.write(entries, outputFile, SCENE_MPKG_MAGIC)
        } finally {
            transformedDirectory.deleteRecursively()
        }
        return WorkshopConversionReport(
            kind = WorkshopKind.SCENE,
            outputFile = outputFile,
            convertedTextures = convertedTextures,
            copiedTextures = copiedTextures,
            removedAudio = removedAudio,
            rewrittenShaders = rewrittenShaders,
            warnings = warnings.distinct(),
        )
    }

    private fun convertWeb(inputDir: File, outputFile: File): WorkshopConversionReport {
        val outputPath = outputFile.canonicalFile.toPath()
        val files = walkFiles(inputDir).filterNot { it.canonicalFile.toPath() == outputPath }
        writeAtomically(outputFile) { temporaryFile ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temporaryFile))).use { zip ->
                files.forEach { file ->
                    val path = relativePath(inputDir, file)
                    zip.putNextEntry(ZipEntry(path))
                    BufferedInputStream(FileInputStream(file)).use { input ->
                        input.copyTo(zip, bufferSize = 1024 * 1024)
                    }
                    zip.closeEntry()
                }
            }
        }
        return WorkshopConversionReport(WorkshopKind.WEB, outputFile)
    }

    private fun createConversionDirectory(outputFile: File): File {
        val parent = outputFile.absoluteFile.parentFile ?: error("Output file has no parent directory")
        check(parent.exists() || parent.mkdirs()) { "Unable to create MPKG output directory" }
        repeat(10) {
            val candidate = File(parent, ".${outputFile.name}.assets-${UUID.randomUUID()}")
            if (candidate.mkdir()) return candidate
        }
        error("Unable to create MPKG conversion directory")
    }

    private fun findPreview(inputDir: File): File? {
        val files = inputDir.listFiles().orEmpty()
            .filter { it.isFile && it.nameWithoutExtension.equals("preview", ignoreCase = true) }
        return files.minWithOrNull(
            compareBy<File> {
                previewExtensions.indexOf(it.extension.lowercase(Locale.ROOT)).let { index ->
                    if (index >= 0) index else Int.MAX_VALUE
                }
            }.thenBy { it.name.lowercase(Locale.ROOT) },
        )
    }

    private fun resolveLocalProjectFile(root: File, rawPath: String): File {
        val normalized = normalizeRelativePath(rawPath)
        val rootPath = root.canonicalFile.toPath()
        val candidate = File(root, normalized).canonicalFile
        require(candidate.toPath().startsWith(rootPath)) { "Video file escapes workshop folder" }
        require(candidate.isFile) { "Video project file is missing: $normalized" }
        return candidate
    }

    private fun normalizeRelativePath(rawPath: String): String {
        val normalized = rawPath.trim().replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && !normalized.matches(Regex("^[A-Za-z]:.*"))) {
            "Project file must be a relative path"
        }
        return MpkgWriter.normalizePath(normalized)
    }

    private fun relativePath(root: File, file: File): String {
        val rootPath = root.canonicalFile.toPath()
        val path = file.canonicalFile.toPath()
        require(path.startsWith(rootPath)) { "Website file escapes workshop folder" }
        return MpkgWriter.normalizePath(rootPath.relativize(path).toString())
    }

    private fun walkFiles(root: File): List<File> {
        return root.walkTopDown()
            .filter { it.isFile }
            .sortedBy { relativePath(root, it).lowercase(Locale.ROOT) }
            .toList()
    }

    private fun readProject(inputDir: File): WorkshopProject {
        val projectFile = File(inputDir, "project.json")
        require(projectFile.isFile) { "Workshop input is missing project.json" }
        return WorkshopProject(projectFile.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
    }

    private fun buildVideoProjectJson(title: String, videoName: String, previewName: String): ByteArray {
        fun escape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        return buildString {
            append("{\r\n")
            append("\t\"file\" : \"").append(escape(videoName)).append("\",\r\n")
            append("\t\"preview\" : \"").append(escape(previewName)).append("\",\r\n")
            append("\t\"title\" : \"").append(escape(title)).append("\",\r\n")
            append("\t\"type\" : \"video\"\r\n")
            append("}")
        }.toByteArray(Charsets.UTF_8)
    }

    private class WorkshopProject(private val source: String) {
        fun string(name: String): String? {
            val expression = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
            val encoded = expression.find(source)?.groupValues?.getOrNull(1) ?: return null
            return encoded.replace(Regex("\\\\(.)")) { match ->
                when (match.groupValues[1]) {
                    "n" -> "\n"
                    "r" -> "\r"
                    "t" -> "\t"
                    else -> match.groupValues[1]
                }
            }
        }
    }
}

object ShaderCompatibility {
    private val sampleCountDeclaration = Regex("const int sampleCount = (\\d+);")
    private val sampleCountLoop = Regex("for \\(int i = 0; i < sampleCount; \\+\\+i\\)")
    private val textureCoordinateZero = Regex("(v_TexCoord\\.[zw] = )0;")
    private const val COMMON_BLUR_INCLUDE = "#include \"common_blur.h\""
    private const val HIDDEN_TEXTURE_DECLARATION = "uniform sampler2D g_Texture0; // {\"hidden\":true}"
    private const val HIDDEN_TEXTURE_MARKER = " // {\"hidden\":true}"

    fun rewrite(source: String): String {
        var output = source.replace(Regex("\\bsample\\b"), "_sample")
        mapOf(
            Regex("(?<![A-Za-z0-9_])vec4\\(1, 0, 0, 1\\)") to "vec4(1.0, 0.0, 0.0, 1.0)",
            Regex("(?<![A-Za-z0-9_])vec2\\(1, 0\\)") to "vec2(1.0, 0.0)",
            Regex("(?<![A-Za-z0-9_])vec2\\(0, 1\\)") to "vec2(0.0, 1.0)",
            Regex("(?<![A-Za-z0-9_])vec2\\(0, -0\\.5\\)") to "vec2(0.0, -0.5)",
            Regex("(?<![A-Za-z0-9_])vec2\\(0, 0\\.5\\)") to "vec2(0.0, 0.5)",
        ).forEach { (old, new) -> output = output.replace(old, new) }
        mapOf(
            "CAST3(0)" to "CAST3(0.0)",
            "mix(blurred.a, 1, step(blurred.a, 0))" to "mix(blurred.a, 1.0, step(blurred.a, 0.0))",
            "2 * abs(" to "2.0 * abs(",
            "30 / 50.0" to "30.0 / 50.0",
            "30 / 4.0" to "30.0 / 4.0",
            "30 / 8.0" to "30.0 / 8.0",
            "30 / 15.0" to "30.0 / 15.0",
        ).forEach { (old, new) -> output = output.replace(old, new) }
        output = output.replace(
            "#define SMOOTH_CURVE A_SMOOTH_CURVE // For compatability with old wallpapers that get ported to Android\r\n\r\n",
            "",
        ).replace(
            "#define SMOOTH_CURVE A_SMOOTH_CURVE // For compatability with old wallpapers that get ported to Android\n\n",
            "",
        )
        output = output.replace(sampleCountDeclaration) { match ->
            "const float sampleCount = ${match.groupValues[1]}.0;"
        }
        output = output.replace("const float sampleDrop = sampleCount - 1;", "const float sampleDrop = sampleCount - 1.0;")
        output = output.replace(sampleCountLoop, "for (float i = 0.0; i < sampleCount; ++i)")
        output = output.replace(textureCoordinateZero) { match -> "${match.groupValues[1]}0.0;" }
        if (output.contains(COMMON_BLUR_INCLUDE)) {
            val newline = if (output.contains("\r\n")) "\r\n" else "\n"
            output = output.replace(HIDDEN_TEXTURE_DECLARATION, HIDDEN_TEXTURE_MARKER)
            if (output.contains(HIDDEN_TEXTURE_MARKER) && !output.contains("uniform sampler2D g_Texture0;")) {
                output = output.replaceFirst(
                    COMMON_BLUR_INCLUDE,
                    "uniform sampler2D g_Texture0;$newline$COMMON_BLUR_INCLUDE",
                )
            }
        }
        return output
    }

}

private const val MAX_DESKTOP_TEX_BYTES = 64L * 1024L * 1024L
