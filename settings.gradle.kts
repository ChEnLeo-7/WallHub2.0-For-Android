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

// Every authoritative Gradle invocation must prove that the staged SDK AAR/POM and native libraries match
// the complete lock and the sibling SDK checkout. Only GitHub Actions may take the explicit source-only path.
val sourceOnlyHostedCi =
    System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("WALLHUB_SOURCE_ONLY_CI") == "1"
if (!sourceOnlyHostedCi) {
    val python =
        System.getenv("WALLHUB_PYTHON")
            ?: if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
    val configuredSdkRepo = System.getenv("WALLHUB_STEAM_CONTENT_SDK_REPO")?.let { java.io.File(it) }
    val sdkRepo =
        configuredSdkRepo
            ?: listOf(rootDir.resolve("../Steam Content SDK"), rootDir.resolve("../sdk-source"))
                .firstOrNull { it.resolve(".git").exists() }
    val sdkCommitFile = rootDir.resolve("config/steam-content-sdk.commit")
    val provenanceArgs =
        when {
            sdkCommitFile.isFile -> listOf("--sdk-commit-file", sdkCommitFile.absolutePath)
            sdkRepo != null -> listOf("--sdk-repo", sdkRepo.absolutePath)
            else -> error(
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
}

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
include(":shared:ui")
include(":preview:web")
