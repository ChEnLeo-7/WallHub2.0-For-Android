import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.uwuaosp.compose"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    sourceSets {
        named("main") {
            manifest.srcFile("uwuCompose/AndroidManifest.xml")
            kotlin.directories += "uwuCompose/src"
            res.srcDirs("uwuCompose/res")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }
}

android.sourceSets.getByName("test").java.setSrcDirs(listOf("uwuCompose/test"))
android.sourceSets.getByName("test").kotlin.srcDirs("uwuCompose/test")

dependencies {
    api(libs.androidx.compose.animation)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.foundation.layout)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.runtime.saveable)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    testImplementation(libs.junit)
}
