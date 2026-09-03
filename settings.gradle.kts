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

val usePatchedKSteam = providers.gradleProperty("wallhub.usePatchedKSteam").orNull == "true"

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
