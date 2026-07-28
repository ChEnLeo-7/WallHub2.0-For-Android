plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.wallhub.android.data.steamaccess"
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
    implementation(libs.hilt.android)
    api(libs.okhttp.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.json)
    implementation(libs.spongycastle.prov)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
