package com.wallhub.android.data.downloads

import com.wallhub.prototype.mpkg.DeclarativeMaterialGraph
import com.wallhub.prototype.mpkg.TextureSemantic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeclarativeMaterialGraphTest {
    @Test
    fun `declarative bindings classify color and data textures through nested objects and arrays`() {
        val result =
            DeclarativeMaterialGraph.analyze(
                documents =
                    mapOf(
                        "scene.json" to
                            """{"materials":[{"albedoTexture":"materials/color.tex","normalMap":"materials/normal.tex"}]}""",
                    ),
                texturePaths = setOf("materials/color.tex", "materials/normal.tex"),
            )

        assertEquals(TextureSemantic.COLOR, result.semantics["materials/color.tex"])
        assertEquals(TextureSemantic.DATA, result.semantics["materials/normal.tex"])
        assertTrue(result.warningCodes.isEmpty())
    }

    @Test
    fun `conflicting declarations choose the most conservative data semantic`() {
        val result =
            DeclarativeMaterialGraph.analyze(
                mapOf("material.json" to """{"color":"shared.tex","roughness":"shared.tex"}"""),
                setOf("shared.tex"),
            )

        assertEquals(TextureSemantic.DATA, result.semantics["shared.tex"])
    }

    @Test
    fun `unknown slot remains unknown instead of being guessed as color`() {
        val result =
            DeclarativeMaterialGraph.analyze(
                mapOf("material.json" to """{"customSampler":"materials/custom.tex"}"""),
                setOf("materials/custom.tex"),
            )

        assertEquals(TextureSemantic.UNKNOWN, result.semantics["materials/custom.tex"])
    }

    @Test
    fun `invalid document degrades unresolved textures to unknown with a stable warning`() {
        val result =
            DeclarativeMaterialGraph.analyze(
                mapOf("broken.json" to "{"),
                setOf("materials/unresolved.tex"),
            )

        assertEquals(TextureSemantic.UNKNOWN, result.semantics["materials/unresolved.tex"])
        assertEquals(setOf("material_graph_document_invalid"), result.warningCodes)
    }

    @Test
    fun `analysis order and path separators do not change the result`() {
        val result =
            DeclarativeMaterialGraph.analyze(
                linkedMapOf(
                    "z.json" to """{"normal":"Materials\\Shared.TEX"}""",
                    "a.json" to """{"albedo":"materials/shared.tex"}""",
                ),
                setOf("materials/shared.tex"),
            )

        assertEquals(mapOf("materials/shared.tex" to TextureSemantic.DATA), result.semantics)
    }
}
