import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.uwuaosp.compose"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    sourceSets {
        named("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.setSrcDirs(listOf("uwuCompose/src"))
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

dependencies {
    implementation("androidx.compose.foundation:foundation:1.10.0-alpha01")
    implementation("androidx.compose.material:material-icons-extended:1.7.0-alpha01")
    implementation("androidx.compose.material3:material3:1.4.0-alpha17")
    implementation("androidx.compose.ui:ui:1.10.0-alpha01")
    implementation("androidx.compose.ui:ui-graphics:1.10.0-alpha01")
}
