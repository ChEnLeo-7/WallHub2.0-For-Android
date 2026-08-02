plugins {
    `kotlin-dsl`
}
group = "com.wallhub.android.buildlogic"

dependencies {
    implementation("com.android.tools.build:gradle:9.1.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.21")
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "wallhub.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidCompose") {
            id = "wallhub.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
    }
}
