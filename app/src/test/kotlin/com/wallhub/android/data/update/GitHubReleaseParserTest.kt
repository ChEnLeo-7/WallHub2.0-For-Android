package com.wallhub.android.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubReleaseParserTest {
    @Test
    fun `selects the single universal apk and compares versions`() {
        val release = parseLatestRelease(releaseJson(), installedVersionName = "0.8.25")

        assertEquals("v0.9.0", release.tagName)
        assertEquals("WallHub-0.9.0-universal.apk", release.assetName)
        assertEquals(
            "https://api.github.com/repos/ChEnLeo-7/WallHub2.0-For-Android/releases/assets/123456",
            release.assetUrl,
        )
        assertEquals(CHECKSUM, release.sha256)
        assertTrue(release.isNewer)
    }

    @Test
    fun `same version is up to date but remains downloadable`() {
        val release = parseLatestRelease(releaseJson(), installedVersionName = "0.9.0")

        assertFalse(release.isNewer)
        assertTrue(release.assetUrl.startsWith("https://"))
    }

    @Test
    fun `rejects ambiguous universal assets prereleases and unofficial urls`() {
        assertFailsWith<IllegalArgumentException> {
            parseLatestRelease(releaseJson(extraUniversalAsset = true), "0.8.25")
        }
        assertFailsWith<IllegalArgumentException> {
            parseLatestRelease(releaseJson(prerelease = true), "0.8.25")
        }
        assertFailsWith<IllegalArgumentException> {
            parseLatestRelease(releaseJson(unofficialAssetUrl = true), "0.8.25")
        }
    }

    @Test
    fun `extracts checksum from release notes when digest is absent`() {
        val body = "| `WallHub-0.9.0-universal.apk` | `$CHECKSUM` |"

        assertEquals(
            CHECKSUM,
            extractReleaseChecksum("", body, "WallHub-0.9.0-universal.apk"),
        )
    }

    @Test
    fun `semantic comparison handles missing patch components`() {
        assertTrue(compareSemanticVersions("1.2.1", "1.2") > 0)
        assertEquals(0, compareSemanticVersions("v1.2.0", "1.2"))
        assertTrue(compareSemanticVersions("1.10.0", "1.9.9") > 0)
    }

    private fun releaseJson(
        prerelease: Boolean = false,
        extraUniversalAsset: Boolean = false,
        unofficialAssetUrl: Boolean = false,
    ): String {
        val extra =
            if (extraUniversalAsset) {
                "," + assetJson("WallHub-0.9.0-copy-universal.apk")
            } else {
                ""
            }
        return """
            {
              "tag_name": "v0.9.0",
              "name": "WallHub Android 0.9.0",
              "body": "Release notes",
              "published_at": "2026-08-01T00:00:00Z",
              "html_url": "https://github.com/ChEnLeo-7/WallHub2.0-For-Android/releases/tag/v0.9.0",
              "draft": false,
              "prerelease": $prerelease,
              "assets": [${assetJson("WallHub-0.9.0-universal.apk", unofficialAssetUrl)}$extra]
            }
            """.trimIndent()
    }

    private fun assetJson(
        name: String,
        unofficialUrl: Boolean = false,
    ): String {
        val url =
            if (unofficialUrl) {
                "https://api.github.com/repos/example/releases/assets/123456"
            } else {
                "https://api.github.com/repos/ChEnLeo-7/WallHub2.0-For-Android/releases/assets/123456"
            }
        return """
            {
              "id": 123456,
              "name": "$name",
              "size": 30400000,
              "content_type": "application/vnd.android.package-archive",
              "url": "$url",
              "digest": "sha256:$CHECKSUM"
            }
            """.trimIndent()
    }

    @Test
    fun `accepts only exact signed release asset redirects`() {
        val valid =
            "https://release-assets.githubusercontent.com/github-production-release-asset/1/file?sp=r&sig=abc"

        assertEquals(valid, requireOfficialReleaseAssetRedirect(valid))
        listOf(
            "http://release-assets.githubusercontent.com/github-production-release-asset/1/file?sig=abc",
            "https://release-assets.githubusercontent.com:444/github-production-release-asset/1/file?sig=abc",
            "https://example.com/github-production-release-asset/1/file?sig=abc",
            "https://release-assets.githubusercontent.com/other/1/file?sig=abc",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1/file",
            "https://user@release-assets.githubusercontent.com/github-production-release-asset/1/file?sig=abc",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1/file?sig=abc#fragment",
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException> { requireOfficialReleaseAssetRedirect(url) }
        }
    }

    private companion object {
        const val CHECKSUM = "144bb986ecc6cd89b36536920cacc48124c9895fef107f564ca6708593d2688d"
    }
}
