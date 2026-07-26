plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.wallhub.android.data.downloads"
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

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.hilt.android)
    implementation(libs.javasteam) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp-jvm")
    }
    implementation(libs.okhttp.android)
    implementation(libs.protobuf.java)
    implementation(libs.spongycastle.prov)
    implementation(libs.xz)
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")
    implementation(libs.lz4.java)
    implementation(libs.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.jdk8)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
