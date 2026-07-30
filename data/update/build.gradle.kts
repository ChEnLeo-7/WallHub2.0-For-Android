plugins {
    id("wallhub.android.library")
}

android {
    namespace = "com.wallhub.android.data.update"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.hilt.android)
    implementation(libs.okhttp.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
