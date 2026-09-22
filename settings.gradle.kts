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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Local preview of the Steam Content SDK AAR (sc-sdk-maven/, gitignored).
        maven(url = uri("sc-sdk-maven/repository"))
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
