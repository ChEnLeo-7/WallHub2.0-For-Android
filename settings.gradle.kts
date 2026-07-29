pluginManagement {
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
        google()
        mavenCentral()
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "WallHubAndroid"

include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:database")
include(":data:settings")
include(":data:steamaccess")
include(":data:steam")
include(":data:workshop")
include(":data:downloads")
include(":data:diagnostics")
include(":data:update")
include(":feature:home")
include(":feature:detail")
include(":feature:downloads")
include(":feature:library")
include(":feature:local")
include(":feature:settings")
