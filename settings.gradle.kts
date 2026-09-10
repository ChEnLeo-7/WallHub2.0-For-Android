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
    providers.gradleProperty("wallhub.usePatchedKSteam").orNull?.toBooleanStrictOrNull() == true

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
