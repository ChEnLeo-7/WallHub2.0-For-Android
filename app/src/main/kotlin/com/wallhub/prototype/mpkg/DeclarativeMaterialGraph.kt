package com.wallhub.prototype.mpkg

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal enum class TextureSemantic {
    COLOR,
    DATA,
    UNKNOWN,
}

internal data class MaterialGraphResult(
    val semantics: Map<String, TextureSemantic>,
    val warningCodes: Set<String>,
    val documentWarnings: Map<String, Set<String>>,
)

/** Bounded declarative project/scene/material texture-reference analysis; shader source is deliberately opaque. */
internal object DeclarativeMaterialGraph {
    const val MAX_NODES = 100_000
    const val MAX_DEPTH = 64
    const val MAX_REFERENCES_PER_NODE = 4_096

    fun analyze(
        documents: Map<String, String>,
        texturePaths: Set<String>,
        checkCancellation: () -> Unit = {},
    ): MaterialGraphResult =
        analyzeDocuments(
            documents.toSortedMap().asSequence().map { (path, json) -> path to Result.success(json) },
            texturePaths,
            checkCancellation,
        )

    fun analyzeDocuments(
        documents: Sequence<Pair<String, Result<String>>>,
        texturePaths: Set<String>,
        checkCancellation: () -> Unit = {},
    ): MaterialGraphResult {
        val normalizedTextures = texturePaths.associateBy(::normalizeReference)
        val semantics = mutableMapOf<String, TextureSemantic>()
        val warnings = mutableSetOf<String>()
        val documentWarnings = mutableMapOf<String, MutableSet<String>>()
        var visited = 0

        fun warn(documentPath: String, code: String) {
            warnings += code
            documentWarnings.getOrPut(documentPath) { mutableSetOf() } += code
        }
        var exhausted = false

        fun record(documentPath: String, key: String, rawReference: String) {
            val normalized = normalizeReference(rawReference)
            val documentParent = normalizeReference(documentPath).substringBeforeLast('/', "")
            val canonical =
                normalizedTextures[normalized]
                    ?: documentParent.takeIf(String::isNotEmpty)?.let { normalizedTextures["$it/$normalized"] }
                    ?: return
            val semantic = semanticForKey(key)
            semantics[canonical] = merge(semantics[canonical], semantic)
        }

        fun visit(documentPath: String, value: Any?, key: String, depth: Int) {
            checkCancellation()
            if (exhausted) return
            if (depth > MAX_DEPTH) {
                warn(documentPath, "material_graph_depth_exceeded")
                return
            }
            visited = Math.addExact(visited, 1)
            if (visited > MAX_NODES) {
                warn(documentPath, "material_graph_node_limit_exceeded")
                exhausted = true
                return
            }
            when (value) {
                is JSONObject -> {
                    val keys = value.keys().asSequence().toList().sorted()
                    if (keys.size > MAX_REFERENCES_PER_NODE) {
                        warn(documentPath, "material_graph_fanout_exceeded")
                    }
                    keys.take(MAX_REFERENCES_PER_NODE).forEach { childKey ->
                        visit(documentPath, value.opt(childKey), childKey, depth + 1)
                    }
                }
                is JSONArray -> {
                    if (value.length() > MAX_REFERENCES_PER_NODE) {
                        warn(documentPath, "material_graph_fanout_exceeded")
                    }
                    repeat(minOf(value.length(), MAX_REFERENCES_PER_NODE)) { index ->
                        visit(documentPath, value.opt(index), key, depth + 1)
                    }
                }
                is String -> record(documentPath, key, value)
            }
        }

        documents.forEach { (documentPath, document) ->
            checkCancellation()
            val json = document.getOrElse {
                warn(documentPath, "material_graph_document_invalid")
                return@forEach
            }
            if (exceedsStructuralDepth(json)) {
                warn(documentPath, "material_graph_depth_exceeded")
                return@forEach
            }
            val root = runCatching { JSONTokener(json).nextValue() }.getOrElse {
                warn(documentPath, "material_graph_document_invalid")
                return@forEach
            }
            if (root !is JSONObject && root !is JSONArray) {
                warn(documentPath, "material_graph_document_invalid")
                return@forEach
            }
            visit(documentPath, root, "", 0)
        }
        texturePaths.filterNot(semantics::containsKey).forEach { path -> semantics[path] = TextureSemantic.UNKNOWN }
        return MaterialGraphResult(
            semantics.toSortedMap(),
            warnings.toSortedSet(),
            documentWarnings.mapValues { (_, codes) -> codes.toSortedSet() }.toSortedMap(),
        )
    }

    private fun exceedsStructuralDepth(json: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        json.forEach { character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth = Math.addExact(depth, 1)
                        if (depth > MAX_DEPTH + 1) return true
                    }
                    '}', ']' -> depth--
                }
            }
        }
        return false
    }

    private fun semanticForKey(rawKey: String): TextureSemantic {
        val tokens = rawKey.lowercase(Locale.ROOT).split(NON_ALPHANUMERIC).filter(String::isNotBlank)
        return when {
            tokens.any(DATA_TOKENS::contains) -> TextureSemantic.DATA
            tokens.any(COLOR_TOKENS::contains) -> TextureSemantic.COLOR
            else -> TextureSemantic.UNKNOWN
        }
    }

    private fun merge(
        current: TextureSemantic?,
        incoming: TextureSemantic,
    ): TextureSemantic =
        when {
            current == null -> incoming
            current == TextureSemantic.DATA || incoming == TextureSemantic.DATA -> TextureSemantic.DATA
            current == TextureSemantic.UNKNOWN || incoming == TextureSemantic.UNKNOWN -> TextureSemantic.UNKNOWN
            else -> TextureSemantic.COLOR
        }

    private fun normalizeReference(path: String): String = path.replace('\\', '/').trim().trimStart('/').lowercase(Locale.ROOT)

    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private val DATA_TOKENS = setOf("normal", "norm", "nrm", "mask", "depth", "roughness", "metallic", "height", "specular", "ao", "alpha")
    private val COLOR_TOKENS = setOf("color", "albedo", "diffuse", "background", "wallpaper", "backdrop", "emissive")
}
