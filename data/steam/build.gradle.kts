plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.wallhub.android.data.steam"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":data:steamaccess"))
    implementation(libs.hilt.android)
    implementation(libs.javasteam) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp-jvm")
    }
    implementation(libs.okhttp.android)
    implementation(libs.protobuf.java)
    implementation(libs.spongycastle.prov)
    implementation(libs.xz)
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.jdk8)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
