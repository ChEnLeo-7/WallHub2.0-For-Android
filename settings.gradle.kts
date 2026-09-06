pluginManagement {
    includeBuild("build-logic")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
    }
}

val usePatchedKSteam =
    providers.gradleProperty("wallhub.usePatchedKSteam").orNull?.toBooleanStrictOrNull()
        ?: System.getProperty("os.name").contains("Windows", ignoreCase = true)

if (usePatchedKSteam && System.getenv("GITHUB_ACTIONS") != "true") {
    val patchStamp = "c6ca6ef389d65c5223b2af9bf422ac830a0a2f32:cm-failover-v1"
    val stampFile = file("build/ksteam-patched/stamp")
    if (!stampFile.isFile || stampFile.readText().trim() != patchStamp) {
        val script = file("scripts/build-patched-ksteam.sh")
        check(script.isFile) { "Missing patched kSteam build script: $script" }
        val bashCandidates =
            listOfNotNull(
                System.getenv("WALLHUB_BASH"),
                "F:\\S\\Git\\bin\\bash.exe",
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "bash",
            )
        val bash =
            bashCandidates.firstOrNull { candidate ->
                candidate == "bash" || java.io.File(candidate).isFile
            } ?: error("Git Bash is required to build patched kSteam")
        val scriptPath =
            if (bash != "bash" && System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
                val windowsPath = script.absolutePath.replace('\\', '/')
                val drive = Regex("^([A-Za-z]):").find(windowsPath)?.groupValues?.get(1)
                if (drive != null) "/${drive.lowercase()}${windowsPath.substring(2)}" else windowsPath
            } else {
                script.absolutePath
            }
        val exitCode =
            run {
                val patchLog = file("build/ksteam-patched/build.log")
                patchLog.parentFile.mkdirs()
                ProcessBuilder(bash, scriptPath)
                    .directory(rootDir)
                    .redirectErrorStream(true)
                    .redirectOutput(patchLog)
                    .start()
                    .waitFor()
            }
        val patchLog = file("build/ksteam-patched/build.log")
        check(exitCode == 0) {
            val details = patchLog.takeIf { it.isFile }?.readText()?.takeLast(12_000).orEmpty()
            "Patched kSteam build failed with exit code $exitCode\n$details"
        }
        check(stampFile.isFile && stampFile.readText().trim() == patchStamp) {
            "Patched kSteam build completed without publishing the expected stamp"
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (usePatchedKSteam) {
            // The Debug Action patches the pinned kSteam source and publishes it locally.
            mavenLocal()
        }
        // Vendored kSteam engine artifacts (built once by the "Build kSteam" CI step and
        // refreshed on demand) so offline LAN workers can resolve kSteam without MavenLocal.
        maven { url = uri("$rootDir/ksteam-maven/repository") }
        if (!usePatchedKSteam) {
            mavenLocal()
        }
        google()
        mavenCentral()
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "WallHubAndroid"

include(":app")
include(":uwu-sdk")
