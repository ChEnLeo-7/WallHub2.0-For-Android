package com.wallhub.prototype.mpkg

import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class ShaderCompatibilityTest {
    @Test
    fun `float literal promotion does not rewrite numbered identifiers`() {
        val source = "float q = mix(q0, q1, step(0.5, edge));"

        val rewritten = ShaderCompatibility.rewrite(source)

        assertEquals("float q = mix(q0, q1, step(0.5, edge));", rewritten)
    }

    @Test
    fun `vector assignments and compound updates promote integer literals`() {
        val source =
            """
            varying vec2 v_PointDelta;
            uniform float g_Left;
            vec3 normal = textureColor.rgb * 2 - 1;
            vec2 p0 = vec2(1 - g_Left, 0);
            vec4 force = vec4(0, 0, 0, 0);
            vec4 upper = texSample2D(texture, coords + vec2(0, -offset.y));
            v_PointDelta.x *= 100;
            """.trimIndent()

        val rewritten = ShaderCompatibility.rewrite(source)

        assertEquals(
            """
            varying vec2 v_PointDelta;
            uniform float g_Left;
            vec3 normal = textureColor.rgb * 2.0 - 1.0;
            vec2 p0 = vec2(1.0 - g_Left, 0.0);
            vec4 force = vec4(0.0, 0.0, 0.0, 0.0);
            vec4 upper = texSample2D(texture, coords + vec2(0.0, -offset.y));
            v_PointDelta.x *= 100.0;
            """.trimIndent(),
            rewritten,
        )
    }

    @Test
    fun `vector promotion preserves integer contexts comments statements and line endings`() {
        val source =
            listOf(
                "vec2 p;",
                "float keep = 1.0;",
                "ivec2 pixel;",
                "// vec2 count;",
                "int step = 1;",
                "int pixel = 2;",
                "int count = 3;",
                "vec2 q = vec2(0); int i = 1;",
                "vec2 casted = vec2(int (2), int(int(2) + 3));",
                "vec2 indexed = values[4];",
                "vec2 commented = vec2(0 /* keep 2 */, 1);",
                "vec2 divided = vec2(float(i / 2), 0);",
                "vec2 modulo = vec2(float(i % (4)), 0);",
                "vec2 compared = vec2(float(i == (3)), 0);",
                "vec2 scientific = vec2(1e+0, 2E-3);",
                "p.x += 5;",
            ).joinToString("\r\n", postfix = "\r\n")

        val rewritten = ShaderCompatibility.rewrite(source)

        assertEquals(
            listOf(
                "vec2 p;",
                "float keep = 1.0;",
                "ivec2 pixel;",
                "// vec2 count;",
                "int step = 1;",
                "int pixel = 2;",
                "int count = 3;",
                "vec2 q = vec2(0.0); int i = 1;",
                "vec2 casted = vec2(int (2), int(int(2) + 3));",
                "vec2 indexed = values[4];",
                "vec2 commented = vec2(0.0 /* keep 2 */, 1.0);",
                "vec2 divided = vec2(float(i / 2), 0.0);",
                "vec2 modulo = vec2(float(i % (4)), 0);",
                "vec2 compared = vec2(float(i == (3)), 0);",
                "vec2 scientific = vec2(1e+0, 2E-3);",
                "p.x += 5.0;",
            ).joinToString("\r\n", postfix = "\r\n"),
            rewritten,
        )
    }

    @Test
    fun `shader token rewrites preserve include strings and comments`() {
        val source =
            """
            #include "sample.h"
            // vec4 sample = vec4(1, 0, 0, 1);
            /* CAST3(0) and 2 * abs(x) */
            vec4 sample = vec4(1, 0, 0, 1);
            """.trimIndent()

        val rewritten = ShaderCompatibility.rewrite(source)

        assertEquals(
            """
            #include "sample.h"
            // vec4 sample = vec4(1, 0, 0, 1);
            /* CAST3(0) and 2 * abs(x) */
            vec4 _sample = vec4(1.0, 0.0, 0.0, 1.0);
            """.trimIndent(),
            rewritten,
        )
    }

    @Test
    fun `malformed mix input stops at a linear scan budget`() {
        val source = "float value = " + "mix(".repeat(400) + "0.5;"

        val error = assertFailsWith<IllegalArgumentException> { ShaderCompatibility.rewrite(source) }

        assertEquals("Shader compatibility scan budget exceeded", error.message)
    }

    @Test
    fun `large vector shader scan cooperatively checks cancellation`() {
        val source = buildString {
            repeat(2_000) { index -> append("vec2 v$index;\n") }
            append("v1999.x += 1;\n")
        }
        var checks = 0

        assertFailsWith<CancellationException> {
            ShaderCompatibility.rewrite(source) {
                checks += 1
                if (checks == 4) throw CancellationException("cancel shader scan")
            }
        }
        assertEquals(4, checks)
    }
}
