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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Vendored kSteam engine artifacts (built once by the "Build kSteam" CI step and
        // refreshed on demand) so offline LAN workers can resolve kSteam without MavenLocal.
        maven { url = uri("$rootDir/ksteam-maven/repository") }
        // Resolves the CI-published kSteam engine (see "Build kSteam" workflow steps).
        mavenLocal()
        google()
        mavenCentral()
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "WallHubAndroid"

include(":app")
include(":uwu-sdk")
