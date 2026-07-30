plugins {
    id("wallhub.android.library")
}

android {
    namespace = "com.wallhub.android.data.steamaccess"
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
