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
    val mavenRepository =
        System.getProperty("maven.repo.local")?.let(::file)
            ?: file(System.getProperty("user.home")).resolve(".m2/repository")
    val corePom =
        mavenRepository.resolve(
            "bruhcollective/itaysonlab/ksteam/core/r50/core-r50.pom",
        )
    if (!corePom.isFile) {
        val script = file("scripts/build-patched-ksteam.sh")
        check(script.isFile) { "Missing patched kSteam build script: $script" }
        val bash =
            listOfNotNull(
                System.getenv("WALLHUB_BASH"),
                "F:\\S\\Git\\bin\\bash.exe",
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "bash",
            ).firstOrNull { it == "bash" || file(it).isFile }
                ?: error("Git Bash is required to build patched kSteam")
        val scriptPath =
            if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
                val windowsPath = script.absolutePath.replace('\\', '/')
                val drive = windowsPath.substringBefore(':').lowercase()
                "/$drive${windowsPath.substringAfter(':')}"
            } else {
                script.absolutePath
            }
        val exitCode =
            ProcessBuilder(bash, scriptPath)
                .directory(rootDir)
                .inheritIO()
                .start()
                .waitFor()
        check(exitCode == 0 && corePom.isFile) {
            "Patched kSteam build failed with exit code $exitCode"
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
