package com.wallhub.prototype.mpkg

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal enum class TextureSemantic {
    COLOR,
    DATA,
    UNKNOWN,
}

internal data class MaterialGraphResult(
    val semantics: Map<String, TextureSemantic>,
    val warningCodes: Set<String>,
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
    ): MaterialGraphResult {
        val normalizedTextures = texturePaths.associateBy(::normalizeReference)
        val semantics = mutableMapOf<String, TextureSemantic>()
        val warnings = mutableSetOf<String>()
        var visited = 0
        var exhausted = false

        fun record(key: String, rawReference: String) {
            val canonical = normalizedTextures[normalizeReference(rawReference)] ?: return
            val semantic = semanticForKey(key)
            semantics[canonical] = merge(semantics[canonical], semantic)
        }

        fun visit(value: Any?, key: String, depth: Int) {
            checkCancellation()
            if (exhausted) return
            if (depth > MAX_DEPTH) {
                warnings += "material_graph_depth_exceeded"
                return
            }
            visited = Math.addExact(visited, 1)
            if (visited > MAX_NODES) {
                warnings += "material_graph_node_limit_exceeded"
                exhausted = true
                return
            }
            when (value) {
                is JSONObject -> {
                    val keys = value.keys().asSequence().toList().sorted()
                    if (keys.size > MAX_REFERENCES_PER_NODE) {
                        warnings += "material_graph_fanout_exceeded"
                    }
                    keys.take(MAX_REFERENCES_PER_NODE).forEach { childKey ->
                        visit(value.opt(childKey), childKey, depth + 1)
                    }
                }
                is JSONArray -> {
                    if (value.length() > MAX_REFERENCES_PER_NODE) {
                        warnings += "material_graph_fanout_exceeded"
                    }
                    repeat(minOf(value.length(), MAX_REFERENCES_PER_NODE)) { index ->
                        visit(value.opt(index), key, depth + 1)
                    }
                }
                is String -> record(key, value)
            }
        }

        documents.toSortedMap().forEach { (_, json) ->
            val root = runCatching { JSONObject(json) }.getOrElse {
                warnings += "material_graph_document_invalid"
                return@forEach
            }
            visit(root, "", 0)
        }
        if (warnings.isNotEmpty()) {
            texturePaths.filterNot(semantics::containsKey).forEach { path -> semantics[path] = TextureSemantic.UNKNOWN }
        }
        return MaterialGraphResult(semantics.toSortedMap(), warnings.toSortedSet())
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
