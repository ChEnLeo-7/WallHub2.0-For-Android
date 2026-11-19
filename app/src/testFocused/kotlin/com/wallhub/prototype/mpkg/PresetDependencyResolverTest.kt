package com.wallhub.prototype.mpkg

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetDependencyResolverTest {
    @Test
    fun `dependency directory rejects non positive identifiers`() {
        val root = Files.createTempDirectory("wallhub-preset-id").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                PresetDependencyResolver.dependencyDirectory(root, 0L)
            }
            assertFailsWith<IllegalArgumentException> {
                PresetDependencyResolver.dependencyDirectory(root, -7L)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `dependency directory stays inside the wallhub dependency root`() {
        val root = Files.createTempDirectory("wallhub-preset-path").toFile()
        try {
            val directory = PresetDependencyResolver.dependencyDirectory(root, 42L)

            assertEquals(
                root.resolve(PRESET_DEPENDENCY_DIRECTORY_NAME).resolve("42").canonicalPath,
                directory.canonicalPath,
            )
            assertTrue(directory.toPath().startsWith(root.canonicalFile.toPath()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `read dependency id is null when the directory has no project file`() {
        val root = Files.createTempDirectory("wallhub-preset-noproject").toFile()
        try {
            assertNull(PresetDependencyResolver.readDependencyId(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolve reports no dependency chain without a project file`() {
        val root = Files.createTempDirectory("wallhub-preset-plain").toFile()
        try {
            val resolution = PresetDependencyResolver.resolve(root)

            assertTrue(resolution.isSuccess)
            assertNull(resolution.getOrNull())
        } finally {
            root.deleteRecursively()
        }
    }
}
