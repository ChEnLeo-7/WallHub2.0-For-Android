package com.wallhub.prototype.mpkg

import android.content.Context
import java.io.File

data class MpkgSelfTestResult(
    val passed: Boolean,
    val message: String,
)

object MpkgSelfTest {
    fun run(context: Context): MpkgSelfTestResult = runCatching {
        val root = File(context.cacheDir, "wallhub-mpkg-self-test")
        root.deleteRecursively()
        root.mkdirs()

        val videoInput = File(root, "video-input").apply { mkdirs() }
        File(videoInput, "preview.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(videoInput, "media").mkdirs()
        File(videoInput, "media/demo.mp4").writeBytes(ByteArray(1024) { (it % 251).toByte() })
        File(videoInput, "project.json").writeText("{\"type\":\"video\",\"file\":\"media/demo.mp4\",\"title\":\"Video\"}")
        val videoOutput = File(root, "video.mpkg")
        WorkshopConverter.convert(videoInput, videoOutput)
        check(MpkgInspector.inspect(videoOutput).magic == VIDEO_MPKG_MAGIC)

        val sceneInput = File(root, "scene-input").apply { mkdirs() }
        File(sceneInput, "preview.png").writeBytes(byteArrayOf(4, 5, 6))
        File(sceneInput, "project.json").writeText("{\"type\":\"scene\",\"title\":\"Scene\"}")
        val tex = TexMobileConverter.writeMobileRgba(
            flags = 0,
            width = 2,
            height = 2,
            rgba = byteArrayOf(
                1, 2, 3, -1,
                4, 5, 6, -1,
                7, 8, 9, -1,
                10, 11, 12, -1,
            ),
        )
        PkgTestWriter.write(
            File(sceneInput, "scene.pkg"),
            listOf(
                PkgEntry("materials/demo.tex", tex),
                PkgEntry("effects/demo.frag", "void main() { sample; }".toByteArray()),
                PkgEntry("sound.mp3", byteArrayOf(7)),
            ),
        )
        val sceneOutput = File(root, "scene.mpkg")
        val sceneReport = WorkshopConverter.convert(sceneInput, sceneOutput)
        val sceneManifest = MpkgInspector.inspect(sceneOutput)
        check(sceneManifest.magic == SCENE_MPKG_MAGIC)
        check(sceneManifest.entries.any { it.path == "materials/demo.tex" })
        check(sceneReport.convertedTextures == 1)
        check(sceneReport.removedAudio == 1)

        val webInput = File(root, "web-input").apply { mkdirs() }
        File(webInput, "project.json").writeText("{\"type\":\"web\"}")
        File(webInput, "index.html").writeText("<html><body>WallHub</body></html>")
        val webOutput = File(root, "web.zip")
        WorkshopConverter.convert(webInput, webOutput)
        check(webOutput.length() > 0L)

        MpkgSelfTestResult(true, "视频 MPKG、场景 MPKG、网站 ZIP 均已通过本地格式校验")
    }.getOrElse { error ->
        MpkgSelfTestResult(false, "MPKG 自检失败：${error.message ?: error.javaClass.simpleName}")
    }
}
