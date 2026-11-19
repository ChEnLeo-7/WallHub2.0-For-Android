pluginManagement {
    includeBuild("build-logic")

    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
            }
        }
        gradlePluginPortal()
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
    }
}

// Every authoritative Gradle invocation proves that the staged SDK AAR/POM and native libraries match
// the complete lock and either the sibling SDK checkout or a clean committed provenance file.
val python =
    System.getenv("WALLHUB_PYTHON")
        ?: if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
val configuredSdkRepo = System.getenv("WALLHUB_STEAM_CONTENT_SDK_REPO")?.let { java.io.File(it) }
val sdkRepo =
    configuredSdkRepo
        ?: listOf(
            // The repository may sit one level below the workspace (WallHub for Android-repo/),
            // so the sibling SDK checkout can be a direct sibling or a grandparent sibling.
            // The SDK itself may also be reorganized into a "Steam Content SDK-repo" folder.
            rootDir.resolve("../Steam Content SDK"),
            rootDir.resolve("../../Steam Content SDK"),
            rootDir.resolve("../../Steam Content SDK/Steam Content SDK-repo"),
            rootDir.resolve("../sdk-source"),
        ).firstOrNull { it.resolve(".git").exists() }
val sdkCommitFile = rootDir.resolve("config/steam-content-sdk.commit")
val provenanceArgs =
    when {
        sdkRepo != null -> listOf("--sdk-repo", sdkRepo.absolutePath)
        sdkCommitFile.isFile -> listOf("--sdk-commit-file", sdkCommitFile.absolutePath)
        else ->
            error(
                "Steam Content SDK checkout/provenance not found; set WALLHUB_STEAM_CONTENT_SDK_REPO",
            )
    }
val verification =
    ProcessBuilder(
        listOf(
            python,
            rootDir.resolve("scripts/sdk-artifact-lock.py").absolutePath,
            "verify",
            "--require-complete",
        ) + provenanceArgs,
    ).directory(rootDir).redirectErrorStream(true).start()
val output = verification.inputStream.bufferedReader().use { it.readText() }
check(verification.waitFor() == 0) {
    "Steam Content SDK artifact verification failed before Gradle configuration:\n$output"
}
println(output.trim())

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Locked local preview of the Steam Content SDK AAR (sc-sdk-maven/, gitignored).
        // CI and LAN builds must stage and verify this exact artifact; never fall back to ~/.m2.
        maven(url = uri("sc-sdk-maven/repository"))
        mavenCentral()
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
            }
        }
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "WallHubAndroid"

include(":app")
include(":uwu-sdk")
