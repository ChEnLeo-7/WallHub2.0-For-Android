import com.google.protobuf.gradle.*

plugins {
    id("wallhub.android.application")
    id("wallhub.android.compose")
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.paparazzi)
}

val releaseStoreFile = providers.environmentVariable("WALLHUB_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("WALLHUB_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("WALLHUB_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("WALLHUB_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues =
    listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    )
val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }
val publishAbiApks = providers.gradleProperty("wallhub.publishAbiApks").orNull == "true"

check(releaseSigningValues.all { it.isNullOrBlank() } || hasReleaseSigning) {
    "Configure all WALLHUB_RELEASE_* signing variables or none of them."
}

android {
    namespace = "com.wallhub.android"
    defaultConfig {
        applicationId = "com.wallhub.android"
        targetSdk = 35
        versionCode = 35
        versionName = "0.8.25"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = publishAbiApks
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = publishAbiApks
        }
    }

    if (hasReleaseSigning) {
        signingConfigs.create("releaseSigning") {
            storeFile = file(requireNotNull(releaseStoreFile))
            storePassword = requireNotNull(releaseStorePassword)
            keyAlias = requireNotNull(releaseKeyAlias)
            keyPassword = requireNotNull(releaseKeyPassword)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig =
                if (hasReleaseSigning) {
                    signingConfigs.getByName("releaseSigning")
                } else {
                    signingConfigs.getByName("debug")
                }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}

val protobufVersion = libs.versions.protobuf.java.get()

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java")
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.runtime.saveable)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.haze)
    implementation(libs.hilt.android)
    implementation(libs.javasteam) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp-jvm")
    }
    implementation(libs.javax.inject)
    implementation(libs.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.lz4.java)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.material)
    implementation(libs.okhttp.android)
    implementation(libs.protobuf.java)
    implementation(libs.spongycastle.prov)
    implementation(libs.xz)
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")

    kapt(libs.hilt.compiler)
    kapt(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
