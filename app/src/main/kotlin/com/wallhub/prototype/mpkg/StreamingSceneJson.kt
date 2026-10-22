package com.wallhub.prototype.mpkg

import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale

/** Memory-bounded JSON inspection and scene-settings rewrite over one PKG file slice. */
internal object StreamingSceneJson {
    data class Analysis(
        val semantics: Map<String, TextureSemantic>,
        val warning: String? = null,
    )

    fun analyzeDocuments(
        source: File,
        entries: List<IndexedPkgEntry>,
        texturePaths: Set<String>,
        checkCancellation: () -> Unit = {},
    ): MaterialGraphResult {
        val normalizedTextures = texturePaths.associateBy(::normalizeReference)
        val semantics = mutableMapOf<String, TextureSemantic>()
        val warnings = mutableSetOf<String>()
        val documentWarnings = mutableMapOf<String, MutableSet<String>>()
        val budget = WorkBudget(MAX_ANALYSIS_NODES)
        entries.forEach { entry ->
            checkCancellation()
            val result =
                runCatching {
                    SliceParser(source, entry, normalizedTextures, semantics, budget, checkCancellation).parse()
                }
            if (result.isFailure) {
                warnings += "material_graph_document_invalid"
                documentWarnings.getOrPut(entry.path) { mutableSetOf() } += "material_graph_document_invalid"
            }
            if (budget.exhausted) {
                warnings += "material_graph_node_budget_exhausted"
                documentWarnings.getOrPut(entry.path) { mutableSetOf() } += "material_graph_node_budget_exhausted"
            }
        }
        texturePaths.filterNot(semantics::containsKey).forEach { semantics[it] = TextureSemantic.UNKNOWN }
        return MaterialGraphResult(
            semantics = semantics.toSortedMap(),
            warningCodes = warnings.toSortedSet(),
            documentWarnings = documentWarnings.mapValues { it.value.toSortedSet() }.toSortedMap(),
        )
    }

    fun rewriteTextureReduction(
        source: File,
        entry: IndexedPkgEntry,
        textureReduction: Int,
        output: File,
        checkCancellation: () -> Unit = {},
    ) {
        require(textureReduction in setOf(1, 2, 4)) { "Unsupported official texture reduction" }
        val locator = SliceParser(source, entry, emptyMap(), mutableMapOf(), WorkBudget(0), checkCancellation)
        locator.parse()
        val replacement = textureReduction.toString().toByteArray(Charsets.US_ASCII)
        val patch =
            when {
                locator.textureReductionValueStart != null ->
                    Patch(requireNotNull(locator.textureReductionValueStart), requireNotNull(locator.textureReductionValueEnd), replacement)
                locator.generalObjectOpen != null -> {
                    val insertion =
                        if (locator.generalObjectEmpty) {
                            "\"texturereduction\":$textureReduction"
                        } else {
                            "\"texturereduction\":$textureReduction,"
                        }.toByteArray(Charsets.UTF_8)
                    val at = requireNotNull(locator.generalObjectOpen) + 1L
                    Patch(at, at, insertion)
                }
                else -> {
                    val insertion =
                        if (locator.rootObjectEmpty) {
                            "\"general\":{\"texturereduction\":$textureReduction}"
                        } else {
                            "\"general\":{\"texturereduction\":$textureReduction},"
                        }.toByteArray(Charsets.UTF_8)
                    val at = requireNotNull(locator.rootObjectOpen) + 1L
                    Patch(at, at, insertion)
                }
            }
        check(output.parentFile?.let { it.exists() || it.mkdirs() } != false) { "Unable to create scene rewrite directory" }
        RandomAccessFile(source, "r").use { input ->
            BufferedOutputStream(FileOutputStream(output)).use { target ->
                copyRange(input, entry.offset, patch.start, target, checkCancellation)
                target.write(patch.bytes)
                copyRange(input, patch.end, entry.offset + entry.length, target, checkCancellation)
            }
        }
        require(output.length() <= entry.length + MAX_PATCH_GROWTH_BYTES) { "Scene settings rewrite grew unexpectedly" }
        SliceParser(output, IndexedPkgEntry(entry.path, 0L, output.length()), emptyMap(), mutableMapOf(), WorkBudget(0), checkCancellation).parse()
    }

    private data class Patch(val start: Long, val end: Long, val bytes: ByteArray)
    private data class WorkBudget(var remaining: Long, var exhausted: Boolean = false) {
        fun visit(): Boolean {
            if (remaining <= 0L) {
                exhausted = true
                return false
            }
            remaining--
            return true
        }
    }

    private class SliceParser(
        source: File,
        private val entry: IndexedPkgEntry,
        private val textures: Map<String, String>,
        private val semantics: MutableMap<String, TextureSemantic>,
        private val budget: WorkBudget,
        private val checkCancellation: () -> Unit,
    ) {
        private val input = RandomAccessFile(source, "r")
        private val end = entry.offset + entry.length
        var rootObjectOpen: Long? = null
            private set
        var rootObjectEmpty: Boolean = true
            private set
        var generalObjectOpen: Long? = null
            private set
        var generalObjectEmpty: Boolean = true
            private set
        var textureReductionValueStart: Long? = null
            private set
        var textureReductionValueEnd: Long? = null
            private set
        private var steps = 0

        fun parse() {
            input.use {
                input.seek(entry.offset)
                skipWhitespace()
                require(peek() == '{'.code) { "Scene JSON root must be an object" }
                rootObjectOpen = input.filePointer
                parseObject(depth = 0, objectName = null)
                skipWhitespace()
                require(input.filePointer == end) { "Scene JSON has trailing bytes" }
            }
        }

        private fun parseObject(depth: Int, objectName: String?) {
            require(depth <= MAX_STRUCTURAL_DEPTH) { "Scene JSON nesting exceeds limit" }
            expect('{')
            skipWhitespace()
            var first = true
            while (peek() != '}'.code) {
                if (!first) {
                    expect(',')
                    skipWhitespace()
                }
                val key = requireNotNull(readString(MAX_KEY_BYTES)) { "Scene JSON key exceeds limit" }
                if (depth == 0) rootObjectEmpty = false
                if (depth == 1 && objectName == "general") generalObjectEmpty = false
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val valueStart = input.filePointer
                if (depth == 0 && key == "general" && peek() == '{'.code) {
                    generalObjectOpen = input.filePointer
                    parseObject(depth + 1, "general")
                } else if (depth == 1 && objectName == "general" && key == "texturereduction") {
                    skipValue(key, depth + 1)
                    textureReductionValueStart = valueStart
                    textureReductionValueEnd = input.filePointer
                } else {
                    parseValue(key, depth + 1)
                }
                skipWhitespace()
                first = false
            }
            expect('}')
        }

        private fun parseArray(depth: Int, key: String) {
            require(depth <= MAX_STRUCTURAL_DEPTH) { "Scene JSON nesting exceeds limit" }
            expect('[')
            skipWhitespace()
            var first = true
            while (peek() != ']'.code) {
                if (!first) {
                    expect(',')
                    skipWhitespace()
                }
                parseValue(key, depth + 1)
                skipWhitespace()
                first = false
            }
            expect(']')
        }

        private fun parseValue(key: String, depth: Int) {
            if (++steps % CANCELLATION_POLL_STEPS == 0) checkCancellation()
            budget.visit()
            when (peek()) {
                '{'.code -> parseObject(depth, key)
                '['.code -> parseArray(depth, key)
                '"'.code -> recordReference(key, readString(MAX_REFERENCE_BYTES))
                '-'.code, in '0'.code..'9'.code -> readPrimitive()
                't'.code -> expectLiteral("true")
                'f'.code -> expectLiteral("false")
                'n'.code -> expectLiteral("null")
                else -> error("Invalid scene JSON value")
            }
        }

        private fun skipValue(key: String, depth: Int) = parseValue(key, depth)

        private fun recordReference(key: String, value: String?) {
            if (value == null || budget.exhausted) return
            val normalized = normalizeReference(value)
            val canonical = textures[normalized] ?: return
            val incoming = semanticForKey(key)
            semantics[canonical] = merge(semantics[canonical], incoming)
        }

        private fun readString(maxCapturedBytes: Int): String? {
            expect('"')
            val bytes = ArrayList<Byte>(minOf(maxCapturedBytes, 256))
            var capture = true
            var escaped = false
            while (true) {
                val value = read()
                if (escaped) {
                    if (capture && bytes.size < maxCapturedBytes) {
                        bytes += '\\'.code.toByte()
                        bytes += value.toByte()
                    } else {
                        capture = false
                    }
                    escaped = false
                } else {
                    when (value) {
                        '\\'.code -> escaped = true
                        '"'.code -> break
                        else -> {
                            if (capture && bytes.size < maxCapturedBytes) bytes += value.toByte() else capture = false
                        }
                    }
                }
            }
            if (!capture) return null
            val encoded = bytes.toByteArray()
            val quoted = byteArrayOf('"'.code.toByte()) + encoded + byteArrayOf('"'.code.toByte())
            return org.json.JSONTokener(quoted.toString(Charsets.UTF_8)).nextValue() as? String
        }

        private fun readPrimitive() {
            while (peek() != -1 && peek() !in PRIMITIVE_DELIMITERS) read()
        }

        private fun expectLiteral(value: String) = value.forEach(::expect)

        private fun skipWhitespace() {
            while (peek() in WHITESPACE) read()
        }

        private fun expect(character: Char) {
            require(read() == character.code) { "Invalid scene JSON structure" }
        }

        private fun peek(): Int {
            if (input.filePointer >= end) return -1
            val position = input.filePointer
            val value = input.read()
            input.seek(position)
            return value
        }

        private fun read(): Int {
            if (input.filePointer >= end) throw EOFException("Truncated scene JSON")
            return input.read().also { if (it < 0) throw EOFException("Truncated scene JSON") }
        }
    }

    private fun copyRange(
        input: RandomAccessFile,
        start: Long,
        end: Long,
        output: java.io.OutputStream,
        checkCancellation: () -> Unit,
    ) {
        require(end >= start)
        input.seek(start)
        var remaining = end - start
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (remaining > 0L) {
            checkCancellation()
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw EOFException("Truncated scene JSON source")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun normalizeReference(path: String): String = path.replace('\\', '/').trim().trimStart('/').lowercase(Locale.ROOT)

    private fun semanticForKey(rawKey: String): TextureSemantic {
        val tokens = rawKey.lowercase(Locale.ROOT).split(NON_ALPHANUMERIC).filter(String::isNotBlank)
        return when {
            tokens.any(DATA_TOKENS::contains) -> TextureSemantic.DATA
            tokens.any(COLOR_TOKENS::contains) -> TextureSemantic.COLOR
            else -> TextureSemantic.UNKNOWN
        }
    }

    private fun merge(current: TextureSemantic?, incoming: TextureSemantic): TextureSemantic =
        when {
            current == null -> incoming
            current == TextureSemantic.DATA || incoming == TextureSemantic.DATA -> TextureSemantic.DATA
            current == TextureSemantic.UNKNOWN || incoming == TextureSemantic.UNKNOWN -> TextureSemantic.UNKNOWN
            else -> TextureSemantic.COLOR
        }

    private const val MAX_ANALYSIS_NODES = 1_000_000L
    private const val MAX_STRUCTURAL_DEPTH = 64
    private const val MAX_KEY_BYTES = 4 * 1024
    private const val MAX_REFERENCE_BYTES = 8 * 1024
    private const val MAX_PATCH_GROWTH_BYTES = 64L * 1024L
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val CANCELLATION_POLL_STEPS = 1024
    private val WHITESPACE = setOf(' '.code, '\t'.code, '\r'.code, '\n'.code)
    private val PRIMITIVE_DELIMITERS = setOf(' '.code, '\t'.code, '\r'.code, '\n'.code, ','.code, '}'.code, ']'.code)
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private val DATA_TOKENS = setOf("normal", "norm", "nrm", "mask", "depth", "roughness", "metallic", "height", "specular", "ao", "alpha")
    private val COLOR_TOKENS = setOf("color", "albedo", "diffuse", "background", "wallpaper", "backdrop", "emissive")
}
